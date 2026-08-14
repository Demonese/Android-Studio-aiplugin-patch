package com.google.studiobot.ui.querybox;

import androidx.compose.runtime.Composer;
import androidx.compose.runtime.MutableState;
import androidx.compose.runtime.SnapshotStateKt;
import com.google.studiobot.agentsdk.models.ModelId;
import com.google.studiobot.datamodel.models.ModelRunningState;
import com.google.studiobot.ui.ModelPickerEvent;
import com.google.studiobot.ui.ModelPickerItemUiState;
import com.google.studiobot.ui.ModelPickerLabel;
import com.google.studiobot.ui.ModelPickerUiState;
import com.google.studiobot.ui.trajectory.ModelPickerKt;
import java.util.ArrayList;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

// Agent 发送区"思考强度"下拉菜单。
// 复用 ModelPickerKt.ModelPicker 渲染（与模型选择同款样式）：
// 构造合成的 ModelPickerUiState（7 个可选档位），事件回调交给 ThinkingEffortStore。
// STATE 用 Snapshot MutableState 持有，refreshUi 触发 ActionsRow 重组刷新标签。
// 档位按会话持久化（metadata.json 的 reasoningEffort 字段），见 ThinkingEffortStore。
public final class ThinkingEffortPicker {
    public static final String[] LEVELS = {"none", "minimal", "low", "medium", "high", "xhigh", "max"};
    static final String PROVIDER_CLASS = "thinking-effort";
    private static final MutableState<ModelPickerUiState> STATE =
            SnapshotStateKt.mutableStateOf(buildState(ThinkingEffortStore.DEFAULT_LEVEL), SnapshotStateKt.structuralEqualityPolicy());
    private static final Function0<Unit> ON_DISMISS = () -> Unit.INSTANCE;
    private static final Function1<ModelPickerEvent, Unit> ON_EVENT = event -> {
        if (event instanceof ModelPickerEvent.SelectModel) {
            ModelId id = ((ModelPickerEvent.SelectModel) event).getModelId();
            if (id instanceof ModelId.Custom && PROVIDER_CLASS.equals(id.getProviderClass())) {
                String level = ((ModelId.Custom) id).getId();
                ThinkingEffortStore.onPickerSelect(level);
                refreshUi(level);
            }
        }
        return Unit.INSTANCE;
    };

    private ThinkingEffortPicker() {
    }

    public static String getSelectedLevel() {
        return ThinkingEffortStore.getActiveLevel();
    }

    public static void refreshUi(String level) {
        STATE.setValue(buildState(level));
    }

    static ModelPickerUiState buildState(String selected) {
        List<ModelPickerItemUiState> items = new ArrayList<>();
        for (String level : LEVELS) {
            items.add(new ModelPickerItemUiState.SelectableModel(
                    new ModelId.Custom(PROVIDER_CLASS, null, level),
                    new ModelPickerLabel.StatusLabel(level, ModelRunningState.Unspecified),
                    level.equals(selected)));
        }
        return new ModelPickerUiState(
                new ModelPickerLabel.StatusLabel(selected, ModelRunningState.Unspecified),
                items, true, null);
    }

    // 由补丁后的 QueryBoxKt.ActionsRow 调用：模型选择与发送按钮之间。
    // $changed=547（bit0 强制重组 + 参数0/1/2 changed 位），$default=8（modifier 取默认）。
    public static void render(Composer composer) {
        ModelPickerKt.ModelPicker(STATE.getValue(), ON_DISMISS, ON_EVENT, null, composer, 547, 8);
    }
}
