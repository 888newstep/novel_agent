package com.novel.agent.service;

import com.novel.agent.config.AiProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek API 调用服务
 * 提供两种调用方式：
 * 1. 通过 langchain4j ChatLanguageModel（推荐）
 * 2. 通过 RestTemplate 直接调用 DeepSeek API
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekService {

    private final ChatLanguageModel chatLanguageModel;
    private final AiProperties aiProperties;

    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(30).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 使用 langchain4j 调用 DeepSeek 生成文本
     */
    public String chat(String systemPrompt, String userPrompt) {
        String fullPrompt = buildPrompt(systemPrompt, userPrompt);
        String response = chatLanguageModel.generate(fullPrompt);
        log.info("DeepSeek 生成完成，长度: {} 字符", response.length());
        return response;
    }

    /**
     * 直接调用 DeepSeek API（使用 RestTemplate，支持更多参数控制）
     */
    public String chatDirect(String systemPrompt, String userPrompt,
                              double temperature, int maxTokens) {
        String apiKey = aiProperties.getModel().getDeepseek().getApiKey();
        String baseUrl = aiProperties.getModel().getDeepseek().getBaseUrl();

        Map<String, Object> requestBody = new java.util.HashMap<>();
        requestBody.put("model", "deepseek-chat");
        requestBody.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)
        ));
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        requestBody.put("stream", false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        @SuppressWarnings("unchecked")
        var response = restTemplate.exchange(
                baseUrl + "/chat/completions",
                HttpMethod.POST,
                entity,
                Map.class
        );

        Map<String, Object> body = response.getBody();
        if (body == null) {
            throw new RuntimeException("DeepSeek API 返回空响应");
        }

        @SuppressWarnings("unchecked")
        var choices = (List<Map<String, Object>>) body.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("DeepSeek API 返回空的 choices 列表");
        }

        var message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new RuntimeException("DeepSeek API 返回的消息中缺少 message 字段");
        }

        String content = (String) message.get("content");
        if (content == null) {
            throw new RuntimeException("DeepSeek API 返回的消息中缺少 content 字段");
        }

        return content;
    }

    private String buildPrompt(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return userPrompt;
        }
        return "【系统指令】\n" + systemPrompt + "\n\n【用户输入】\n" + userPrompt;
    }
}