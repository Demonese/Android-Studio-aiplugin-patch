import com.google.studiobot.agentsdk.agents.AgentConfigurationData;
import com.google.studiobot.agentsdk.conversations.PersistedMetadata;
import com.google.studiobot.controller.ConversationSelection;
import com.google.studiobot.ui.querybox.ThinkingEffortStore;
import java.util.Collections;
import kotlinx.serialization.json.Json;

public class ReasoningEffortPersistTest {
    static int failed = 0;

    static void check(boolean cond, String msg) {
        System.out.println((cond ? "ok: " : "FAILED: ") + msg);
        if (!cond) failed++;
    }

    static PersistedMetadata meta(String id) {
        return new PersistedMetadata(2, id, new AgentConfigurationData(), "t", 1, 1L, 2L, 3L,
                null, Collections.emptySet(), false, null);
    }

    public static void main(String[] args) {
        PersistedMetadata pm = meta("conv-1");
        pm.setReasoningEffort("high");
        String json = Json.Default.encodeToString(PersistedMetadata.Companion.serializer(), pm);
        check(json.contains("\"reasoningEffort\":\"high\""), "序列化包含 reasoningEffort");

        PersistedMetadata back = Json.Default.decodeFromString(PersistedMetadata.Companion.serializer(), json);
        check("high".equals(back.getReasoningEffort()), "往返保留 reasoningEffort");
        check("conv-1".equals(back.getId()), "往返保留其它字段");

        String oldJson = "{\"version\":2,\"id\":\"conv-2\","
                + "\"agentConfiguration\":{\"promptSectionOperations\":{},\"tools\":null,\"subAgents\":[],"
                + "\"includeMcpTools\":true,\"enablePlanningMode\":true,\"toolInfo\":null},"
                + "\"title\":\"t\",\"trajectoryNextId\":1,\"creationTime\":1,\"lastUserQueryTime\":2,"
                + "\"lastModificationTime\":3,\"modelId\":null,\"visitedFiles\":[],"
                + "\"hasCustomTitle\":false,\"delegateMetadata\":null}";
        PersistedMetadata old = Json.Default.decodeFromString(PersistedMetadata.Companion.serializer(), oldJson);
        check(old.getReasoningEffort() == null, "旧格式（无字段）解码为 null");

        PersistedMetadata pmNull = meta("conv-3");
        String jsonNull = Json.Default.encodeToString(PersistedMetadata.Companion.serializer(), pmNull);
        check(!jsonNull.contains("reasoningEffort"), "null 时不写出字段");

        ThinkingEffortStore.resetForTest();
        ThinkingEffortStore.onConversationSelection(new ConversationSelection.ExistingConversation("conv-1"));
        ThinkingEffortStore.onLoaded("conv-1", "high");
        check("high".equals(ThinkingEffortStore.getActiveLevel()), "加载更新当前档位");
        ThinkingEffortStore.onPickerSelect("max");
        check("max".equals(ThinkingEffortStore.getActiveLevel()), "下拉选择更新档位");
        PersistedMetadata save = meta("conv-1");
        ThinkingEffortStore.applyTo(save);
        check("max".equals(save.getReasoningEffort()), "保存回填所选档位");

        ThinkingEffortStore.onConversationSelection(ConversationSelection.EmptyConversation.INSTANCE);
        check("medium".equals(ThinkingEffortStore.getActiveLevel()), "新建会话回到默认档位");
        ThinkingEffortStore.onPickerSelect("low");
        PersistedMetadata first = meta("conv-new");
        ThinkingEffortStore.applyTo(first);
        check("low".equals(first.getReasoningEffort()), "新会话首次保存用所选档位");

        ThinkingEffortStore.resetForTest();
        ThinkingEffortStore.onConversationSelection(new ConversationSelection.ExistingConversation("conv-9"));
        ThinkingEffortStore.onLoaded("conv-9", null);
        check("medium".equals(ThinkingEffortStore.getActiveLevel()), "旧对话（null）按默认 medium");

        ThinkingEffortStore.resetForTest();
        ThinkingEffortStore.onLoaded("conv-a", "high");
        ThinkingEffortStore.onLoaded("conv-b", null);
        ThinkingEffortStore.onConversationPresented("conv-a");
        check("high".equals(ThinkingEffortStore.getActiveLevel()), "重启首会话：呈现事件同步正确档位");

        System.out.println(failed == 0 ? "ALL_OK" : "FAILED_COUNT=" + failed);
    }
}
