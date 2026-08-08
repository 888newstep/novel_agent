package com.novel.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "item_logs", indexes = {
        @Index(name = "idx_novel_item", columnList = "novel_id, item_type, item_id"),
        @Index(name = "idx_chapter", columnList = "novel_id, chapter_num")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ItemLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "novel_id", nullable = false)
    private Long novelId;

    @Column(name = "item_type", nullable = false, length = 20)
    private String itemType;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "chapter_num", nullable = false)
    private Integer chapterNum;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "old_owner")
    private Long oldOwner;

    @Column(name = "new_owner")
    private Long newOwner;

    @Column(name = "old_talent", length = 20)
    private String oldTalent;

    @Column(name = "new_talent", length = 20)
    private String newTalent;

    @Column(name = "old_rank", length = 50)
    private String oldRank;

    @Column(name = "new_rank", length = 50)
    private String newRank;

    @Column(name = "old_stage", length = 50)
    private String oldStage;

    @Column(name = "new_stage", length = 50)
    private String newStage;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}