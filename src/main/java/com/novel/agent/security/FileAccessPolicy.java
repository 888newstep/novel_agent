package com.novel.agent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Component
public class FileAccessPolicy {

    private final List<Path> allowedRoots;

    public FileAccessPolicy(@Value("${app.file-access.allowed-roots:novels;artifacts}") String configuredRoots) {
        this.allowedRoots = parseAllowedRoots(configuredRoots);
    }

    public Path requireAllowedRegularFile(String filePath) {
        Path candidate = resolveAllowedPath(filePath);
        if (!Files.isRegularFile(candidate)) {
            throw new IllegalArgumentException("file path is not a regular file");
        }
        Path realPath = toRealPath(candidate);
        if (!isWithinAllowedRoot(realPath) || !Files.isRegularFile(realPath)) {
            throw new IllegalArgumentException("file path is outside the allowed roots");
        }
        return realPath;
    }

    public Path resolveAllowedPath(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("file path is required");
        }
        final Path candidate;
        try {
            candidate = Path.of(filePath).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("file path is invalid", exception);
        }
        if (!isWithinAllowedRoot(candidate)) {
            throw new IllegalArgumentException("file path is outside the allowed roots");
        }
        return candidate;
    }

    public Path requireAllowedDirectory(String directoryPath) {
        Path candidate = resolveAllowedPath(directoryPath);
        if (!Files.isDirectory(candidate)) {
            throw new IllegalArgumentException("directory path is not a directory");
        }
        Path realPath = toRealPath(candidate);
        if (!isWithinAllowedRoot(realPath) || !Files.isDirectory(realPath)) {
            throw new IllegalArgumentException("directory path is outside the allowed roots");
        }
        return realPath;
    }

    public Path requireAllowedFileName(String directoryPath, String fileName) {
        validateSimpleFileName(fileName);
        Path directory = resolveAllowedPath(directoryPath);
        return requireAllowedRegularFile(directory.resolve(fileName).normalize().toString());
    }

    private void validateSimpleFileName(String fileName) {
        if (fileName == null || fileName.isBlank() || ".".equals(fileName) || "..".equals(fileName)
                || fileName.contains("/") || fileName.contains("\\") || fileName.indexOf(':') >= 0) {
            throw new IllegalArgumentException("file name must be a single safe path segment");
        }
    }

    private Path toRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("file path cannot be resolved", exception);
        }
    }

    private boolean isWithinAllowedRoot(Path candidate) {
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();
        return allowedRoots.stream().anyMatch(root -> {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            if (normalizedCandidate.startsWith(normalizedRoot)) {
                return true;
            }
            if (!Files.exists(normalizedRoot)) {
                return false;
            }
            try {
                return normalizedCandidate.startsWith(normalizedRoot.toRealPath());
            } catch (IOException exception) {
                return false;
            }
        });
    }

    private List<Path> parseAllowedRoots(String configuredRoots) {
        if (configuredRoots == null || configuredRoots.isBlank()) {
            return List.of();
        }
        return Arrays.stream(configuredRoots.split("[;,\\r\\n]"))
                .map(String::trim)
                .filter(root -> !root.isBlank())
                .map(this::normalizeRoot)
                .toList();
    }

    private Path normalizeRoot(String root) {
        try {
            return Path.of(root).toAbsolutePath().normalize();
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("configured file root is invalid", exception);
        }
    }
}
