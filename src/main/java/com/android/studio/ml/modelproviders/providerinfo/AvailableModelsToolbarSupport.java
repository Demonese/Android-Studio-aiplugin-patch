package com.android.studio.ml.modelproviders.providerinfo;

import com.android.studio.ml.modelproviders.data.ModelDetails;
import com.android.studio.ml.modelproviders.data.ProviderDetails;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
import java.awt.Component;
import java.awt.event.InputEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kotlin.jvm.functions.Function0;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// Available Models 表格工具栏的 "+" 按钮（补丁注入）。
// 原实现 ToolbarDecorator 链 disableAddAction/disableRemoveAction/disableUpAction/disableDownAction，
// 表格无任何手动增删入口；本类以官方同款 addExtraAction 方式（对照 ModelProviderGroupPanel
// 在 Model Providers 列表上的 "+" 按钮组）挂一个 "Add Model" 动作。
// 注入点：ModelInformationTablePanel.setupUi 的 ToolbarDecorator 链尾（ASM 补丁，PatchTool modelstable）。
// 行为约束：动作在点击时才通过 getCurrentProvider 解析当前选中行（与 Refresh 链接同款解析方式），
// 不缓存 ProviderDetails —— 左侧列表切换后自动跟随。
//
// 对话框模式对照官方 "Add Command Prefix"（PermissionSettingsUi.addStringToList）：
// Messages.showInputDialog(component, "Enter command prefix", "Add Command Prefix", null)
// → 空输入/取消直接放弃，不做任何写操作。此处同款："Enter model ID" / "Add Model"。
//
// 手动添加的条目持久化路径与拉取条目一致：写入 ProviderDetails.modelList →
// 设置页 Apply/OK → ModelDataStateManager.saveState → ai.providers.xml。
// 自动刷新（ModelProviderAutoRefreshService）对不在拉取结果里的孤儿条目整体保留，
// 手动条目可存活；手动 Refresh 经 mergeModelList（PatchTool modelsmerge 替换
// Companion.updateModelList 体）后同样保留孤儿并与自动刷新语义一致。
public final class AvailableModelsToolbarSupport {

    private AvailableModelsToolbarSupport() {
    }

    // 被补丁的 setupUi 在 disableDownAction 之后调用（栈上持有 decorator）。
    // panel 参数供动作在添加成功后回写表格 UI（updateUi）。
    public static void decorate(@NotNull ToolbarDecorator decorator,
                                @Nullable ModelInformationTablePanel panel,
                                @NotNull Function0<ProviderDetails> getCurrentProvider) {
        decorator.addExtraAction(new AddModelAction(panel, getCurrentProvider));
    }

    // 按钮启用判定：有当前选中的 provider 即可用（与工具栏其他动作的判定时机一致）。
    // update() 与测试共用，避免依赖 AnActionEvent 构造。
    public static boolean isEnabled(@Nullable ProviderDetails provider) {
        return provider != null;
    }

    /**
     * "Add Model" 输入对话框（对照官方 Add Command Prefix）。
     * 取消/空白输入 → 不做任何写操作；identifier 重复 → 提示后放弃；
     * 成功 → 追加 {@link #createCustomModel(String)}（enabled=true）并刷新表格。
     */
    public static void showAddModelDialog(@Nullable Component parent,
                                          @Nullable ModelInformationTablePanel panel,
                                          @NotNull Function0<ProviderDetails> getCurrentProvider) {
        ProviderDetails provider = getCurrentProvider.invoke();
        if (provider == null) {
            return;
        }
        String id = Messages.showInputDialog(parent, "Enter model ID", "Add Model", null);
        if (id == null || id.trim().isEmpty()) {
            return;
        }
        if (addCustomModel(provider, id) == null) {
            Messages.showInfoMessage(parent, "Model \"" + id.trim() + "\" already exists.", "Add Model");
            return;
        }
        if (panel != null) {
            panel.updateUi(provider);
        }
    }

