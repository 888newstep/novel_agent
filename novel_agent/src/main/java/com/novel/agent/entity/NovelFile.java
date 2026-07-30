package com.novel.agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 小说文件信息
 * 用于知识库 TXT 文件扫描
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NovelFile {
    private String fileName;
    private String filePath;
    private long fileSize;
    private boolean processed;
}