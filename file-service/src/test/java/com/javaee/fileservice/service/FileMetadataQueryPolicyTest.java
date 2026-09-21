package com.javaee.fileservice.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileMetadataQueryPolicyTest {

    @Test
    void acceptsOnlyKnownSortFieldsAndDirections() {
        assertThat(FileMetadataQueryPolicy.normalize(1, 20, "fileName", "ASC"))
                .isEqualTo(new FileMetadataQueryPolicy.QuerySpec(1, 20, "file_name", true));
        assertThat(FileMetadataQueryPolicy.normalize(
                1, 20, "id desc; drop table file_metadata", "asc"))
                .isEqualTo(new FileMetadataQueryPolicy.QuerySpec(1, 20, "create_time", true));
        assertThat(FileMetadataQueryPolicy.normalize(1, 20, "createTime", "sideways").ascending())
                .isFalse();
    }

    @Test
    void clampsPageAndSize() {
        var query = FileMetadataQueryPolicy.normalize(-5, 1_000_000, null, null);

        assertThat(query.page()).isEqualTo(1);
        assertThat(query.size()).isEqualTo(100);
        assertThat(query.column()).isEqualTo("create_time");
    }
}
