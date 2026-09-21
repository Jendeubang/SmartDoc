package com.javaee.fileservice.service;

import java.util.Map;

/**
 * Normalizes file list query parameters before they reach MyBatis-Plus.
 */
public final class FileMetadataQueryPolicy {

    private static final Map<String, String> COLUMNS = Map.of(
            "createTime", "create_time",
            "updateTime", "update_time",
            "fileName", "file_name",
            "fileSize", "file_size");

    private FileMetadataQueryPolicy() {
    }

    public static QuerySpec normalize(int page, int size, String sortBy, String direction) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(size, 100));
        String column = sortBy == null ? "create_time" : COLUMNS.getOrDefault(sortBy, "create_time");
        boolean ascending = "asc".equalsIgnoreCase(direction);
        return new QuerySpec(safePage, safeSize, column, ascending);
    }

    public record QuerySpec(int page, int size, String column, boolean ascending) {
    }
}
