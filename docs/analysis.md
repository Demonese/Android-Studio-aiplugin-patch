# Android Studio Gemini 插件逆向分析

> 分析对象：Android Studio 2026.1.4（build 261.26222.65.2614.16204760）Windows
> 发行包中的 `plugins/gemini/lib/aiplugin.jar`（插件 id `com.google.tools.ij.aiplugin`，
> Google 闭源，不在 AOSP `studio-master-dev` 公开源码清单内，只能反编译分析）。
> 反编译工具：CFR 0.152。反编译输出可通过 `scripts/20_decompile.sh` 重现。

## 1. OpenAI 兼容 API 调用与自动 fallback（问题根源）

### 当前第三方远程 provider 路径（OpenAI SDK V2）

`com.android.studio.ml.backends.openai.OpenAiModelApi`（源文件 `OpenAiModelApi.kt`，模块 `aiplugin.backends.third-party`）：

```java
// 三个内存开关，初始全为 true
this.supportStreaming       = new AtomicBoolean(true);
this.supportResponses       = new AtomicBoolean(true);   // ← 关键
this.supportReasoningEffort = new AtomicBoolean(true);

public Flow<ModelResponse> streamGenerateContent(ModelRequest req) {
    boolean useResponsesAPI = supportResponses.get();
    // true  → client.responses().createStreaming(...)      // Responses API
    // false → client.chat().completions().createStreaming(...) // Chat Completions
    return catch(flow) { e ->
        when (detectErrorType(e)) {
            STREAMING_NOT_SUPPORTED (且 $streaming)  -> supportStreaming=false;       retry
            (REASONING_EFFORT_NOT_SUPPORTED | BAD_REQUEST_OTHER)
              && $reasoningEffort && !$useResponsesAPI ->
                                         supportReasoningEffort=false;  retry
            (BAD_REQUEST_OTHER | NOT_FOUND) && $useResponsesAPI ->
                                         supportResponses=false;        retry  // ← 问题行为
            else -> throw toStatusRuntimeException(e)
        }
    }
}
```

即：**默认先调 `/v1/responses`；只要返回 HTTP 400（任意非特定文案）或 404，就把
`supportResponses` 永久置 false 并自动改走 `/v1/chat/completions`。**

### 错误分类过于宽松

`OpenAiUtilsKt.detectErrorType`（同包）：

| 异常 | 消息特征 | 分类 |
|---|---|---|
| `BadRequestException`(400) | 含 "must be verified to stream" | STREAMING_NOT_SUPPORTED |
| 400 | 含 role+developer/system | INVALID_MESSAGE_ROLE |
| 400 | 含 reasoning_effort+not supported+chat/completions | REASONING_EFFORT_NOT_SUPPORTED |
| 400 | 其余一切 | **BAD_REQUEST_OTHER** |
| `NotFoundException`(404) | 含 "model is only supported in v1/responses" 或 "not supported in the v1/chat/completions endpoint. Did you mean to use v1/completions?" | COMPLETION_NOT_SUPPORTED |
| 404 | 其余一切 | **NOT_FOUND** |
| 其他异常 | — | OTHER |

任何 OpenAI 兼容服务端只要 `/v1/responses` 返回普通 400/404 就会触发切换。

### 为什么"卡住且不恢复"

- `supportResponses` 只是 `OpenAiModelApi` 实例字段（`OpenAiModelApiProvider.computeState`
  创建实例），**不持久化、无 UI、无 registry 开关**。
- 一旦置 false，实例存活期间永远走 chat completions；若 chat completions 也失败，
  错误经 `toStatusRuntimeException` 抛出，聊天界面停在报错状态。
- 只有 provider 状态重建（改 provider 设置 / 刷新模型 / 重启 IDE）才重置为 true。

## 2. Model Providers 设置界面结构

注册：jar 内 `META-INF/sml-core.xml`

```xml
<projectConfigurable id="templates.modelproviders.configurable"
                     groupId="com.android.studio.ml.bot.mainConfigurable"
                     bundle="messages.SmlBundle"
                     key="sml.studiobot.settings.modelProviders.library.title"   <!-- = "Model Providers" -->
                     provider="com.android.studio.ml.modelproviders.ModelProviderConfigurableProvider"/>
```

界面为左右分栏（`ModelProviderSettingsComponent`），右侧详情面板按 provider 类型分发，
远程 provider 使用 `com.android.studio.ml.backends.settings.RemoteModelProviderInfoPanel`
（Kotlin UI DSL 构建，`setupUi(Panel)` 逐行添加）：

| 行 | bundle key | 控件 | 可见性 |
|---|---|---|---|
| 警告横幅 | remote.studiobot.settings.warning(+.link) | InlineBanner（可跳转文档） | `isProviderSettingVisible` |
| 警告补充说明 | ...warning.note | `Row.comment(...)` | `isProviderSettingVisible` |
| Description | ...description.title | JBTextField → descriptionProperty | `isProviderSettingVisible` |
| URL | ...url.title | JBTextField → urlProperty | `isProviderSettingVisible` |
| **URL Schema** | ...schema.title | **ComboBox → schemaProperty**（ApiSchema 枚举） | `isProviderSettingVisible` |
| API key | ...apikey.title | RevealablePasswordField → apiKeyProperty | `isProviderSettingVisible` |
| API key header | ...apikeyheader.title | JBTextField → apiKeyHeaderProperty | `isProviderSettingVisible AND isApiKeyHeaderVisible` |
| Available models | — | `modelInformationTablePanel.setupUi(builder, getCurrentProvider)` | — |

