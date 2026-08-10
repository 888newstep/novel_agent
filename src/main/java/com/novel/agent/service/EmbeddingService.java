package com.novel.agent.service;

import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.agent.config.AiProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private static final int EMBEDDING_CACHE_SIZE = 512;

    private final AiProperties aiProperties;
    private final TokenCostService tokenCostService;

    private RestTemplate restTemplate;
    private String provider;
    private String baseUrl;
    private String modelName;
    private int dimension;
    private Map<String, List<Float>> embeddingCache;
    private String apiKey;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(180_000);
        factory.setReadTimeout(600_000);
        this.restTemplate = new RestTemplate(factory);

        AiProperties.Embedding embeddingConfig = aiProperties.getEmbedding();
        this.provider = embeddingConfig.getProvider();

        switch (provider) {
            case "siliconflow" -> {
                AiProperties.SiliconflowEmbedding config = embeddingConfig.getSiliconflow();
                this.baseUrl = config.getBaseUrl();
                this.modelName = config.getModelName();
                this.dimension = config.getDimension();
                this.apiKey = config.getApiKey();
                log.info("Embedding provider: siliconflow, model: {}, dim: {}", modelName, dimension);
            }
            default -> {
                AiProperties.OllamaEmbedding config = embeddingConfig.getOllama();
                this.provider = "ollama";
                this.baseUrl = config.getBaseUrl();
                this.modelName = config.getModelName();
                this.dimension = config.getDimension();
                this.apiKey = null;
                log.info("Embedding provider: ollama, model: {}, dim: {}", modelName, dimension);

                List<MappingJackson2HttpMessageConverter> converters = restTemplate.getMessageConverters().stream()
                        .filter(c -> c instanceof MappingJackson2HttpMessageConverter)
                        .map(c -> (MappingJackson2HttpMessageConverter) c)
                        .toList();
                for (MappingJackson2HttpMessageConverter converter : converters) {
                    ObjectMapper mapper = converter.getObjectMapper();
                    mapper.getFactory().configure(JsonWriteFeature.ESCAPE_NON_ASCII.mappedFeature(), true);
                }
            }
        }

        this.embeddingCache = Collections.synchronizedMap(new LinkedHashMap<>(EMBEDDING_CACHE_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Float>> eldest) {
                return size() > EMBEDDING_CACHE_SIZE;
            }
        });
    }

    public String preprocess(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\s+", " ");
    }

    public List<Float> generateEmbedding(String text) {
        String cleanText = preprocess(text);
        if (cleanText.isEmpty()) {
            return List.of();
        }

        List<Float> cached = embeddingCache.get(cleanText);
        if (cached != null) {
            return cached;
        }

        TokenCostService.UsageReservation reservation = tokenCostService.reserveEmbeddingRequest(
                provider, modelName, "embedding.single", List.of(cleanText)
        );
        try {
            List<Float> result = callActiveProvider(List.of(cleanText)).get(0);
            List<Float> immutable = List.copyOf(result);
            embeddingCache.put(cleanText, immutable);
            tokenCostService.recordEmbeddingSuccess(reservation, tokenCostService.estimateTokens(cleanText));
            return immutable;
        } catch (RuntimeException ex) {
            tokenCostService.recordFailure(reservation, ex.getMessage());
            List<Float> fallback = tryOllamaFallback(List.of(cleanText), "embedding.single_fallback", ex.getMessage()).stream()
                    .findFirst()
                    .orElse(null);
            if (fallback != null) {
                List<Float> immutable = List.copyOf(fallback);
                embeddingCache.put(cleanText, immutable);
                return immutable;
            }
            throw ex;
        }
    }

    public List<List<Float>> batchGenerateEmbedding(List<String> texts) {
        List<String> cleanTexts = texts.stream().map(this::preprocess).toList();
        List<List<Float>> results = new ArrayList<>(Collections.nCopies(cleanTexts.size(), null));
        List<String> missingTexts = new ArrayList<>();
        List<Integer> missingIndexes = new ArrayList<>();

        for (int i = 0; i < cleanTexts.size(); i++) {
            String cleanText = cleanTexts.get(i);
            if (cleanText.isEmpty()) {
                results.set(i, List.of());
                continue;
            }
            List<Float> cached = embeddingCache.get(cleanText);
            if (cached != null) {
                results.set(i, cached);
                continue;
            }
            missingTexts.add(cleanText);
            missingIndexes.add(i);
        }

        if (missingTexts.isEmpty()) {
            return results;
        }

        TokenCostService.UsageReservation reservation = tokenCostService.reserveEmbeddingRequest(
                provider, modelName, "embedding.batch", missingTexts
        );

        try {
            List<List<Float>> generated = callActiveProvider(missingTexts);
            fillGeneratedResults(results, missingTexts, missingIndexes, generated);
            tokenCostService.recordEmbeddingSuccess(reservation, tokenCostService.estimateTokens(missingTexts));
            return results;
        } catch (RuntimeException ex) {
            tokenCostService.recordFailure(reservation, ex.getMessage());
            log.error("Batch embedding failed (batch={}, provider={})", texts.size(), provider, ex);
            List<List<Float>> fallbackGenerated = tryOllamaFallback(missingTexts, "embedding.batch_fallback", ex.getMessage());
            if (!fallbackGenerated.isEmpty()) {
                fillGeneratedResults(results, missingTexts, missingIndexes, fallbackGenerated);
                return results;
            }
            for (int i = 0; i < missingTexts.size(); i++) {
                String missingText = missingTexts.get(i);
                try {
                    results.set(missingIndexes.get(i), generateEmbedding(missingText));
                } catch (Exception singleEx) {
                    log.error("Single embedding fallback failed: {}", missingText.substring(0, Math.min(50, missingText.length())), singleEx);
                    results.set(missingIndexes.get(i), null);
                }
            }
            return results;
        }
    }

    private void fillGeneratedResults(List<List<Float>> results,
                                      List<String> missingTexts,
                                      List<Integer> missingIndexes,
                                      List<List<Float>> generated) {
        for (int i = 0; i < missingTexts.size(); i++) {
            List<Float> embedding = i < generated.size() ? generated.get(i) : null;
            List<Float> immutable = embedding == null ? null : List.copyOf(embedding);
            if (immutable != null) {
                embeddingCache.put(missingTexts.get(i), immutable);
            }
            results.set(missingIndexes.get(i), immutable);
        }
    }

    private List<List<Float>> tryOllamaFallback(List<String> texts, String source, String failureReason) {
        if (!aiProperties.getCostControl().isDegradeOnEmbeddingFailure()) {
            return List.of();
        }
        if ("ollama".equals(provider)) {
            return List.of();
        }

        AiProperties.OllamaEmbedding ollama = aiProperties.getEmbedding().getOllama();
        tokenCostService.recordDegradation(
                "EMBEDDING_FAILURE",
                source,
                failureReason,
                null,
                provider,
                modelName,
                source
        );

        TokenCostService.UsageReservation fallbackReservation = tokenCostService.reserveEmbeddingRequest(
                "ollama",
                ollama.getModelName(),
                source,
                texts
        );
        try {
            List<List<Float>> generated = callOllama(texts, ollama.getBaseUrl(), ollama.getModelName(), ollama.getDimension());
            tokenCostService.recordEmbeddingSuccess(fallbackReservation, tokenCostService.estimateTokens(texts));
            return generated;
        } catch (RuntimeException fallbackEx) {
            tokenCostService.recordFailure(fallbackReservation, fallbackEx.getMessage());
            tokenCostService.recordDegradation(
                    "EMBEDDING_FAILURE",
                    source + "_failed",
                    fallbackEx.getMessage(),
                    null,
                    "ollama",
                    ollama.getModelName(),
                    source
            );
            return List.of();
        }
    }

    private List<List<Float>> callActiveProvider(List<String> texts) {
        return switch (provider) {
            case "siliconflow" -> callSiliconflow(texts);
            default -> callOllama(texts, baseUrl, modelName, dimension);
        };
    }

    private List<List<Float>> callOllama(List<String> texts, String currentBaseUrl, String currentModelName, int currentDimension) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", currentModelName);
        request.put("input", texts.size() == 1 ? texts.get(0) : texts);
        request.put("dimensions", currentDimension);
        Map<String, Object> response = postForEmbed(currentBaseUrl + "/api/embed", request);
        @SuppressWarnings("unchecked")
        List<List<Double>> embeddings = (List<List<Double>>) response.get("embeddings");
        if (embeddings == null || embeddings.isEmpty()) {
            throw new RuntimeException("Ollama batch embedding result is empty");
        }
        List<List<Float>> generatedResults = new ArrayList<>();
        for (List<Double> emb : embeddings) {
            if (emb == null) {
                generatedResults.add(null);
            } else {
                generatedResults.add(emb.stream().map(Double::floatValue).toList());
            }
        }
        return generatedResults;
    }

    private Map<String, Object> postForEmbed(String url, Map<String, Object> request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
        if (response == null) {
            throw new RuntimeException("Embedding provider returned empty response");
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<List<Float>> callSiliconflow(List<String> texts) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", modelName);
        request.put("input", texts.size() == 1 ? texts.get(0) : texts);
        request.put("encoding_format", "float");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        String url = baseUrl + "/embeddings";
        Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
        if (response == null) {
            throw new RuntimeException("SiliconFlow returned empty response");
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("SiliconFlow response missing embedding data");
        }

        data.sort(Comparator.comparingInt(item -> ((Number) item.getOrDefault("index", 0)).intValue()));
        List<List<Float>> results = new ArrayList<>();
        for (Map<String, Object> item : data) {
            List<Double> embedding = (List<Double>) item.get("embedding");
            results.add(embedding == null ? null : embedding.stream().map(Double::floatValue).toList());
        }
        return results;
    }
}
