package com.novel.agent.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ChatPromptSupport {

    private ChatPromptSupport() {
    }

    public static List<ChatMessage> toLangChainMessages(String systemPrompt, String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(requireUserPrompt(userPrompt)));
        return List.copyOf(messages);
    }

    public static List<Map<String, String>> toApiMessages(String systemPrompt, String userPrompt) {
        List<Map<String, String>> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", requireUserPrompt(userPrompt)));
        return List.copyOf(messages);
    }

    public static String toEstimationText(String systemPrompt, String userPrompt) {
        String system = nullToEmpty(systemPrompt);
        String user = requireUserPrompt(userPrompt);
        if (system.isBlank()) {
            return user;
        }
        return system + "\n" + user;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String requireUserPrompt(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("userPrompt cannot be null or blank");
        }
        return value;
    }
}
