import com.android.studio.ml.backends.openai.OpenAiResponsesApiV2;
import com.android.studio.ml.backends.openai.OpenAiResponsesSupport;
import com.google.studiobot.datamodel.models.GenerationConfig;
import com.google.studiobot.datamodel.models.ModelChatMessage;
import com.google.studiobot.datamodel.models.ModelRequest;
import com.google.studiobot.datamodel.models.ToolCall;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseReasoningItem;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class ResponsesReasoningTest {
    public static void main(String[] args) {
        ToolCall tc = new ToolCall("call_1", "get_weather", "{\"city\":\"SF\"}", true);
        ModelChatMessage noThought = new ModelChatMessage("", Collections.singletonList(tc), null, null, "deepseek-chat");
        ModelChatMessage withThought = new ModelChatMessage("答案", Collections.emptyList(), "思考内容", null, "deepseek-chat");
        ModelChatMessage withSig = new ModelChatMessage("", Collections.singletonList(new ToolCall("call_s", "f", "{}", true)), null, "sig123", "deepseek-chat");
        ModelChatMessage otherModel = new ModelChatMessage("", Collections.singletonList(new ToolCall("call_2", "f", "{}", true)), null, null, "other-model");

        ModelRequest req = new ModelRequest("", Collections.emptyList(),
                Arrays.asList(noThought, withThought, withSig, otherModel), new GenerationConfig());
        ResponseCreateParams params = OpenAiResponsesApiV2.INSTANCE.createParams("deepseek-chat", req, t -> true);
        List<ResponseInputItem> items = params.input().get().asResponse();

        int injected = 0, originalThought = 0, originalSig = 0;
        for (ResponseInputItem it : items) {
            Optional<ResponseReasoningItem> r = it.reasoning();
            if (!r.isPresent()) continue;
            ResponseReasoningItem ri = r.get();
            String text = ri.content().filter(l -> !l.isEmpty()).map(l -> l.get(0).text()).orElse(null);
            if (OpenAiResponsesSupport.FALLBACK_THINKING_TEXT.equals(text)) {
                injected++;
            } else if ("思考内容".equals(text)) {
                originalThought++;
            } else if (ri.encryptedContent().isPresent()) {
                originalSig++;
            }
        }
        check(injected == 1, "无思考+无签名的本模型轮次补 1 个占位思考（实际 " + injected + "）");
        check(originalThought == 1, "已有思考的轮次保留原思考、不重复注入（实际 " + originalThought + "）");
        check(originalSig == 1, "仅有思考签名的轮次保留原签名项、不注入（实际 " + originalSig + "）");

        // 占位思考内容正确
        check("继续调用工具……".equals(OpenAiResponsesSupport.FALLBACK_THINKING_TEXT), "占位思考文案正确");
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
