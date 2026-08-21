package com.novel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "retrieval")
public class RetrievalProperties {

    private Search search = new Search();
    private Memory memory = new Memory();
    private Ranking ranking = new Ranking();
    private Hints hints = new Hints();

    @Data
    public static class Search {
        private int efSearch = 64;
        private int defaultFetchMultiplier = 2;
        private int maxQueryVariants = 3;
        private int maxQueryChars = 240;
        private int maxTotalQueryChars = 480;
        private int perChapterSegmentLimit = 1;
        private int perChapterEventLimit = 1;
    }

    @Data
    public static class Memory {
        private int promptTokenBudget = 1600;
        private int recentChapterLimit = 3;
        private int segmentLimit = 3;
        private int hookLimit = 2;
        private int characterLimit = 3;
        private int itemLimit = 1;
        private int factionLimit = 1;
        private int relationLimit = 2;
    }

    @Data
    public static class Ranking {
        private double keywordHitWeight = 0.10;
        private double variantHitWeight = 0.06;
        private double primaryFieldKeywordHitWeight = 0.14;
        private double primaryFieldExactMatchBonus = 0.20;
        private double unresolvedEventBonus = 0.18;
        private double plotHookBonus = 0.10;
        private double exactMatchBonus = 0.16;
        private double recencyMaxBoost = 0.12;
    }

    @Data
    public static class Hints {
        private String segment = "章节片段 续写 上下文";
        private String event = "事件 伏笔 设定";
        private String character = "人物 关系 设定";
        private String item = "道具 状态 归属";
        private String faction = "势力 灵感 设定";
        private String currentChapter = "当前章节 上下文 续写";
    }
}
