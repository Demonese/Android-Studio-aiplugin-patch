# Android Studio aiplugin patch

对 Android Studio Gemini 插件（`plugins/gemini/lib/aiplugin.jar`，闭源）的字节码补丁项目。

## AI 辅助编程披露

该项目完全由 Qwen3.8-Max 自主完成，我没有写一行代码，只负责需求规划。

## 关于源代码协议

Android Studio Gemini 插件是商业闭源项目，理论上该项目也应该闭源且不公开发布。但谷歌的插件实现实在是太恶心了，我决定公开脚本和工作流，并将打补丁后的 aiplugin.jar 分享给大家。

## 背景

Android Studio 的 AI 功能对 OpenAI 兼容 provider **默认先调 Responses API（`/v1/responses`），
失败（任意 400/404）后自动且不可逆地 fallback 到 Chat Completions API**，没有任何 UI 开关，
导致部分 OpenAI 兼容服务端上聊天直接报错卡死（详见 `docs/analysis.md`）。

本项目：在 **Model Providers 设置界面新增 "OpenAI API protocol:" 下拉框**
（仅 OpenAI-compatible 时可见），选项 `Auto / OpenAI Chat Completions API / OpenAI Responses API`，
持久化到 provider 配置，并**让该选项实际控制 API 调用**：
固定为某一协议时不再做 Responses→Completions 的自动回退。
此外修复思考模式（DeepSeek/Qwen 等）多轮对话的思考内容回传问题：
- Responses API：部分轮次无思考块导致
  `400: The reasoning_text in the thinking mode must be passed back to the API`，
  对缺失思考的 assistant 轮次自动补占位思考内容；
- Chat Completions API：原实现丢弃 assistant 消息的思考内容，
  现将已收到的 thought 作为 `reasoning_content` 附加字段回传，
  避免 `400: The reasoning_content in the thinking mode must be passed back to the API`；
- Chat Completions API 系统消息 role：原实现在 `useSystemMessage=false`
  （agent 主路径硬编码）时发送 `developer` role，众多第三方兼容供应商不认而报 400，
  现恒用 `system` role（OpenAI 官方仍兼容）；
- 思考强度：Agent 发送区模型选择与 Submit 之间新增下拉（none/minimal/low/medium/high/xhigh/max，
  样式复用模型选择器），选择按会话持久化到对话目录 `metadata.json` 的 `reasoningEffort`
  字段（旧对话缺省 medium）；并接入请求参数——Chat Completions 发 `reasoning_effort`、
  Responses 发 `reasoning.effort`（供应商不接受时沿用原生自适应回退）。

## 目录结构

```
aiplugin-patch/
├── README.md
├── config.env                 # 路径/版本配置
├── Android Studio.zip         # Android Studio 发行包（自备，不入 git，见 .gitignore）
├── scripts/
│   ├── 00_setup_tools.sh      # 下载/校验工具 jar（CFR + ASM，SHA-1 校验，支持镜像）
│   ├── 10_extract_jars.sh     # 从 Android Studio.zip 提取 jar/class
│   ├── 20_decompile.sh        # CFR 反编译（分析用）
│   ├── 30_build_patch.sh      # 多阶段 ASM 补丁 + 编译 + 组装补丁 jar
│   ├── 40_verify.sh           # 字节码校验 + 运行时测试
│   └── common.sh
├── tools/                     # 构建工具 jar（00_setup_tools.sh 下载，勿手改）
├── src/
│   ├── main/java/             # 新增类源码（编译进 jar）
│   │   └── com/android/studio/ml/
│   │       ├── modelproviders/data/OpenAiApiType.java
│   │       ├── modelproviders/data/OpenAiApiTypeConverter.java
│   │       ├── backends/settings/OpenAiApiTypeUi.java
│   │       ├── backends/openai/OpenAiApiTypeSupport.java
│   │       ├── backends/openai/OpenAiResponsesSupport.java
│   │       └── backends/openai/OpenAiCompletionSupport.java
│   ├── patcher/java/PatchTool.java   # ASM 补丁工具
│   └── test/java/             # SerializeTest / ApiProtocolTest / ResponsesReasoningTest / CompletionReasoningTest / UiLoadTest
├── docs/
│   ├── analysis.md            # 逆向分析：fallback 机制、UI 结构、持久化
│   └── patch-design.md        # 补丁设计、插入点、后端协议控制
├── work/                      # 构建中间产物（脚本生成）
└── dist/                      # 输出：aiplugin-patched.jar
```

