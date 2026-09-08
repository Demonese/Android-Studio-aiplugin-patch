# 补丁设计（API 协议选择 + 第三方供应商兼容 + 思考强度）

## 目标

在 Model Providers → 远程 provider 设置面板中，当 **URL Schema = OpenAI-compatible** 时，
显示第二个下拉框 **"OpenAI API protocol:"**，可选：

| 枚举值 | id（持久化值） | 显示文本 |
|---|---|---|
| `AUTO`（默认） | `auto` | Auto (Responses API, fallback to Chat Completions) |
| `CHAT_COMPLETION` | `openai-chat-completion` | OpenAI Chat Completions API |
| `RESPONSE` | `openai-response` | OpenAI Responses API |

选择结果持久化到 `RemoteProviderData`（xmlb），重启 IDE / 重开设置后保留，
并**真正控制请求所用的 API 协议**：

| 选项 | 请求协议 | Responses 失败时自动回退到 Chat Completions |
|---|---|---|
| AUTO | 先 Responses（原生行为） | 允许（原生行为） |
| CHAT_COMPLETION | 恒 Chat Completions | 不适用（从不请求 Responses） |
| RESPONSE | 恒 Responses | **禁止**（错误直接抛给用户） |

> 为什么默认 AUTO：保持现有行为不变，存量 provider 配置行为不突变。
> 固定协议时禁用回退是核心诉求：某些供应商上 Responses 失败后自动回退到
> Chat Completions 会导致下一轮请求继续失败并卡死在错误协议上。

## 修改方式总览

没有 Kotlin 源码，全部通过 **ASM 字节码手术 + 新增 Java 类** 实现：

```
新增类（javac 编译进 jar）
├── OpenAiApiType            枚举（id/displayName/fromId）
├── OpenAiApiTypeConverter   xmlb Converter（模仿 ApiSchemaConverter）
├── OpenAiApiTypeUi          UI 辅助：行创建/可见性联动/回写（状态 WeakHashMap 按面板实例保存）
├── OpenAiApiTypeSupport     后端决策：resolveUseResponses / allowResponsesFallback
├── OpenAiResponsesSupport   Responses 请求构造：缺失思考时补占位 reasoning item
├── OpenAiCompletionSupport  Chat Completions 请求构造：回传 reasoning_content
├── ThinkingEffortPicker     发送区思考强度下拉（复用 ModelPicker 渲染）
└── ThinkingEffortStore      思考强度运行时存储 + 持久化钩子 + ReasoningEffort 映射

ASM 补丁（PatchTool.java）
├── ProviderData$RemoteProviderData
│   ├── + 字段 openAiApiType @OptionTag(converter=OpenAiApiTypeConverter)
│   ├── + getOpenAiApiType()/setOpenAiApiType()
│   ├── <init>(4参)：return 前插入 this.openAiApiType = AUTO
│   ├── copy()：return 前把字段拷给新实例（局部变量1）
│   ├── copy(4参)：ARETURN 前 dup 新实例并拷入字段
│   ├── equals(Object)：最终 return-true 路径前插入 openAiApiType 引用比较
│   └── hashCode()：末尾插入 result = result*31 + openAiApiType.hashCode()
├── RemoteModelProviderInfoPanel
│   ├── setupUi：在 LDC "remote.studiobot.settings.apikey.title" 之前
│   │            插入 OpenAiApiTypeUi.addRow(this, builder)   // 行位于 URL Schema 与 API key 之间
│   ├── update()：schemaProperty.set(...) 之后插入 OpenAiApiTypeUi.load(this, remoteProviderData)
│   └── schema afterChange lambda（按“调用 setSchema”定位，不依赖 Kotlin 编号）：
│                setSchema 之后插入 OpenAiApiTypeUi.syncVisibility(panel, schema)
├── OpenAiModelApi
│   ├── + 字段 openAiApiType（默认 AUTO，<init> 末尾初始化）+ getter/setter
│   └── streamGenerateContent：supportResponses.get() 替换为
│                OpenAiApiTypeSupport.resolveUseResponses(this)
├── OpenAiModelApi$streamGenerateContent$1（catch 处理器）
│   └── invokeSuspend：$useResponsesAPI 的 IFEQ 门之后追加
│                allowResponsesFallback(this$0) 检查，IFEQ 复用同一 throw 目标
├── OpenAiModelApiProvider
│   └── computeState：new OpenAiModelApi(...) 后 dup + 从局部变量9
│                 （providerSettings）getOpenAiApiType → setOpenAiApiType
├── OpenAiResponsesApiV2
│   ├── toInputItem：toolCalls 循环汇合点插入 ensureReasoning（补占位思考）
│   └── createParams：末尾覆写 paramsBuilder.reasoning(effort=会话档位)
├── OpenAiCompletionApiV2
│   ├── toMessageParam：return ofAssistant(...) 前插入 attachReasoningContent
│   └── createParams：addDeveloperMessage 改写为 addSystemMessage；
│                toReasoningEffort 调用替换为 ThinkingEffortStore.toOpenAiReasoningEffort()；
│                reasoningEffort(NONE) 回退分支门控改为恒跳过
├── QueryBoxKt.ActionsRow
│   └── ModelPicker + 8dp Spacer 之后插入 ThinkingEffortPicker.render + 8dp Spacer
├── PersistedMetadata
│   ├── + 字段 reasoningEffort（String，可空）+ getter/setter
│   └── write$Self：末尾调 ThinkingEffortStore.encodeElement（元素 16）
├── PersistedMetadata$$serializer
│   └── descriptor 容量 +1 并 addElement("reasoningEffort")；childSerializers +1；
│        deserialize 顺序路径与 tableswitch 各插元素 16 读取，构造后回填
├── DefaultConversation.prepareMetadata：构造后 dup + applyTo 回填
├── ActiveConversationOrchestrator.selectConversation：入口通知 onConversationSelection
└── TrajectoryTimelineController.handleEvent：ConversationPresented 分支的
     clearStatus 前插 dup + onConversationPresented（锚定其实参来自 getConversationId）
```

