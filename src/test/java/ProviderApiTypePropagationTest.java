import com.android.studio.ml.backends.openai.OpenAiApiTypeSupport;
import com.android.studio.ml.backends.openai.OpenAiModelApi;
import com.android.studio.ml.backends.openai.OpenAiModelApiProvider;
import com.android.studio.ml.bot.configuration.StudioBotSettingsNotificationService;
import com.android.studio.ml.modelproviders.data.ModelDataStateManager;
import com.android.studio.ml.modelproviders.data.ModelDetails;
import com.android.studio.ml.modelproviders.data.OpenAiApiType;
import com.android.studio.ml.modelproviders.data.ProviderData;
import com.android.studio.ml.modelproviders.data.ProviderDetails;
import com.android.studio.ml.modelproviders.data.ProviderType;
import com.google.studiobot.agentsdk.models.ModelApiProviderState;
import com.google.studiobot.datamodel.models.ModelApi;
import com.intellij.openapi.Disposable;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function0;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.flow.Flow;
import kotlinx.coroutines.flow.FlowKt;

// 端到端传播测试：Model Providers 设置中的 openAiApiType
// → OpenAiModelApiProvider.computeState()（ASM 注入：new OpenAiModelApi 后从
//   providerSettings=局部变量9 读取并 setOpenAiApiType）
// → 创建出的 OpenAiModelApi 实例携带正确协议值
// → resolveUseResponses / allowResponsesFallback 按该值决策。
// 覆盖：按 provider 隔离（两个 provider 不同协议）、设置变更后重算生效、
// 默认 AUTO 行为不变、固定协议时分派强制且回退禁用。
public class ProviderApiTypePropagationTest {

    static int fail = 0;

