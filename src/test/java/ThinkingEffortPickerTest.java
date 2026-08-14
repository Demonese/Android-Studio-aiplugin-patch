import androidx.compose.runtime.State;
import com.google.studiobot.agentsdk.models.ModelId;
import com.google.studiobot.ui.ModelPickerEvent;
import com.google.studiobot.ui.ModelPickerItemUiState;
import com.google.studiobot.ui.ModelPickerUiState;
import com.google.studiobot.ui.querybox.ThinkingEffortPicker;
import java.lang.reflect.Field;
import kotlin.jvm.functions.Function1;

public class ThinkingEffortPickerTest {
    static int failed = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "ok: " : "FAILED: ") + msg);
        if (!cond) failed++;
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        String[] levels = ThinkingEffortPicker.LEVELS;
        check(levels.length == 7, "档位数量 = 7");
        check(String.join(",", levels).equals("none,minimal,low,medium,high,xhigh,max"), "档位顺序正确");
        check("medium".equals(ThinkingEffortPicker.getSelectedLevel()), "默认档位 = medium");

        Field f = ThinkingEffortPicker.class.getDeclaredField("ON_EVENT");
        f.setAccessible(true);
        Function1<Object, Object> onEvent = (Function1<Object, Object>) f.get(null);

        onEvent.invoke(new ModelPickerEvent.SelectModel(new ModelId.Custom("thinking-effort", null, "max")));
        check("max".equals(ThinkingEffortPicker.getSelectedLevel()), "选择事件更新档位");

        Field sf = ThinkingEffortPicker.class.getDeclaredField("STATE");
        sf.setAccessible(true);
        ModelPickerUiState ui = (ModelPickerUiState) ((State<Object>) sf.get(null)).getValue();
        check("max".equals(ui.getCurrentModelLabel().getText()), "按钮标签随选择更新");
        check(ui.getItems().size() == 7, "下拉项 = 7");
        int selected = 0;
        for (ModelPickerItemUiState it : ui.getItems()) {
            if (it instanceof ModelPickerItemUiState.SelectableModel
                    && ((ModelPickerItemUiState.SelectableModel) it).isSelected()) {
                selected++;
            }
        }
        check(selected == 1, "恰好 1 项选中");
        check(ui.isVisible(), "picker 可见");

        onEvent.invoke(new ModelPickerEvent.SelectModel(new ModelId.Custom("other-provider", null, "low")));
        check("max".equals(ThinkingEffortPicker.getSelectedLevel()), "忽略其它来源的选择事件");

        System.out.println(failed == 0 ? "ALL_OK" : "FAILED_COUNT=" + failed);
    }
}
