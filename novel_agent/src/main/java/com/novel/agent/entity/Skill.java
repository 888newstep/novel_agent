package com.novel.agent.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "skills", indexes = {
        @Index(name = "idx_novel", columnList = "novel_id"),
        @Index(name = "idx_owner", columnList = "owner_id"),
        @Index(name = "idx_talent", columnList = "talent")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Skill {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "novel_id", nullable = false)
    private Long novelId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 20)
    @Builder.Default
    private String talent = "下品";

    @Column(length = 50)
    private String element;

    @Column(length = 50)
    private String rank;

    @Column(length = 50)
    private String type;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(length = 50)
    @Builder.Default
    private String stage = "入门";

    @Column(columnDefinition = "TEXT")
    private String description;

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