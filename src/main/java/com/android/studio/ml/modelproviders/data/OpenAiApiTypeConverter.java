package com.android.studio.ml.modelproviders.data;

import com.intellij.util.xmlb.Converter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class OpenAiApiTypeConverter extends Converter<OpenAiApiType> {

    @NotNull
    @Override
    public String toString(@NotNull OpenAiApiType value) {
        return value.getId();
    }

    @Nullable
    @Override
    public OpenAiApiType fromString(@NotNull String value) {
        OpenAiApiType result = OpenAiApiType.fromId(value);
        return result != null ? result : OpenAiApiType.AUTO;
    }
}
