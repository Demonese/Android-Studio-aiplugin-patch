import com.android.studio.ml.backends.settings.OpenAiApiTypeUi;
import com.android.studio.ml.backends.settings.RemoteModelProviderInfoPanel;
import com.android.studio.ml.modelproviders.data.OpenAiApiType;
import com.android.studio.ml.modelproviders.data.ProviderData;
import com.android.studio.ml.modelproviders.data.ProviderDetails;
import com.android.studio.ml.modelproviders.data.ProviderType;
import com.intellij.openapi.Disposable;
import java.util.Collections;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function2;

// 协议下拉控件的运行时行为测试（构造真实 RemoteModelProviderInfoPanel + mock provider 上下文）：
//   1) load(): 初始化加载设置中储存的协议 + 按 schema 决定可见性（旧配置 schema=null -> OpenAI 可见）
//   2) syncVisibility(): 厂商切换 OpenAI/Anthropic -> 行显示/隐藏实时联动
//   3) 回写: 下拉切换 -> apiTypeProperty.afterChange -> writeBack 写入当前 provider 的 live RemoteProviderData
//   4) 容错: 当前 provider 为 null / 非 Remote 时回写静默跳过，不抛异常
// 需在 headless JVM（-Djava.awt.headless=true）下运行；构造 Swing 组件要求
// java.desktop 模块开放（--add-opens），见 40_verify.sh 的 AWT_OPENS。
public class ApiProtocolUiBehaviorTest {

    static int fail = 0;

    public static void main(String[] args) throws Exception {
        // ---- 构造真实面板，getCurrentProvider 指向一个 live RemoteProviderData ----
        ProviderData.RemoteProviderData data = new ProviderData.RemoteProviderData(
                "https://api.deepseek.com/v1", "", "Authorization",
                ProviderData.RemoteProviderData.ApiSchema.OPENAI);
        data.setOpenAiApiType(OpenAiApiType.CHAT_COMPLETION);
        final ProviderDetails[] slot = new ProviderDetails[1];
        slot[0] = new ProviderDetails("deepseek", ProviderType.REMOTE, Collections.emptyList(),
                data, null, data, 0, 0);

        RemoteModelProviderInfoPanel panel = new RemoteModelProviderInfoPanel(
                ProviderType.REMOTE,
                new Function0<ProviderDetails>() {
                    public ProviderDetails invoke() { return slot[0]; }
                },
                new Function0<Unit>() {
                    public Unit invoke() { return Unit.INSTANCE; }
                },
                new Function2<ProviderDetails, Continuation<?>, Object>() {
                    public Object invoke(ProviderDetails p1, Continuation<?> p2) { return Collections.emptyList(); }
                },
                new Disposable() {
                    public void dispose() { }
                });

        OpenAiApiTypeUi.State st = OpenAiApiTypeUi.state(panel);

        // ---- 1) load(): 读取储存的协议 + 可见性 ----
        OpenAiApiTypeUi.load(panel, data);
        check("load 读取储存协议 CHAT_COMPLETION",
                st.apiTypeProperty.get() == OpenAiApiType.CHAT_COMPLETION,
                "actual=" + st.apiTypeProperty.get());
        check("schema=OPENAI -> 行可见", st.apiTypeVisible.get());

        // 旧配置 schema=null -> 按 OPENAI 显示（可见）
        ProviderData.RemoteProviderData legacy = new ProviderData.RemoteProviderData(
                "https://x/v1", "", "Authorization", null);
        OpenAiApiTypeUi.load(panel, legacy);
        check("旧配置 schema=null -> 协议默认 AUTO",
                st.apiTypeProperty.get() == OpenAiApiType.AUTO,
                "actual=" + st.apiTypeProperty.get());
        check("旧配置 schema=null -> 行可见(OpenAI)", st.apiTypeVisible.get());

        // ---- 2) syncVisibility(): 厂商切换联动 ----
        OpenAiApiTypeUi.syncVisibility(panel, ProviderData.RemoteProviderData.ApiSchema.OPENAI);
        check("厂商切到 OpenAI -> 显示", st.apiTypeVisible.get());
        OpenAiApiTypeUi.syncVisibility(panel, ProviderData.RemoteProviderData.ApiSchema.ANTHROPIC);
        check("厂商切到 Anthropic -> 隐藏", !st.apiTypeVisible.get());
        OpenAiApiTypeUi.syncVisibility(panel, ProviderData.RemoteProviderData.ApiSchema.OPENAI);
        check("厂商切回 OpenAI -> 重新显示", st.apiTypeVisible.get());

        // ---- 3) 回写: afterChange -> 实时写入当前 provider 的 live data ----
        ProviderData.RemoteProviderData live = (ProviderData.RemoteProviderData) slot[0].getProviderData();
        live.setOpenAiApiType(OpenAiApiType.AUTO);           // 先复位，排除 load 误写干扰
        st.apiTypeProperty.set(OpenAiApiType.RESPONSE);
        check("下拉切到 RESPONSE -> 回写 live data",
                live.getOpenAiApiType() == OpenAiApiType.RESPONSE,
                "actual=" + live.getOpenAiApiType());
        st.apiTypeProperty.set(OpenAiApiType.CHAT_COMPLETION);
        check("下拉切到 CHAT_COMPLETION -> 回写 live data",
                live.getOpenAiApiType() == OpenAiApiType.CHAT_COMPLETION,
                "actual=" + live.getOpenAiApiType());

        // ---- 4) 容错: provider 为 null / 非 Remote -> 静默跳过 ----
        slot[0] = null;                                        // 面板无选中 provider
        st.apiTypeProperty.set(OpenAiApiType.AUTO);
        check("provider 为 null 时不抛异常（静默跳过）", true);
        slot[0] = new ProviderDetails("local", ProviderType.LOCAL, Collections.emptyList(),
                null, new ProviderData.LocalProviderData("8080"), null, 0, 0);
        st.apiTypeProperty.set(OpenAiApiType.RESPONSE);
        check("provider 非 Remote 时不抛异常（静默跳过）", true);

        if (fail > 0) {
            System.out.println(fail + " FAILED");
            System.exit(1);
        }
        System.out.println("UI_BEHAVIOR_OK");
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
}