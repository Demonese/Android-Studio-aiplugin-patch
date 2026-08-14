# Android Studio Gemini 插件逆向分析

> 分析对象：Android Studio (build 261.26222.65) Windows 发行包中的
> `plugins/gemini/lib/aiplugin.jar`（插件 id `com.google.tools.ij.aiplugin`，Google 闭源，
> 不在 AOSP `studio-master-dev` 公开源码清单内，只能反编译分析）。
> 反编译工具：CFR 0.152。反编译输出可通过 `scripts/20_decompile.sh` 重现。

## 1. OpenAI 兼容 API 调用与自动 fallback（问题根源）

### V2 路径（当前第三方远程 provider 使用）

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
            STREAMING_NOT_SUPPORTED        -> supportStreaming=false;       retry
            REASONING_EFFORT_NOT_SUPPORTED / BAD_REQUEST_OTHER(带reasoning) ->
                                             supportReasoningEffort=false;  retry
            BAD_REQUEST_OTHER / NOT_FOUND  -> supportResponses=false;       retry  // ← 问题行为
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
| `NotFoundException`(404) | 含 "model is only supported in v1/responses" 等 | COMPLETION_NOT_SUPPORTED |
| 404 | 其余一切 | **NOT_FOUND** |

任何 OpenAI 兼容服务端只要 `/v1/responses` 返回普通 400/404 就会触发切换。

### 为什么"卡住且不恢复"

- `supportResponses` 只是 `OpenAiModelApi` 实例字段（`OpenAiModelApiProvider.computeState`
  创建实例），**不持久化、无 UI、无 registry 开关**。
- 一旦置 false，实例存活期间永远走 chat completions；若 chat completions 也失败，
  错误经 `toStatusRuntimeException` 抛出，聊天界面停在报错状态。
- 只有 provider 状态重建（改 provider 设置 / 刷新模型 / 重启 IDE）才重置为 true。

### V1 路径（旧实现，反向 fallback）

`OpenAiChatImpl.generateContent`：默认走 chat completions，遇到
`COMPLETION_NOT_SUPPORTED`（404 特定文案）时把 modelId 加入 `responsesApiModels`
集合改走 Responses API。机制相同，方向相反。

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

| 行 | bundle key | 控件 |
|---|---|---|
| 警告横幅 | remote.studiobot.settings.warning | InlineBanner |
| Description | ...description.title | JBTextField → descriptionProperty |
| URL | ...url.title | JBTextField → urlProperty |
| **URL Schema** | ...schema.title | **ComboBox → schemaProperty**（ApiSchema 枚举） |
| API key | ...apikey.title | RevealablePasswordField → apiKeyProperty |
| API key header | ...apikeyheader.title | 条件可见 |
| Available models | — | ModelInformationTablePanel |

### 已有的"协议"下拉框（URL Schema）

- 枚举 `ProviderData.RemoteProviderData.ApiSchema`：`OPENAI`("OpenAI-compatible") /
  `ANTHROPIC`("Anthropic-compatible")，定义于 `ProviderData.java`（源文件 ProviderDetails.kt）。
- 持久化：字段 `RemoteProviderData.schema` 标注 `@OptionTag(converter=ApiSchemaConverter.class)`。
- 数据流：`schemaProperty.afterChange { remoteProviderData.setSchema(it) }`（`_init_$lambda$4`），
  `update()` 反向读取。**UI 属性变更直接写回当前 ProviderDetails，无草稿副本。**
- 后端分发：`RemoteModelProviderSettings` 按 schema 选择 OpenAI 客户端（→ 上述
  OpenAiModelApi）或 Anthropic 客户端。

### 结论

URL Schema 下拉框只选 **协议族**（OpenAI 兼容 vs Anthropic 兼容）。
**Responses API vs Chat Completions 没有任何 UI 开关** —— 完全由
`OpenAiModelApi.supportResponses` 运行时自动切换。这正是本项目要打补丁的原因。

## 3. 持久化机制

- `ProviderSettingsConverter`（xmlb `Converter<ProviderSettings>`）：
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

- 反序列化经无参构造 `RemoteProviderData()`（委托到 4 参主构造，mask=15），
  因此**新字段只要在主构造中给默认值即可向后兼容旧配置文件**。

### 3.1 isModified / apply / 保存链路（v2 逆向补充）

- 存储：`ModelDataStateManagerImpl`（application service）
  `@State(name="ModelDataProviders", storages=@Storage("ai.providers.xml"))`，
  继承 `SimplePersistentStateComponent<ProviderDetailsState>`。
- `ModelProviderConfigurable.isModified()`：
  `Intrinsics.areEqual(getFullState(), ModelDataStateManager.loadState())`
  —— 列表逐元素 `ProviderDetails.equals`；另有 `modelInfoPanel.isModified()` 与
  `any { it.isModified() }`（后者是 apiKeyHolder 的修改标记，与字段无关）。
- `ModelProviderConfigurable.apply()`：校验后 `saveState(getFullState())`。
- `saveState`：`map { it.copy() }` → `state.setProviderDetails(copies)` →
  `ApplicationManager.saveSettings()` → 通知服务 + 指标上报。
- **平台侧关键行为**（`intellij.platform.ide.impl.jar`）：
  - `SettingsDialog.doOKAction()` → `applyAndClose()` → `editor.apply()`
  - `ConfigurableEditor.apply()`：
    `apply(myApplyAction.isEnabled() ? configurable : null)` ——
    **Apply 按钮未启用（isModified==false）时点 OK 会完全跳过 apply**。
  - `CompositeConfigurable.apply()`（ModelsConfigurable 的父类）本身无条件转发子 apply。
- 推论：任何新增字段要能被保存，必须先让 `equals`（从而 isModified）感知它。

## 4. 关键类清单（补丁涉及）

| 类 | 作用 |
|---|---|
| `ProviderData$RemoteProviderData` | 远程 provider 数据模型（url/apiKey/apiKeyHeader/schema） |
| `ProviderData$RemoteProviderData$ApiSchema(+Converter)` | 协议族枚举与 xmlb 转换器 |
| `RemoteModelProviderInfoPanel` | 远程 provider 设置面板（UI DSL） |
| `RemoteModelProviderSettings` | provider 业务逻辑（fetchModels/apply 等） |
| `ProviderSettingsConverter` | ProviderSettings ↔ XML |
| `OpenAiModelApi` / `OpenAiModelApiProvider` | OpenAI 兼容后端（fallback 所在） |
| `OpenAiUtilsKt.detectErrorType` / `ErrorType` | 错误分类 |
