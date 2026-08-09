package com.javaee.documentservice.controller;

import com.javaee.common.exception.BusinessException;
import com.javaee.common.model.Result;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.security.RequestUserContext;
import com.javaee.documentservice.service.DocumentAccessService;
import com.javaee.documentservice.service.ParagraphLockService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/documents/{documentId}/paragraph-locks")
public class ParagraphLockController {
    private static final Pattern PARAGRAPH_ID = Pattern.compile("[A-Za-z0-9_-]{1,80}");
    private final ParagraphLockService paragraphLockService;
    private final DocumentMapper documentMapper;
    private final DocumentAccessService documentAccessService;
    private final RequestUserContext requestUserContext;

    public ParagraphLockController(ParagraphLockService paragraphLockService, DocumentMapper documentMapper,
                                   DocumentAccessService documentAccessService, RequestUserContext requestUserContext) {
        this.paragraphLockService = paragraphLockService;
        this.documentMapper = documentMapper;
        this.documentAccessService = documentAccessService;
        this.requestUserContext = requestUserContext;
    }

    @GetMapping
    public Result<Map<String, Object>> list(@PathVariable String documentId) {
        Long userId = assertWritable(documentId);
        return Result.success(paragraphLockService.list(documentId, userId));
    }

    @PostMapping
    public Result<Map<String, Object>> acquire(@PathVariable String documentId, @RequestBody Map<String, String> body) {
        Long userId = assertWritable(documentId);
        return Result.success(paragraphLockService.acquire(documentId, validate(body == null ? null : body.get("paragraphId")), userId));
    }

    @DeleteMapping("/{paragraphId}")
    public Result<Map<String, Object>> release(@PathVariable String documentId, @PathVariable String paragraphId) {
        Long userId = assertWritable(documentId);
        return Result.success(paragraphLockService.release(documentId, validate(paragraphId), userId));
    }

    private Long assertWritable(String documentId) {
        Long userId = requestUserContext.getRequiredUserId();
        Document document = documentMapper.selectById(documentId);
        if (document == null) throw new BusinessException("文档不存在");
        documentAccessService.assertCanWrite(document, userId);
        return userId;
    }

    private String validate(String paragraphId) {
        if (paragraphId == null || !PARAGRAPH_ID.matcher(paragraphId).matches()) throw new BusinessException("段落标识不合法");
        return paragraphId;
    }
}