import com.android.studio.ml.modelproviders.data.OpenAiApiType;
import com.android.studio.ml.modelproviders.data.OpenAiApiTypeConverter;
import com.intellij.util.xmlb.annotations.OptionTag;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

// 设置界面补丁面与布局兼容性校验：
//   1) 新增 UI 类可加载，枚举 id / 转换器回退正确；
//   2) RemoteProviderData 的 openAiApiType 字段带 @OptionTag(converter=...)；
//   3) 面板被补丁依赖的成员仍在（getCurrentProvider 字段、可见性属性访问器、被补丁的方法）；
//   4) 三处注入点在字节码中的相对位置仍然正确——
//      addRow 必须落在 "URL Schema" 行与 "API key" 行之间（决定下拉框在界面上的位置），
//      load 紧跟 schemaProperty.set，syncVisibility 紧跟 setSchema。
public class UiLoadTest {

    private static final String PANEL = "com.android.studio.ml.backends.settings.RemoteModelProviderInfoPanel";
    private static final String RPD = "com.android.studio.ml.modelproviders.data.ProviderData$RemoteProviderData";
    private static final String ATOMIC_PROPERTY = "com/intellij/openapi/observable/properties/AtomicProperty";

    private static int failCount = 0;

    public static void main(String[] args) throws Exception {
        Class.forName("com.android.studio.ml.backends.settings.OpenAiApiTypeUi");
        Class.forName("com.android.studio.ml.backends.settings.OpenAiApiTypeUi$State");
        System.out.println("enum values:");
        for (OpenAiApiType t : OpenAiApiType.values()) {
            System.out.println("  " + t.getId() + " -> " + t.getDisplayName());
        }
        check("fromId(openai-response)==RESPONSE", OpenAiApiType.fromId("openai-response") == OpenAiApiType.RESPONSE);
        check("未知 id 由转换器回退为 AUTO",
                new OpenAiApiTypeConverter().fromString("no-such-id") == OpenAiApiType.AUTO);

        checkDataField();
        checkPanelMembers();
        checkInjectionPoints();

        if (failCount > 0) {
            System.out.println(failCount + " FAILED");
            System.exit(1);
        }
        System.out.println("UI_CLASSES_LOAD_OK");
    }

    // ---- 2) 持久化字段 ----
    private static void checkDataField() throws Exception {
        Class<?> rpd = Class.forName(RPD);
        Field f = rpd.getDeclaredField("openAiApiType");
        check("RemoteProviderData.openAiApiType 字段类型", f.getType() == OpenAiApiType.class);
        OptionTag tag = f.getAnnotation(OptionTag.class);
        check("openAiApiType 带 @OptionTag", tag != null);
        check("@OptionTag.converter=OpenAiApiTypeConverter",
                tag != null && tag.converter().getName().endsWith("OpenAiApiTypeConverter"));
        check("getter/setter 存在",
                rpd.getMethod("getOpenAiApiType") != null
                        && rpd.getMethod("setOpenAiApiType", OpenAiApiType.class) != null);
    }

    // ---- 3) 面板成员 ----
    private static void checkPanelMembers() throws Exception {
        Class<?> panel = Class.forName(PANEL);
        Field provider = panel.getDeclaredField("getCurrentProvider");
        provider.setAccessible(true);
        check("面板 getCurrentProvider 字段为 Function0",
                kotlin.jvm.functions.Function0.class.isAssignableFrom(provider.getType()));
        check("面板可见性访问器存在",
                panel.getMethod("isProviderSettingVisible$aiplugin_backends_third_party") != null);
        check("面板 schemaProperty 访问器存在",
                panel.getMethod("getSchemaProperty$aiplugin_backends_third_party") != null);
        check("面板 setupUi/update 存在",
                hasMethod(panel, "setupUi") && hasMethod(panel, "update"));
    }

