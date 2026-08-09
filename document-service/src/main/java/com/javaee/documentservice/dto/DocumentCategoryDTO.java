package com.javaee.documentservice.dto;

import lombok.Data;

/** Payload for creating a document category board. */
@Data
public class DocumentCategoryDTO {
    private String name;
    private String color;
}