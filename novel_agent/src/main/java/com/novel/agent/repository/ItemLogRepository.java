package com.novel.agent.repository;

import com.novel.agent.entity.ItemLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItemLogRepository extends JpaRepository<ItemLog, Long> {
    List<ItemLog> findByNovelIdAndItemTypeAndItemId(Long novelId, String itemType, Long itemId);
    List<ItemLog> findByNovelIdOrderByChapterNumAsc(Long novelId);
}