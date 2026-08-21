package com.novel.agent.service;

import com.novel.agent.config.AiProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {

    @Test
    void batchEmbeddingDeduplicatesNormalizedTextsAndRestoresInputOrder() {
        AiProperties properties = new AiProperties();
        properties.getEmbedding().setProvider("siliconflow");
        properties.getEmbedding().getSiliconflow().setApiKey("test-key");

        TokenCostService tokenCostService = mock(TokenCostService.class);
        TokenCostService.UsageReservation reservation = TokenCostService.UsageReservation.builder().build();
        when(tokenCostService.reserveEmbeddingRequest(
                eq("siliconflow"), eq("BAAI/bge-m3"), eq("embedding.batch"), any()))
                .thenReturn(reservation);
        when(tokenCostService.estimateTokens(any(List.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> texts = invocation.getArgument(0, List.class);
            return texts.stream().mapToInt(String::length).sum();
        });

        EmbeddingService service = new EmbeddingService(properties, tokenCostService);
        service.init();

        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", List.of(
                Map.of("index", 0, "embedding", List.of(1.0, 2.0)),
                Map.of("index", 1, "embedding", List.of(3.0, 4.0))
        ));
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Map.class))).thenReturn(response);

        List<List<Float>> result = service.batchGenerateEmbedding(List.of(
                " same   text ",
                "same\ttext",
                "different",
                "same\ntext"
        ));
        List<List<Float>> cachedResult = service.batchGenerateEmbedding(List.of(
                " same   text ",
                "same\ttext",
                "different",
                "same\ntext"
        ));

        assertThat(result).containsExactly(
                List.of(1.0F, 2.0F),
                List.of(1.0F, 2.0F),
                List.of(3.0F, 4.0F),
                List.of(1.0F, 2.0F)
        );
        assertThat(cachedResult).isEqualTo(result);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate, times(1)).postForObject(anyString(), entityCaptor.capture(), eq(Map.class));
        @SuppressWarnings("unchecked")
        Map<String, Object> requestBody = (Map<String, Object>) entityCaptor.getValue().getBody();
        assertThat(requestBody.get("input")).isEqualTo(List.of("same text", "different"));
        verify(tokenCostService).reserveEmbeddingRequest(
                "siliconflow", "BAAI/bge-m3", "embedding.batch", List.of("same text", "different"));
    }

    @Test
    void batchEmbeddingDoesNotCacheLongSourceTextAcrossRequests() {
        AiProperties properties = new AiProperties();
        properties.getEmbedding().setProvider("siliconflow");
        properties.getEmbedding().setCacheMaxTextChars(32);
        properties.getEmbedding().getSiliconflow().setApiKey("test-key");

        TokenCostService tokenCostService = mock(TokenCostService.class);
        TokenCostService.UsageReservation reservation = TokenCostService.UsageReservation.builder().build();
        when(tokenCostService.reserveEmbeddingRequest(
                eq("siliconflow"), eq("BAAI/bge-m3"), eq("embedding.batch"), any()))
                .thenReturn(reservation);

        EmbeddingService service = new EmbeddingService(properties, tokenCostService);
        service.init();

        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        Map<String, Object> response = Map.of("data", List.of(
                Map.of("index", 0, "embedding", List.of(1.0, 2.0))
        ));
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Map.class))).thenReturn(response);

        String longText = "chapter source text that exceeds cache length";
        assertThat(service.batchGenerateEmbedding(List.of(longText, longText)))
                .containsExactly(List.of(1.0F, 2.0F), List.of(1.0F, 2.0F));
        assertThat(service.batchGenerateEmbedding(List.of(longText, longText)))
                .containsExactly(List.of(1.0F, 2.0F), List.of(1.0F, 2.0F));

        verify(restTemplate, times(2)).postForObject(anyString(), any(HttpEntity.class), eq(Map.class));
        verify(tokenCostService, times(2)).reserveEmbeddingRequest(
                "siliconflow", "BAAI/bge-m3", "embedding.batch", List.of(longText));
    }

    @Test
    void requestCacheReusesLongTextAcrossMultipleBatchesWithoutPersistingIt() {
        AiProperties properties = new AiProperties();
        properties.getEmbedding().setProvider("siliconflow");
        properties.getEmbedding().setCacheMaxTextChars(32);
        properties.getEmbedding().getSiliconflow().setApiKey("test-key");

        TokenCostService tokenCostService = mock(TokenCostService.class);
        TokenCostService.UsageReservation reservation = TokenCostService.UsageReservation.builder().build();
        when(tokenCostService.reserveEmbeddingRequest(
                eq("siliconflow"), eq("BAAI/bge-m3"), eq("embedding.batch"), any()))
                .thenReturn(reservation);

        EmbeddingService service = new EmbeddingService(properties, tokenCostService);
        service.init();
        RestTemplate restTemplate = mock(RestTemplate.class);
        ReflectionTestUtils.setField(service, "restTemplate", restTemplate);
        Map<String, Object> response = Map.of("data", List.of(
                Map.of("index", 0, "embedding", List.of(1.0, 2.0))
        ));
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(Map.class))).thenReturn(response);

        String longText = "a long chapter query that bypasses the persistent cache";
        service.withRequestCache(() -> {
            assertThat(service.batchGenerateEmbedding(List.of(longText))).containsExactly(List.of(1.0F, 2.0F));
            assertThat(service.batchGenerateEmbedding(List.of(longText))).containsExactly(List.of(1.0F, 2.0F));
            return null;
        });
        service.batchGenerateEmbedding(List.of(longText));

        verify(restTemplate, times(2)).postForObject(anyString(), any(HttpEntity.class), eq(Map.class));
    }
}
