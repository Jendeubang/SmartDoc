package com.javaee.documentservice.mapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ToolboxJobMapperTest {

    @Test
    void mapperContractDeclaresAtomicStateOperations() throws NoSuchMethodException {
        assertThat(ToolboxJobMapper.class
                .getDeclaredMethod("claimPending", String.class, Instant.class)).isNotNull();
        assertThat(ToolboxJobMapper.class
                .getDeclaredMethod("selectOwned", String.class, Long.class, String.class)).isNotNull();
        assertThat(ToolboxJobMapper.class
                .getDeclaredMethod("listOwned", Long.class, String.class, int.class)).isNotNull();
        assertThat(ToolboxJobMapper.class
                .getDeclaredMethod("markSuccess", String.class, String.class, String.class, String.class, String.class))
                .isNotNull();
        assertThat(ToolboxJobMapper.class
                .getDeclaredMethod("markFailure", String.class, String.class)).isNotNull();
        assertThat(ToolboxJobMapper.class
                .getDeclaredMethod("resetStaleProcessing", String.class, Instant.class)).isNotNull();
        assertThat(ToolboxJobMapper.class
                .getDeclaredMethod("markExpired", String.class, Instant.class)).isNotNull();
    }
}
