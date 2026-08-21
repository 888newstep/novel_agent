package com.novel.agent.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatPromptSupportTest {

    @Test
    void buildsStructuredMessagesWithoutRoleLabelsInContent() {
        List<ChatMessage> messages = ChatPromptSupport.toLangChainMessages("system rules", "write chapter");

        assertThat(messages).hasSize(2);
        assertThat(((SystemMessage) messages.get(0)).text()).isEqualTo("system rules");
        assertThat(((UserMessage) messages.get(1)).singleText()).isEqualTo("write chapter");
        assertThat(ChatPromptSupport.toEstimationText("system rules", "write chapter"))
                .isEqualTo("system rules\nwrite chapter")
                .doesNotContain("[SYSTEM]", "[USER]");
    }

    @Test
    void omitsBlankSystemMessageAndRejectsBlankUserPrompt() {
        assertThat(ChatPromptSupport.toLangChainMessages(" ", "write chapter"))
                .singleElement()
                .isInstanceOf(UserMessage.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ChatPromptSupport.toLangChainMessages("rules", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userPrompt");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ChatPromptSupport.toApiMessages("rules", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userPrompt");
    }
}
