package com.novel.agent.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class NovelCategoryGuesser {

    private static final Map<String, List<String>> CATEGORY_KEYWORDS = new HashMap<>();

    static {
        CATEGORY_KEYWORDS.put("仙侠", List.of(
                "修仙", "修真", "仙帝", "金丹", "元婴", "渡劫", "灵根",
                "飞升", "仙尊", "道祖", "剑修", "炼丹", "法宝", "宗门"));
        CATEGORY_KEYWORDS.put("玄幻", List.of(
                "斗气", "斗帝", "武魂", "魂师", "魔法", "异界", "位面",
                "神王", "圣阶", "大千世界", "主宰", "苍穹"));
        CATEGORY_KEYWORDS.put("都市", List.of(
                "总裁", "神医", "校花", "保镖", "赘婿", "首富", "兵王"));
        CATEGORY_KEYWORDS.put("科幻", List.of(
                "机甲", "星舰", "星际", "末世", "丧尸", "系统", "黑科技"));
        CATEGORY_KEYWORDS.put("历史", List.of(
                "穿越", "古代", "皇帝", "三国", "权谋", "朝堂", "科举"));
    }

    public String guess(String fileName, String firstContent) {
        String textToCheck = fileName + "，" + firstContent;
        String bestCategory = "未知";
        int bestScore = 0;

        for (Map.Entry<String, List<String>> entry : CATEGORY_KEYWORDS.entrySet()) {
            int score = 0;
            for (String keyword : entry.getValue()) {
                if (textToCheck.contains(keyword)) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestCategory = entry.getKey();
            }
        }

        return bestCategory;
    }
}