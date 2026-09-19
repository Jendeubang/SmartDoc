package com.javaee.documentservice.service.impl;

import com.javaee.common.exception.BusinessException;
import com.javaee.documentservice.dto.CommentCreateDTO;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.entity.DocumentComment;
import com.javaee.documentservice.mapper.DocumentCommentMapper;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.service.CommentService;
import com.javaee.documentservice.service.DocumentAccessService;
import com.javaee.documentservice.vo.CommentVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// 评论服务实现：负责评论的创建、列表、详情、删除与回复查询，支持父子评论结构。
// 对应简历第 6 条「企业空间与在线协同」中的文档评论。
@Service
public class CommentServiceImpl implements CommentService {

    private static final Logger log = LoggerFactory.getLogger(CommentServiceImpl.class);

    @Autowired
    private DocumentCommentMapper commentMapper;

    @Autowired
    private DocumentMapper documentMapper;
    @Autowired private DocumentAccessService documentAccessService;

    // 创建评论：校验文档与父评论后落库，支持回复（parentId）
    @Override
    @Transactional
    public CommentVO createComment(CommentCreateDTO dto, Long userId) {
        Document document = documentMapper.selectById(dto.getDocumentId());
        if (document == null) {
            throw new BusinessException("文档不存在");
        }
        documentAccessService.assertCanRead(document, userId);

        if (dto.getParentId() != null) {
            DocumentComment parent = commentMapper.selectById(dto.getParentId());
            if (parent == null || !dto.getDocumentId().equals(parent.getDocumentId())) {
                throw new BusinessException("父评论不存在");
            }
        }

        DocumentComment comment = new DocumentComment();
        comment.setDocumentId(dto.getDocumentId());
        comment.setOrganizationId(document.getOrganizationId());
        comment.setUserId(userId);
        comment.setContent(dto.getContent());
        comment.setParentId(dto.getParentId());
        comment.setCreatedBy(String.valueOf(userId));
        comment.setCreateTime(LocalDateTime.now());
        comment.setStatus("active");

        commentMapper.insert(comment);
        log.info("创建评论成功: id={}, documentId={}", comment.getId(), dto.getDocumentId());

        return convertToVO(comment);
    }

    // 查询文档的根评论并递归附带回复
    @Override
    public List<CommentVO> getCommentsByDocumentId(String documentId) {
        List<DocumentComment> rootComments = commentMapper.selectRootCommentsByDocumentId(documentId);
        return rootComments.stream()
                .map(this::convertToVOWithReplies)
                .collect(Collectors.toList());
    }

    @Override
    public CommentVO getCommentById(String documentId, String id) {
        DocumentComment comment = commentMapper.selectById(id);
        if (comment == null || !documentId.equals(comment.getDocumentId())) {
            throw new BusinessException("评论不存在");
        }
        return convertToVO(comment);
    }

    // 删除评论（软删除，仅作者本人可删）
    @Override
    @Transactional
    public void deleteComment(String documentId, String id, Long userId) {
        DocumentComment comment = commentMapper.selectById(id);
        if (comment == null || !documentId.equals(comment.getDocumentId())) {
            throw new BusinessException("评论不存在");
        }

        if (!comment.getUserId().equals(userId)) {
            throw new BusinessException("无权删除此评论");
        }

        comment.setStatus("deleted");
        commentMapper.updateById(comment);
        log.info("删除评论成功: id={}", id);
    }

    // 查询某父评论下的回复列表
    @Override
    public List<CommentVO> getReplies(String documentId, String parentId) {
        DocumentComment parent = commentMapper.selectById(parentId);
        if (parent == null || !documentId.equals(parent.getDocumentId())) {
            throw new BusinessException("Parent comment does not exist in this document");
        }
        List<DocumentComment> replies = commentMapper.selectByParentId(parentId);
        return replies.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList());
    }

    private CommentVO convertToVO(DocumentComment comment) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setDocumentId(comment.getDocumentId());
        vo.setUserId(comment.getUserId());
        vo.setContent(comment.getContent());
        vo.setParentId(comment.getParentId());
        vo.setCreatedBy(comment.getCreatedBy());
        vo.setCreateTime(comment.getCreateTime());
        vo.setStatus(comment.getStatus());
        vo.setReplies(new ArrayList<>());
        return vo;
    }

    // 将评论实体转为 VO 并递归填充其回复
    private CommentVO convertToVOWithReplies(DocumentComment comment) {
        CommentVO vo = convertToVO(comment);
        List<DocumentComment> replies = commentMapper.selectByParentId(comment.getId());
        vo.setReplies(replies.stream()
                .map(this::convertToVO)
                .collect(Collectors.toList()));
        return vo;
    }
}
