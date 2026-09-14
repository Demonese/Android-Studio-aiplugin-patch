#!/usr/bin/env bash
# 30_build_patch.sh — 构建补丁 jar（ASM 补丁 + 新类编译 + 组装）
#
# 流程：
#   阶段1  ASM 补丁 RemoteProviderData：加 openAiApiType 字段/@OptionTag/getter/setter，
#          并在 <init>/copy()/copy(4参)/equals/hashCode 中处理该字段
#   阶段2  ASM 补丁 OpenAiModelApi/$streamGenerateContent$1/OpenAiModelApiProvider：
#          按 openAiApiType 选择协议、固定协议时禁用 Responses->Completion 自动回退；
#          两个 createParams 接入会话级思考强度（reasoning_effort / reasoning.effort）
#   阶段3  ASM 补丁 PersistedMetadata：加 reasoningEffort 字段/访问器与写出
#   阶段4  编译新增 Java 源码（依赖阶段1/2产生的 getter/setter）
#   阶段5  ASM 补丁 RemoteModelProviderInfoPanel：setupUi 加行、update 加载、schema 监听联动
#   阶段6  ASM 补丁 QueryBoxKt.ActionsRow：模型选择与发送按钮之间插入思考强度下拉
#   阶段7  ASM 补丁 PersistedMetadata$$serializer：descriptor/childSerializers/deserialize
#          读写 reasoningEffort（元素 16）
#   阶段8  ASM 补丁 DefaultConversation.prepareMetadata：保存回填
#   阶段9  ASM 补丁 ActiveConversationOrchestrator：会话切换刷新
#   阶段10 ASM 补丁 TrajectoryTimelineController：会话呈现同步
#   阶段11 ASM 补丁 RunShellCommandHandler/RunShellCommandTool：Windows 平台 pwsh 优先
#          （where.exe 探测，默认/显式 powershell 走 pwsh.exe）与按 pwsh 可用性的动态文案
#   阶段13 ASM 补丁 ModelInformationTablePanel.setupUi：Available Models 表格工具栏挂
#          AvailableModelsToolbarSupport 的 "Add Model" "+" 按钮（官方同款 addExtraAction）
#   阶段14 ASM 补丁 ModelInformationTablePanel$Companion.updateModelList：
#          方法体前置早退替换为 mergeModelList —— 手动 Refresh 保留孤儿条目
#          （对齐自动刷新语义，手动添加的自定义模型不再被抹掉）；
#          Refresh 失败（fetched 为空）时列表原样保留（修复原实现整表清空缺陷）
#   阶段15 组装 dist/aiplugin-patched.jar（原 jar + 替换/新增 class）
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_tools

PATCHED="$WORK/patched"      # ASM 补丁输出的 class
OUT="$WORK/out"              # 新编译的 class
DIST="$PROJ/dist"
mkdir -p "$PATCHED" "$OUT" "$DIST"

ASMC="$(asm_cp)"
PLAT="$(platform_cp)"
PLIB="$(plugin_lib_cp)"
FULL="$(full_lib_cp)"

echo "[1/15] 编译 ASM 补丁工具 PatchTool ..."
javac -cp "$ASMC" -d "$WORK/tools-out" "$PROJ/src/patcher/java/PatchTool.java"

echo "[2/15] 阶段1：补丁 RemoteProviderData（加字段与访问器）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool data "$WORK/classes-orig" "$PATCHED"

echo "[3/15] 阶段2：补丁 OpenAiModelApi 等（协议选择、回退控制、思考强度接入）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool api "$WORK/classes-orig" "$PATCHED"

echo "[4/15] 阶段3：补丁 PersistedMetadata（reasoningEffort 字段与写出）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool metadata "$WORK/classes-orig" "$PATCHED"

echo "[5/15] 阶段4：编译新增源码（OpenAiApiType/Converter/Ui/Support/ThinkingEffortPicker/WindowsShellResolver/AvailableModelsToolbarSupport）..."
javac --release "$JAVA_RELEASE" -nowarn \
  -cp "$PATCHED:$PLUGIN_JAR:$PLAT:$PLIB" \
  -d "$OUT" $(find "$PROJ/src/main/java" -name "*.java")

echo "[6/15] 阶段5：补丁 RemoteModelProviderInfoPanel（UI 注入）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool panel "$WORK/classes-orig" "$PATCHED"

echo "[7/15] 阶段6：补丁 QueryBoxKt（发送区插入思考强度下拉）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool querybox "$WORK/classes-orig" "$PATCHED"

echo "[8/15] 阶段7：补丁 PersistedMetadata\$\$serializer（descriptor 与读取）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool metaser "$WORK/classes-orig" "$PATCHED" "$PATCHED:$OUT:$PLUGIN_JAR:$PLAT:$PLIB:$FULL"

