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

/**
 * Embedding ?? ? ??? provider ??
 */
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

    @SuppressWarnings("unchecked")
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
            List<Float> result = switch (provider) {
                case "siliconflow" -> callSiliconflow(List.of(cleanText)).get(0);
                default -> {
                    Map<String, Object> request = buildOllamaRequest(cleanText);
                    Map<String, Object> response = postForEmbed(buildOllamaUrl(), request);
                    List<List<Double>> embeddings = (List<List<Double>>) response.get("embeddings");
                    if (embeddings == null || embeddings.isEmpty()) {
                        throw new RuntimeException("Ollama embedding result is empty");
                    }
                    List<Double> embedding = embeddings.get(0);
                    if (embedding == null) {
                        throw new RuntimeException("Ollama embedding vector is null");
                    }
                    yield embedding.stream().map(Double::floatValue).toList();
                }
            };

            List<Float> immutable = List.copyOf(result);
            embeddingCache.put(cleanText, immutable);
            tokenCostService.recordEmbeddingSuccess(reservation, tokenCostService.estimateTokens(cleanText));
            return immutable;
        } catch (RuntimeException ex) {
            tokenCostService.recordFailure(reservation, ex.getMessage());
            throw ex;
        }
    }

    @SuppressWarnings("unchecked")
    public List<List<Float>> batchGenerateEmbedding(List<String> texts) {
        List<String> cleanTexts = texts.stream()
                .map(this::preprocess)
                .toList();

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
            List<List<Float>> generated = switch (provider) {
                case "siliconflow" -> callSiliconflow(missingTexts);
                default -> {
                    Map<String, Object> request = buildOllamaRequest(missingTexts);
                    Map<String, Object> response = postForEmbed(buildOllamaUrl(), request);
                    List<List<Double>> embeddings = (List<List<Double>>) response.get("embeddings");
                    if (embeddings == null || embeddings.isEmpty()) {
                        throw new RuntimeException("Ollama batch embedding result is empty");
                    }
                    List<List<Float>> generatedResults = new ArrayList<>();
                    for (List<Double> emb : embeddings) {
                        generatedResults.add(emb.stream().map(Double::floatValue).toList());
                    }
                    yield generatedResults;
                }
            };

            for (int i = 0; i < missingTexts.size(); i++) {
                List<Float> embedding = generated.get(i);
                List<Float> immutable = embedding == null ? null : List.copyOf(embedding);
                if (immutable != null) {
                    embeddingCache.put(missingTexts.get(i), immutable);
                }
                results.set(missingIndexes.get(i), immutable);
            }

            tokenCostService.recordEmbeddingSuccess(reservation, tokenCostService.estimateTokens(missingTexts));
            return results;
        } catch (Exception e) {
            log.error("Batch embedding failed (batch={}, provider={})", texts.size(), provider, e);
            for (int i = 0; i < missingTexts.size(); i++) {
                String text = missingTexts.get(i);
                try {
                    results.set(missingIndexes.get(i), generateEmbedding(text));
                } catch (Exception ex) {
                    log.error("Single embedding fallback failed: {}", text.substring(0, Math.min(50, text.length())), ex);
                    results.set(missingIndexes.get(i), null);
                }
            }
            return results;
        }
    }

    private String buildOllamaUrl() {
        return baseUrl + "/api/embed";
    }

    private Map<String, Object> buildOllamaRequest(Object input) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", modelName);
        request.put("input", input);
        request.put("dimensions", dimension);
        return request;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> postForEmbed(String url, Map<String, Object> request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
        if (response == null) {
            throw new RuntimeException("Ollama ?????");
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private List<List<Float>> callSiliconflow(List<String> texts) {
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", modelName);
        request.put("input", texts.size() == 1 ? texts.get(0) : texts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        String url = baseUrl + "/embeddings";
        log.info("?????? API: {} ???, model={}", texts.size(), modelName);
        long start = System.currentTimeMillis();
        Map<String, Object> response;
        try {
            response = restTemplate.postForObject(url, entity, Map.class);
        } catch (Exception e) {
            log.error("???? API ????: {}ms - {}", System.currentTimeMillis() - start, e.getMessage());
            throw e;
        }
        log.info("???? API ????: {}ms", System.currentTimeMillis() - start);
        if (response == null) {
            throw new RuntimeException("SiliconFlow ?????");
        }

        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("SiliconFlow ??? data ????");
        }

        data.sort(Comparator.comparingInt(item -> ((Number) item.getOrDefault("index", 0)).intValue()));

        List<List<Float>> results = new ArrayList<>();
        for (Map<String, Object> item : data) {
            List<Double> embedding = (List<Double>) item.get("embedding");
            if (embedding == null) {
                results.add(null);
            } else {
                results.add(embedding.stream().map(Double::floatValue).toList());
            }
        }
        return results;
    }
}
