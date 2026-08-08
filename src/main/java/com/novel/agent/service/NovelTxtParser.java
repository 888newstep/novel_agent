package com.novel.agent.service;

import com.novel.agent.entity.KnowledgeSegment;
import com.novel.agent.utils.NovelCategoryGuesser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class NovelTxtParser {

    private final NovelCategoryGuesser categoryGuesser;

    private static final int MIN_LENGTH = 200;
    private static final int MAX_LENGTH = 2000;

    // 章节标题正则
    private static final Pattern CHAPTER_PATTERN = Pattern.compile(
            "(第[一二三四五六七八九十百千零\\d]+[章回节部卷]\\s*[^。\\n]{0,50})" +
            "|(序章|楔子|尾声|后记|番外[^。\\n]{0,30})" +
            "|(第[0-9]+章\\s*[^。\\n]{0,50})"
    );

    // 广告正则
    private static final Pattern[] AD_PATTERNS = new Pattern[]{
            Pattern.compile("^.{0,10}(?:推荐|收藏|投票|订阅|加群|Q群|微信公众号|qq群).{0,20}$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^.{0,5}(?:本章完|未完待续|感谢支持|求月票|求推荐).{0,20}$"),
            Pattern.compile("^[.\\-_*#~\\s]{10,}$"),
    };

    public List<KnowledgeSegment> parse(Path filePath) {
        List<KnowledgeSegment> segments = new ArrayList<>();
        String fileName = filePath.getFileName().toString().replace(".txt", "");

        StringBuilder fullText = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(filePath.toFile()), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                fullText.append(line).append("\n");
            }
        } catch (Exception e) {
            log.error("读取文件失败: {}", filePath, e);
            return segments;
        }

        String text = fullText.toString();
        String category = categoryGuesser.guess(fileName,
                text.substring(0, Math.min(500, text.length())));

        // 按章节分割
        Matcher matcher = CHAPTER_PATTERN.matcher(text);
        List<int[]> chapterRanges = new ArrayList<>();
        String currentChapter = "开头";

        while (matcher.find()) {
            chapterRanges.add(new int[]{matcher.start(), matcher.end()});
        }

        int lastEnd = 0;
        for (int i = 0; i <= chapterRanges.size(); i++) {
            String chapterTitle;
            int startPos;
            int endPos;

            if (i < chapterRanges.size()) {
                int[] range = chapterRanges.get(i);
                chapterTitle = text.substring(range[0], range[1]).trim();
                startPos = range[1];
                endPos = (i + 1 < chapterRanges.size()) ? chapterRanges.get(i + 1)[0] : text.length();
            } else {
                chapterTitle = currentChapter;
                startPos = lastEnd;
                endPos = text.length();
            }

            String chapterContent = text.substring(startPos, endPos).trim();

            // 按段落拆分
            String[] paragraphs = chapterContent.split("\\n");
            for (String para : paragraphs) {
                para = para.trim();
                if (para.isEmpty()) continue;

                // 过滤广告
                if (isAdvertisement(para)) continue;

                // 长度过滤
                if (para.length() < MIN_LENGTH) continue;
                if (para.length() > MAX_LENGTH) {
                    para = para.substring(0, MAX_LENGTH);
                }

                segments.add(KnowledgeSegment.builder()
                        .source(fileName)
                        .category(category)
                        .chapter(chapterTitle)
                        .content(para)
                        .contentHash(md5(para))
                        .contentLength(para.length())
                        .build());
            }

            currentChapter = chapterTitle;
            lastEnd = endPos;
        }

        log.info("解析 [{}]: 提取 {} 个段落, 分类: {}", fileName, segments.size(), category);
        return segments;
    }

    private boolean isAdvertisement(String text) {
        if (text.length() < 20) return true;
        for (Pattern pattern : AD_PATTERNS) {
            if (pattern.matcher(text).find()) return true;
        }
        return false;
    }

    private String md5(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}