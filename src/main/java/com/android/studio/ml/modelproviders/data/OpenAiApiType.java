package com.android.studio.ml.modelproviders.data;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public enum OpenAiApiType {
    AUTO("auto", "Auto (Responses API, fallback to Chat Completions)"),
    CHAT_COMPLETION("openai-chat-completion", "OpenAI Chat Completions API"),
    RESPONSE("openai-response", "OpenAI Responses API");

    private final String id;
    private final String displayName;

    OpenAiApiType(@NotNull String id, @NotNull String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @NotNull
    public String getId() {
        return id;
    }

    @NotNull
    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    @Nullable
    public static OpenAiApiType fromId(@NotNull String id) {
        for (OpenAiApiType t : values()) {
            if (t.id.equalsIgnoreCase(id)) {
                return t;
            }
        }
        return null;
    }
}
