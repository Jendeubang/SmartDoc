package com.javaee.documentservice.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.javaee.common.exception.BusinessException;
import com.javaee.common.model.Result;
import com.javaee.documentservice.dto.DocumentUpdateDTO;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.entity.DocumentSuggestion;
import com.javaee.documentservice.entity.DocumentVersion;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.mapper.DocumentSuggestionMapper;
import com.javaee.documentservice.mapper.DocumentVersionMapper;
import com.javaee.documentservice.security.RequestUserContext;
import com.javaee.documentservice.service.DocumentAccessService;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.service.EnterpriseAuditService;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/documents/{documentId}")
public class DocumentReviewController {
    private final DocumentMapper documents;
    private final DocumentVersionMapper versions;
    private final DocumentSuggestionMapper suggestions;
    private final DocumentAccessService access;
    private final DocumentService documentService;
    private final RequestUserContext users;
    private final EnterpriseAuditService audit;

    public DocumentReviewController(DocumentMapper documents, DocumentVersionMapper versions,
                                    DocumentSuggestionMapper suggestions, DocumentAccessService access,
                                    DocumentService documentService, RequestUserContext users,
                                    EnterpriseAuditService audit) {
        this.documents = documents; this.versions = versions; this.suggestions = suggestions;
        this.access = access; this.documentService = documentService; this.users = users; this.audit = audit;
    }

    @PostMapping("/suggestions")
    public Result<DocumentSuggestion> createSuggestion(@PathVariable String documentId, @RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId(); Document document = requireDocument(documentId); access.assertCanRead(document, userId);
        String suggested = text(body.get("suggestedText"));
        if (suggested.isBlank()) throw new BusinessException("建议内容不能为空");
        DocumentSuggestion item = new DocumentSuggestion();
        item.setDocumentId(documentId); item.setUserId(userId); item.setOriginalText(text(body.get("originalText")));
        item.setSuggestedText(suggested); item.setReason(text(body.get("reason"))); item.setStatus("pending");
        item.setStartOffset(integer(body.get("startOffset"))); item.setEndOffset(integer(body.get("endOffset")));
        item.setCreateTime(LocalDateTime.now()); suggestions.insert(item);
        audit.record(document.getOrganizationId(), userId, "SUGGESTION_CREATE", "suggestion", item.getId(), documentId);
        return Result.success(item);
    }

    @GetMapping("/suggestions")
    public Result<List<DocumentSuggestion>> listSuggestions(@PathVariable String documentId,
                                                             @RequestParam(required = false) String status) {
        Long userId = users.getRequiredUserId(); Document document = requireDocument(documentId); access.assertCanRead(document, userId);
        LambdaQueryWrapper<DocumentSuggestion> query = new LambdaQueryWrapper<DocumentSuggestion>()
                .eq(DocumentSuggestion::getDocumentId, documentId).orderByDesc(DocumentSuggestion::getCreateTime);
        if (status != null && !status.isBlank()) query.eq(DocumentSuggestion::getStatus, status);
        return Result.success(suggestions.selectList(query));
    }

    @PostMapping("/suggestions/{suggestionId}/decision")
    @Transactional
    public Result<Void> decideSuggestion(@PathVariable String documentId, @PathVariable String suggestionId,
                                         @RequestBody Map<String, Object> body) {
        Long userId = users.getRequiredUserId(); Document document = requireDocument(documentId); access.assertCanWrite(document, userId);
        DocumentSuggestion suggestion = suggestions.selectById(suggestionId);
        if (suggestion == null || !documentId.equals(suggestion.getDocumentId())) throw new BusinessException("建议不存在");
        if (!"pending".equals(suggestion.getStatus())) throw new BusinessException("该建议已处理");
        String decision = text(body.get("decision"));
        if (!Set.of("accepted", "rejected").contains(decision)) throw new BusinessException("处理结果不合法");
        if ("accepted".equals(decision)) {
            String current = documentService.getById(documentId, userId).getContent();
            String updated = applySuggestion(current == null ? "" : current, suggestion);
            DocumentUpdateDTO dto = new DocumentUpdateDTO(); dto.setContent(updated);
            dto.setChangeLog("接受用户 " + suggestion.getUserId() + " 的修订建议");
            documentService.update(documentId, dto, userId);
        }
        suggestion.setStatus(decision); suggestion.setReviewedBy(userId);
        suggestion.setReviewComment(text(body.get("comment"))); suggestion.setReviewTime(LocalDateTime.now());
        suggestions.updateById(suggestion);
        audit.record(document.getOrganizationId(), userId, "SUGGESTION_DECISION", "suggestion", suggestionId, decision);
        return Result.success();
    }

