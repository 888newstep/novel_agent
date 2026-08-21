package com.novel.agent.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class AiModelConfig {

    private final AiProperties aiProperties;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        AiProperties.Model modelConfig = aiProperties.getModel();
        return switch (modelConfig.getProvider()) {
            case "deepseek" -> createDeepseekChatModel(modelConfig.getDeepseek());
            case "qianwen" -> createQianwenChatModel(modelConfig.getQianwen());
            case "local" -> createLocalChatModel(modelConfig.getLocal());
            default -> createDeepseekChatModel(modelConfig.getDeepseek());
        };
    }

    @Bean
    public StreamingChatLanguageModel streamingChatLanguageModel() {
        AiProperties.Model modelConfig = aiProperties.getModel();
        return switch (modelConfig.getProvider()) {
            case "deepseek" -> createDeepseekStreamingModel(modelConfig.getDeepseek());
            case "qianwen" -> createQianwenStreamingModel(modelConfig.getQianwen());
            case "local" -> createLocalStreamingModel(modelConfig.getLocal());
            default -> createDeepseekStreamingModel(modelConfig.getDeepseek());
        };
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        AiProperties.Embedding embeddingConfig = aiProperties.getEmbedding();
        return switch (embeddingConfig.getProvider()) {
            case "siliconflow" -> {
                AiProperties.SiliconflowEmbedding config = embeddingConfig.getSiliconflow();
                yield OpenAiEmbeddingModel.builder()
                        .baseUrl(config.getBaseUrl())
                        .apiKey(config.getApiKey())
                        .modelName(config.getModelName())
                        .dimensions(config.getDimension())
                        .timeout(Duration.ofSeconds(180))
                        .build();
            }
            default -> {
                // 默认 ollama
                AiProperties.OllamaEmbedding config = embeddingConfig.getOllama();
                yield OpenAiEmbeddingModel.builder()
                        .baseUrl(config.getBaseUrl() + "/v1")
                        .apiKey("dummy")
                        .modelName(config.getModelName())
                        .dimensions(config.getDimension())
                        .timeout(Duration.ofSeconds(180))
                        .build();
            }
        };
    }

    // ========== DeepSeek ==========

    private ChatLanguageModel createDeepseekChatModel(AiProperties.Deepseek config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.8)
                .maxTokens(maxOutputTokens())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private StreamingChatLanguageModel createDeepseekStreamingModel(AiProperties.Deepseek config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.8)
                .maxTokens(maxOutputTokens())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    // ========== 通义千问 ==========

    private ChatLanguageModel createQianwenChatModel(AiProperties.Qianwen config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.8)
                .maxTokens(maxOutputTokens())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private StreamingChatLanguageModel createQianwenStreamingModel(AiProperties.Qianwen config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.8)
                .maxTokens(maxOutputTokens())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    // ========== 本地 Ollama ==========

    private ChatLanguageModel createLocalChatModel(AiProperties.Local config) {
        return OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.8)
                .maxTokens(maxOutputTokens())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private StreamingChatLanguageModel createLocalStreamingModel(AiProperties.Local config) {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(0.8)
                .maxTokens(maxOutputTokens())
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    private int maxOutputTokens() {
        return Math.max(1, aiProperties.getCostControl().getReservedCompletionTokens());
    }

    
}