## 快速开始

```bash
cd aiplugin-patch
./scripts/00_setup_tools.sh     # 下载工具 jar（CFR/ASM，Maven Central + SHA-1 校验）
./scripts/10_extract_jars.sh    # 从项目目录下的 "Android Studio.zip" 提取依赖
./scripts/30_build_patch.sh     # 构建 dist/aiplugin-patched.jar
./scripts/40_verify.sh          # 验证（字节码 + 序列化往返 + 行为测试 + 类加载）
```

可选：`./scripts/20_decompile.sh` 重新生成反编译源码到 `work/decompiled/`
（仓库上级目录的 `../aiplugin/` 是同一反编译结果的副本）。

## 工具下载（tools/）

`tools/` 下的 CFR 与 ASM jar 由 `scripts/00_setup_tools.sh` 从 Maven Central 下载，
**无需手工放置**：

- 版本在 `config.env`（`CFR_VERSION=0.152`、`ASM_VERSION=9.7.1`）
- 下载先写入 `.part` 临时文件，与 Maven Central 官方 **SHA-1** 校验一致后才落盘，
  损坏/被篡改的文件会自动重新下载
- `20/30/40` 脚本启动时自动检查工具是否齐全，缺失会自动调用下载流程
- 用法：
  ```bash
  ./scripts/00_setup_tools.sh            # 下载缺失的 jar（已有且校验通过则跳过）
  ./scripts/00_setup_tools.sh --check    # 只校验，不下载
  ./scripts/00_setup_tools.sh --force    # 强制全部重新下载
  ```
- 网络受限时可换镜像：
  ```bash
  MAVEN_REPO_BASE=https://maven.aliyun.com/repository/public ./scripts/00_setup_tools.sh
  ```

## 安装补丁

1. 备份：`Android Studio/plugins/gemini/lib/aiplugin.jar`
2. 用 `dist/aiplugin-patched.jar` 替换之（改回文件名 `aiplugin.jar`）
3. 启动 Android Studio → Settings → Tools → AI Assistant → Model Providers
4. 远程 provider 选 OpenAI-compatible 时应出现 "OpenAI API protocol:" 下拉框；
   Apply 后重开设置选项应保留（写入 provider 设置 XML 的 `openAiApiType` option）
5. 协议生效：选 Chat Completions → 只走 `/chat/completions`；选 Responses → 只走
   `/responses` 且失败不回退；Auto → 原生行为（Responses 失败自动回退）

## 环境要求

- Linux + JDK 21（`sudo apt-get install openjdk-21-jdk-headless`）
- `unzip`、`curl`、网络（首次下载 CFR/ASM）
- Android Studio 发行包 zip：**放到项目目录根下**（默认 `./Android Studio.zip`）；
  如放在别处，用环境变量覆盖：`AS_ZIP=/path/to/"Android Studio.zip" ./scripts/10_extract_jars.sh`

## 技术说明

- 插件为编译后的 Kotlin/Java 字节码，无源码：新逻辑以 **新增 Java 类 + ASM 修改既有类** 实现
- 构建分多阶段：先 ASM 给 `RemoteProviderData`/`OpenAiModelApi` 加字段与访问器，
  新增的 UI/Support 类才能编译通过
- 详细插入点与设计决策见 `docs/patch-design.md`

## 效果展示

Model Providers 设置界面：

![Model Providers](docs/model-providers.jpg)

Deepseek V4 Pro 修复：

![Deepseek V4 Pro 修复](docs/deepseek-v4-pro-fix.jpg)
