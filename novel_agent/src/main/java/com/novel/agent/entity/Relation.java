package com.novel.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "relations", indexes = {
        @Index(name = "idx_novel", columnList = "novel_id"),
        @Index(name = "idx_source", columnList = "source_type, source_id"),
        @Index(name = "idx_target", columnList = "target_type, target_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Relation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "novel_id", nullable = false)
    private Long novelId;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_id", nullable = false)
    private Long sourceId;

    @Column(name = "source_name", length = 100)
    private String sourceName;

    @Column(name = "target_type", nullable = false, length = 20)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "target_name", length = 100)
    private String targetName;

    @Column(name = "relation_type", nullable = false, length = 50)
    private String relationType;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}