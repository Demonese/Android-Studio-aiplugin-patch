package com.google.studiobot.ui.querybox;

import com.google.studiobot.agentsdk.conversations.PersistedMetadata;
import com.google.studiobot.controller.ConversationSelection;
import java.util.concurrent.ConcurrentHashMap;
import kotlinx.serialization.descriptors.SerialDescriptor;
import kotlinx.serialization.encoding.CompositeDecoder;
import kotlinx.serialization.encoding.CompositeEncoder;
import kotlinx.serialization.internal.StringSerializer;

// 思考强度运行时存储：会话ID -> 档位（持久化字段 reasoningEffort 的内存侧）。
// - 加载会话元数据时由 $$serializer.deserialize 尾部调 onLoaded 写入；
// - 切换/新建会话时由 ActiveConversationOrchestrator.selectConversation 调 onConversationSelection；
// - 保存时由 TopLevelConversation/DefaultConversation.prepareMetadata 调 applyTo 回填 PersistedMetadata；
// - 下拉选择时由 ThinkingEffortPicker 调 onPickerSelect。
public final class ThinkingEffortStore {
    public static final String DEFAULT_LEVEL = "medium";
    static final int ELEMENT_INDEX = 12;
    private static final ConcurrentHashMap<String, String> KNOWN = new ConcurrentHashMap<>();
    private static volatile String currentId = null;
    private static volatile String activeLevel = DEFAULT_LEVEL;

    private ThinkingEffortStore() {
    }

    public static String getActiveLevel() {
        return activeLevel;
    }

    public static String getLevelFor(String conversationId) {
        if (conversationId == null) {
            return activeLevel;
        }
        String level = KNOWN.get(conversationId);
        return level != null ? level : DEFAULT_LEVEL;
    }

    // 元数据反序列化完成（reasoningEffort 可能为 null = 旧对话）。
    public static void onLoaded(String conversationId, String effort) {
        if (conversationId == null) {
            return;
        }
        String level = effort != null ? effort : DEFAULT_LEVEL;
        KNOWN.put(conversationId, level);
        String id = currentId;
        if (id == null || conversationId.equals(id)) {
            activeLevel = level;
            ThinkingEffortPicker.refreshUi(level);
        }
    }

    // 会话切换/新建入口（ActiveConversationOrchestrator.selectConversation）。
    public static void onConversationSelection(ConversationSelection selection) {
        if (selection instanceof ConversationSelection.ExistingConversation) {
            syncTo(((ConversationSelection.ExistingConversation) selection).getId());
        } else if (selection instanceof ConversationSelection.EmptyConversation) {
            currentId = null;
            activeLevel = DEFAULT_LEVEL;
            ThinkingEffortPicker.refreshUi(DEFAULT_LEVEL);
        }
    }

    // 会话在 UI 中呈现（TrajectoryEvent.ConversationPresented）。
    // 覆盖 IDE 重启后首个会话：初始选择不经 selectConversation，
    // 但呈现事件一定触发，此时按 KNOWN 同步下拉。
    public static void onConversationPresented(String conversationId) {
        syncTo(conversationId);
    }

    private static void syncTo(String conversationId) {
        if (conversationId == null) {
            return;
        }
        currentId = conversationId;
        String level = KNOWN.get(conversationId);
        activeLevel = level != null ? level : DEFAULT_LEVEL;
        ThinkingEffortPicker.refreshUi(activeLevel);
    }

    // 下拉选择。
    public static void onPickerSelect(String level) {
        activeLevel = level;
        String id = currentId;
        if (id != null) {
            KNOWN.put(id, level);
        }
    }

    // 保存元数据前回填。新会话首次保存时 currentId 尚为 null，
    // 此时用当前选择并绑定会话 ID。
    public static void applyTo(PersistedMetadata metadata) {
        String id = metadata.getId();
        String level = KNOWN.get(id);
        if (level == null) {
            if (currentId == null) {
                level = activeLevel;
                KNOWN.put(id, level);
                currentId = id;
            } else {
                level = DEFAULT_LEVEL;
            }
        }
        metadata.setReasoningEffort(level);
    }

    // 测试用。
    public static void resetForTest() {
        KNOWN.clear();
        currentId = null;
        activeLevel = DEFAULT_LEVEL;
    }

    public static void setCurrentIdForTest(String id) {
        currentId = id;
    }

    // kotlinx.serialization 辅助（被补丁的 write$Self / deserialize 直线调用，
    // 避免在字节码中生成新分支/帧）。
    public static void encodeElement(PersistedMetadata self, CompositeEncoder output, SerialDescriptor desc) {
        String value = self.getReasoningEffort();
        if (output.shouldEncodeElementDefault(desc, ELEMENT_INDEX) || value != null) {
            output.encodeNullableSerializableElement(desc, ELEMENT_INDEX, StringSerializer.INSTANCE, value);
        }
    }

    public static String decodeElement(CompositeDecoder input, SerialDescriptor desc) {
        Object value = input.decodeNullableSerializableElement(desc, ELEMENT_INDEX, StringSerializer.INSTANCE, null);
        return (String) value;
    }
}