### 设计要点

1. **构建顺序**：`OpenAiApiTypeUi` 调用 `RemoteProviderData.get/setOpenAiApiType`，
   `OpenAiApiTypeSupport` 调用 `OpenAiModelApi.getOpenAiApiType`，
   这些方法均由 ASM 阶段添加，因此必须先补丁数据类与 API 类、再编译新源码
   （见 30_build_patch.sh 的阶段顺序：data → api → metadata → javac →
   panel → querybox → metaser → convmeta → orch → timeline → 组装）。
2. **面板私有状态用反射读**：回写设置需要面板的 `getCurrentProvider`（private
   `Function0<ProviderDetails>`）。反射优先读 private 字段（字段名是稳定 API），
   取不到时退回 Kotlin synthetic 访问器 `access$getGetCurrentProvider$p`；
   两者都失败则放弃回写，不把异常抛进 Swing 事件链。
3. **可见性**：新行 `visibleIf(isProviderSettingVisible AND apiTypeVisible)`，
   `apiTypeVisible = (schema == OPENAI)`；schema 变更经既有 afterChange 链实时联动。
4. **状态管理**：`OpenAiApiTypeUi.STATES` 为 `WeakHashMap<面板, State>`，
   State 持有 `AtomicProperty<OpenAiApiType>`（绑定 combo）与 `AtomicBooleanProperty`（可见性）。
   `load()` 可能先于 `setupUi()` 发生（update 先调用），State 惰性创建解决时序问题。
5. **帧与栈**：绝大多数插入为无分支直线代码；新增分支处（equals 的 IF_ACMPEQ、
   catch 处理器的 IFEQ）均复用已有跳转目标或补 F_SAME 帧；
   用 `ClassWriter.COMPUTE_MAXS` 重算最大栈（`PersistedMetadata$$serializer.deserialize`
   因新增 switch 分支目标改用 `COMPUTE_FRAMES`，见持久化一节）。CheckClassAdapter 校验通过。
6. **向后兼容**：旧配置 XML 无 `openAiApiType` option → 反序列化走构造器默认值 AUTO；
   旧对话 metadata.json 无 `reasoningEffort` → 解码为 null → 默认 medium。
7. **工具链**：插件 class 文件为 major 69（Java 25），ASM 9.10.1 才能读改写；
   构建/验证在 JDK 25 上进行；新增源码编译 `--release 21`。

## Apply 按钮不亮 / 设置不持久化（equals/hashCode 补丁）