    @GetMapping("/versions/diff")
    public Result<Map<String, Object>> versionDiff(@PathVariable String documentId,
                                                   @RequestParam Integer from,
                                                   @RequestParam Integer to) {
        Long userId = users.getRequiredUserId(); Document document = requireDocument(documentId); access.assertCanRead(document, userId);
        DocumentVersion left = versions.selectByDocumentIdAndVersion(documentId, from);
        DocumentVersion right = versions.selectByDocumentIdAndVersion(documentId, to);
        if (left == null || right == null) throw new BusinessException("版本不存在");
        return Result.success(diff(left, right));
    }

    private Map<String, Object> diff(DocumentVersion left, DocumentVersion right) {
        String[] a = Optional.ofNullable(left.getContent()).orElse("").split("\\R", -1);
        String[] b = Optional.ofNullable(right.getContent()).orElse("").split("\\R", -1);
        int[][] dp = new int[a.length + 1][b.length + 1];
        for (int i = a.length - 1; i >= 0; i--) for (int j = b.length - 1; j >= 0; j--)
            dp[i][j] = a[i].equals(b[j]) ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1]);
        List<Map<String, Object>> changes = new ArrayList<>(); int i = 0, j = 0, added = 0, removed = 0;
        while (i < a.length || j < b.length) {
            if (i < a.length && j < b.length && a[i].equals(b[j])) { changes.add(line("equal", a[i], i + 1, j + 1)); i++; j++; }
            else if (j < b.length && (i == a.length || dp[i][j + 1] >= dp[i + 1][j])) { changes.add(line("added", b[j], null, j + 1)); added++; j++; }
            else { changes.add(line("removed", a[i], i + 1, null)); removed++; i++; }
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("fromVersion", left.getVersionNumber());
        result.put("toVersion", right.getVersionNumber()); result.put("addedLines", added); result.put("removedLines", removed); result.put("changes", changes); return result;
    }

    private Map<String, Object> line(String type, String content, Integer oldLine, Integer newLine) {
        Map<String, Object> line = new LinkedHashMap<>(); line.put("type", type); line.put("content", content);
        line.put("oldLine", oldLine); line.put("newLine", newLine); return line;
    }
    private String applySuggestion(String content, DocumentSuggestion s) {
        Integer start = s.getStartOffset(), end = s.getEndOffset();
        if (start != null && end != null && start >= 0 && end >= start && end <= content.length())
            return content.substring(0, start) + s.getSuggestedText() + content.substring(end);
        if (s.getOriginalText() != null && !s.getOriginalText().isBlank() && content.contains(s.getOriginalText()))
            return content.replaceFirst(java.util.regex.Pattern.quote(s.getOriginalText()), java.util.regex.Matcher.quoteReplacement(s.getSuggestedText()));
        throw new BusinessException("原文已变更，无法安全应用该建议");
    }
    private Document requireDocument(String id) { Document d = documents.selectById(id); if (d == null) throw new BusinessException("文档不存在"); return d; }
    private String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private Integer integer(Object value) { try { return value == null || text(value).isBlank() ? null : Integer.valueOf(text(value)); } catch (Exception e) { throw new BusinessException("文本位置不合法"); } }
}
