package com.novel.agent.repository;

import com.novel.agent.entity.Artifact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {
    List<Artifact> findByNovelId(Long novelId);
    List<Artifact> findByNovelIdAndOwnerId(Long novelId, Long ownerId);
    List<Artifact> findByNovelIdAndStatus(Long novelId, String status);
}