**现象**：只切换 "OpenAI API protocol" 时 Apply 按钮不启用；点 OK 退出重开后设置丢失。

**根因链**（单一根因）：
1. `RemoteProviderData` 是 Kotlin data class，自动生成的 `equals()`/`hashCode()` 只覆盖
   构造器属性（url/apiKey/apiKeyHeader/schema），**不含** ASM 后加的 `openAiApiType`。
2. `ModelProviderConfigurable.isModified()` 用 `Intrinsics.areEqual(uiState, loadState())`
   （列表逐元素 equals）判断修改 → 只改 openAiApiType 时恒为 false。
3. Apply 按钮由 isModified 驱动 → 不亮。
4. 平台 `options.newEditor.ConfigurableEditor.apply()`：
   `apply(myApplyAction.isEnabled() ? configurable : null)` —— Apply 未启用时点 OK
   **直接跳过 apply** → `ModelDataStateManagerImpl.saveState` 根本不执行 → 不写 ai.providers.xml。

**修复**：ASM 补丁 `equals`/`hashCode` 纳入 openAiApiType：
- equals：原 return-true 路径为 `[Label][Frame][ICONST_1][IRETURN]`，Label 是 schema 比较
  `if_acmpeq` 的目标。新比较插在 Frame 之后、ICONST_1 之前 —— 原跳转先经过新检查；
  新分支目标补 `F_SAME` 帧（状态与原帧相同）。枚举用引用比较（IF_ACMPEQ），字段恒非 null。
- hashCode：末尾 `ILOAD 1; IRETURN` 前插入 `result = result*31 + openAiApiType.hashCode()`
  （result 存于局部变量1，无新分支、无帧问题）。
- COMPUTE_MAXS 下新增分支必须自带目标帧：equals 的新分支目标补 `F_SAME` 帧（状态与原帧相同）。

**验证**：SerializeTest 新增断言 —— 仅 openAiApiType 不同的两实例 `!equals`；
copy/序列化往返后 equals 成立。CheckClassAdapter 通过。

**修复后行为链**：改下拉框 → live 对象字段变更 → isModified 中列表 equals 为 false →
Apply 亮起 → OK/Apply 触发 `saveState(getFullState())` → `copy()`（已补丁保留字段）→
xmlb 序列化含 `openAiApiType` option → 写入 `ai.providers.xml` → 重启后 converter 还原。

## 持久化格式（补丁后）

```xml
<RemoteProviderData>
  <option name="apiKey" value="" />
  <option name="apiKeyHeader" value="Authorization" />
  <option name="url" value="https://api.openai.com/v1" />
  <option name="schema" value="openai" />
  <option name="openAiApiType" value="openai-chat-completion" />
</RemoteProviderData>
```

## 验证（scripts/40_verify.sh）

1. `CheckClassAdapter`：13 个被补丁类的字节码合法性（含类型分析）。
2. `SerializeTest`：真实平台 jar 上运行 `XmlSerializer` 往返：
   序列化出现 `openAiApiType` option；反序列化还原；构造默认 AUTO；
   `copy()` 与 `copy(4参)` 保留字段；equals/hashCode 感知字段。
3. `ApiProtocolTest`：构造真实 `OpenAiModelApi`，验证三种协议模式的分派与回退决策，
   以及 `streamGenerateContent` 在三种模式下均正常返回 Flow。
4. `ResponsesReasoningTest`：真实 `createParams` 验证缺失思考轮次补占位 reasoning、
   已有思考/签名不重复注入、其他模型消息不注入。
5. `CompletionReasoningTest`：真实 `createParams` 验证 thought 非空时 assistant 消息
   附加 `reasoning_content`、tool_calls 保留、thought 为 null/空串时不附加；
   并验证系统消息恒为 `system` role。
6. `ThinkingEffortPickerTest`：下拉状态与事件。
7. `ReasoningEffortPersistTest`：kotlinx JSON 往返、旧格式（无字段）解码为 null、
   null 不写出、Store 加载/选择/保存/新建/旧对话默认，
   并以真实补丁字节码端到端断言反序列化即读到会话档位（含旧对话默认 medium）
   及完整生命周期回路：切换 → 保存回填 → 落盘 JSON → 模拟重启读取 → 请求档位映射。
8. `ReasoningEffortApiTest`：7 档 × 2 协议逐一断言 `reasoning_effort` /
   `reasoning.effort` 等于档位；`omitReasoningEffort=true` 时 Completion 不带该参数。
