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
import java.util.List;
import java.util.Map;

/**
 * Embedding 服务 — 支持双 provider 切换
 * <p>
 * provider = ollama（默认）：调用本地 WSL2 Ollama /api/embed
 * provider = siliconflow：调用硅基流动 BAAI/bge-m3 API
 * <p>
 * 优化策略：
 * - 连接超时 120s，适配大 batch 推理
 * - 文本预处理：合并多余空白，减少 token 消耗
 * - 批量调用 provider 的 batch API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final AiProperties aiProperties;

    private RestTemplate restTemplate;
    private String provider;
    private String baseUrl;
    private String modelName;
    private int dimension;
    private String apiKey; // 仅 siliconflow provider 使用

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(180_000);
        factory.setReadTimeout(600_000);
        this.restTemplate = new RestTemplate(factory);

        // 从配置加载 provider 参数
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
                // 默认 ollama
                AiProperties.OllamaEmbedding config = embeddingConfig.getOllama();
                this.baseUrl = config.getBaseUrl();
                this.modelName = config.getModelName();
                this.dimension = config.getDimension();
                this.apiKey = null;
                log.info("Embedding provider: ollama, model: {}, dim: {}", modelName, dimension);

                // Ollama 0.17.5 cannot parse raw UTF-8 in JSON, must escape non-ASCII
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
    }

    /**
     * 文本预处理：合并多余换行、连续空格，减少 token 消耗
     */
    public String preprocess(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("\\s+", " ");
    }

    /**
     * 生成单条文本向量
     */
    @SuppressWarnings("unchecked")
    public List<Float> generateEmbedding(String text) {
        String cleanText = preprocess(text);
        List<Float> result = switch (provider) {
            case "siliconflow" -> callSiliconflow(List.of(cleanText)).get(0);
            default -> {
                Map<String, Object> request = buildOllamaRequest(cleanText);
                Map<String, Object> response = postForEmbed(buildOllamaUrl(), request);
                List<List<Double>> embeddings = (List<List<Double>>) response.get("embeddings");
                if (embeddings == null || embeddings.isEmpty()) {
                    throw new RuntimeException("Ollama 返回的 embeddings 列表为空");
                }
                List<Double> embedding = embeddings.get(0);
                if (embedding == null) {
                    throw new RuntimeException("Ollama 返回的 embedding 向量为空");
                }
                yield embedding.stream().map(Double::floatValue).toList();
            }
        };
        return result;
    }

    /**
     * 批量生成向量
     */
    @SuppressWarnings("unchecked")
    public List<List<Float>> batchGenerateEmbedding(List<String> texts) {
        List<String> cleanTexts = texts.stream()
                .map(this::preprocess)
                .toList();

        try {
            return switch (provider) {
                case "siliconflow" -> callSiliconflow(cleanTexts);
                default -> {
                    Map<String, Object> request = buildOllamaRequest(cleanTexts);
                    Map<String, Object> response = postForEmbed(buildOllamaUrl(), request);
                    List<List<Double>> embeddings = (List<List<Double>>) response.get("embeddings");
                    if (embeddings == null || embeddings.isEmpty()) {
                        throw new RuntimeException("Ollama 批量请求返回的 embeddings 列表为空");
                    }
                    log.debug("批量生成向量 {} 条，返回 {} 条", texts.size(), embeddings.size());
                    List<List<Float>> results = new ArrayList<>();
                    for (List<Double> emb : embeddings) {
                        results.add(emb.stream().map(Double::floatValue).toList());
                    }
                    yield results;
                }
            };
        } catch (Exception e) {
            log.error("批量生成向量失败 (batch={}, provider={})", texts.size(), provider, e);
            // 降级：逐条重试
            List<List<Float>> results = new ArrayList<>();
            for (String text : cleanTexts) {
                try {
                    results.add(generateEmbedding(text));
                } catch (Exception ex) {
                    log.error("单条生成向量失败: {}", text.substring(0, Math.min(50, text.length())), ex);
                    results.add(null);
                }
            }
            return results;
        }
    }

    // =============================================
    // Ollama 调用
    // =============================================

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
            throw new RuntimeException("Ollama 返回空响应");
        }
        return response;
    }

    // =============================================
    // SiliconFlow 调用（OpenAI 兼容接口）
    // =============================================

    @SuppressWarnings("unchecked")
    private List<List<Float>> callSiliconflow(List<String> texts) {
        // 构建请求体
        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", modelName);
        if (texts.size() == 1) {
            request.put("input", texts.get(0));
        } else {
            request.put("input", texts);
        }

        // 设置 headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        headers.setBearerAuth(apiKey);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        // 发送请求
        String url = baseUrl + "/embeddings";
        Map<String, Object> response = restTemplate.postForObject(url, entity, Map.class);
        if (response == null) {
            throw new RuntimeException("SiliconFlow 返回空响应");
        }

        // 解析响应
        List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
        if (data == null || data.isEmpty()) {
            throw new RuntimeException("SiliconFlow 返回的 data 列表为空");
        }

        log.debug("SiliconFlow 批量生成向量 {} 条，返回 {} 条", texts.size(), data.size());
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