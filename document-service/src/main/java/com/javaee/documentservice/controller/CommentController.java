package com.javaee.documentservice.controller;

import com.javaee.common.model.Result;
import com.javaee.documentservice.dto.CommentCreateDTO;
import com.javaee.documentservice.service.CommentService;
import com.javaee.documentservice.security.RequestUserContext;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.service.DocumentAccessService;
import com.javaee.documentservice.entity.Document;
import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.vo.CommentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/documents/{documentId}/comments")
@Tag(name = "文档评论", description = "文档评论管理接口")
public class CommentController {

    @Autowired
    private CommentService commentService;
    @Autowired private RequestUserContext requestUserContext;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private DocumentAccessService documentAccessService;

    @PostMapping
    @Operation(summary = "添加评论", description = "为文档添加评论")
    public Result<CommentVO> createComment(
            @Parameter(description = "文档ID") @PathVariable String documentId,
            @RequestBody CommentCreateDTO dto) {
        Long userId = requestUserContext.getRequiredUserId();
        assertRead(documentId, userId);
        if (dto == null || dto.getContent() == null || dto.getContent().trim().isEmpty()) {
            throw new BusinessException("评论内容不能为空");
        }
        if (dto.getContent().length() > 4000) throw new BusinessException("评论内容不能超过 4000 字");
        dto.setDocumentId(documentId);
        CommentVO vo = commentService.createComment(dto, userId);
        return Result.success(vo);
    }

    @GetMapping
    @Operation(summary = "获取评论列表", description = "获取文档的所有评论")
    public Result<List<CommentVO>> getComments(
            @Parameter(description = "文档ID") @PathVariable String documentId) {
        assertRead(documentId, requestUserContext.getRequiredUserId());
        List<CommentVO> comments = commentService.getCommentsByDocumentId(documentId);
        return Result.success(comments);
    }

    @GetMapping("/{commentId}")
    @Operation(summary = "获取评论详情", description = "获取指定评论的详情")
    public Result<CommentVO> getComment(
            @Parameter(description = "文档ID") @PathVariable String documentId,
            @Parameter(description = "评论ID") @PathVariable String commentId) {
        assertRead(documentId, requestUserContext.getRequiredUserId());
        CommentVO vo = commentService.getCommentById(commentId);
        return Result.success(vo);
    }

    @DeleteMapping("/{commentId}")
    @Operation(summary = "删除评论", description = "删除指定评论")
    public Result<Void> deleteComment(
            @Parameter(description = "文档ID") @PathVariable String documentId,
            @Parameter(description = "评论ID") @PathVariable String commentId) {
        Long userId = requestUserContext.getRequiredUserId();
        assertRead(documentId, userId);
        commentService.deleteComment(commentId, userId);
        return Result.success();
    }

    @GetMapping("/{commentId}/replies")
    @Operation(summary = "获取回复列表", description = "获取评论的所有回复")
    public Result<List<CommentVO>> getReplies(
            @Parameter(description = "文档ID") @PathVariable String documentId,
            @Parameter(description = "评论ID") @PathVariable String commentId) {
        assertRead(documentId, requestUserContext.getRequiredUserId());
        List<CommentVO> replies = commentService.getReplies(commentId);
        return Result.success(replies);
    }

    private void assertRead(String documentId, Long userId) {
        Document document = documentMapper.selectById(documentId);
        if (document == null) throw new BusinessException("文档不存在");
        documentAccessService.assertCanRead(document, userId);
    }
}
