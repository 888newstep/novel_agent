package com.novel.agent.service;

import com.novel.agent.config.AiProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeepSeekServiceTest {

    @Test
    void sendsStructuredMessagesAndReservesCompactPromptText() {
        ChatLanguageModel chatLanguageModel = mock(ChatLanguageModel.class);
        TokenCostService tokenCostService = mock(TokenCostService.class);
        AiProperties properties = new AiProperties();
        TokenCostService.UsageReservation reservation = TokenCostService.UsageReservation.builder().build();
        when(tokenCostService.reserveChatRequest(
                eq(9L), eq("deepseek"), eq("deepseek-chat"), eq("chat.generate"),
                eq("system rules\nwrite chapter"), eq(1200)))
                .thenReturn(reservation);
        when(chatLanguageModel.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("generated chapter")));
        DeepSeekService service = new DeepSeekService(chatLanguageModel, properties, tokenCostService);

        assertThat(service.chat(9L, "system rules", "write chapter")).isEqualTo("generated chapter");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor.forClass(List.class);
        verify(chatLanguageModel).generate(messagesCaptor.capture());
        assertThat(messagesCaptor.getValue()).hasSize(2);
        assertThat(((SystemMessage) messagesCaptor.getValue().get(0)).text()).isEqualTo("system rules");
        assertThat(((UserMessage) messagesCaptor.getValue().get(1)).singleText()).isEqualTo("write chapter");
        verify(tokenCostService).recordChatSuccess(reservation, "generated chapter", null, null);
    }
}
