package com.android.studio.ml.modelproviders.providerinfo;

import com.android.studio.ml.modelproviders.data.ProviderDetails;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.ToolbarDecorator;
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
public final class AvailableModelsToolbarSupport {

    private AvailableModelsToolbarSupport() {
    }

    // 被补丁的 setupUi 在 disableDownAction 之后调用（栈上持有 decorator）。
    // panel 参数供动作回写表格 UI 使用（updateUi）；当前 UI 阶段仅保存引用。
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
            // TODO(手动加模型功能)：弹出添加对话框或直接插入条目，随后
            //  panel.updateUi(provider) 刷新表格；数据写入 ProviderDetails.modelList
            //  随 Apply/OK 持久化到 ai.providers.xml。当前阶段仅打通 UI 链路。
            ProviderDetails provider = getCurrentProvider.invoke();
            if (provider == null) {
                return;
            }
            Messages.showInfoMessage(
                    "Add-model dialog is not implemented yet (provider: " + provider.getDescription() + ")",
                    "Add Model");
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