前两行（警告横幅与补充说明）带 `bottomGap(BottomGap.SMALL)`，其余行没有。
`isApiKeyHeaderVisible` 由 `modifyApiKeyHeaderVisibility(url)` 根据 URL 动态置值（`update()` 里也会刷）。

> 本项目补丁后的 "OpenAI API protocol" 行插在 **URL Schema 与 API key 之间**（见 patch-design）。

### 已有的"协议"下拉框（URL Schema）

- 枚举 `ProviderData.RemoteProviderData.ApiSchema`：`OPENAI`(id=`openai`) /
  `ANTHROPIC`(id=`anthropic`)，定义于 `ProviderData.java`（源文件 ProviderDetails.kt）。
  枚举成员带 `id / displayNameKey / descriptionKey / defaultUrl`（OPENAI 的默认 URL
  为 `https://api.openai.com/v1`）。两组文案在不同的 bundle 里（已逐个核实）：
  - 行标题：`messages/RemoteProviderBundle.properties`（`RemoteProviderBundle` 基名）
    —— `remote.studiobot.settings.schema.title=URL Schema:` 等；
  - 枚举显示名：`messages/SmlBundle.properties`
    —— `sml.studiobot.settings.modelProviders.remote.schema.openai=OpenAI-compatible`、
    `...remote.schema.anthropic=Anthropic-compatible`。
- 持久化：字段 `RemoteProviderData.schema` 标注 `@OptionTag(converter=ApiSchemaConverter.class)`。
- 数据流：`schemaProperty.afterChange { remoteProviderData.setSchema(it) }`（`_init_$lambda$4`），
  `update()` 反向读取（`getSchema()` 为 null 时回退 OPENAI 再 `schemaProperty.set`）。
  **UI 属性变更直接写回当前 ProviderDetails，无草稿副本。**
- API key 已改为 `ApiKeyHolder` 管理（`getApiKey()` 标 `@Deprecated("Use apiKeyHolder instead")`）：
  `RemoteProviderData.copy()/isModified()/apply()` 全部代理到 `apiKeyHolder`，
  面板通过 `fetchKeyCoroutineScope`/`fetchKeyJob` 异步取真实密钥。
  该 getter 标 `@Transient`，所以密钥不进 provider XML。
- 后端分发：`RemoteModelProviderSettings`（`backends/settings`）按 schema 选择 OpenAI 客户端
  （→ 上述 OpenAiModelApi）或 Anthropic 客户端；schema 为 null 时会先按 URL 猜
  （含 anthropic 域名 → ANTHROPIC，否则 OPENAI）。

### 结论

URL Schema 下拉框只选 **协议族**（OpenAI 兼容 vs Anthropic 兼容）。
**Responses API vs Chat Completions 没有任何 UI 开关** —— 完全由
`OpenAiModelApi.supportResponses` 运行时自动切换。这正是本项目要打补丁的原因。

## 3. 持久化机制

- `ProviderSettingsConverter`（`modelproviders/data`，xmlb `Converter<ProviderSettings>`）：
  `toString()` = `XmlSerializer.serialize(value)` → JDOM 字符串；`fromString()` 反之。
- 即 `RemoteProviderData` 的持久化完全依赖 IntelliJ xmlb 反射序列化：
  字段上的 `@OptionTag(converter=...)` 生效；`@Transient` 排除（如 apiKeyHolder 经 getter 标注）。
- 序列化样例：

```xml
<RemoteProviderData>
  <option name="apiKey" value="" />
  <option name="apiKeyHeader" value="Authorization" />
  <option name="url" value="https://api.openai.com/v1" />
  <option name="schema" value="openai" />
</RemoteProviderData>
```

- 反序列化沿 Kotlin 构造器链（主构造 + `DefaultConstructorMarker` 合成构造器，mask 按位选参），
  补丁在主构造 return 前给 `openAiApiType` 赋默认 AUTO，因此**旧配置文件无该 option 也兼容**。

### 3.1 isModified / apply / 保存链路

- 存储：`ModelDataStateManagerImpl`（`modelproviders/data`，application service）
  `@State(name="ModelDataProviders", storages=@Storage("ai.providers.xml"))`，
  继承 `SimplePersistentStateComponent<ProviderDetailsState>`。
- `ModelProviderConfigurable.isModified()`：
  `Intrinsics.areEqual(getFullState(), ModelDataStateManager.loadState())`
  —— 列表逐元素 `ProviderDetails.equals`；另有 `modelInfoPanel.isModified()` 与
  `any { it.isModified() }`（后者是 apiKeyHolder 的修改标记，与字段无关）。