    // 手动条目工厂：identifier 同时作为显示名（与 OpenAI fetcher 的 name=identifier 约定一致）。
    // enabled=true：手动添加是显式意图，直接可用（拉取条目默认 false 是批量导入的保守策略）。
    // token limits 取 -1（未知）：运行时 toModelConfig 回退 OpenAiUtilsKt.tokenLimit(identifier)
    // 硬编码表，未命中时兜底 16384/16384 —— 与第三方供应商拉取到的未知模型行为一致。
    // releaseDate/description 留空。
    @NotNull
    public static ModelDetails createCustomModel(@NotNull String id) {
        return new ModelDetails(id, id, true, Collections.emptyList(), -1, -1, null, "");
    }

    /**
     * 向 provider.modelList 追加自定义模型（trim 后按 identifier 去重）。
     *
     * @return 新增的条目；provider/id 为空、空白或 identifier 已存在时返回 null（不修改列表）
     */
    @Nullable
    public static ModelDetails addCustomModel(@NotNull ProviderDetails provider, @Nullable String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (ModelDetails existing : provider.getModelList()) {
            if (trimmed.equals(existing.getIdentifier())) {
                return null;
            }
        }
        ModelDetails created = createCustomModel(trimmed);
        provider.getModelList().add(created);
        return created;
    }

    /**
     * Companion.updateModelList 的替换实现（PatchTool modelsmerge：方法体前置早退）。
     *
     * 与原实现的差异（原实现：仅按 fetched 重建、孤儿丢弃）：
     * 1. 保留孤儿条目（不在 fetched 中的既有条目追加到列表尾部）——
     *    与后台自动刷新（ModelProviderAutoRefreshService）语义对齐，手动 Refresh
     *    不再抹掉手动添加的模型/已下架模型；
     * 2. fetched 为空（Refresh 失败路径传 emptyList()）时列表原样保留 ——
     *    修复原实现"刷新失败弹错误框后整表清空"的缺陷。
     *
     * 共同语义保持不变：以 fetched 顺序重建，同 identifier 的条目继承既有条目的
     * enabled 开关（其余字段以 fetched 为准）。
     */
    public static void mergeModelList(@Nullable List<ModelDetails> existing,
                                      @Nullable List<ModelDetails> fetched) {
        if (existing == null || fetched == null) {
            return;
        }
        Map<String, ModelDetails> existingById = new LinkedHashMap<>();
        for (ModelDetails model : existing) {
            existingById.putIfAbsent(model.getIdentifier(), model);
        }
        List<ModelDetails> updated = new ArrayList<>(fetched.size());
        for (ModelDetails fresh : fetched) {
            ModelDetails old = existingById.remove(fresh.getIdentifier());
            ModelDetails merged = fresh.copy();
            if (old != null) {
                merged.setEnabled(old.getEnabled());
            }
            updated.add(merged);
        }
        // 孤儿条目按原顺序追加到尾部（existingById 中未被 fetched 消费的剩余项）。
        for (ModelDetails orphan : existing) {
            if (existingById.remove(orphan.getIdentifier()) != null) {
                updated.add(orphan);
            }
        }
        existing.clear();
        existing.addAll(updated);
    }

    public static final class AddModelAction extends AnAction {
        @Nullable
        private final ModelInformationTablePanel panel;
        @NotNull
        private final Function0<ProviderDetails> getCurrentProvider;

        public AddModelAction(@Nullable ModelInformationTablePanel panel,
                              @NotNull Function0<ProviderDetails> getCurrentProvider) {
            super("Add Model", "Add a model manually to this provider", AllIcons.General.Add);
            this.panel = panel;
            this.getCurrentProvider = getCurrentProvider;
        }

        @Override
        public void actionPerformed(@NotNull AnActionEvent e) {
            Component parent = null;
            InputEvent input = e.getInputEvent();
            if (input != null && input.getSource() instanceof Component) {
                parent = (Component) input.getSource();
            }
            showAddModelDialog(parent, panel, getCurrentProvider);
        }

        @Override
        public void update(@NotNull AnActionEvent e) {
            e.getPresentation().setEnabled(isEnabled(getCurrentProvider.invoke()));
        }

        // update() 读取左侧列表选中状态（Swing 数据），必须在 EDT 执行。
        @Override
        public @NotNull ActionUpdateThread getActionUpdateThread() {
            return ActionUpdateThread.EDT;
        }
    }
}
