package com.novel.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "key_events", indexes = {
        @Index(name = "idx_novel_type", columnList = "novel_id, event_type"),
        @Index(name = "idx_novel_unresolved", columnList = "novel_id, resolved")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KeyEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "novel_id", nullable = false)
    private Long novelId;

    @Column(name = "chapter_num", nullable = false)
    private Integer chapterNum;

    @Column(name = "event_type", nullable = false, length = 30)
    private String eventType;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "involved_characters", columnDefinition = "TEXT")
    private String involvedCharacters;

    @Column(name = "involved_artifacts", columnDefinition = "TEXT")
    private String involvedArtifacts;

    @Column
    @Builder.Default
    private Boolean resolved = false;

    @Column(name = "resolved_at")
    private Integer resolvedAt;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}