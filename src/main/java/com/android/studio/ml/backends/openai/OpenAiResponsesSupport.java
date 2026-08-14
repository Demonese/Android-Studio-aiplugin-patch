package com.android.studio.ml.backends.openai;

import com.google.studiobot.datamodel.models.ModelChatMessage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseReasoningItem;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

// DeepSeek 等供应商的思考模式要求每一轮 assistant 消息回传 reasoning_text，
// 但模型有时不返回思考块就直接返回工具调用，导致下一轮请求 400：
// "The reasoning_text in the thinking mode must be passed back to the API."
// 对没有思考内容的轮次补一个占位思考项。
public final class OpenAiResponsesSupport {
    public static final String FALLBACK_THINKING_TEXT = "继续调用工具……";

    private OpenAiResponsesSupport() {
    }

    public static void ensureReasoning(List<ResponseInputItem> items, ModelChatMessage message,
                                       String modelId, AtomicInteger messageIndexer) {
        if (message == null || !Objects.equals(modelId, message.getModelName())) return;
        String thought = message.getThought();
        if (thought != null && thought.length() > 0) return;
        String signature = message.getThoughtSignature();
        if (signature != null && signature.length() > 0) return;
        ResponseReasoningItem item = ResponseReasoningItem.Companion.builder()
                .id("msg_" + messageIndexer.incrementAndGet())
                .status(ResponseReasoningItem.Status.COMPLETED)
                .summary(Collections.emptyList())
                .addContent(ResponseReasoningItem.Content.Companion.builder()
                        .text(FALLBACK_THINKING_TEXT)
                        .build())
                .build();
        items.add(ResponseInputItem.Companion.ofReasoning(item));
    }
}
