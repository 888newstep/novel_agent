package com.novel.agent.repository;

import com.novel.agent.entity.Inspiration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InspirationRepository extends JpaRepository<Inspiration, Long> {
    List<Inspiration> findByNovelIdAndCategory(Long novelId, String category);
    List<Inspiration> findByNovelIdIsNull();
    List<Inspiration> findByCategory(String category);
}