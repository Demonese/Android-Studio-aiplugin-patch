import com.android.studio.ml.backends.settings.OpenAiApiTypeUi;
import com.android.studio.ml.modelproviders.data.OpenAiApiType;

public class UiLoadTest {
    public static void main(String[] args) throws Exception {
        Class.forName("com.android.studio.ml.backends.settings.OpenAiApiTypeUi");
        Class.forName("com.android.studio.ml.backends.settings.OpenAiApiTypeUi$State");
        System.out.println("enum values:");
        for (OpenAiApiType t : OpenAiApiType.values()) System.out.println("  " + t.getId() + " -> " + t.getDisplayName());
        System.out.println("fromId(openai-response)=" + OpenAiApiType.fromId("openai-response"));
        System.out.println("UI_CLASSES_LOAD_OK");
    }
}