9. `UiLoadTest`：设置界面与发送区补丁面、布局兼容性 —— 新增类可加载、枚举 id/转换器回退正确、
   `openAiApiType` 字段带 `@OptionTag(converter=...)`、面板反射目标存在，
   并断言三处注入点的相对位置（协议下拉行必须在 "URL Schema" 与 "API key" 两行之间，
   `load` 紧跟 `schemaProperty.set`，`syncVisibility` 紧跟 `setSchema`）；
   另断言发送区 `ActionsRow` 布局：思考强度下拉（`ThinkingEffortPicker.render`）位于
   ModelPicker 与 Submit（AnimatedContent）之间、两侧各 8dp 间隙（宽度常量与 Compose
   参数掩码同原生间隙），且 render 内部复用 `ModelPickerKt.ModelPicker` 渲染。

## 安装与测试

1. 备份 `Android Studio/plugins/gemini/lib/aiplugin.jar`。
2. 将 `dist/aiplugin-patched.jar` 复制为上述路径的 `aiplugin.jar`。
3. 启动 IDE → Settings → Tools → AI Assistant → Model Providers → 选择/新建远程 provider。
4. 检查：OpenAI-compatible 时出现新下拉框；Anthropic-compatible 时隐藏；
   Apply → 重开设置，选择保留。
5. 协议行为：CHAT_COMPLETION → 只发 `/chat/completions` 请求；
   RESPONSE → 只发 `/responses` 请求且失败不回退；AUTO → 原生行为。

## 后端协议控制：实现细节

### 原生机制（逆向结论）

- `OpenAiModelApi` 持有三个 `AtomicBoolean`：`supportStreaming` / `supportResponses` /
  `supportReasoningEffort`，初始均为 true。
- `streamGenerateContent` 按 `useResponsesAPI = supportResponses.get()` 选择
  Responses（流式/非流式）或 Chat Completions 流式路径。
- `FlowKt.catch` 处理器（内部类 `$streamGenerateContent$1.invokeSuspend`）：
  错误类型为 `BAD_REQUEST_OTHER`/`NOT_FOUND` 且 `$useResponsesAPI` 为真时，
  `supportResponses.getAndSet(false)` 并递归重试 —— 即自动回退，
  且 AtomicBoolean 一旦置 false 永不恢复（该 ModelApi 实例生命周期内卡在 Completion）。

### 补丁方案

1. **协议分派**：`streamGenerateContent` 中 `supportResponses.get()` 替换为
   `OpenAiApiTypeSupport.resolveUseResponses(this)`：
   CHAT_COMPLETION → false；RESPONSE → true；AUTO → `supportResponses.get()`（原逻辑）。
   仅替换两条指令为一条 invokestatic，无新分支、无帧问题。
2. **回退门禁**：catch 处理器原条件链
   `(BAD_REQUEST_OTHER || NOT_FOUND) && $useResponsesAPI` 之后追加
   `&& allowResponsesFallback(this$0)`（即 openAiApiType == AUTO）。
   插入点在原 `IFEQ` 门之后，新 `IFEQ` 复用同一跳转目标（throw 路径，已有栈帧）。
   固定协议时错误经 `toStatusRuntimeException` 直接抛出，不再静默换协议。
3. **设置注入**：`OpenAiModelApiProvider.computeState` 中
   `new OpenAiModelApi(...)` 之后从 `providerSettings`（RemoteProviderData，局部变量9）
   读取 `openAiApiType` 写入。`LocalModelApiProvider` 不补丁（本地模型无此设置，
   字段保持默认 AUTO，行为不变）。
4. **设置变更生效时机**：`OpenAiModelApiProvider` 构造时把
   `StudioBotSettingsNotificationService.notificationsFlow` 接成
   `map { computeState() }.stateIn(...)`，因此 `saveState` 尾部的
   `notifySettingsUpdated()` 会驱动重算，新的 `OpenAiModelApi` 实例携带新协议值；
   无需重启 IDE。

## DeepSeek 思考模式 "reasoning_text must be passed back" 400 错误（Responses 占位思考）

**现象**（DeepSeek V4 Flash/Pro 实测）：`Model query failed: 400: The reasoning_text in
the thinking mode must be passed back to the API.`