    private static boolean hasMethod(Class<?> c, String name) {
        for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // ---- 4) 注入点相对位置 ----
    private static void checkInjectionPoints() throws Exception {
        ClassNode cn = read(PANEL);

        MethodNode setupUi = method(cn, "setupUi", "(Lcom/intellij/ui/dsl/builder/Panel;)V");
        int schemaRow = ldcIndex(setupUi, "remote.studiobot.settings.schema.title");
        int apiKeyRow = ldcIndex(setupUi, "remote.studiobot.settings.apikey.title");
        int addRow = callIndex(setupUi, "addRow");
        check("协议下拉行位于 URL Schema 与 API key 之间", schemaRow < addRow && addRow < apiKeyRow);

        MethodNode update = method(cn, "update", "()V");
        int getSchema = callIndex(update, "getSchema");
        int schemaSet = nextCallIndex(update, getSchema, ATOMIC_PROPERTY, "set");
        int load = callIndex(update, "load");
        check("update() 在 schemaProperty.set 之后加载档位", schemaSet < load);

        MethodNode lambda = setSchemaLambda(cn);
        int setSchema = callIndex(lambda, "setSchema");
        int sync = callIndex(lambda, "syncVisibility");
        check("schema 变更后联动可见性", setSchema < sync);
    }

    // ---- 辅助 ----
    // 与补丁同样按“调用 RemoteProviderData.setSchema”定位 lambda，不写死 Kotlin 编号
    private static MethodNode setSchemaLambda(ClassNode cn) {
        String owner = binaryToInternal(RPD);
        MethodNode found = null;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in : insns(m)) {
                if (in instanceof MethodInsnNode
                        && ((MethodInsnNode) in).owner.equals(owner)
                        && ((MethodInsnNode) in).name.equals("setSchema")) {
                    if (found != null) {
                        throw new IllegalStateException("multiple setSchema lambdas");
                    }
                    found = m;
                }
            }
        }
        if (found == null) {
            throw new IllegalStateException("setSchema lambda not found in " + cn.name);
        }
        return found;
    }

    private static String binaryToInternal(String binaryName) {
        return binaryName.replace('.', '/');
    }

    private static ClassNode read(String binaryName) throws Exception {
        try (InputStream in = UiLoadTest.class.getClassLoader()
                .getResourceAsStream(binaryToInternal(binaryName) + ".class")) {
            if (in == null) {
                throw new IllegalStateException("class not on classpath: " + binaryName);
            }
            ClassNode cn = new ClassNode();
            new ClassReader(in).accept(cn, 0);
            return cn;
        }
    }

    private static MethodNode method(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.name.equals(name) && (desc == null || m.desc.equals(desc))) {
                return m;
            }
        }
        throw new IllegalStateException("method not found: " + name + " " + desc + " in " + cn.name);
    }

    private static List<AbstractInsnNode> insns(MethodNode m) {
        List<AbstractInsnNode> list = new ArrayList<>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            list.add(in);
        }
        return list;
    }

    private static int ldcIndex(MethodNode m, String value) {
        List<AbstractInsnNode> is = insns(m);
        for (int i = 0; i < is.size(); i++) {
            if (is.get(i) instanceof LdcInsnNode && value.equals(((LdcInsnNode) is.get(i)).cst)) {
                return i;
            }
        }
        throw new IllegalStateException("ldc not found: " + value + " in " + m.name);
    }

    private static int callIndex(MethodNode m, String name) {
        List<AbstractInsnNode> is = insns(m);
        for (int i = 0; i < is.size(); i++) {
            if (is.get(i) instanceof MethodInsnNode
                    && ((MethodInsnNode) is.get(i)).name.equals(name)) {
                return i;
            }
        }
        throw new IllegalStateException("call not found: " + name + " in " + m.name);
    }

    private static int nextCallIndex(MethodNode m, int from, String owner, String name) {
        List<AbstractInsnNode> is = insns(m);
        for (int i = from; i < is.size(); i++) {
            AbstractInsnNode in = is.get(i);
            if (in instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) in;
                if (mi.owner.equals(owner) && mi.name.equals(name)) {
                    return i;
                }
            }
        }
        throw new IllegalStateException("call not found after " + from + ": " + owner + "." + name
                + " in " + m.name);
    }

    private static void check(String what, boolean ok) {
        System.out.println((ok ? "ok: " : "FAILED: ") + what);
        if (!ok) {
            failCount++;
        }
    }
}
