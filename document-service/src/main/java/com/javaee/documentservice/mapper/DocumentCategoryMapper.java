package com.javaee.documentservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.javaee.documentservice.entity.DocumentCategory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentCategoryMapper extends BaseMapper<DocumentCategory> {
}