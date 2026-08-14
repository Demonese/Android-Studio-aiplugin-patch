# aiplugin-patch

对 Android Studio Gemini 插件（`plugins/gemini/lib/aiplugin.jar`，闭源）的字节码补丁项目。

## 背景

Android Studio 的 AI 功能对 OpenAI 兼容 provider **默认先调 Responses API（`/v1/responses`），
失败（任意 400/404）后自动且不可逆地 fallback 到 Chat Completions API**，没有任何 UI 开关，
导致部分 OpenAI 兼容服务端上聊天直接报错卡死（详见 `docs/analysis.md`）。

本项目：在 **Model Providers 设置界面新增 "OpenAI API protocol:" 下拉框**
（仅 OpenAI-compatible 时可见），选项 `Auto / OpenAI Chat Completions API / OpenAI Responses API`，
持久化到 provider 配置，并**让该选项实际控制 API 调用**：
固定为某一协议时不再做 Responses→Completions 的自动回退。
此外修复 DeepSeek 思考模式下因部分轮次无思考块导致的
`400: The reasoning_text in the thinking mode must be passed back to the API`：
对缺失思考的 assistant 轮次自动补占位思考内容。

## 目录结构

```
aiplugin-patch/
├── README.md
├── config.env                 # 路径/版本配置
├── scripts/
│   ├── 00_setup_tools.sh      # 准备 JDK21 + CFR + ASM
│   ├── 10_extract_jars.sh     # 从 Android Studio.zip 提取 jar/class
│   ├── 20_decompile.sh        # CFR 反编译（分析用）
│   ├── 30_build_patch.sh      # 两阶段 ASM 补丁 + 编译 + 组装补丁 jar
│   ├── 40_verify.sh           # 字节码校验 + 运行时测试
│   └── common.sh
├── src/
│   ├── main/java/             # 新增类源码（编译进 jar）
│   │   └── com/android/studio/ml/
│   │       ├── modelproviders/data/OpenAiApiType.java
│   │       ├── modelproviders/data/OpenAiApiTypeConverter.java
│   │       ├── backends/settings/OpenAiApiTypeUi.java
│   │       ├── backends/openai/OpenAiApiTypeSupport.java
│   │       └── backends/openai/OpenAiResponsesSupport.java
│   ├── patcher/java/PatchTool.java   # ASM 补丁工具
│   └── test/java/             # SerializeTest / ApiProtocolTest / ResponsesReasoningTest / UiLoadTest
├── docs/
│   ├── analysis.md            # 逆向分析：fallback 机制、UI 结构、持久化
│   └── patch-design.md        # 补丁设计、插入点、后端协议控制
├── work/                      # 构建中间产物（脚本生成）
└── dist/                      # 输出：aiplugin-patched.jar
```

## 快速开始

```bash
cd aiplugin-patch
./scripts/00_setup_tools.sh     # 安装/下载工具（需要网络；JDK21 需 apt）
./scripts/10_extract_jars.sh    # 从 ../"Android Studio.zip" 提取依赖
./scripts/30_build_patch.sh     # 构建 dist/aiplugin-patched.jar
./scripts/40_verify.sh          # 验证（字节码 + 序列化往返 + 类加载）
```

可选：`./scripts/20_decompile.sh` 重新生成反编译源码到 `work/decompiled/`
（仓库上级目录的 `../aiplugin/` 是同一反编译结果的副本）。

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
- Android Studio 发行包 zip（路径在 `config.env` 中配置，默认 `../Android Studio.zip`）

## 技术说明

- 插件为编译后的 Kotlin/Java 字节码，无源码：新逻辑以 **新增 Java 类 + ASM 修改既有类** 实现
- 构建分多阶段：先 ASM 给 `RemoteProviderData`/`OpenAiModelApi` 加字段与访问器，
  新增的 UI/Support 类才能编译通过
- 详细插入点与设计决策见 `docs/patch-design.md`
