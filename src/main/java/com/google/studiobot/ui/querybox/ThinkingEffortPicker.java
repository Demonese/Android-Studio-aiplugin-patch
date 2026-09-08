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
    // Compose 参数掩码（按本版本 ModelPicker 生成码核实）：
    //  - $changed：四个参数组分别占 bit1-2 / bit4-5 / bit7-8 / bit10-11，
    //    每组低位=“已知未变”、高位=“已知已变”；bit0 置位则 callee 的
    //    shouldExecute(force, …) 中 force 恒真（必执行）。这里只给 bit0，
    //    参数组位全部留空 → ModelPicker 自己跑 changedInstance() 动态判定，
    //    不会收到“state 未变”这类谎报信息（原生的透传重调用也用同一形状：
    //    RecomposeScopeImplKt.updateChangedFlags(x | 1)，对 1 而言结果仍为 1）。
    //  - $default=8：第 4 个参数（modifier）取默认值，与原生调用点写法一致（callee 用 &8 判）。
    public static void render(Composer composer) {
        ModelPickerKt.ModelPicker(STATE.getValue(), ON_DISMISS, ON_EVENT, null, composer, 1, 8);
    }
}
