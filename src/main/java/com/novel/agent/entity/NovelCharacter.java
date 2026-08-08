package com.novel.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "characters", indexes = {
        @Index(name = "idx_novel", columnList = "novel_id"),
        @Index(name = "idx_talent", columnList = "talent"),
        @Index(name = "idx_element", columnList = "element")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NovelCharacter {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "novel_id", nullable = false)
    private Long novelId;

    @Column(nullable = false, length = 50)
    private String name;

    @Column(length = 100)
    private String identity;

    @Column(length = 50)
    private String realm;

    @Column(length = 20)
    @Builder.Default
    private String talent = "下品";

    @Column(length = 50)
    private String element;

    @Column(name = "element_main", length = 20)
    private String elementMain;

    @Column(columnDefinition = "TEXT")
    private String personality;

    @Column(columnDefinition = "TEXT")
    private String backstory;

    @Column(length = 20)
    @Builder.Default
    private String status = "alive";

    @Column(name = "first_appear")
    private Integer firstAppear;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}