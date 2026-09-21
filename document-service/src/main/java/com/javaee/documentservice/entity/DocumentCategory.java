package com.javaee.documentservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** User-owned category board used to organize cloud documents. */
@Data
@TableName("document_category")
public class DocumentCategory {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private Long userId;

    private String organizationId;

    private String name;

    private String color;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
