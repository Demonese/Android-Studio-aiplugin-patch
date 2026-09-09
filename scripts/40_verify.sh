#!/usr/bin/env bash
# 40_verify.sh — 验证补丁 jar：
#   1) ASM CheckClassAdapter 字节码校验（所有被补丁的类）
#   2) SerializeTest：xmlb 序列化往返 + 默认值 + copy()/equals/hashCode
#   3) ApiProtocolTest：协议选择与回退控制行为测试
#   4) ResponsesReasoningTest：Responses 请求缺失思考时补占位思考
#   5) CompletionReasoningTest：Chat Completions 请求回传 reasoning_content
#   6) ThinkingEffortPickerTest：思考强度下拉状态与事件
#   7) ReasoningEffortPersistTest：reasoningEffort 序列化往返与 Store 行为
#   8) ReasoningEffortApiTest：两个 createParams 按会话档位发 reasoning_effort/reasoning.effort
#   9) UiLoadTest：设置界面与发送区布局兼容性（注入点相对位置 + 反射目标 + 枚举/转换器）
#  10) ApiProtocolUiBehaviorTest：协议下拉控件运行时行为（headless 构造真实面板，
#      验证 load 加载存储值 / 厂商切换显隐联动 / 切换回写 live provider / 容错）
#  11) ProviderApiTypePropagationTest：设置 -> computeState -> ModelApi 实例 端到端传播
#      （按 provider 隔离、设置变更重算生效、AUTO 默认行为、固定协议分派/回退决策）
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_tools

DIST_JAR="$DIST/aiplugin-patched.jar"
[[ -f "$DIST_JAR" ]] || { echo "[!] 缺少 $DIST_JAR，先运行 30_build_patch.sh"; exit 1; }

ASMC="$(asm_cp)"
PLAT="$(platform_cp)"
FULL="$(full_lib_cp)"
PLIB="$(plugin_lib_cp)"
TESTOUT="$WORK/test-out"
mkdir -p "$TESTOUT"

# ApiProtocolUiBehaviorTest 需实例化 Swing 组件（headless 下构造），
# IntelliJ 平台以反射访问 java.desktop 内部，需要模块开放
AWT_OPENS="--add-opens java.desktop/javax.swing=ALL-UNNAMED --add-opens java.desktop/java.awt=ALL-UNNAMED"

echo "[1/11] 字节码校验 (CheckClassAdapter) ..."
for c in \
  "com.android.studio.ml.modelproviders.data.ProviderData\$RemoteProviderData" \
  "com.android.studio.ml.backends.settings.RemoteModelProviderInfoPanel" \
  "com.android.studio.ml.backends.openai.OpenAiModelApi" \
  "com.android.studio.ml.backends.openai.OpenAiModelApi\$streamGenerateContent\$1" \
  "com.android.studio.ml.backends.openai.OpenAiModelApiProvider" \
  "com.android.studio.ml.backends.openai.OpenAiResponsesApiV2" \
  "com.android.studio.ml.backends.openai.OpenAiCompletionApiV2" \
  "com.google.studiobot.ui.querybox.QueryBoxKt" \
  "com.google.studiobot.agentsdk.conversations.PersistedMetadata" \
  "com.google.studiobot.agentsdk.conversations.PersistedMetadata\$\$serializer" \
  "com.google.studiobot.agentsdk.conversations.DefaultConversation" \
  "com.google.studiobot.controller.ActiveConversationOrchestrator" \
  "com.google.studiobot.controller.TrajectoryTimelineController" \
  "com.google.aiplugin.agents.tools.execute.RunShellCommandHandler"; do
  java "$JAVA_ENC" -cp "$ASMC:$DIST_JAR:$PLUGIN_JAR:$PLAT:$PLIB:$FULL:$WORK/out" \
    org.objectweb.asm.util.CheckClassAdapter "$c"
  echo "    ok: $c"
done

echo "[2/11] 序列化往返测试 ..."
RT_CP="$DIST_JAR:$FULL:$PLIB:$KOTLIN_STDLIB"
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/SerializeTest.java"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" SerializeTest | grep -E "serialized|restored|default|copy|ok:|info:|FAILED|ALL_OK"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" SerializeTest | grep -q ALL_OK || { echo "[!] SerializeTest 失败"; exit 1; }

echo "[3/11] 协议选择与回退控制测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ApiProtocolTest.java"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ApiProtocolTest | grep -E "ok:|FAILED|ALL_OK"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ApiProtocolTest | grep -q ALL_OK || { echo "[!] ApiProtocolTest 失败"; exit 1; }

