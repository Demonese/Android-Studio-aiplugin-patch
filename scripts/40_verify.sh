#!/usr/bin/env bash
# 40_verify.sh — 验证补丁 jar：
#   1) ASM CheckClassAdapter 字节码校验（所有被补丁的类）
#   2) SerializeTest：xmlb 序列化往返 + 默认值 + copy()/equals/hashCode
#   3) ApiProtocolTest：协议选择与回退控制行为测试
#   4) ResponsesReasoningTest：Responses 请求缺失思考时补占位思考
#   5) CompletionReasoningTest：Chat Completions 请求回传 reasoning_content
#   6) ThinkingEffortPickerTest：思考强度下拉状态与事件
#   7) ReasoningEffortPersistTest：reasoningEffort 序列化往返与 Store 行为
#   8) UiLoadTest：新增 UI 类加载
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

echo "[1/8] 字节码校验 (CheckClassAdapter) ..."
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
  "com.google.studiobot.agentsdk.conversations.TopLevelConversation" \
  "com.google.studiobot.agentsdk.conversations.DefaultConversation" \
  "com.google.studiobot.controller.ActiveConversationOrchestrator" \
  "com.google.studiobot.controller.TrajectoryTimelineController"; do
  java -cp "$ASMC:$DIST_JAR:$PLUGIN_JAR:$PLAT:$PLIB:$FULL:$WORK/out" \
    org.objectweb.asm.util.CheckClassAdapter "$c"
  echo "    ok: $c"
done

echo "[2/8] 序列化往返测试 ..."
RT_CP="$DIST_JAR:$FULL:$PLIB:$KOTLIN_STDLIB"
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/SerializeTest.java"
java -cp "$RT_CP:$TESTOUT" SerializeTest | grep -E "serialized|restored|default|copy|ok:|info:|FAILED|ALL_OK"
java -cp "$RT_CP:$TESTOUT" SerializeTest | grep -q ALL_OK || { echo "[!] SerializeTest 失败"; exit 1; }

echo "[3/8] 协议选择与回退控制测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ApiProtocolTest.java"
java -cp "$RT_CP:$TESTOUT" ApiProtocolTest | grep -E "ok:|FAILED|ALL_OK"
java -cp "$RT_CP:$TESTOUT" ApiProtocolTest | grep -q ALL_OK || { echo "[!] ApiProtocolTest 失败"; exit 1; }

echo "[4/8] Responses 思考回退补全测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ResponsesReasoningTest.java"
java -cp "$RT_CP:$TESTOUT" ResponsesReasoningTest | grep -E "ok:|FAILED|ALL_OK"
java -cp "$RT_CP:$TESTOUT" ResponsesReasoningTest | grep -q ALL_OK || { echo "[!] ResponsesReasoningTest 失败"; exit 1; }

echo "[5/8] Chat Completions reasoning_content 回传测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/CompletionReasoningTest.java"
java -cp "$RT_CP:$TESTOUT" CompletionReasoningTest | grep -E "ok:|FAILED|ALL_OK"
java -cp "$RT_CP:$TESTOUT" CompletionReasoningTest | grep -q ALL_OK || { echo "[!] CompletionReasoningTest 失败"; exit 1; }

echo "[6/8] 思考强度下拉测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ThinkingEffortPickerTest.java"
java -cp "$RT_CP:$TESTOUT" ThinkingEffortPickerTest | grep -E "ok:|FAILED|ALL_OK"
java -cp "$RT_CP:$TESTOUT" ThinkingEffortPickerTest | grep -q ALL_OK || { echo "[!] ThinkingEffortPickerTest 失败"; exit 1; }

echo "[7/8] reasoningEffort 持久化测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/ReasoningEffortPersistTest.java"
java -cp "$RT_CP:$TESTOUT" ReasoningEffortPersistTest | grep -E "ok:|FAILED|ALL_OK"
java -cp "$RT_CP:$TESTOUT" ReasoningEffortPersistTest | grep -q ALL_OK || { echo "[!] ReasoningEffortPersistTest 失败"; exit 1; }

echo "[8/8] UI 类加载测试 ..."
javac --release "$JAVA_RELEASE" -nowarn -cp "$RT_CP" -d "$TESTOUT" "$PROJ/src/test/java/UiLoadTest.java"
java -cp "$RT_CP:$TESTOUT" UiLoadTest | grep -E "enum|fromId|UI_CLASSES_LOAD_OK"
java -cp "$RT_CP:$TESTOUT" UiLoadTest | grep -q UI_CLASSES_LOAD_OK || { echo "[!] UiLoadTest 失败"; exit 1; }

echo "== 全部验证通过 =="
