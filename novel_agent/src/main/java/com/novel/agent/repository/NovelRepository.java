package com.novel.agent.repository;

import com.novel.agent.entity.Novel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface NovelRepository extends JpaRepository<Novel, Long> {
    List<Novel> findByGenre(String genre);
}