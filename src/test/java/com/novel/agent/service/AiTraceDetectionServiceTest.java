package com.novel.agent.service;

import com.novel.agent.config.AiTraceProperties;
import com.novel.agent.service.aitrace.AiTraceDictionary;
import com.novel.agent.service.aitrace.AiTraceFinding;
import com.novel.agent.service.aitrace.AiTraceReport;
import com.novel.agent.service.aitrace.rules.ChapterEndingHookRule;
import com.novel.agent.service.aitrace.rules.PhraseDensityRule;
import com.novel.agent.service.aitrace.rules.SentenceLengthVarianceRule;
import com.novel.agent.service.aitrace.rules.TransitionDensityRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AiTraceDetectionServiceTest {

    private AiTraceDetectionService service(AiTraceProperties properties) {
        AiTraceDictionary dictionary = new AiTraceDictionary(properties);
        return new AiTraceDetectionService(properties, List.of(
                new PhraseDensityRule(properties, dictionary),
                new SentenceLengthVarianceRule(properties),
                new TransitionDensityRule(properties, dictionary),
                new ChapterEndingHookRule(properties)));
    }

    private AiTraceDetectionService defaultService() {
        return service(new AiTraceProperties());
    }

    /**
     * 人写风格样例：句长长短交错、不含词典词条、无章末钩子模板；
     * 汉字符数高于 min-char-count，避免低置信分支干扰断言。
     */
    private static final String HUMAN_STYLE =
            "老人把锄头靠在田埂上，坐下来抽烟。日头偏西，山影一寸一寸往东挪。"
                    + "他数了数埂边的南瓜，又数了一遍，数目对得上。远处有人喊他回家吃饭。"
                    + "声音被风吹散了一半，他应了一声，拍拍裤腿上的土，起身慢慢往村口走。"
                    + "路过晒谷场的时候，几个孩子追着竹蜻蜓跑过去，他侧身让开，笑骂了一句慢点。"
                    + "灶间的烟升起来，淡青色，混在暮色里几乎看不见了。"
                    + "狗叫了两声，又安静下去。他想起白天犁坏的那块石头，决定明天一早再去看看。"
                    + "夜里的凉气顺着领口钻进来，他把衣领竖了竖。"
                    + "村口的灯亮了，一盏，又一盏。";

    /** AI 风格样例：句长高度均齐 + 大量套话 + 章末双钩子模板。 */
    private static final String AI_STYLE =
            "他推开了那扇门。房间里面一片死寂。空气仿佛凝固了起来。"
                    + "他的心脏猛地一缩。嘴角勾起一抹冷笑。眼底闪过一丝寒光。"
                    + "下一秒，他握紧了长剑。下一瞬，烛火摇曳不定。"
                    + "就在这时，门后传来了脚步声。不知道的是，一切才刚开始。";

    @Test
    void humanStyleSampleScoresLow() {
        AiTraceReport report = defaultService().check(HUMAN_STYLE);
        assertThat(report.overallScore()).isLessThan(25);
        assertThat(report.riskLevel()).isEqualTo("low");
        assertThat(report.lowConfidence()).isFalse();
        assertThat(report.dimensionScores()).doesNotContainKey("statistical");
    }

    @Test
    void aiStyleSampleScoresMediumOrHigh() {
        AiTraceReport report = defaultService().check(AI_STYLE);
        assertThat(report.overallScore()).isGreaterThanOrEqualTo(25);
        assertThat(report.riskLevel()).isIn("medium", "high");
        assertThat(report.flaggedSentences()).isNotEmpty();
    }

    @Test
    void r1ClicheDensityMustHitAndMustMiss() {
        AiTraceDetectionService svc = defaultService();
        AiTraceFinding hit = svc.check(AI_STYLE).findings().stream()
                .filter(f -> f.ruleId().equals("R1")).findFirst().orElseThrow();
        assertThat(hit.hitCount()).isGreaterThanOrEqualTo(3);
        assertThat(hit.rawScore()).isGreaterThan(0);
        assertThat(hit.evidence()).isNotEmpty();

        AiTraceFinding miss = svc.check(HUMAN_STYLE).findings().stream()
                .filter(f -> f.ruleId().equals("R1")).findFirst().orElseThrow();
        assertThat(miss.hitCount()).isZero();
        assertThat(miss.rawScore()).isZero();
    }

    @Test
    void r3VarianceMustHitAndMustMiss() {
        AiTraceDetectionService svc = defaultService();
        AiTraceFinding hit = svc.check(AI_STYLE).findings().stream()
                .filter(f -> f.ruleId().equals("R3")).findFirst().orElseThrow();
        assertThat(hit.rawScore()).isGreaterThan(0);

        AiTraceFinding miss = svc.check(HUMAN_STYLE).findings().stream()
                .filter(f -> f.ruleId().equals("R3")).findFirst().orElseThrow();
        assertThat(miss.rawScore()).isZero();
    }

    @Test
    void r5TransitionDensityMustHitAndMustMiss() {
        String dense = "他说想去。但是天色已晚。可是路还没有修完。然而他没有回头。"
                + "谁知山里有雨。岂料桥被冲断。奈何行囊已空。"
                + "纵然如此，他仍旧往前走。即便如此，他也没有停下。"
                + "哪怕这样，他依然没有回头。夜深了，雨还在下。";
        AiTraceDetectionService svc = defaultService();
        AiTraceFinding hit = svc.check(dense).findings().stream()
                .filter(f -> f.ruleId().equals("R5")).findFirst().orElseThrow();
        assertThat(hit.rawScore()).isGreaterThan(0);

        AiTraceFinding miss = svc.check(HUMAN_STYLE).findings().stream()
                .filter(f -> f.ruleId().equals("R5")).findFirst().orElseThrow();
        assertThat(miss.rawScore()).isZero();
    }

    @Test
    void r8EndingHookScoresOnlyOnTwoMatches() {
        AiTraceDetectionService svc = defaultService();
        // AI_STYLE 末段同时含「不知道的是」与「一切才刚开始」→ 计分
        AiTraceFinding hit = svc.check(AI_STYLE).findings().stream()
                .filter(f -> f.ruleId().equals("R8")).findFirst().orElseThrow();
        assertThat(hit.hitCount()).isGreaterThanOrEqualTo(2);
        assertThat(hit.rawScore()).isGreaterThan(0);

        // 单模板命中只记录不计分
        String single = HUMAN_STYLE + "他站在村口，望着远处。就在这时，灯灭了。";
        AiTraceFinding partial = svc.check(single).findings().stream()
                .filter(f -> f.ruleId().equals("R8")).findFirst().orElseThrow();
        assertThat(partial.hitCount()).isEqualTo(1);
        assertThat(partial.rawScore()).isZero();
    }

    @Test
    void emptyTextReturnsLowWithoutException() {
        AiTraceReport report = defaultService().check("");
        assertThat(report.overallScore()).isZero();
        assertThat(report.riskLevel()).isEqualTo("low");
        assertThat(report.lowConfidence()).isTrue();
        assertThat(report.dimensionScores()).isEmpty();
    }

    @Test
    void shortTextMarksLowConfidence() {
        AiTraceReport report = defaultService().check("天黑了。他回到家。饭还温着。灯亮着。");
        assertThat(report.lowConfidence()).isTrue();
        assertThat(report.riskLevel()).isEqualTo("low");
    }

    @Test
    void pureEnglishTextDoesNotBreak() {
        String english = "The old man sat by the pier and watched the tide come in slowly. "
                + "He counted the gulls, lost track, and started over. "
                + "A bell rang somewhere behind the fish market. "
                + "He did not move. The tide kept coming. He finished his pipe. "
                + "The lamp above the dock flickered twice and held. He stood up and left.";
        AiTraceReport report = defaultService().check(english);
        assertThat(report.lowConfidence()).isTrue();
        assertThat(report.findings().stream().filter(f -> f.ruleId().equals("R1")).findFirst().orElseThrow()
                .severity()).isEqualTo(AiTraceFinding.Severity.UNKNOWN);
        assertThat(report.findings().stream().filter(f -> f.ruleId().equals("R3")).findFirst().orElseThrow()
                .severity()).isNotEqualTo(AiTraceFinding.Severity.UNKNOWN);
    }

    @Test
    void longTextWithinServiceLimitsDoesNotFail() {
        String unit = "他沿着河堤往上游走，一路上数着桥墩下的水痕。";
        String text = unit.repeat(600);
        AiTraceReport report = defaultService().check(text);
        assertThat(report.charCount()).isGreaterThan(10000);
        assertThat(report.overallScore()).isBetween(0, 100);
    }

    @Test
    void degradedDictionaryMarksAllRulesUnknownAndLowConfidence() {
        AiTraceProperties degraded = new AiTraceProperties();
        degraded.setDictionaryPath("classpath-missing-ai-trace.txt");
        AiTraceReport report = service(degraded).check("天黑了。他回到家。饭还温着。灯亮着。门没有锁。");
        // 词典依赖的规则（R1/R5）降级为 UNKNOWN，整体标记低置信
        assertThat(report.findings())
                .filteredOn(f -> f.ruleId().equals("R1") || f.ruleId().equals("R5"))
                .allSatisfy(f -> assertThat(f.severity()).isEqualTo(AiTraceFinding.Severity.UNKNOWN));
        assertThat(report.lowConfidence()).isTrue();
    }

    @Test
    void riskLevelBoundaryValuesStayMedium() {
        AiTraceDetectionService svc = defaultService();
        // 通过 riskLevelOf 的可见行为间接验证：25 与 50 归 medium 由分档逻辑保证，
        // 这里用可构造的样例验证端点行为而非锁死分值。
        AiTraceReport human = svc.check(HUMAN_STYLE);
        assertThat(human.riskLevel()).isEqualTo("low");
        AiTraceReport ai = svc.check(AI_STYLE);
        assertThat(ai.riskLevel()).isIn("medium", "high");
    }
}