**根因**：DeepSeek 思考模式要求多轮对话中每个 assistant 轮回传 reasoning_text。
插件在 `OpenAiResponsesApiV2.toInputItem(ModelChatMessage,...)` 中仅当历史消息带
`thought` 或 `thoughtSignature` 时才构造 reasoning item；而 DeepSeek **有时不返回
思考块就直接返回工具调用块**，该轮 thought 为空 → 下一轮请求缺少 reasoning_text → 400。

**修复**：新增 `OpenAiResponsesSupport.ensureReasoning(items, message, modelId, indexer)`：
当消息属于当前模型（modelId == modelName）且 thought 与 thoughtSignature 均为空时，
补一个占位 reasoning item（id 递增、status=COMPLETED、summary 空、
content text = `继续调用工具……`）。

**注入点**（ASM，`patchResponsesApi`）：`toInputItem` 中 toolCalls 循环起点
（`getToolCalls` 前的 `aload_1`）。原生代码为
`if (!((thought 与 signature 皆空) || 模型不匹配)) { 构造并 add reasoning item }`，
因此“加入该项”与“整块跳过”两条路径在 toolCalls 循环前合流，合流处已有 `F_SAME` 帧
（部分局部变量归并为 Object/Null）。helper 插在帧之后：两条路径全部流经它，
由 helper 内部条件判断是否注入——**不改任何分支、不新增栈帧**。

**互斥性**：原逻辑仅在（thought 或 signature 非空）且模型匹配时加 reasoning item；
helper 仅在两者皆空且模型匹配时注入 → 不会重复注入。

**验证**：`ResponsesReasoningTest` 用真实 `createParams` 构造含 4 类历史消息的请求：
无思考+工具调用轮次 → 补 1 个占位思考；有思考轮次 → 保留原文本；
仅有签名轮次 → 保留签名项不注入；其他模型消息 → 不注入。CheckClassAdapter 通过。

## Chat Completions API 不回传思考内容（reasoning_content）

**现象**：DeepSeek/Qwen 思考模式走 Chat Completions 协议多轮对话（含工具调用）时
报 `400: The reasoning_content in the thinking mode must be passed back to the API.`

**根因**：接收方向没问题——`OpenAiCompletionApiV2.thinkingDelta` 已按
`reasoning_content` → `reasoning` → `thinking` → `reasoning_text` 顺序从流式 delta 的
附加属性读取思考内容，并经 `ModelResponse.deltaThinking` → 轨迹累积 → 写入
`ModelChatMessage.thought`。但**请求方向**的 `toMessageParam(ModelChatMessage)`
只构造 `content` 与 `tool_calls`，完全丢弃 `thought`，下一轮请求缺少 reasoning_content。

**修复**：新增 `OpenAiCompletionSupport.attachReasoningContent(builder, message)`：
`thought` 非空时经 `builder.putAdditionalProperty("reasoning_content", JsonValue.from(thought))`
附加到 assistant 消息。SDK 序列化时附加字段随消息发出；OpenAI 官方等不支持该字段的
服务端会忽略未知字段，无副作用。

**注入点**（ASM，`patchCompletionApi`）：`toMessageParam(ModelChatMessage)` 末尾
`return ofAssistant(builder.build())`——`getstatic Companion` 之后、`aload_2`（builder）
之前插入 `aload_2; aload_1; invokestatic attachReasoningContent`。插入点非跳转目标、
方法无 StackMapTable 显式帧，直线调用不改分支、不新增帧。局部变量：0=this、
1=ModelChatMessage、2=builder。

**与 Responses 占位思考补丁的区别**：前者是"缺失时补占位"（Responses 的 reasoning item 结构要求每轮必带）；后者是"已有时回传"（Chat Completions 仅在 thought 非空时附加，不伪造内容）。

**验证**：`CompletionReasoningTest` 用真实 `createParams` 构造含 3 类 assistant 历史
消息的请求：thought 非空 → `reasoning_content` 等于原思考文本且 tool_calls 保留；
thought 为 null/空串 → 不附加。CheckClassAdapter 通过。

## Chat Completions 系统消息 developer role 导致第三方供应商 400

**现象**：部分 OpenAI 兼容供应商对 `developer` role 返回 400（只认 `system`）。

