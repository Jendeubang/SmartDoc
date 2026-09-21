package com.javaee.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorsOriginPolicyTest {

    @Test
    void normalizesUniqueExactOriginsAndRejectsWildcard() {
        assertThat(CorsOriginPolicy.parse(
                " http://localhost:5173,https://docs.example.com,http://localhost:5173 ", false))
                .containsExactly("http://localhost:5173", "https://docs.example.com");
        assertThatThrownBy(() -> CorsOriginPolicy.parse("*", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void productionRequiresExplicitOrigin() {
        assertThatThrownBy(() -> CorsOriginPolicy.parse(" ", true))
                .isInstanceOf(IllegalStateException.class);
        assertThat(CorsOriginPolicy.parse(" ", false))
                .containsExactly("http://localhost:5173");
    }

    @Test
    void rejectsOriginsWithPathsOrFragments() {
        assertThatThrownBy(() -> CorsOriginPolicy.parse("https://docs.example.com/app", false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorsOriginPolicy.parse("https://docs.example.com#fragment", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
