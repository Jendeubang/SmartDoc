package com.javaee.fileservice.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PathUtilsTest {

    @Test
    void normalizePathUnifiesSeparatorsAndRemovesTrailingSlash() {
        assertThat(PathUtils.normalizePath("folder\\nested//file/"))
                .isEqualTo("folder/nested/file");
    }

    @Test
    void safePathRejectsTraversalAbsoluteAndWindowsDrivePaths() {
        assertThat(PathUtils.isSafePath("documents/report.pdf")).isTrue();
        assertThat(PathUtils.isSafePath("../secret.txt")).isFalse();
        assertThat(PathUtils.isSafePath("/etc/passwd")).isFalse();
        assertThat(PathUtils.isSafePath("C:\\Windows\\system.ini")).isFalse();
    }

    @Test
    void relativePathOnlyStripsMatchingBase() {
        assertThat(PathUtils.getRelativePath("/data/files", "/data/files/team/report.docx"))
                .isEqualTo("team/report.docx");
        assertThat(PathUtils.getRelativePath("/data/files", "/archive/report.docx"))
                .isEqualTo("/archive/report.docx");
    }
}
