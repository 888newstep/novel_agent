package com.novel.agent.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 知识库片段 - 从 TXT 小说中解析的段落
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSegment {
    private Long id;
    private String source;
    private String category;
    private String chapter;
    private String content;
    private String contentHash;
    private Integer contentLength;
}