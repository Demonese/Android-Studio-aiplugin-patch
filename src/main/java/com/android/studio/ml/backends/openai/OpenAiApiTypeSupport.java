package com.android.studio.ml.backends.openai;

import com.android.studio.ml.modelproviders.data.OpenAiApiType;

public final class OpenAiApiTypeSupport {
    private OpenAiApiTypeSupport() {
    }

    public static boolean resolveUseResponses(OpenAiModelApi api) {
        OpenAiApiType t = api.getOpenAiApiType();
        if (t == OpenAiApiType.CHAT_COMPLETION) return false;
        if (t == OpenAiApiType.RESPONSE) return true;
        return api.getSupportResponses().get();
    }

    public static boolean allowResponsesFallback(OpenAiModelApi api) {
        return api.getOpenAiApiType() == OpenAiApiType.AUTO;
    }
}
