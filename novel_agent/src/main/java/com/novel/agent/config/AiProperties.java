package com.novel.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    private Model model = new Model();
    private Embedding embedding = new Embedding();
    private CostControl costControl = new CostControl();

    @Data
    public static class Model {
        private String provider = "deepseek";
        private Deepseek deepseek = new Deepseek();
        private Qianwen qianwen = new Qianwen();
        private Local local = new Local();
        private Fallback fallback = new Fallback();
    }

    @Data
    public static class Fallback {
        private boolean enabled = true;
        private int maxRetries = 1;
    }

    @Data
    public static class Deepseek {
        private String apiKey;
        private String modelName = "deepseek-chat";
        private String baseUrl = "https://api.deepseek.com/v1";
    }

    @Data
    public static class Qianwen {
        private String apiKey;
        private String modelName = "qwen-max";
        private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    }

    @Data
    public static class Local {
        private String baseUrl = "http://localhost:11434/v1";
        private String modelName = "llama2";
        private String apiKey = "dummy";
    }

    @Data
    public static class Embedding {
        private String provider = "ollama";
        private OllamaEmbedding ollama = new OllamaEmbedding();
        private SiliconflowEmbedding siliconflow = new SiliconflowEmbedding();
    }

    @Data
    public static class OllamaEmbedding {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "bge-m3";
        private int dimension = 1024;
    }

    @Data
    public static class SiliconflowEmbedding {
        private String baseUrl = "https://api.siliconflow.cn/v1";
        private String modelName = "BAAI/bge-m3";
        private int dimension = 1024;
        private String apiKey;
    }

    @Data
    public static class CostControl {
        private boolean enabled = true;
        private boolean strictMode = false;
        private int recentRecords = 200;
        private int maxEstimatedTokensPerRequest = 12000;
        private int reservedCompletionTokens = 1200;
        private long dailyTokenBudget = 300000;
        private long monthlyTokenBudget = 5000000;
        private double dailyBudgetUsd = 5.0;
        private double monthlyBudgetUsd = 100.0;
        private Pricing pricing = new Pricing();
    }

    @Data
    public static class Pricing {
        private String currency = "USD";
        private double inputPerMillionTokens = 0.0;
        private double outputPerMillionTokens = 0.0;
        private double embeddingPerMillionTokens = 0.0;
    }
}
