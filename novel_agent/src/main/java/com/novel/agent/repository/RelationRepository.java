package com.novel.agent.repository;

import com.novel.agent.entity.Relation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RelationRepository extends JpaRepository<Relation, Long> {
    List<Relation> findByNovelId(Long novelId);
    List<Relation> findBySourceTypeAndSourceId(String sourceType, Long sourceId);
    List<Relation> findByTargetTypeAndTargetId(String targetType, Long targetId);
    List<Relation> findByNovelIdAndRelationType(Long novelId, String relationType);
}