echo "[4/11] Responses 思考回退补全测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ResponsesReasoningTest.java"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ResponsesReasoningTest | grep -E "ok:|FAILED|ALL_OK"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ResponsesReasoningTest | grep -q ALL_OK || { echo "[!] ResponsesReasoningTest 失败"; exit 1; }

echo "[5/11] Chat Completions reasoning_content 回传测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/CompletionReasoningTest.java"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" CompletionReasoningTest | grep -E "ok:|FAILED|ALL_OK"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" CompletionReasoningTest | grep -q ALL_OK || { echo "[!] CompletionReasoningTest 失败"; exit 1; }

echo "[6/11] 思考强度下拉测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ThinkingEffortPickerTest.java"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ThinkingEffortPickerTest | grep -E "ok:|FAILED|ALL_OK"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ThinkingEffortPickerTest | grep -q ALL_OK || { echo "[!] ThinkingEffortPickerTest 失败"; exit 1; }

echo "[7/11] reasoningEffort 持久化测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ReasoningEffortPersistTest.java"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ReasoningEffortPersistTest | grep -E "ok:|FAILED|ALL_OK"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ReasoningEffortPersistTest | grep -q ALL_OK || { echo "[!] ReasoningEffortPersistTest 失败"; exit 1; }

echo "[8/11] reasoning_effort/reasoning.effort 接入测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ReasoningEffortApiTest.java"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ReasoningEffortApiTest | grep -E "ok:|FAILED|ALL_OK"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" ReasoningEffortApiTest | grep -q ALL_OK || { echo "[!] ReasoningEffortApiTest 失败"; exit 1; }

echo "[9/11] 设置界面与发送区布局校验 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$ASMC:$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/UiLoadTest.java"
java "$JAVA_ENC" -cp "$ASMC:$RT_CP:$TESTOUT" UiLoadTest | grep -E "enum|  |ok:|FAILED|UI_CLASSES_LOAD_OK"
java "$JAVA_ENC" -cp "$ASMC:$RT_CP:$TESTOUT" UiLoadTest | grep -q UI_CLASSES_LOAD_OK || { echo "[!] UiLoadTest 失败"; exit 1; }

echo "[10/11] 协议下拉控件运行时行为测试（headless）..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ApiProtocolUiBehaviorTest.java"
java "$JAVA_ENC" -Djava.awt.headless=true $AWT_OPENS -cp "$RT_CP:$TESTOUT" ApiProtocolUiBehaviorTest | grep -E "ok:|FAILED|UI_BEHAVIOR_OK"
java "$JAVA_ENC" -Djava.awt.headless=true $AWT_OPENS -cp "$RT_CP:$TESTOUT" ApiProtocolUiBehaviorTest | grep -q UI_BEHAVIOR_OK || { echo "[!] ApiProtocolUiBehaviorTest 失败"; exit 1; }

echo "[11/11] 设置 -> computeState -> ModelApi 实例 端到端传播测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ProviderApiTypePropagationTest.java"
java "$JAVA_ENC" -Djava.awt.headless=true -cp "$RT_CP:$TESTOUT" ProviderApiTypePropagationTest | grep -E "ok:|FAILED|PROPAGATION_OK"
java "$JAVA_ENC" -Djava.awt.headless=true -cp "$RT_CP:$TESTOUT" ProviderApiTypePropagationTest | grep -q PROPAGATION_OK || { echo "[!] ProviderApiTypePropagationTest 失败"; exit 1; }

echo "[12/13] WindowsShellResolver 单元测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/WindowsShellResolverTest.java"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" WindowsShellResolverTest | grep -E "ok:|FAILED|RESOLVER_OK"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" WindowsShellResolverTest | grep -q RESOLVER_OK || { echo "[!] WindowsShellResolverTest 失败"; exit 1; }

echo "[13/13] run_shell_command Windows 分支行为测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/RunShellCommandWindowsArgTest.java"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" RunShellCommandWindowsArgTest | grep -E "ok:|FAILED|WINDOWS_ARG_OK"
java "$JAVA_ENC" -cp "$RT_CP:$TESTOUT" RunShellCommandWindowsArgTest | grep -q WINDOWS_ARG_OK || { echo "[!] RunShellCommandWindowsArgTest 失败"; exit 1; }

echo "== 全部验证通过 =="
