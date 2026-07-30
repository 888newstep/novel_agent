package com.novel.agent.repository;

import com.novel.agent.entity.Faction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FactionRepository extends JpaRepository<Faction, Long> {
    List<Faction> findByNovelId(Long novelId);
    List<Faction> findByNovelIdAndStatus(Long novelId, String status);
    List<Faction> findByNovelIdAndCategory(Long novelId, String category);
}