**现状确认（插件无对应设置项）**：
- provider 数据模型与 UI 中没有任何 developer/system role 相关开关；
- `createParams(useSystemMessage=...)` 参数存在，但唯一调用方 `OpenAiModelApi`
  硬编码传 false → 恒走 `addDeveloperMessage`；
- JVM 属性 `studio.ml.openai.chat.sendAsSystemMessage=true` 可强制 system，
  但需改 IDE vmoptions，普通用户不可见。

**修复**（ASM，`patchCompletionApi`）：把 `createParams` 中
`ChatCompletionCreateParams$Builder.addDeveloperMessage` 调用原地改写为
`addSystemMessage`（描述符相同，三元两个分支殊途同归）。OpenAI 官方仍兼容
`system` role（`developer` 仅为 2024-12 起的改名），故恒 system 对官方与第三方均安全。

**验证**：`CompletionReasoningTest` 断言三态下首条消息均 `isSystem() && !isDeveloper()`：
`useSystemMessage=false`（唯一调用方硬编码值，原版此处为 developer，补丁核心变化）、
`useSystemMessage=true`（原版本就 system，回归保护）、
JVM 属性 `sendAsSystemMessage=true` + `useSystemMessage=false`
（原版隐藏开关，测试后还原属性）。CheckClassAdapter 通过。

## 思考强度：UI 下拉 + reasoningEffort 按会话持久化

**目标**：Agent 发送区模型选择与 Submit 之间加思考强度下拉（none/minimal/low/
medium/high/xhigh/max），选择按会话持久化到对话目录 `metadata.json` 的
`reasoningEffort` 字段；旧对话缺省 medium（请求参数接入见下节）。

**UI（复用模型选择器样式）**
- 新增 `ThinkingEffortPicker`（`com.google.studiobot.ui.querybox`）：复用
  `ModelPickerKt.ModelPicker` 渲染，构造合成 `ModelPickerUiState`（7 档），
  Snapshot `MutableState` 持有状态，`refreshUi` 触发重组。
- ASM 补丁 `QueryBoxKt.ActionsRow`：在 `ModelPicker` 调用与其后 8dp Spacer 之后
  插入 `ThinkingEffortPicker.render(composer)` + 8dp Spacer，两侧间距一致。

**持久化（kotlinx.serialization 加字段）**
- `PersistedMetadata`：加私有字段 `reasoningEffort` + getter/setter；
  `write$Self` 末尾直线调 `ThinkingEffortStore.encodeElement`（元素 16，
  nullable String；值为 null 且 descriptor 不要求编码默认值时不写出）。
- `PersistedMetadata$$serializer`（PersistedMetadata 本身有 16 个原生字段）
  - `<clinit>` descriptor 容量 16+1，追加 `addElement("reasoningEffort", true)`；
  - `childSerializers()` 数组 16+1，新下标补
    `getNullable(StringSerializer.INSTANCE)`（数组按元素下标直接索引，
    新下标处原本为 null 有 NPE 隐患，补全）；
  - `deserialize`：开头初始化局部 27（CompositeDecoder 在局部 25、
    descriptor 在局部 2、seen0 在局部 5）；顺序路径与 tableswitch（case 16）
    各插元素 16 读取（seen0 置位 65536）；构造后 `setReasoningEffort` 回填
    并调 `ThinkingEffortStore.onLoaded(id, effort)`。
  - 因新增 switch 分支目标，用 COMPUTE_FRAMES 重算栈映射帧（`framesWriter`
    带 classpath 的 `getCommonSuperClass`）。
  - 各容量/尺寸锚点均“读原值 +1”，不硬编码数字。
- `DefaultConversation.prepareMetadata`：构造 PersistedMetadata 后
  `dup + ThinkingEffortStore.applyTo` 回填。
- `ActiveConversationOrchestrator.selectConversation`：入口通知
  `ThinkingEffortStore.onConversationSelection` 切换/新建刷新。

**运行时存储 `ThinkingEffortStore`**：`conversationId -> 档位` 映射 +
当前会话 ID + 当前档位（`ELEMENT_INDEX=16`）；加载/切换/选择/保存各钩子；
新会话首次保存时绑定 ID。

