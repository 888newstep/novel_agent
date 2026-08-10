package com.novel.agent.service;

import com.novel.agent.config.AiProperties;
import dev.langchain4j.model.chat.ChatLanguageModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeepSeekService {

    private final ChatLanguageModel chatLanguageModel;
    private final AiProperties aiProperties;
    private final TokenCostService tokenCostService;

    private RestTemplate restTemplate;

    @PostConstruct
    public void init() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(30).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(120).toMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    public String chat(String systemPrompt, String userPrompt) {
        return chat(null, systemPrompt, userPrompt);
    }

    public String chat(Long novelId, String systemPrompt, String userPrompt) {
        String fullPrompt = buildPrompt(systemPrompt, userPrompt);
        int reservedCompletionTokens = aiProperties.getCostControl().getReservedCompletionTokens();
        TokenCostService.UsageReservation reservation = tokenCostService.reserveChatRequest(
                novelId,
                currentProvider(),
                currentChatModelName(),
                "chat.generate",
                fullPrompt,
                reservedCompletionTokens
        );
        try {
            String response = chatLanguageModel.generate(fullPrompt);
            tokenCostService.recordChatSuccess(reservation, response, null, null);
            log.info("Chat generation finished, chars={}", response.length());
            return response;
        } catch (RuntimeException ex) {
            tokenCostService.recordFailure(reservation, ex.getMessage());
            if (canUseDirectFallback()) {
                tokenCostService.recordDegradation(
                        "MODEL_FAILURE",
                        "retry_via_direct_api",
                        ex.getMessage(),
                        novelId,
                        currentProvider(),
                        currentChatModelName(),
                        "chat.generate"
                );
                try {
                    return chatDirectInternal(novelId, systemPrompt, userPrompt, 0.7, reservedCompletionTokens, "chat.direct_fallback");
                } catch (RuntimeException fallbackEx) {
                    fallbackEx.addSuppressed(ex);
                    tokenCostService.recordDegradation(
                            "MODEL_FAILURE",
                            "direct_api_fallback_failed",
                            fallbackEx.getMessage(),
                            novelId,
                            "deepseek",
                            aiProperties.getModel().getDeepseek().getModelName(),
                            "chat.direct_fallback"
                    );
                    throw fallbackEx;
                }
            }
            throw ex;
        }
    }

    public String chatDirect(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
        return chatDirectInternal(null, systemPrompt, userPrompt, temperature, maxTokens, "chat.direct");
    }

    private String chatDirectInternal(Long novelId,
                                      String systemPrompt,
                                      String userPrompt,
                                      double temperature,
                                      int maxTokens,
                                      String source) {
        String apiKey = aiProperties.getModel().getDeepseek().getApiKey();
        String baseUrl = aiProperties.getModel().getDeepseek().getBaseUrl();
        String modelName = aiProperties.getModel().getDeepseek().getModelName();
        TokenCostService.UsageReservation reservation = tokenCostService.reserveChatRequest(
                novelId,
                "deepseek",
                modelName,
                source,
                buildPrompt(systemPrompt, userPrompt),
                maxTokens
        );

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", modelName);
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

        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    baseUrl + "/chat/completions",
                    HttpMethod.POST,
                    entity,
                    new ParameterizedTypeReference<>() {}
            );

            Map<String, Object> body = response.getBody();
            if (body == null) {
                throw new RuntimeException("DeepSeek API returned empty body");
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("DeepSeek API returned empty choices");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            if (message == null) {
                throw new RuntimeException("DeepSeek API response missing message field");
            }

            String content = (String) message.get("content");
            if (content == null) {
                throw new RuntimeException("DeepSeek API response missing content field");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> usage = (Map<String, Object>) body.get("usage");
            tokenCostService.recordChatSuccess(
                    reservation,
                    content,
                    readInt(usage, "prompt_tokens"),
                    readInt(usage, "completion_tokens")
            );
            return content;
        } catch (RuntimeException ex) {
            tokenCostService.recordFailure(reservation, ex.getMessage());
            throw ex;
        }
    }

    private boolean canUseDirectFallback() {
        AiProperties.Deepseek deepseek = aiProperties.getModel().getDeepseek();
        AiProperties.Fallback fallback = aiProperties.getModel().getFallback();
        return fallback.isEnabled()
                && fallback.getMaxRetries() > 0
                && deepseek.getApiKey() != null
                && !deepseek.getApiKey().isBlank()
                && deepseek.getBaseUrl() != null
                && !deepseek.getBaseUrl().isBlank();
    }

    private String buildPrompt(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isEmpty()) {
            return userPrompt;
        }
        return "[SYSTEM]\n" + systemPrompt + "\n\n[USER]\n" + userPrompt;
    }

    private String currentProvider() {
        return aiProperties.getModel().getProvider();
    }

    private String currentChatModelName() {
        return switch (currentProvider()) {
            case "qianwen" -> aiProperties.getModel().getQianwen().getModelName();
            case "local" -> aiProperties.getModel().getLocal().getModelName();
            default -> aiProperties.getModel().getDeepseek().getModelName();
        };
    }

    private Integer readInt(Map<String, Object> usage, String field) {
        if (usage == null) {
            return null;
        }
        Object value = usage.get(field);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return null;
    }
}
