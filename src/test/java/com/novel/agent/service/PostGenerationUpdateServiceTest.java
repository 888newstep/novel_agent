package com.novel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.agent.entity.Chapter;
import com.novel.agent.entity.KeyEvent;
import com.novel.agent.repository.ChapterRepository;
import com.novel.agent.repository.KeyEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostGenerationUpdateServiceTest {

    @Mock DeepSeekService deepSeekService;
    @Mock ChapterRepository chapterRepository;
    @Mock KeyEventRepository keyEventRepository;
    @Mock MilvusService milvusService;

    PostGenerationUpdateService service;

    @BeforeEach
    void setUp() {
        this.service = new PostGenerationUpdateService(
                deepSeekService, chapterRepository, keyEventRepository, milvusService, new ObjectMapper());
    }

    private static final String EXTRACTED_JSON = """
            {
              "chapterSummary": "少宫主夜探天极门藏经阁，遭遇神秘黑衣人。",
              "newForeshadowing": [
                {"title": "神秘玉佩发光", "description": "少宫主玉佩异动，似有指引之力", "involvedCharacters": ["少宫主"], "eventType": "foreshadowing"},
                {"title": "黑衣人身份", "description": "黑衣人似与十年前灭门案有关", "involvedCharacters": [], "eventType": "foreshadowing"}
              ],
              "resolvedForeshadowingKeywords": ["玉佩"]
            }
            """;

    @Test
    void postGenerate_normalFlow_aiExtractSuccess() {
        when(deepSeekService.chat(anyLong(), anyString(), anyString())).thenReturn(EXTRACTED_JSON);
        when(chapterRepository.findByNovelIdAndChapterNum(1L, 30)).thenReturn(Optional.empty());
        when(chapterRepository.save(any(Chapter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(keyEventRepository.findByNovelIdAndResolvedFalse(1L)).thenReturn(List.of(
                KeyEvent.builder().id(50L).title("少宫主玉佩").description("少宫主身上的玉佩").resolved(false).build()
        ));
        when(keyEventRepository.save(any(KeyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        String text = generateLongText(3000);
        var result = service.postGenerate(1L, 30, text);

        assertTrue(result.isAiExtracted());
        assertTrue(result.isSummaryPersisted());
        assertTrue(result.getWordCount() > 0);
        assertEquals(2, result.getNewForeshadowingCount());
        assertEquals(1, result.getResolvedForeshadowingCount());
        assertTrue(result.getSyncedSegmentCount() > 0);

        verify(chapterRepository).save(argThat(c ->
                c.getChapterNum() == 30
                && c.getWordCount() > 0
                && c.getSummary() != null && !c.getSummary().isBlank()
        ));
        verify(keyEventRepository, times(2)).save(argThat(ke ->
                !Boolean.TRUE.equals(ke.getResolved())
        ));
        verify(keyEventRepository).save(argThat(ke ->
                Boolean.TRUE.equals(ke.getResolved()) && ke.getResolvedAt() == 30
        ));
        verify(milvusService).insertChapterSegments(eq(1L), eq(30), argThat(segs -> !segs.isEmpty()));
    }

    @Test
    void postGenerate_aiExtractFails_fallbackToPlainSummary() {
        when(deepSeekService.chat(anyLong(), anyString(), anyString()))
                .thenThrow(new RuntimeException("API timeout"));
        when(chapterRepository.findByNovelIdAndChapterNum(1L, 30)).thenReturn(Optional.empty());
        when(chapterRepository.save(any(Chapter.class))).thenAnswer(inv -> inv.getArgument(0));

        String text = generateLongText(2500);
        var result = service.postGenerate(1L, 30, text);

        assertFalse(result.isAiExtracted());
        assertTrue(result.isSummaryPersisted());
        assertEquals(0, result.getNewForeshadowingCount());
        assertEquals(0, result.getResolvedForeshadowingCount());
        assertTrue(result.getSyncedSegmentCount() > 0);

        verify(deepSeekService).chat(anyLong(), anyString(), anyString());
        verify(milvusService).insertChapterSegments(anyLong(), anyInt(), anyList());
        verifyNoInteractions(keyEventRepository); // 没 AI 结果时不碰伏笔表
    }

    @Test
    void postGenerate_milvusSyncFails_doesNotAffectMainFlow() {
        when(deepSeekService.chat(anyLong(), anyString(), anyString())).thenReturn(EXTRACTED_JSON);
        when(chapterRepository.findByNovelIdAndChapterNum(1L, 30)).thenReturn(Optional.empty());
        when(chapterRepository.save(any(Chapter.class))).thenAnswer(inv -> inv.getArgument(0));
        when(keyEventRepository.findByNovelIdAndResolvedFalse(1L)).thenReturn(new ArrayList<>());
        when(keyEventRepository.save(any(KeyEvent.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("Milvus connection lost")).when(milvusService)
                .insertChapterSegments(anyLong(), anyInt(), anyList());

        String text = generateLongText(2000);
        var result = service.postGenerate(1L, 30, text);

        assertTrue(result.isAiExtracted());
        assertTrue(result.isSummaryPersisted());
        assertEquals(0, result.getSyncedSegmentCount()); // 失败了就 0
        assertEquals(2, result.getNewForeshadowingCount()); // MySQL 伏笔仍然入库

        verify(milvusService).insertChapterSegments(anyLong(), anyInt(), anyList());
    }

    @Test
    void postGenerate_keywordMatchingResolvesForeshadowing() {
        when(deepSeekService.chat(anyLong(), anyString(), anyString())).thenReturn(EXTRACTED_JSON);
        when(chapterRepository.findByNovelIdAndChapterNum(1L, 30)).thenReturn(Optional.empty());
        when(chapterRepository.save(any(Chapter.class))).thenAnswer(inv -> inv.getArgument(0));

        // 模拟 3 个未回收伏笔，其中 2 个匹配 "玉佩" 关键字
        List<KeyEvent> unresolved = List.of(
                KeyEvent.builder().id(50L).title("少宫主玉佩").description("少宫主身上的玉佩").resolved(false).build(),
                KeyEvent.builder().id(51L).title("玉佩传说").description("传说中的上古玉佩").resolved(false).build(),
                KeyEvent.builder().id(52L).title("黑衣人").description("神秘黑衣人").resolved(false).build()
        );
        when(keyEventRepository.findByNovelIdAndResolvedFalse(1L)).thenReturn(unresolved);
        when(keyEventRepository.save(any(KeyEvent.class))).thenAnswer(inv -> inv.getArgument(0));

        String text = generateLongText(2000);
        var result = service.postGenerate(1L, 30, text);

        assertEquals(2, result.getResolvedForeshadowingCount()); // 玉佩匹配 2 个

        verify(keyEventRepository, times(2)).save(argThat(ke ->
                ke.getId() != null && (ke.getId() == 50L || ke.getId() == 51L)
                && Boolean.TRUE.equals(ke.getResolved())
                && ke.getResolvedAt() == 30
        ));
    }

    private String generateLongText(int length) {
        StringBuilder sb = new StringBuilder();
        // 整段 ~300 字（无换行），确保超过 MIN_SEG_LEN=200
        String paragraph = """
                　　少宫主缓缓走近那扇古老的石门，手中的玉佩隐隐发出微光。
                十年前的灭门惨案仿佛还在眼前，他握紧了腰间的长剑，目光深邃而坚定。
                藏经阁内的烛火摇曳，一个神秘的黑衣人悄然出现在他身后，气息隐匿得几乎无迹可寻。
                他知道今晚注定不会平静，多年来的隐忍和修炼就是为了这一刻能够直面真相。
                石门上的符文闪烁了一下，仿佛在回应他体内涌动的灵力，周围的空气开始微微震颤。
                黑衣人似乎察觉到了什么，身形一闪就消失在了黑暗之中，只留下一缕淡淡的檀香味。""";
        paragraph = paragraph.replace("\n", ""); // 去掉所有换行，让整段作为一个 segment
        while (sb.length() < length) {
            sb.append(paragraph).append("\n\n");
        }
        return sb.substring(0, length);
    }
}
