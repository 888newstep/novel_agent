package com.novel.agent.repository;

import com.novel.agent.entity.NovelCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterRepository extends JpaRepository<NovelCharacter, Long> {
    List<NovelCharacter> findByNovelId(Long novelId);
    List<NovelCharacter> findByNovelIdAndStatus(Long novelId, String status);
}