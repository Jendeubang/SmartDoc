package com.javaee.documentservice.controller;

/**
 * 【简历：文档服务 API】
 * 提供文档 CRUD、文档内容管理等接口。
 */

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.javaee.common.exception.BusinessException;
import com.javaee.common.model.Result;
import com.javaee.documentservice.dto.DocumentCategoryDTO;
import com.javaee.documentservice.dto.DocumentCreateDTO;
import com.javaee.documentservice.dto.DocumentQueryDTO;
import com.javaee.documentservice.dto.DocumentUpdateDTO;
import com.javaee.documentservice.entity.Document;
import com.javaee.documentservice.entity.DocumentCategory;
import com.javaee.documentservice.entity.DocumentVersion;
import com.javaee.documentservice.mapper.DocumentCategoryMapper;
import com.javaee.documentservice.mapper.DocumentMapper;
import com.javaee.documentservice.security.RequestUserContext;
import com.javaee.documentservice.service.DocumentAccessService;
import com.javaee.documentservice.service.DocumentService;
import com.javaee.documentservice.vo.DocumentVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 文档管理控制器
 * 提供文档的CRUD和版本控制REST API接口
 */
@RestController
@RequestMapping("/api/documents")
@Tag(name = "文档管理", description = "文档创建、更新、删除、查询、版本控制等接口")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @Autowired
    private RequestUserContext requestUserContext;

    @Autowired
    private DocumentAccessService documentAccessService;

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private DocumentCategoryMapper documentCategoryMapper;

    @GetMapping("/access/ids")
    @Operation(summary = "获取可访问文档ID", description = "供权限感知RAG等内部能力获取当前用户实时可访问的文档范围")
    public Result<List<String>> accessibleDocumentIds() {
        Long userId = requestUserContext.getRequiredUserId();
        return Result.success(documentMapper.selectAccessibleByUserId(userId).stream().map(Document::getId).toList());
    }

    @GetMapping("/access/catalog")
    @Operation(summary = "获取可访问文档目录", description = "返回当前用户实时可访问的文档元数据，供 Agent 精确回答数量、名称和索引覆盖率")
    public Result<List<DocumentVO>> accessibleDocumentCatalog() {
        Long userId = requestUserContext.getRequiredUserId();
        return Result.success(documentService.getByUserId(userId));
    }

    @GetMapping("/{id}/access")
    @Operation(summary = "校验文档权限", description = "校验当前用户对文档的读或写权限")
    public Result<Map<String, Object>> checkAccess(@PathVariable String id,
                                                   @RequestParam(defaultValue = "read") String mode) {
        Long userId = requestUserContext.getRequiredUserId();
        Document document = documentMapper.selectById(id);
        if (document == null || !"active".equals(document.getStatus())) throw new BusinessException("文档不存在");
        if ("write".equalsIgnoreCase(mode)) documentAccessService.assertCanWrite(document, userId);
        else documentAccessService.assertCanRead(document, userId);
        return Result.success(Map.of("documentId", id, "mode", mode, "allowed", true));
    }

    /**
     * 创建文档
     * @param dto 创建文档请求参数
     * @return 文档VO
     */
    @PostMapping
    @Operation(summary = "创建文档", description = "创建新文档，自动保存初始版本")
    public Result<DocumentVO> create(@RequestBody DocumentCreateDTO dto) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.create(dto, userId);
        return Result.success(document);
    }

    /**
     * 更新文档
     * @param id 文档ID
     * @param dto 更新文档请求参数
     * @return 更新后的文档VO
     */
    @PutMapping("/{id}")
    @Operation(summary = "更新文档", description = "更新文档内容，自动保存历史版本")
    public Result<DocumentVO> update(
            @Parameter(description = "文档ID") @PathVariable String id,
            @RequestBody DocumentUpdateDTO dto) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.update(id, dto, userId);
        return Result.success(document);
    }

    @PostMapping("/{id}/reparse")
    @Operation(summary = "Reparse original upload", description = "Re-extract document text from the original uploaded file")
    public Result<DocumentVO> reparse(@PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        return Result.success(documentService.reparseSource(id, userId));
    }
    /**
     * 删除文档
     * @param id 文档ID
     * @return 无
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "删除文档", description = "软删除文档，将文档状态标记为已删除")
    public Result<Void> delete(@Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        documentService.delete(id, userId);
        return Result.success();
    }

    /**
     * 添加文档协作者
     * @param id 文档ID
     * @param collaboratorUserId 协作者用户ID
     * @param role 协作角色
     * @return 无
     */
    @PostMapping("/{id}/collaborators/{collaboratorUserId}")
    @Operation(summary = "添加文档协作者", description = "为文档授权协作者，角色支持 owner/editor/viewer")
    public Result<Void> grantAccess(
            @Parameter(description = "文档ID") @PathVariable String id,
            @Parameter(description = "协作者用户ID") @PathVariable Long collaboratorUserId,
            @Parameter(description = "协作角色") @RequestParam(defaultValue = "editor") String role,
            @Parameter(description = "有效期小时数，0 表示永久") @RequestParam(defaultValue = "0") Integer expiresHours) {
        Long userId = requestUserContext.getRequiredUserId();
        documentService.grantAccess(id, collaboratorUserId, role, expiresHours, userId);
        return Result.success();
    }

    /**
     * 获取文档协作者列表
     * @param id 文档ID
     * @return 协作者列表
     */
    @GetMapping("/{id}/collaborators")
    @Operation(summary = "获取文档协作者列表", description = "获取文档所有已授权的协作者")
    public Result<List<Map<String, Object>>> listCollaborators(
            @Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        Document doc = documentMapper.selectById(id);
        if (doc == null) throw new BusinessException("文档不存在");
        documentAccessService.assertCanRead(doc, userId);
        List<Map<String, Object>> collaborators = documentAccessService.getCollaborators(id);
        boolean ownerIncluded = collaborators.stream()
                .anyMatch(access -> doc.getUserId() != null && doc.getUserId().equals(access.get("userId")));
        if (!ownerIncluded && doc.getUserId() != null) {
            Map<String, Object> owner = new java.util.LinkedHashMap<>();
            owner.put("userId", doc.getUserId());
            owner.put("role", "owner");
            owner.put("expiresAt", null);
            owner.put("expired", false);
            owner.put("createTime", doc.getCreateTime());
            collaborators.add(0, owner);
        }
        return Result.success(collaborators);
    }

    /**
     * 移除文档协作者
     * @param id 文档ID
     * @param collaboratorUserId 协作者用户ID
     * @return 无
     */
    @DeleteMapping("/{id}/collaborators/{collaboratorUserId}")
    @Operation(summary = "移除文档协作者", description = "取消指定用户对文档的协作权限")
    public Result<Void> revokeAccess(
            @Parameter(description = "文档ID") @PathVariable String id,
            @Parameter(description = "协作者用户ID") @PathVariable Long collaboratorUserId) {
        Long userId = requestUserContext.getRequiredUserId();
        Document doc = documentMapper.selectById(id);
        if (doc == null) throw new BusinessException("文档不存在");
        // 只有文档创建者（owner）才能取消授权，协作者自己不能取消
        if (!documentAccessService.isOwner(doc, userId)) {
            throw new BusinessException("只有文档创建者可以取消授权");
        }
        documentAccessService.revokeAccess(id, collaboratorUserId);
        return Result.success();
    }

    @GetMapping("/categories")
    @Operation(summary = "获取分类板块", description = "获取当前用户创建的文档分类板块")
    public Result<List<DocumentCategory>> listCategories() {
        Long userId = requestUserContext.getRequiredUserId();
        List<DocumentCategory> categories = documentCategoryMapper.selectList(
                new LambdaQueryWrapper<DocumentCategory>()
                        .eq(DocumentCategory::getUserId, userId)
                        .orderByAsc(DocumentCategory::getCreateTime));
        return Result.success(categories);
    }

    @PostMapping("/categories")
    @Operation(summary = "新建分类板块", description = "为当前用户创建一个可选择的文档分类板块")
    public Result<DocumentCategory> createCategory(@RequestBody DocumentCategoryDTO dto) {
        Long userId = requestUserContext.getRequiredUserId();
        String name = dto == null || dto.getName() == null ? "" : dto.getName().trim();
        if (name.isBlank() || name.length() > 50) {
            throw new BusinessException("分类名称不能为空且不能超过 50 个字符");
        }
        Long exists = documentCategoryMapper.selectCount(new LambdaQueryWrapper<DocumentCategory>()
                .eq(DocumentCategory::getUserId, userId)
                .eq(DocumentCategory::getName, name));
        if (exists != null && exists > 0) {
            throw new BusinessException("该分类板块已存在");
        }
        String color = dto.getColor();
        if (color == null || !color.matches("#[0-9a-fA-F]{6}")) {
            color = "#ACA0CE";
        }
        DocumentCategory category = new DocumentCategory();
        category.setUserId(userId);
        category.setName(name);
        category.setColor(color);
        category.setCreateTime(LocalDateTime.now());
        category.setUpdateTime(LocalDateTime.now());
        documentCategoryMapper.insert(category);
        return Result.success(category);
    }

    @DeleteMapping("/categories")
    @Operation(summary = "按名称删除分类板块", description = "兼容历史文档分类；删除后对应文档自动归为未分类")
    public Result<Void> deleteCategoryByName(@RequestParam String name) {
        Long userId = requestUserContext.getRequiredUserId();
        String categoryName = name == null ? "" : name.trim();
        if (categoryName.isBlank()) {
            throw new BusinessException("分类名称不能为空");
        }
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getCategory, categoryName)
                .set(Document::getCategory, null));
        documentCategoryMapper.delete(new LambdaQueryWrapper<DocumentCategory>()
                .eq(DocumentCategory::getUserId, userId)
                .eq(DocumentCategory::getName, categoryName));
        return Result.success();
    }
    @DeleteMapping("/categories/{id}")
    @Operation(summary = "删除分类板块", description = "删除时会将该板块内文档自动设为未分类，文档本身不会丢失")
    public Result<Void> deleteCategory(@PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentCategory category = documentCategoryMapper.selectById(id);
        if (category == null || !userId.equals(category.getUserId())) {
            throw new BusinessException("分类板块不存在或无权操作");
        }
        documentMapper.update(null, new LambdaUpdateWrapper<Document>()
                .eq(Document::getUserId, userId)
                .eq(Document::getCategory, category.getName())
                .set(Document::getCategory, null));
        documentCategoryMapper.deleteById(id);
        return Result.success();
    }
    @GetMapping("/trash")
    @Operation(summary = "获取回收站", description = "获取当前用户软删除的文档")
    public Result<List<DocumentVO>> getTrash() {
        Long userId = requestUserContext.getRequiredUserId();
        return Result.success(documentService.getDeletedByUserId(userId));
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "恢复删除的文档", description = "将回收站文档恢复到云端文档库")
    public Result<DocumentVO> restoreDeleted(@PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        return Result.success(documentService.restoreDeleted(id, userId));
    }

    @DeleteMapping("/{id}/purge")
    @Operation(summary = "永久删除文档", description = "永久删除回收站文档及其正文，此操作不可恢复")
    public Result<Void> purge(@PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        documentService.purge(id, userId);
        return Result.success();
    }

    /**
     * 获取文档详情
     * @param id 文档ID
     * @return 文档VO
     * @param id 文档ID
     * @return 文档VO
     */
    @GetMapping("/{id}")
    @Operation(summary = "获取文档详情", description = "根据文档ID获取文档详细信息")
    public Result<DocumentVO> getById(@Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.getById(id, userId);
        return Result.success(document);
    }

    @GetMapping("/{id}/source-content")
    @Operation(summary = "读取原始上传正文", description = "只读解析原始上传文件，供校对等需要忠实原文的场景使用，不修改当前文档")
    public Result<String> getOriginalSourceContent(
            @Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        return Result.success(documentService.getOriginalSourceContent(id, userId));
    }

    /**
     * 获取文档MinIO存储位置
     * @param id 文档ID
     * @return 文档存储位置
     */
    @GetMapping("/{id}/storage")
    @Operation(summary = "获取文档存储位置", description = "根据文档ID获取其MinIO bucket/objectName，供AI文件操作使用")
    public Result<DocumentVO> getStorageLocation(@Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.getStorageLocation(id, userId);
        return Result.success(document);
    }

    /**
     * 获取用户文档列表
     * @param userId 用户ID
     * @return 文档VO列表
     */
    @GetMapping("/user/{userId}")
    @Operation(summary = "获取用户文档列表", description = "获取指定用户的所有活跃文档列表")
    public Result<List<DocumentVO>> getByUserId(@Parameter(description = "用户ID") @PathVariable Long userId) {
        Long currentUserId = requestUserContext.getRequiredUserId();
        if (!currentUserId.equals(userId)) {
            throw new com.javaee.common.exception.BusinessException("无权查看其他用户的文档");
        }
        List<DocumentVO> documents = documentService.getByUserId(currentUserId);
        return Result.success(documents);
    }

    /**
     * 搜索文档
     * @param keyword 关键词（可选）
     * @param category 分类（可选）
     * @return 文档VO列表
     */
    @GetMapping("/search")
    @Operation(summary = "搜索文档", description = "根据关键词搜索标题、内容、关键词，或按分类筛选文档")
    public Result<List<DocumentVO>> search(
            @Parameter(description = "关键词（搜索标题、内容、关键词）") @RequestParam(required = false) String keyword,
            @Parameter(description = "分类") @RequestParam(required = false) String category) {
        DocumentQueryDTO dto = new DocumentQueryDTO();
        dto.setKeyword(keyword);
        dto.setCategory(category);
        Long userId = requestUserContext.getRequiredUserId();
        List<DocumentVO> documents = documentService.search(dto, userId);
        return Result.success(documents);
    }

    /**
     * 获取文档版本列表
     * @param id 文档ID
     * @return 文档版本列表
     */
    @GetMapping("/{id}/versions")
    @Operation(summary = "获取文档版本列表", description = "获取文档的所有历史版本，按版本号降序排列")
    public Result<List<DocumentVersion>> getVersions(@Parameter(description = "文档ID") @PathVariable String id) {
        Long userId = requestUserContext.getRequiredUserId();
        List<DocumentVersion> versions = documentService.getVersions(id, userId);
        return Result.success(versions);
    }

    /**
     * 恢复文档版本
     * @param id 文档ID
     * @param versionNumber 版本号
     * @return 恢复后的文档VO
     */
    @PostMapping("/{id}/restore/{versionNumber}")
    @Operation(summary = "恢复文档版本", description = "将文档恢复到指定版本，自动保存当前版本作为历史版本")
    public Result<DocumentVO> restoreVersion(
            @Parameter(description = "文档ID") @PathVariable String id,
            @Parameter(description = "版本号") @PathVariable Integer versionNumber) {
        Long userId = requestUserContext.getRequiredUserId();
        DocumentVO document = documentService.restoreVersion(id, versionNumber, userId);
        return Result.success(document);
    }
}