echo "[9/15] 阶段8：补丁 DefaultConversation.prepareMetadata（保存回填）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool convmeta "$WORK/classes-orig" "$PATCHED"

echo "[10/15] 阶段9：补丁 ActiveConversationOrchestrator（会话切换刷新）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool orch "$WORK/classes-orig" "$PATCHED"

echo "[11/15] 阶段10：补丁 TrajectoryTimelineController（会话呈现同步）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool timeline "$WORK/classes-orig" "$PATCHED"

echo "[12/15] 阶段11：补丁 RunShellCommandHandler/RunShellCommandTool（pwsh 优先 + 动态文案）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool winshell "$WORK/classes-orig" "$PATCHED"

echo "[13/15] 阶段13：补丁 ModelInformationTablePanel（Available Models 工具栏 + 按钮）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool modelstable "$WORK/classes-orig" "$PATCHED"

echo "[14/15] 阶段14：补丁 ModelInformationTablePanel\$Companion（updateModelList → mergeModelList）..."
java "$JAVA_ENC" -cp "$WORK/tools-out:$ASMC" PatchTool modelsmerge "$WORK/classes-orig" "$PATCHED"

echo "[15/15] 阶段15：组装 $DIST/aiplugin-patched.jar ..."
cp "$PLUGIN_JAR" "$DIST/aiplugin-patched.jar"
jar uf "$DIST/aiplugin-patched.jar" \
  -C "$PATCHED" "com/android/studio/ml/modelproviders/data/ProviderData\$RemoteProviderData.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/settings/RemoteModelProviderInfoPanel.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/openai/OpenAiModelApi.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/openai/OpenAiModelApi\$streamGenerateContent\$1.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/openai/OpenAiModelApiProvider.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/openai/OpenAiResponsesApiV2.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/openai/OpenAiCompletionApiV2.class" \
  -C "$PATCHED" "com/google/studiobot/ui/querybox/QueryBoxKt.class" \
  -C "$PATCHED" "com/google/studiobot/agentsdk/conversations/PersistedMetadata.class" \
  -C "$PATCHED" "com/google/studiobot/agentsdk/conversations/PersistedMetadata\$\$serializer.class" \
  -C "$PATCHED" "com/google/studiobot/agentsdk/conversations/DefaultConversation.class" \
  -C "$PATCHED" "com/google/studiobot/controller/ActiveConversationOrchestrator.class" \
  -C "$PATCHED" "com/google/studiobot/controller/TrajectoryTimelineController.class" \
  -C "$PATCHED" "com/google/aiplugin/agents/tools/execute/RunShellCommandHandler.class" \
  -C "$PATCHED" "com/google/aiplugin/agents/tools/execute/RunShellCommandTool.class" \
  -C "$PATCHED" "com/android/studio/ml/modelproviders/providerinfo/ModelInformationTablePanel.class" \
  -C "$PATCHED" "com/android/studio/ml/modelproviders/providerinfo/ModelInformationTablePanel\$Companion.class" \
  -C "$OUT" "com/google/aiplugin/agents/tools/execute/WindowsShellResolver.class" \
  -C "$OUT" "com/android/studio/ml/modelproviders/data/OpenAiApiType.class" \
  -C "$OUT" "com/android/studio/ml/modelproviders/data/OpenAiApiTypeConverter.class" \
  -C "$OUT" "com/android/studio/ml/backends/settings/OpenAiApiTypeUi.class" \
  -C "$OUT" "com/android/studio/ml/backends/settings/OpenAiApiTypeUi\$State.class" \
  -C "$OUT" "com/android/studio/ml/backends/settings/OpenAiApiTypeUi\$State\$1.class" \
  -C "$OUT" "com/android/studio/ml/backends/settings/OpenAiApiTypeUi\$1.class" \
  -C "$OUT" "com/android/studio/ml/backends/openai/OpenAiApiTypeSupport.class" \
  -C "$OUT" "com/android/studio/ml/backends/openai/OpenAiResponsesSupport.class" \
  -C "$OUT" "com/android/studio/ml/backends/openai/OpenAiCompletionSupport.class" \
  -C "$OUT" "com/google/studiobot/ui/querybox/ThinkingEffortPicker.class" \
  -C "$OUT" "com/google/studiobot/ui/querybox/ThinkingEffortStore.class" \
  -C "$OUT" "com/android/studio/ml/modelproviders/providerinfo/AvailableModelsToolbarSupport.class" \
  -C "$OUT" "com/android/studio/ml/modelproviders/providerinfo/AvailableModelsToolbarSupport\$AddModelAction.class"

unzip -t "$DIST/aiplugin-patched.jar" > /dev/null
echo "== 构建完成：$DIST/aiplugin-patched.jar =="
ls -la "$DIST/aiplugin-patched.jar"
