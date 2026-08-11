package com.novel.agent.repository;

import com.novel.agent.entity.RagEvaluationSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RagEvaluationSnapshotRepository extends JpaRepository<RagEvaluationSnapshot, Long> {

    List<RagEvaluationSnapshot> findByProfileNameAndNovelIdOrderByEvaluatedAtDesc(
            String profileName, Long novelId, Pageable pageable);

    Optional<RagEvaluationSnapshot> findFirstByProfileNameAndNovelIdOrderByEvaluatedAtDesc(
            String profileName, Long novelId);

    Optional<RagEvaluationSnapshot> findFirstByProfileNameOrderByEvaluatedAtDesc(String profileName);

    List<RagEvaluationSnapshot> findAllByOrderByEvaluatedAtDesc(Pageable pageable);
}
