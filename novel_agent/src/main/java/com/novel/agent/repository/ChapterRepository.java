package com.novel.agent.repository;

import com.novel.agent.entity.Chapter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChapterRepository extends JpaRepository<Chapter, Long> {
    List<Chapter> findByNovelIdOrderByChapterNumAsc(Long novelId);
    Optional<Chapter> findByNovelIdAndChapterNum(Long novelId, Integer chapterNum);
    long countByNovelId(Long novelId);
}