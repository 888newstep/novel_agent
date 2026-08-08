package com.novel.agent.repository;

import com.novel.agent.entity.KeyEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KeyEventRepository extends JpaRepository<KeyEvent, Long> {
    List<KeyEvent> findByNovelIdAndResolvedFalse(Long novelId);
    List<KeyEvent> findByNovelIdAndEventType(Long novelId, String eventType);
    List<KeyEvent> findByNovelIdOrderByChapterNumAsc(Long novelId);
}