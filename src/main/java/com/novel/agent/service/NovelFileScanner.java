package com.novel.agent.service;

import com.novel.agent.entity.NovelFile;
import com.novel.agent.security.FileAccessPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

@Slf4j
@Service
public class NovelFileScanner {

    private final FileAccessPolicy fileAccessPolicy;

    public NovelFileScanner(FileAccessPolicy fileAccessPolicy) {
        this.fileAccessPolicy = fileAccessPolicy;
    }

    @Value("${knowledge.novel-dir:novels/}")
    private String novelDir;

    private static final String PROCESSED_LOG = "processed_files.txt";

    public List<NovelFile> scanUnprocessed() {
        Set<String> processed = loadProcessedFiles();
        List<NovelFile> unprocessed = new ArrayList<>();

        Path dir;
        try {
            dir = fileAccessPolicy.resolveAllowedPath(novelDir);
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
                log.info("Created novels directory: {}", dir.toAbsolutePath());
            }
            dir = fileAccessPolicy.requireAllowedDirectory(dir.toString());
        } catch (IOException | IllegalArgumentException exception) {
            log.error("Cannot access configured novel directory: {}", novelDir, exception);
            return unprocessed;
        }

        try (Stream<Path> paths = Files.list(dir)) {
            paths.filter(p -> p.toString().endsWith(".txt"))
                    .forEach(p -> {
                        try {
                            Path safePath = fileAccessPolicy.requireAllowedRegularFile(p.toString());
                            String fileName = p.getFileName().toString();
                            NovelFile file = NovelFile.builder()
                                    .fileName(fileName)
                                    .filePath(safePath.toString())
                                    .fileSize(Files.size(safePath))
                                    .processed(processed.contains(fileName))
                                    .build();
                            unprocessed.add(file);
                        } catch (IOException | IllegalArgumentException e) {
                            log.error("读取文件信息失败: {}", p, e);
                        }
                    });
        } catch (IOException | IllegalArgumentException e) {
            log.error("扫描目录失败: {}", novelDir, e);
        }

        log.info("扫描到 {} 个文件，其中 {} 个未处理",
                unprocessed.size(), unprocessed.stream().filter(f -> !f.isProcessed()).count());
        return unprocessed;
    }

    public void markProcessed(String fileName) {
        try {
            Files.writeString(Paths.get(PROCESSED_LOG),
                    fileName + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("记录已处理文件失败: {}", fileName, e);
        }
    }

    private Set<String> loadProcessedFiles() {
        Set<String> processed = new HashSet<>();
        try {
            if (Files.exists(Paths.get(PROCESSED_LOG))) {
                processed.addAll(Files.readAllLines(Paths.get(PROCESSED_LOG)));
            }
        } catch (IOException e) {
            log.warn("读取已处理记录失败，将重新处理所有文件");
        }
        return processed;
    }
}
