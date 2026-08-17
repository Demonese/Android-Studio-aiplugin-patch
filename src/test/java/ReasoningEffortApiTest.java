import com.android.studio.ml.backends.openai.OpenAiCompletionApiV2;
import com.android.studio.ml.backends.openai.OpenAiResponsesApiV2;
import com.google.studiobot.datamodel.models.GenerationConfig;
import com.google.studiobot.datamodel.models.ModelRequest;
import com.google.studiobot.datamodel.models.ToolFunction;
import com.google.studiobot.datamodel.models.UserChatMessage;
import com.google.studiobot.ui.querybox.ThinkingEffortPicker;
import com.google.studiobot.ui.querybox.ThinkingEffortStore;
import com.openai.models.ReasoningEffort;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.ResponseCreateParams;
import java.util.Collections;
import kotlin.jvm.functions.Function1;

public class ReasoningEffortApiTest {
    static int failed = 0;

    static void check(boolean ok, String name) {
        System.out.println((ok ? "ok: " : "FAILED: ") + name);
        if (!ok) failed++;
    }

    public static void main(String[] args) {
        Function1<ToolFunction, Boolean> include = t -> Boolean.TRUE;
        GenerationConfig gc = GenerationConfig.Companion.defaultForAgent();
        ModelRequest req = new ModelRequest("sys", Collections.emptyList(),
                Collections.singletonList(new UserChatMessage("hi", Collections.emptyList())), gc);

        for (String level : ThinkingEffortPicker.LEVELS) {
            ThinkingEffortStore.resetForTest();
            ThinkingEffortStore.onPickerSelect(level);

            ChatCompletionCreateParams cc =
                    OpenAiCompletionApiV2.INSTANCE.createParams("m", req, false, false, include);
            ReasoningEffort ce = cc.reasoningEffort().orElse(null);
            check(ce != null && ce.asString().equals(level), "chat completions reasoning_effort=" + level);

            ResponseCreateParams rp = OpenAiResponsesApiV2.INSTANCE.createParams("m", req, include);
            ReasoningEffort re = rp.reasoning().flatMap(r -> r.effort()).orElse(null);
            check(re != null && re.asString().equals(level), "responses reasoning.effort=" + level);
        }

        ThinkingEffortStore.resetForTest();
        ThinkingEffortStore.onPickerSelect("high");
        ChatCompletionCreateParams omit =
                OpenAiCompletionApiV2.INSTANCE.createParams("m", req, false, true, include);
        check(omit.reasoningEffort().isEmpty(), "omitReasoningEffort=true 时不带 reasoning_effort");

        System.out.println(failed == 0 ? "ALL_OK" : "FAILED_COUNT=" + failed);
        if (failed > 0) System.exit(1);
    }
}
