package com.novel.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chapters", indexes = {
        @Index(name = "idx_novel_chapter", columnList = "novel_id, chapter_num", unique = true)
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "novel_id", nullable = false)
    private Long novelId;

    @Column(name = "chapter_num", nullable = false)
    private Integer chapterNum;

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(name = "key_events", columnDefinition = "TEXT")
    private String keyEvents;

    @Column(name = "word_count")
    @Builder.Default
    private Integer wordCount = 0;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}