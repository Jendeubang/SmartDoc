package com.javaee.documentservice.dto;

import lombok.Data;
import java.util.List;

@Data
public class ToolboxJobRequest {
    private String toolType;
    private List<String> documentIds;
    private String pages;
}
