import com.android.studio.ml.backends.openai.OpenAiCompletionApiV2;
import com.android.studio.ml.backends.openai.OpenAiCompletionSupport;
import com.google.studiobot.datamodel.models.GenerationConfig;
import com.google.studiobot.datamodel.models.ModelChatMessage;
import com.google.studiobot.datamodel.models.ModelRequest;
import com.google.studiobot.datamodel.models.ToolCall;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessageParam;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class CompletionReasoningTest {
    public static void main(String[] args) {
        ModelChatMessage withThought = new ModelChatMessage("答案",
                Collections.singletonList(new ToolCall("call_1", "get_weather", "{\"city\":\"SF\"}", true)),
                "思考内容", null, "deepseek-chat");
        ModelChatMessage noThought = new ModelChatMessage("另一条回答", Collections.emptyList(), null, null, "deepseek-chat");
        ModelChatMessage emptyThought = new ModelChatMessage("第三条", Collections.emptyList(), "", null, "deepseek-chat");

        ModelRequest req = new ModelRequest("system", Collections.emptyList(),
                Arrays.asList(withThought, noThought, emptyThought), new GenerationConfig());
        ChatCompletionCreateParams params = OpenAiCompletionApiV2.INSTANCE
                .createParams("deepseek-chat", req, false, false, t -> true);

        List<ChatCompletionMessageParam> messages = params.messages();
        check(messages.size() == 4, "消息数 = 1 系统 + 3 assistant（实际 " + messages.size() + "）");

        check(messages.get(0).isSystem() && !messages.get(0).isDeveloper(),
                "系统消息用 system role（useSystemMessage=false 时也不再是 developer）");

        ChatCompletionAssistantMessageParam a0 = messages.get(1).asAssistant();
        JsonValue rc = a0._additionalProperties().get(OpenAiCompletionSupport.REASONING_CONTENT_KEY);
        check(rc != null && "思考内容".equals(rc.convert(String.class)),
                "thought 非空时回传 reasoning_content");
        check(a0.toolCalls().map(List::size).orElse(0) == 1, "tool_calls 保留不受影响");

        ChatCompletionAssistantMessageParam a1 = messages.get(2).asAssistant();
        check(!a1._additionalProperties().containsKey(OpenAiCompletionSupport.REASONING_CONTENT_KEY),
                "thought 为 null 时不附加 reasoning_content");

        ChatCompletionAssistantMessageParam a2 = messages.get(3).asAssistant();
        check(!a2._additionalProperties().containsKey(OpenAiCompletionSupport.REASONING_CONTENT_KEY),
                "thought 为空串时不附加 reasoning_content");

        System.out.println("ALL_OK");
    }

    static void check(boolean cond, String msg) {
        if (!cond) {
            System.out.println("FAILED: " + msg);
            System.exit(1);
        }
        System.out.println("ok: " + msg);
    }
}
