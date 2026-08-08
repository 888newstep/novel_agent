package com.novel.agent.repository;

import com.novel.agent.entity.Skill;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillRepository extends JpaRepository<Skill, Long> {
    List<Skill> findByNovelId(Long novelId);
    List<Skill> findByNovelIdAndOwnerId(Long novelId, Long ownerId);
    List<Skill> findByNovelIdAndType(Long novelId, String type);
}