    public static void main(String[] args) throws Exception {
        // ---- fake 依赖 ----
        FakeManager manager = new FakeManager();
        StudioBotSettingsNotificationService notif = new StudioBotSettingsNotificationService() {
            public void addListener(Disposable parentDisposable, Listener l) { }
            public void removeListener(Listener l) { }
            public void notifySettingsUpdated() { }
            public Flow<Unit> notificationsFlow(Disposable parentDisposable) { return FlowKt.emptyFlow(); }
        };
        CoroutineScope scope = new CoroutineScope() {
            public kotlin.coroutines.CoroutineContext getCoroutineContext() {
                return EmptyCoroutineContext.INSTANCE;
            }
        };

        // ---- 两个远程 provider：协议分别为 CHAT_COMPLETION 与 RESPONSE ----
        ProviderData.RemoteProviderData cc = new ProviderData.RemoteProviderData(
                "https://cc.example/v1", "", "Authorization",
                ProviderData.RemoteProviderData.ApiSchema.OPENAI);
        cc.setOpenAiApiType(OpenAiApiType.CHAT_COMPLETION);
        ProviderData.RemoteProviderData rs = new ProviderData.RemoteProviderData(
                "https://rs.example/v1", "", "Authorization",
                ProviderData.RemoteProviderData.ApiSchema.OPENAI);
        rs.setOpenAiApiType(OpenAiApiType.RESPONSE);

        ModelDetails m1 = new ModelDetails("Model A", "model-a", true, List.of(), 1000, 1000, null, "a");
        ModelDetails m2 = new ModelDetails("Model B", "model-b", true, List.of(), 1000, 1000, null, "b");
        manager.providers.add(new ProviderDetails("p1", ProviderType.REMOTE, List.of(m1), cc, null, cc, 0, 0));
        manager.providers.add(new ProviderDetails("p2", ProviderType.REMOTE, List.of(m2), rs, null, rs, 0, 0));

        OpenAiModelApiProvider provider = new OpenAiModelApiProvider(
                scope, () -> notif, () -> manager,
                new Function0<com.android.studio.ml.backends.settings.RemoteModelProvidersApiKeyService>() {
                    public com.android.studio.ml.backends.settings.RemoteModelProvidersApiKeyService invoke() { return null; }
                });

        // ---- computeState：设置值传导到 ModelApi 实例（按 provider 隔离）----
        ModelApiProviderState state = provider.computeState();
        OpenAiModelApi api1 = find(state, "model-a");
        OpenAiModelApi api2 = find(state, "model-b");
        check("provider1 协议传播到实例", api1.getOpenAiApiType() == OpenAiApiType.CHAT_COMPLETION,
                "actual=" + api1.getOpenAiApiType());
        check("provider2 协议传播到实例", api2.getOpenAiApiType() == OpenAiApiType.RESPONSE,
                "actual=" + api2.getOpenAiApiType());

        // ---- 分派决策：固定协议强制、AUTO 沿用原生开关 ----
        check("CHAT_COMPLETION -> resolveUseResponses=false",
                !OpenAiApiTypeSupport.resolveUseResponses(api1));
        check("CHAT_COMPLETION -> 禁用回退", !OpenAiApiTypeSupport.allowResponsesFallback(api1));
        check("RESPONSE -> resolveUseResponses=true",
                OpenAiApiTypeSupport.resolveUseResponses(api2));
        check("RESPONSE -> 禁用回退", !OpenAiApiTypeSupport.allowResponsesFallback(api2));

        // ---- 设置变更后重算生效（saveState -> notifySettingsUpdated -> state 重算的等价路径）----
        cc.setOpenAiApiType(OpenAiApiType.RESPONSE);
        rs.setOpenAiApiType(OpenAiApiType.AUTO);
        state = provider.computeState();
        api1 = find(state, "model-a");
        api2 = find(state, "model-b");
        check("设置变更重算后 sync(provider1 -> RESPONSE)", api1.getOpenAiApiType() == OpenAiApiType.RESPONSE,
                "actual=" + api1.getOpenAiApiType());
        check("设置变更重算后(provider2 -> AUTO)", api2.getOpenAiApiType() == OpenAiApiType.AUTO,
                "actual=" + api2.getOpenAiApiType());
        check("AUTO -> resolveUseResponses 沿用 supportResponses(原生)",
                OpenAiApiTypeSupport.resolveUseResponses(api2));
        check("AUTO -> 允许回退", OpenAiApiTypeSupport.allowResponsesFallback(api2));

        // ---- schema=null 的旧配置 provider：协议默认 AUTO ----
        manager.providers.clear();
        ProviderData.RemoteProviderData legacy = new ProviderData.RemoteProviderData(
                "https://legacy.example/v1", "", "Authorization", null);
        manager.providers.add(new ProviderDetails("p3", ProviderType.REMOTE, List.of(m1), legacy, null, legacy, 0, 0));
        api1 = find(provider.computeState(), "model-a");
        check("旧配置(无协议字段) -> 实例默认 AUTO", api1.getOpenAiApiType() == OpenAiApiType.AUTO,
                "actual=" + api1.getOpenAiApiType());

        if (fail > 0) {
            System.out.println(fail + " FAILED");
            System.exit(1);
        }
        System.out.println("PROPAGATION_OK");
    }

    static OpenAiModelApi find(ModelApiProviderState state, String modelId) throws Exception {
        ModelApiProviderState.Available avail = (ModelApiProviderState.Available) state;
        for (ModelApi api : avail.getModels()) {
            String id = api.getConfig().getId();
            if (modelId.equals(id)) {
                return (OpenAiModelApi) api;
            }
        }
        throw new IllegalStateException("model not found in provider state: " + modelId);
    }

    static void check(String msg, boolean cond) {
        check(msg, cond, "");
    }

    static void check(String msg, boolean cond, String detail) {
        if (!cond) {
            System.out.println("FAILED: " + msg + " " + detail);
            fail++;
            return;
        }
        System.out.println("ok: " + msg);
    }

    static class FakeManager implements ModelDataStateManager {
        final List<ProviderDetails> providers = new ArrayList<>();

        public List<ProviderDetails> loadState() { return providers; }

        public void saveState(List<ProviderDetails> list) {
            providers.clear();
            providers.addAll(list);
        }
    }
}