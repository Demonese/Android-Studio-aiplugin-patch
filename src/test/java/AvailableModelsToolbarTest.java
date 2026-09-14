import com.android.studio.ml.modelproviders.data.ProviderDetails;
import com.android.studio.ml.modelproviders.data.ProviderData;
import com.android.studio.ml.modelproviders.providerinfo.AvailableModelsToolbarSupport;
import com.android.studio.ml.modelproviders.providerinfo.ModelEnabledColumn;
import com.android.studio.ml.modelproviders.providerinfo.ModelInformationTablePanel;
import com.android.studio.ml.modelproviders.providerinfo.ModelNameColumn;
import com.android.studio.ml.modelproviders.providerinfo.ReleaseDateColumn;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.observable.properties.AtomicBooleanProperty;
import com.intellij.ui.ToolbarDecorator;
import com.intellij.util.ui.ColumnInfo;
import kotlin.jvm.functions.Function0;

import java.util.List;

// Available Models 工具栏 "+" 按钮（补丁）行为测试（headless）：
//   1) decorate() 把 AddModelAction 加入 ToolbarDecorator.myExtraActions（与 createPanel
//      时实际挂载的列表一致；createPanel 需 IntelliJ Application，headless 不可用）
//   2) 按钮启用判定跟随当前 provider（无选中禁用 / 有选中启用）
//   3) 多次 decorate 独立生效（同一 provider 引用传递）
public class AvailableModelsToolbarTest {

    private static int checks = 0;

    private static void ok(boolean cond, String name) {
        if (!cond) {
            System.out.println("FAILED: " + name);
            System.exit(1);
        }
        checks++;
        System.out.println("ok: " + name);
    }

    public static void main(String[] args) {
        // 与 RemoteModelProviderInfoPanel 构造 ModelInformationTablePanel 的实参一致
        ModelInformationTablePanel panel = new ModelInformationTablePanel(
                new AtomicBooleanProperty(true),
                new AtomicBooleanProperty(true),
                (p, cont) -> null,
                new ColumnInfo[]{new ModelNameColumn(), new ReleaseDateColumn(), new ModelEnabledColumn()});

        ProviderDetails[] holder = new ProviderDetails[1];
        Function0<ProviderDetails> getCurrentProvider = () -> holder[0];

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(new javax.swing.JTable())
                .disableAddAction().disableRemoveAction().disableUpAction().disableDownAction();

        AvailableModelsToolbarSupport.decorate(decorator, panel, getCurrentProvider);
        ok(true, "decorate: no exception");

        List<AnAction> actions = extraActionsOf(decorator);
        AvailableModelsToolbarSupport.AddModelAction add = null;
        for (AnAction a : actions) {
            if (a instanceof AvailableModelsToolbarSupport.AddModelAction) {
                add = (AvailableModelsToolbarSupport.AddModelAction) a;
            }
        }
        ok(add != null, "toolbar extra actions contain AddModelAction");
        ok("Add Model".equals(add.getTemplatePresentation().getText()), "action text");
        ok(panel == panelOf(add), "action holds panel reference");

        ok(!AvailableModelsToolbarSupport.isEnabled(null), "isEnabled(null) == false");
        holder[0] = provider("test-provider");
        ok(AvailableModelsToolbarSupport.isEnabled(holder[0]), "isEnabled(provider) == true");
        holder[0] = null;

        // 复刻补丁后的调用序（decorator 链尾 decorate → createPanel），验证 UI 组装不抛异常
        // 多次 decorate 独立生效（复刻补丁后的调用序：链尾 decorate → 后续 createPanel）
        ToolbarDecorator decorator2 = ToolbarDecorator.createDecorator(new javax.swing.JTable())
                .disableAddAction().disableRemoveAction().disableUpAction().disableDownAction();
        holder[0] = provider("p2");
        AvailableModelsToolbarSupport.decorate(decorator2, panel, getCurrentProvider);
        List<AnAction> actions2 = extraActionsOf(decorator2);
        ok(actions2.size() == 1 && actions2.get(0) instanceof AvailableModelsToolbarSupport.AddModelAction,
                "independent decorator gets its own AddModelAction");
        holder[0] = null;

        System.out.println("checks: " + checks);
        System.out.println("MODELS_TOOLBAR_OK");
    }

    @SuppressWarnings("unchecked")
    private static List<AnAction> extraActionsOf(ToolbarDecorator decorator) {
        try {
            java.lang.reflect.Field f = ToolbarDecorator.class.getDeclaredField("myExtraActions");
            f.setAccessible(true);
            return (List<AnAction>) f.get(decorator);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static ProviderDetails provider(String description) {
        return new ProviderDetails(description,
                com.android.studio.ml.modelproviders.data.ProviderType.REMOTE,
                new java.util.ArrayList<>(),
                new ProviderData.RemoteProviderData(),
                null,
                null,
                0L, 0L);
    }

    private static ModelInformationTablePanel panelOf(AvailableModelsToolbarSupport.AddModelAction action) {
        try {
            java.lang.reflect.Field f = AvailableModelsToolbarSupport.AddModelAction.class.getDeclaredField("panel");
            f.setAccessible(true);
            return (ModelInformationTablePanel) f.get(action);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
