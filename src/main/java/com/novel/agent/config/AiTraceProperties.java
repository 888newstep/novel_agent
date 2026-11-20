package com.novel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 痕迹自检器配置（前缀 {@code ai-trace}），全部可在 application.yml 覆盖。
 */
@Data
@Component
@ConfigurationProperties(prefix = "ai-trace")
public class AiTraceProperties {

    /** 是否启用检测服务。 */
    private boolean enabled = true;

    /** 词典文件（classpath 资源）。 */
    private String dictionaryPath = "ai_trace_phrases_zh.txt";

    /** R1：套话密度阈值（次/千字）。 */
    private double phraseDensityThreshold = 3.0;

    /** R5：转折词密度阈值（次/千字）。 */
    private double transitionDensityThreshold = 2.0;

    /** R3：句长标准差阈值（低于该值触发）。 */
    private double sentenceLengthSigmaThreshold = 6.0;

    /** R3：参与方差计算的最低句数，低于该值不判定。 */
    private int minSentencesForVariance = 8;

    /** R8：末 2 段至少命中的模板数，达到才计分。 */
    private int endingHookMinMatch = 2;

    /** 低于该汉字符数时结果标记为低置信。 */
    private int minCharCount = 200;

    /** flaggedSentences 返回上限。 */
    private int maxFlaggedSentences = 10;

    /** 规则版本号，随结果返回。 */
    private String rulesVersion = "aitrace-v1-p0";

    /** 免责声明，随结果返回。 */
    private String disclaimer = "启发式自检，非平台检测模型复刻，不构成过检保证";

    /** 端点接受的正文最大字符数。 */
    private int maxTextChars = 50000;

    /**
     * 生成后检查（buildPostGenerationCheck）使用的禁用词，来自词典 banned 分组。
     * 空列表表示禁用词检查关闭。
     */
    private List<String> bannedPhrases = List.of();
}
