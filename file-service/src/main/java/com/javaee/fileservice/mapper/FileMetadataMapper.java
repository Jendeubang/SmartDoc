package com.javaee.fileservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.javaee.fileservice.entity.FileMetadata;
import org.apache.ibatis.annotations.Param;

/**
 * 文件元数据数据访问接口
 */
public interface FileMetadataMapper extends BaseMapper<FileMetadata> {

    /**
     * 根据文件ID获取文件元数据
     */
    FileMetadata selectByFileId(String fileId);

    FileMetadata findReadyByMd5(@Param("md5") String md5,
                                @Param("createBy") String createBy,
                                @Param("organizationId") String organizationId,
                                @Param("bucketName") String bucketName);

    /**
     * 根据文件名搜索文件
     */
    java.util.List<FileMetadata> searchByFileName(String keyword);

}
