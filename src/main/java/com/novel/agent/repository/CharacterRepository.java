package com.novel.agent.repository;

import com.novel.agent.entity.NovelCharacter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CharacterRepository extends JpaRepository<NovelCharacter, Long> {
    List<NovelCharacter> findByNovelId(Long novelId);
    List<NovelCharacter> findByNovelIdAndStatus(Long novelId, String status);

    /**
     * 角色名字子串预匹配——绕过 Milvus vector TopN 截断的关键查询。
     * <p>当用户查询中包含明确的角色名字片段（如 "少宫主"、"师兄"）时，
     * 直接在 MySQL 里按 name 字段做子串匹配，把结果作为额外候选
     * 并入 Milvus hybridSearch 的 rerank 链路。</p>
     */
    List<NovelCharacter> findByNovelIdAndNameContainingIgnoreCase(Long novelId, String nameKeyword);
}