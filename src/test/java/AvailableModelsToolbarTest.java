import com.android.studio.ml.modelproviders.data.ModelDetails;
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
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ColumnInfo;
import kotlin.jvm.functions.Function0;

import java.util.List;

// Available Models 工具栏 "+"/"-" 按钮（补丁）行为测试（headless）：
//   1) decorate() 按 Add → Remove 顺序把两个动作加入 ToolbarDecorator.myExtraActions
//      （与 createPanel 时实际挂载的列表一致；createPanel 需 IntelliJ Application，headless 不可用）
//   2) Add 按钮启用判定跟随当前 provider（无选中禁用 / 有选中启用）
//   3) Remove 按钮启用判定跟随表格行选择（无选中禁用 / 有选中启用）
//   4) removeSelectedModel 按对象同一性精确移除选中条目并刷新表格（行数/内容）
//   5) null provider / null 面板 / 无选中 / 陈旧视图行 → no-op 返回 false，不修改列表
//   6) 多次 decorate 独立生效
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

        // 生产环境 setupUi 会先 new TableView(modelTableList) 再走到注入点；测试不跑 setupUi，
        // 反射注入绑定同一 modelTableList 的 TableView（保证 getRowSorter 非空，resetSortState 安全）
        TableView<ModelDetails> table = new TableView<>(panel.getModelTableList());
        table.setAutoCreateRowSorter(true);
        installModelTable(panel, table);

        ProviderDetails[] holder = new ProviderDetails[1];
        Function0<ProviderDetails> getCurrentProvider = () -> holder[0];

        ToolbarDecorator decorator = ToolbarDecorator.createDecorator(new javax.swing.JTable())
                .disableAddAction().disableRemoveAction().disableUpAction().disableDownAction();

        AvailableModelsToolbarSupport.decorate(decorator, panel, getCurrentProvider);
        ok(true, "decorate: no exception");

        List<AnAction> actions = extraActionsOf(decorator);
        ok(actions.size() == 2, "toolbar extra actions contain exactly two entries");
        AvailableModelsToolbarSupport.AddModelAction add =
                (AvailableModelsToolbarSupport.AddModelAction) actions.get(0);
        AvailableModelsToolbarSupport.RemoveModelAction remove =
                (AvailableModelsToolbarSupport.RemoveModelAction) actions.get(1);
        ok("Add Model".equals(add.getTemplatePresentation().getText()), "add action text");
        ok("Remove Model".equals(remove.getTemplatePresentation().getText()), "remove action text");
        ok(panel == panelOf(add) && panel == panelOf(remove), "actions hold panel reference");

        ok(!AvailableModelsToolbarSupport.isEnabled(null), "isEnabled(null) == false");
        holder[0] = provider("test-provider");
        ok(AvailableModelsToolbarSupport.isEnabled(holder[0]), "isEnabled(provider) == true");
        holder[0] = null;

        // ===== "-" 按钮行为 =====
        ProviderDetails provider = provider("remove-target");
        AvailableModelsToolbarSupport.addCustomModel(provider, "model-a");
        AvailableModelsToolbarSupport.addCustomModel(provider, "model-b");
        ok(provider.getModelList().size() == 2, "provider seeded with two models");
        panel.updateUi(provider);

        ok(!AvailableModelsToolbarSupport.isRemoveEnabled(panel), "remove disabled without selection");

        table.setRowSelectionInterval(1, 1);
        ModelDetails selected = AvailableModelsToolbarSupport.getSelectedModel(panel);
        ok(selected != null && selected == provider.getModelList().get(1)
                && "model-b".equals(selected.getIdentifier()),
                "getSelectedModel returns selected row item (sorted view index converted)");
        ok(AvailableModelsToolbarSupport.isRemoveEnabled(panel), "remove enabled with selection");

        ok(AvailableModelsToolbarSupport.removeSelectedModel(provider, panel),
                "removeSelectedModel removes the selected entry");
        ok(provider.getModelList().size() == 1
                && "model-a".equals(provider.getModelList().get(0).getIdentifier()),
                "remaining list keeps the non-selected entry");
        ok(panel.getModelTableList().getItems().size() == 1, "table UI refreshed after removal");
        ok(!AvailableModelsToolbarSupport.isRemoveEnabled(panel), "remove disabled after table cleared");

        // no-op 路径：null 实参与无选中
        int before = provider.getModelList().size();
        ok(!AvailableModelsToolbarSupport.removeSelectedModel(null, panel), "removeSelectedModel(null provider) == false");
        ok(!AvailableModelsToolbarSupport.removeSelectedModel(provider, null), "removeSelectedModel(null panel) == false");
        ok(!AvailableModelsToolbarSupport.removeSelectedModel(provider, panel), "removeSelectedModel(no selection) == false");
        ok(provider.getModelList().size() == before, "no-op paths leave list untouched");

        // 上界防御：陈旧视图行（getSelectedRow 超出当前行数）→ no-op 返回 null，
        // 不让 DefaultRowSorter.convertRowIndexToModel 抛 IllegalArgumentException
        TableView<ModelDetails> staleTable = new TableView<ModelDetails>(panel.getModelTableList()) {
            @Override
            public int getSelectedRow() {
                return 99;
            }
        };
        staleTable.setAutoCreateRowSorter(true);
        installModelTable(panel, staleTable);
        ok(AvailableModelsToolbarSupport.getSelectedModel(panel) == null,
                "stale view row (>= row count) → getSelectedModel returns null (no exception)");
        ok(!AvailableModelsToolbarSupport.isRemoveEnabled(panel), "stale view row → remove disabled");
        installModelTable(panel, table);  // 恢复正常表格

        // 复刻补丁后的调用序（decorator 链尾 decorate → createPanel），验证 UI 组装不抛异常
        // 多次 decorate 独立生效（复刻补丁后的调用序：链尾 decorate → 后续 createPanel）
        ToolbarDecorator decorator2 = ToolbarDecorator.createDecorator(new javax.swing.JTable())
                .disableAddAction().disableRemoveAction().disableUpAction().disableDownAction();
        holder[0] = provider("p2");
        AvailableModelsToolbarSupport.decorate(decorator2, panel, getCurrentProvider);
        List<AnAction> actions2 = extraActionsOf(decorator2);
        ok(actions2.size() == 2
                && actions2.get(0) instanceof AvailableModelsToolbarSupport.AddModelAction
                && actions2.get(1) instanceof AvailableModelsToolbarSupport.RemoveModelAction,
                "independent decorator gets its own action pair");
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

    private static ModelInformationTablePanel panelOf(AnAction action) {
        try {
            java.lang.reflect.Field f = action.getClass().getDeclaredField("panel");
            f.setAccessible(true);
            return (ModelInformationTablePanel) f.get(action);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void installModelTable(ModelInformationTablePanel panel, TableView<ModelDetails> table) {
        try {
            java.lang.reflect.Field f = ModelInformationTablePanel.class.getDeclaredField("modelTable");
            f.setAccessible(true);
            f.set(panel, table);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
