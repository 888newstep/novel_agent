package com.novel.agent.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileAccessPolicyTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsRegularFileInsideConfiguredRoot() throws IOException {
        Path novels = Files.createDirectories(tempDir.resolve("novels"));
        Path chapter = Files.writeString(novels.resolve("chapter.txt"), "chapter");
        FileAccessPolicy policy = new FileAccessPolicy(novels.toString());

        Path resolved = policy.requireAllowedRegularFile(novels.resolve(".").resolve("chapter.txt").toString());

        assertThat(resolved).isEqualTo(chapter.toRealPath());
    }

    @Test
    void rejectsFileOutsideConfiguredRoot() throws IOException {
        Path novels = Files.createDirectories(tempDir.resolve("novels"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "outside");
        FileAccessPolicy policy = new FileAccessPolicy(novels.toString());

        assertThatThrownBy(() -> policy.requireAllowedRegularFile(outside.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTraversalAndPathSeparatorsForSingleFileName() throws IOException {
        Path novels = Files.createDirectories(tempDir.resolve("novels"));
        Path chapter = Files.writeString(novels.resolve("chapter.txt"), "chapter");
        FileAccessPolicy policy = new FileAccessPolicy(novels.toString());

        assertThat(policy.requireAllowedFileName(novels.toString(), "chapter.txt"))
                .isEqualTo(chapter.toRealPath());
        assertThatThrownBy(() -> policy.requireAllowedFileName(novels.toString(), "../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.requireAllowedFileName(novels.toString(), "..\\outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.requireAllowedFileName(novels.toString(), "nested/chapter.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMissingAndDirectoryPathsAsRegularFiles() throws IOException {
        Path novels = Files.createDirectories(tempDir.resolve("novels"));
        FileAccessPolicy policy = new FileAccessPolicy(novels.toString());

        assertThatThrownBy(() -> policy.requireAllowedRegularFile(novels.resolve("missing.txt").toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy.requireAllowedRegularFile(novels.toString()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