- `ModelProviderConfigurable.apply()`：校验后 `ModelDataStateManager.saveState(getFullState())`。
- `ModelDataStateManagerImpl.saveState`（本版本实际顺序，已核对反编译）：
  1. `providerDetails.map { it.copy() }` → providersCopy（我们的 `copy()` 补丁保留 openAiApiType）；
  2. 对每个 `isApplicable()` 的 `ModelProviderSettingsConfiguration` 调 `saveSettings(providersCopy)`；
  3. 对每个传入的 `ProviderDetails.apply()`（远程项 → `RemoteProviderData.apply()` → `apiKeyHolder.apply()`）；
  4. `getState().setProviderDetails(providersCopy)` → `ApplicationManager.getApplication().saveSettings()`；
  5. `StudioBotSettingsNotificationService.notifySettingsUpdated()` + `MetricsReporter` 上报。
- **平台侧关键行为**（均在 `intellij.platform.ide.impl.jar`，本版本已核对字节码）：
  - `options.newEditor.SettingsDialog.doOKAction()` → `applyAndClose(true)` → `editor.apply()`
  - `options.newEditor.ConfigurableEditor.apply()`：
    `setError(apply(myApplyAction.isEnabled() ? configurable : null))` ——
    **Apply 动作未启用（isModified==false）时点 OK 会完全跳过子 configurable 的 apply**。
  - `options.CompositeConfigurable.apply()`（`ModelsConfigurable` → `TabbedConfigurable` → 它）
    无条件逐个转发子 `UnnamedConfigurable.apply()`。
- 推论：任何新增字段要能被保存，必须先让 `equals`（从而 isModified）感知它。

## 4. 关键类清单（补丁涉及）

### 4.1 provider 设置与协议选择

| 类 | 作用 |
|---|---|
| `modelproviders/data/ProviderData$RemoteProviderData` | 远程 provider 数据模型（url/apiKey/apiKeyHeader/schema，另有 `apiKeyHolder`）|
| `...$ApiSchema`（+`ApiSchemaConverter`）| 协议族枚举（OPENAI/ANTHROPIC）与 xmlb 转换器 |
| `backends/settings/RemoteModelProviderInfoPanel` | 远程 provider 设置面板（UI DSL：setupUi/update/schema afterChange）|
| `backends/settings/RemoteModelProviderSettings` | provider 业务逻辑（fetchModels/apply、按 schema 选 ModelFetcher）|
| `modelproviders/data/ProviderSettingsConverter` | ProviderSettings ↔ XML |
| `modelproviders/data/ApiKeyHolder` | API key 托管（`@Transient`，不落 XML）|
| `modelproviders/ModelProviderConfigurable` + `data/ModelDataStateManagerImpl` | isModified/apply/saveState 与 `ai.providers.xml` 落盘 |
| `backends/openai/OpenAiModelApi` / `OpenAiModelApiProvider` | Responses/Completion 分派与自动回退（fallback 所在）|
| `backends/openai/OpenAiUtilsKt.detectErrorType` / `ErrorType` | 错误分类（决定回退触发条件）|

### 4.2 请求构造（openai SDK 在 `plugins/gemini/lib/openai-java-core-4.32.0.jar`）

| 类 | 作用 |
|---|---|
| `backends/openai/OpenAiResponsesApiV2` | Responses 的 `toInputItem`/`createParams`（占位思考、`reasoning.effort`）|
| `backends/openai/OpenAiCompletionApiV2` | Chat Completions 的 `toMessageParam`/`createParams`（reasoning_content、system role、`reasoning_effort`）|
| `datamodel/models/ModelChatMessage` | thought / thoughtSignature / toolCalls 的来源 |
| `com.openai.models.ReasoningEffort`、`ChatCompletionCreateParams`、`ResponseCreateParams` | 实际发往供应商的请求参数 |

### 4.3 思考强度 UI 与会话持久化

| 类 | 作用 |
|---|---|
| `ui/querybox/QueryBoxKt.ActionsRow` | 发送区动作行（下拉插入点）|
| `ui/trajectory/ModelPickerKt` + `ui/ModelPickerUiState`/`ModelPickerItemUiState`/`ModelPickerLabel`/`ModelPickerEvent` + `agentsdk/models/ModelId$Custom` | 复用的同款下拉渲染 |
| `agentsdk/conversations/PersistedMetadata`（+`$$serializer`）| 会话元数据 `metadata.json`（kotlinx.serialization，16 个原生元素）|
| `agentsdk/conversations/DefaultConversation.prepareMetadata` | 保存路径 |
| `controller/ActiveConversationOrchestrator.selectConversation` + `controller/ConversationSelection` | 会话切换/新建 |
| `controller/TrajectoryTimelineController.handleEvent` + `ui/TrajectoryEvent$ConversationPresented` | 会话呈现（重启后首个会话的同步点）|
| `datamodel/models/GenerationConfig`(`defaultForAgent`) / `ThinkingConfig` / `ThinkingLevel` | 原生思考配置来源（决定原生 effort）|