**验证**：`ThinkingEffortPickerTest`（下拉状态与事件）、
`ReasoningEffortPersistTest`（kotlinx JSON 往返、旧格式解码为 null、
null 不写出、Store 加载/选择/保存/新建/旧对话默认、反序列化即读到档位的端到端断言、
切换→落盘→重启读取→请求映射的完整生命周期回路）
null 不写出、Store 加载/选择/保存/新建/旧对话默认）。CheckClassAdapter 通过。

**重启后首个会话的下拉同步**
- IDE 重启后首个会话的选择不经 `selectConversation`（orchestrator 构造时直接
  初始化选择流，`LatestOrCreate` 解析后直接 setValue），切换钩子不触发。
- ASM 补丁 `TrajectoryTimelineController.handleEvent` 的 `ConversationPresented`
  分支：`clearStatus` 调用前插入 `dup + ThinkingEffortStore.onConversationPresented`
  （锚定实参来自 `ConversationPresented.getConversationId()` 的那个 clearStatus）。
  会话在 UI 呈现必然触发该事件，按 KNOWN 映射同步下拉。

## 思考强度接入 OpenAI 请求参数

**目标**：把会话级思考强度档位作为 `reasoning_effort`（Chat Completions）/
`reasoning.effort`（Responses）发给供应商。

**原生链路（逆向结论）**
- model：会话持久化 modelId → controller 选出 `ModelApi` →
  `configureWith(selectedModel, runConfig, GenerationConfig.Companion.defaultForAgent())`
  → `InvocationContextImpl` → `ModelRequest` → `OpenAiModelApi.streamGenerateContent`
  （modelId 取自 ModelApi 持有的 ModelConfig）→ 两个 `createParams(modelId, ...)`。
- 原生 reasoning effort 取自 `modelRequest.generationConfig.thinkingConfig`：
  - Completion：`thinkingConfig != null && includeThoughts==true && !omitReasoningEffort` 时
    `toReasoningEffort(thinkingLevel)`；ThinkingLevel 仅 low/medium/high，
    null→MEDIUM。
  - Responses：仅当 `thinkingLevel!=null` 才发 `reasoning.effort`；
    `includeThoughts==false` 发 NONE。
  - `defaultForAgent()` 的 ThinkingConfig 为 `(includeThoughts=true, level=null)`
    → Completion 恒发 MEDIUM、Responses 从不发。
- 自适应回退：Completion 收到 REASONING_EFFORT_NOT_SUPPORTED/BAD_REQUEST_OTHER
  → `supportReasoningEffort=false` 重试（omitReasoningEffort=true）；Responses
  收到 BAD_REQUEST_OTHER/NOT_FOUND → 回退 Completion 链路。

**补丁方案（与 modelId 同层注入，值来自会话级 Store）**
- `ThinkingEffortStore.toOpenAiReasoningEffort()`：当前档位 →
  `com.openai.models.ReasoningEffort`（none/minimal/low/medium/high/xhigh 常量，
  max 用 `ReasoningEffort.of("max")`）。
- `OpenAiCompletionApiV2.createParams`：`getstatic INSTANCE / aload level /
  getThinkingLevel / invokespecial toReasoningEffort` 四指令整体替换为一条
  `INVOKESTATIC ThinkingEffortStore.toOpenAiReasoningEffort`；原守卫
  （含 omitReasoningEffort 回退）不变。另把原生
  `omitReasoningEffort=true` 时无条件发 `reasoningEffort(NONE)` 的分支门控
  （`ILOAD omit`）改为 `ICONST_0` 恒跳过——供应商拒绝该参数后的自适应重试
  不再携带被拒绝的参数；用户下拉选 none 仍经上方替换点显式发 NONE。
- `OpenAiResponsesApiV2.createParams`：方法末尾 `return paramsBuilder.build()`
  前无条件覆写 `paramsBuilder.reasoning(Reasoning.builder().effort(...).build())`
  （Builder 后写覆盖先写；监督子请求 includeThoughts=false 的 NONE 同样被覆盖；
  供应商不接受时：AUTO 模式由协议/参数自适应回退兜底，固定协议时错误直接抛给用户）。

**验证**：`ReasoningEffortApiTest` —— 7 档 × 2 协议逐一断言请求参数
（`ChatCompletionCreateParams.reasoningEffort()` /
`ResponseCreateParams.reasoning().effort()` 的 `asString()` 等于档位），
另验证 `omitReasoningEffort=true` 时 Completion 不带该参数。
