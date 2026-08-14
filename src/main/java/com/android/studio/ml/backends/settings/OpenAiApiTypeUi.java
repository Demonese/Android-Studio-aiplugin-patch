package com.android.studio.ml.backends.settings;

import com.android.studio.ml.modelproviders.data.OpenAiApiType;
import com.android.studio.ml.modelproviders.data.ProviderData;
import com.android.studio.ml.modelproviders.data.ProviderDetails;
import com.android.studio.ml.modelproviders.data.ProviderSettings;
import com.intellij.openapi.observable.properties.AtomicBooleanProperty;
import com.intellij.openapi.observable.properties.AtomicProperty;
import com.intellij.openapi.observable.properties.ObservableProperty;
import com.intellij.openapi.observable.util.PropertyOperationUtil;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.dsl.builder.Align;
import com.intellij.ui.dsl.builder.Cell;
import com.intellij.ui.dsl.builder.ComboBoxKt;
import com.intellij.ui.dsl.builder.Panel;
import com.intellij.ui.dsl.builder.Row;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

public final class OpenAiApiTypeUi {

    public static final class State {
        public final AtomicProperty<OpenAiApiType> apiTypeProperty = new AtomicProperty<>(OpenAiApiType.AUTO);
        public final AtomicBooleanProperty apiTypeVisible = new AtomicBooleanProperty(false);
        public ComboBox<OpenAiApiType> comboBox;

        State(final RemoteModelProviderInfoPanel panel) {
            apiTypeProperty.afterChange(new Function1<OpenAiApiType, Unit>() {
                @Override
                public Unit invoke(OpenAiApiType it) {
                    writeBack(panel, it);
                    return Unit.INSTANCE;
                }
            });
        }
    }

    private static final Map<RemoteModelProviderInfoPanel, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<RemoteModelProviderInfoPanel, State>());

    private OpenAiApiTypeUi() {
    }

    public static State state(RemoteModelProviderInfoPanel panel) {
        synchronized (STATES) {
            State s = STATES.get(panel);
            if (s == null) {
                s = new State(panel);
                STATES.put(panel, s);
            }
            return s;
        }
    }

    private static volatile Method getCurrentProviderAccessor;

    private static Object invokeGetCurrentProvider(RemoteModelProviderInfoPanel panel) {
        try {
            Method m = getCurrentProviderAccessor;
            if (m == null) {
                m = RemoteModelProviderInfoPanel.class.getDeclaredMethod("access$getGetCurrentProvider$p", RemoteModelProviderInfoPanel.class);
                m.setAccessible(true);
                getCurrentProviderAccessor = m;
            }
            return m.invoke(null, panel);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeBack(RemoteModelProviderInfoPanel panel, OpenAiApiType value) {
        Object fn = invokeGetCurrentProvider(panel);
        if (!(fn instanceof Function0)) {
            return;
        }
        Object current = ((Function0<?>) fn).invoke();
        if (!(current instanceof ProviderDetails)) {
            return;
        }
        ProviderSettings ps = ((ProviderDetails) current).getProviderData();
        if (ps instanceof ProviderData.RemoteProviderData) {
            ((ProviderData.RemoteProviderData) ps).setOpenAiApiType(value);
        }
    }

    public static void addRow(final RemoteModelProviderInfoPanel panel, Panel builder) {
        final State st = state(panel);
        Row row = builder.row("OpenAI API protocol:", new Function1<Row, Unit>() {
            @Override
            public Unit invoke(Row r) {
                Cell<ComboBox<OpenAiApiType>> cell = r.comboBox(Arrays.asList(OpenAiApiType.values()), null);
                ComboBoxKt.bindItem(cell, st.apiTypeProperty);
                st.comboBox = cell.align(Align.FILL).getComponent();
                return Unit.INSTANCE;
            }
        });
        ObservableProperty<Boolean> visible = PropertyOperationUtil.and(
                panel.isProviderSettingVisible$aiplugin_backends_third_party(), st.apiTypeVisible);
        row.visibleIf(visible);
    }

    public static void syncVisibility(RemoteModelProviderInfoPanel panel, ProviderData.RemoteProviderData.ApiSchema schema) {
        state(panel).apiTypeVisible.set(schema == ProviderData.RemoteProviderData.ApiSchema.OPENAI);
    }

    public static void load(RemoteModelProviderInfoPanel panel, ProviderData.RemoteProviderData data) {
        State st = state(panel);
        OpenAiApiType t = data.getOpenAiApiType();
        st.apiTypeProperty.set(t != null ? t : OpenAiApiType.AUTO);
        ProviderData.RemoteProviderData.ApiSchema schema = data.getSchema();
        syncVisibility(panel, schema != null ? schema : ProviderData.RemoteProviderData.ApiSchema.OPENAI);
    }
}
