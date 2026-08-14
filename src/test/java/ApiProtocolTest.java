import com.android.studio.ml.backends.openai.OpenAiApiTypeSupport;
import com.android.studio.ml.backends.openai.OpenAiModelApi;
import com.android.studio.ml.modelproviders.data.OpenAiApiType;
import com.google.studiobot.datamodel.models.GenerationConfig;
import com.google.studiobot.datamodel.models.ModelConfig;
import com.google.studiobot.datamodel.models.ModelRequest;
import com.google.studiobot.datamodel.models.ToolFunction;
import kotlin.LazyKt;
import kotlin.jvm.functions.Function1;
import kotlinx.coroutines.flow.Flow;

import java.util.Collections;

public class ApiProtocolTest {
    public static void main(String[] args) {
        ModelConfig cfg = new ModelConfig("test-model", "openai", "uuid", "Test Model", null,
                Collections.emptySet(), 1000, 1000, true, true, false,
                Collections.emptyList(), false, false, false);
        Function1<ToolFunction, Boolean> include = t -> true;
        OpenAiModelApi api = new OpenAiModelApi(LazyKt.lazy(() -> null), cfg, include);

        check(api.getOpenAiApiType() == OpenAiApiType.AUTO, "ctor default = AUTO");
        check(OpenAiApiTypeSupport.resolveUseResponses(api), "AUTO + supportResponses=true -> Responses");
        check(OpenAiApiTypeSupport.allowResponsesFallback(api), "AUTO -> fallback allowed");

        api.getSupportResponses().getAndSet(false);
        check(!OpenAiApiTypeSupport.resolveUseResponses(api), "AUTO + supportResponses=false -> Completion (原行为)");

        api.setOpenAiApiType(OpenAiApiType.CHAT_COMPLETION);
        check(!OpenAiApiTypeSupport.resolveUseResponses(api), "CHAT_COMPLETION -> 强制 Completion");
        check(!OpenAiApiTypeSupport.allowResponsesFallback(api), "CHAT_COMPLETION -> 禁用回退");

        api.setOpenAiApiType(OpenAiApiType.RESPONSE);
        check(OpenAiApiTypeSupport.resolveUseResponses(api), "RESPONSE -> 强制 Responses（即使 supportResponses=false）");
        check(!OpenAiApiTypeSupport.allowResponsesFallback(api), "RESPONSE -> 禁用回退");

        api.setOpenAiApiType(OpenAiApiType.AUTO);
        api.getSupportResponses().getAndSet(true);
        check(OpenAiApiTypeSupport.resolveUseResponses(api), "AUTO 恢复原行为");

        ModelRequest req = new ModelRequest("", Collections.emptyList(), Collections.emptyList(), new GenerationConfig());
        for (OpenAiApiType t : OpenAiApiType.values()) {
            api.setOpenAiApiType(t);
            Flow<?> flow = api.streamGenerateContent(req);
            check(flow != null, "streamGenerateContent(" + t + ") 返回 Flow（补丁分派 + catch lambda 构造正常）");
        }
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
