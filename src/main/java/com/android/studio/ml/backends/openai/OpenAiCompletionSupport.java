package com.android.studio.ml.backends.openai;

import com.google.studiobot.datamodel.models.ModelChatMessage;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;

// DeepSeek/Qwen 等供应商的思考模式要求多轮对话回传 assistant 消息的 reasoning_content，
// 否则下一轮请求 400："The reasoning_content in the thinking mode must be passed back to the API."
// 原 toMessageParam(ModelChatMessage) 只发送 content 与 tool_calls，丢弃了 thought。
// 在构造 assistant 消息时把已收到的思考内容作为 reasoning_content 附加字段回传。
public final class OpenAiCompletionSupport {
    public static final String REASONING_CONTENT_KEY = "reasoning_content";

    private OpenAiCompletionSupport() {
    }

    public static void attachReasoningContent(ChatCompletionAssistantMessageParam.Builder builder,
                                              ModelChatMessage message) {
        if (builder == null || message == null) return;
        String thought = message.getThought();
        if (thought == null || thought.length() == 0) return;
        builder.putAdditionalProperty(REASONING_CONTENT_KEY, JsonValue.Companion.from(thought));
    }
}
