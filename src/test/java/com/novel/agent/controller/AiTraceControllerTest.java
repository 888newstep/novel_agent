package com.novel.agent.controller;

import com.novel.agent.config.AiTraceProperties;
import com.novel.agent.entity.Chapter;
import com.novel.agent.repository.ChapterRepository;
import com.novel.agent.service.AiTraceDetectionService;
import com.novel.agent.service.aitrace.AiTraceDictionary;
import com.novel.agent.service.aitrace.rules.ChapterEndingHookRule;
import com.novel.agent.service.aitrace.rules.PhraseDensityRule;
import com.novel.agent.service.aitrace.rules.SentenceLengthVarianceRule;
import com.novel.agent.service.aitrace.rules.TransitionDensityRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiTraceControllerTest {

    private ChapterRepository chapterRepository;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AiTraceProperties properties = new AiTraceProperties();
        AiTraceDictionary dictionary = new AiTraceDictionary(properties);
        AiTraceDetectionService service = new AiTraceDetectionService(properties, List.of(
                new PhraseDensityRule(properties, dictionary),
                new SentenceLengthVarianceRule(properties),
                new TransitionDensityRule(properties, dictionary),
                new ChapterEndingHookRule(properties)));
        chapterRepository = Mockito.mock(ChapterRepository.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AiTraceController(service, chapterRepository)).build();
    }

    @Test
    void textModeReturnsFullContract() throws Exception {
        String body = "{\"text\":\"天黑了下来。他回到屋里。灶上还温着饭。灯还亮着。他坐了一会儿，又起身添了把柴。窗外起了风。\"}";
        mockMvc.perform(post("/api/v1/novel/ai-trace/check").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").isNumber())
                .andExpect(jsonPath("$.riskLevel").isString())
                .andExpect(jsonPath("$.lowConfidence").isBoolean())
                .andExpect(jsonPath("$.dimensionScores").isMap())
                .andExpect(jsonPath("$.findings").isArray())
                .andExpect(jsonPath("$.flaggedSentences").isArray())
                .andExpect(jsonPath("$.rulesVersion").value("aitrace-v1-p0"))
                .andExpect(jsonPath("$.disclaimer").isString())
                .andExpect(jsonPath("$.charCount").isNumber())
                .andExpect(jsonPath("$.hanCharCount").isNumber())
                .andExpect(jsonPath("$.sentenceCount").isNumber());
    }

    @Test
    void chapterModeDetectsSummaryAndKeyEvents() throws Exception {
        Chapter chapter = new Chapter();
        chapter.setNovelId(2L);
        chapter.setChapterNum(10);
        chapter.setSummary("他推开了那扇门。房间里面一片死寂。空气仿佛凝固了起来。他的心脏猛地一缩。");
        chapter.setKeyEvents("就在这时，门后传来了脚步声。不知道的是，一切才刚开始。");
        when(chapterRepository.findByNovelIdAndChapterNum(eq(2L), eq(10))).thenReturn(Optional.of(chapter));

        mockMvc.perform(post("/api/v1/novel/ai-trace/check").contentType(APPLICATION_JSON)
                        .content("{\"novelId\":2,\"chapterId\":10}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").isNumber())
                .andExpect(jsonPath("$.findings").isArray());
    }

    @Test
    void chapterNotFoundReturns404() throws Exception {
        when(chapterRepository.findByNovelIdAndChapterNum(eq(2L), eq(99))).thenReturn(Optional.empty());
        mockMvc.perform(post("/api/v1/novel/ai-trace/check").contentType(APPLICATION_JSON)
                        .content("{\"novelId\":2,\"chapterId\":99}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("CHAPTER_NOT_FOUND"));
    }

    @Test
    void bothModesOrNeitherIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/novel/ai-trace/check").contentType(APPLICATION_JSON)
                        .content("{\"text\":\"一段话\",\"novelId\":2,\"chapterId\":10}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/novel/ai-trace/check").contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // 只给 novelId 不给 chapterId，同样属于残缺的章节模式
        mockMvc.perform(post("/api/v1/novel/ai-trace/check").contentType(APPLICATION_JSON)
                        .content("{\"novelId\":2}"))
                .andExpect(status().isBadRequest());
    }
}
