import com.android.studio.ml.modelproviders.data.ModelDetails;
import com.android.studio.ml.modelproviders.data.ProviderDetails;
import com.android.studio.ml.modelproviders.data.ProviderType;
import com.android.studio.ml.modelproviders.providerinfo.AvailableModelsToolbarSupport;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// 自定义模型添加 + mergeModelList 替换测试（headless）：
// 1) createCustomModel：identifier==name、enabled=true、token limits=-1（运行时回退硬编码表）
// 2) addCustomModel：追加 / 按 identifier 去重 / 空白拒绝
// 3) mergeModelList（纯静态 helper，直接调用）：
//    - fetched 顺序重建 + 同 identifier 继承既有 enabled + 孤儿条目保留（对齐自动刷新语义）
//    - fetched 为空（Refresh 失败路径）→ 列表原样保留（修复原实现整表清空缺陷）
// 4) 反射调用被补丁的 ModelInformationTablePanel$Companion.updateModelList（dist jar 内
//    被替换为 mergeModelList 早退）：孤儿保留断言对未补丁实现必然失败，验证 ASM 补丁生效
public class MergeModelListTest {
    private static int passed = 0;
    private static int failed = 0;

    private static void check(boolean cond, String name) {
        if (cond) {
            System.out.println("ok: " + name);
            passed++;
        } else {
            System.out.println("FAILED: " + name);
            failed++;
        }
    }

    private static ProviderDetails newProvider() {
        return new ProviderDetails("p", ProviderType.REMOTE, new ArrayList<>(), null, null, null, 0L, 0L);
    }

    private static ModelDetails model(String identifier, boolean enabled) {
        return new ModelDetails(identifier, identifier, enabled, Collections.emptyList(), 1000, 500,
                null, "desc-" + identifier);
    }

    private static List<String> identifiers(List<ModelDetails> models) {
        List<String> ids = new ArrayList<>();
        for (ModelDetails m : models) {
            ids.add(m.getIdentifier());
        }
        return ids;
    }

    public static void main(String[] args) throws Exception {
        // ---- 1) createCustomModel ----
        ModelDetails custom = AvailableModelsToolbarSupport.createCustomModel("my-model");
        check("my-model".equals(custom.getName()) && "my-model".equals(custom.getIdentifier()),
                "createCustomModel: name==identifier==id");
        check(custom.getEnabled(), "createCustomModel: enabled=true");
        check(custom.getInputTokenLimit() == -1 && custom.getOutputTokenLimit() == -1
                        && custom.getReleaseDate() == null && custom.getDescription().isEmpty(),
                "createCustomModel: limits=-1, releaseDate=null, description空");

        // ---- 2) addCustomModel ----
        ProviderDetails provider = newProvider();
        ModelDetails added = AvailableModelsToolbarSupport.addCustomModel(provider, " my-model ");
        check(added != null && provider.getModelList().size() == 1
                        && "my-model".equals(provider.getModelList().get(0).getIdentifier()),
                "addCustomModel: trim 后追加");
        check(AvailableModelsToolbarSupport.addCustomModel(provider, "my-model") == null
                        && provider.getModelList().size() == 1,
                "addCustomModel: identifier 重复 → null 且列表不变");
        check(AvailableModelsToolbarSupport.addCustomModel(provider, "   ") == null
                        && AvailableModelsToolbarSupport.addCustomModel(provider, null) == null,
                "addCustomModel: 空白/null → null");

        // ---- 3) mergeModelList 语义 ----
        List<ModelDetails> existing = new ArrayList<>(Arrays.asList(
                model("a", true), model("b", false), model("c", true)));
        List<ModelDetails> fetched = new ArrayList<>(Arrays.asList(
                model("a", false), model("d", false)));
        AvailableModelsToolbarSupport.mergeModelList(existing, fetched);
        check(identifiers(existing).equals(Arrays.asList("a", "d", "b", "c")),
                "merge: fetched 顺序重建 + 孤儿保留在尾部");
        check(existing.get(0).getEnabled(), "merge: 同 identifier 继承既有 enabled(a=true)");
        check(!existing.get(2).getEnabled() && existing.get(3).getEnabled(),
                "merge: 孤儿条目原对象保留(b.enabled=false)");
        check(existing.get(2).getDescription().equals("desc-b") && existing.get(3).getDescription().equals("desc-c"),
                "merge: 孤儿条目字段未被覆盖");

        List<ModelDetails> snapshot = new ArrayList<>(existing);
        AvailableModelsToolbarSupport.mergeModelList(existing, new ArrayList<>());
        check(existing.equals(snapshot),
                "merge: fetched 为空（Refresh 失败路径）→ 列表原样保留");

        List<ModelDetails> allNew = new ArrayList<>(Arrays.asList(model("x", false)));
        AvailableModelsToolbarSupport.mergeModelList(allNew, allNew);
        check(allNew.size() == 1 && allNew.get(0).getEnabled() == false && identifiers(allNew).equals(List.of("x")),
                "merge: 全新条目按 fetched 原样保留");

        // ---- 4) 被补丁的 Companion.updateModelList（dist jar）----
        Class<?> outer = Class.forName("com.android.studio.ml.modelproviders.providerinfo.ModelInformationTablePanel");
        Field companionField = outer.getField("Companion");
        Object companion = companionField.get(null);
        Method updateModelList = companion.getClass().getMethod("updateModelList", List.class, List.class);

        List<ModelDetails> patchedExisting = new ArrayList<>(Arrays.asList(
                model("a", true), model("manual-custom", true), model("b", false)));
        List<ModelDetails> patchedFetched = new ArrayList<>(Arrays.asList(model("a", false)));
        updateModelList.invoke(companion, patchedExisting, patchedFetched);
        check(identifiers(patchedExisting).equals(Arrays.asList("a", "manual-custom", "b")),
                "patched Companion.updateModelList: 孤儿条目保留（未补丁实现丢弃 → 本断言失败）");
        check(patchedExisting.get(0).getEnabled(), "patched Companion.updateModelList: enabled 继承");

        List<ModelDetails> patchedEmpty = new ArrayList<>(Arrays.asList(model("a", true)));
        updateModelList.invoke(companion, patchedEmpty, new ArrayList<>());
        check(patchedEmpty.size() == 1 && "a".equals(patchedEmpty.get(0).getIdentifier()),
                "patched Companion.updateModelList: 空 fetched → 原样保留（修复刷新失败清空）");

        System.out.println(passed + " passed, " + failed + " failed");
        if (failed == 0) {
            System.out.println("MERGE_OK");
        } else {
            System.out.println("MERGE_FAILED");
        }
    }
}
