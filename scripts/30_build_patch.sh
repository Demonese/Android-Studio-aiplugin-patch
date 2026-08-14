#!/usr/bin/env bash
# 30_build_patch.sh — 构建补丁 jar（ASM 补丁 + 新类编译 + 组装）
#
# 流程：
#   阶段1  ASM 补丁 RemoteProviderData：加 openAiApiType 字段/@OptionTag/getter/setter，
#          并在 <init>/copy()/copy(4参)/equals/hashCode 中处理该字段
#   阶段2  ASM 补丁 OpenAiModelApi/$streamGenerateContent$1/OpenAiModelApiProvider：
#          按 openAiApiType 选择协议、固定协议时禁用 Responses->Completion 自动回退
#   阶段3  编译新增 Java 源码（依赖阶段1/2产生的 getter/setter）
#   阶段4  ASM 补丁 RemoteModelProviderInfoPanel：setupUi 加行、update 加载、schema 监听联动
#   阶段5  组装 dist/aiplugin-patched.jar（原 jar + 替换/新增 class）
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

PATCHED="$WORK/patched"      # ASM 补丁输出的 class
OUT="$WORK/out"              # 新编译的 class
DIST="$PROJ/dist"
mkdir -p "$PATCHED" "$OUT" "$DIST"

ASMC="$(asm_cp)"
PLAT="$(platform_cp)"
PLIB="$(plugin_lib_cp)"

echo "[1/6] 编译 ASM 补丁工具 PatchTool ..."
javac -cp "$ASMC" -d "$WORK/tools-out" "$PROJ/src/patcher/java/PatchTool.java"

echo "[2/6] 阶段1：补丁 RemoteProviderData（加字段与访问器）..."
java -cp "$WORK/tools-out:$ASMC" PatchTool data "$WORK/classes-orig" "$PATCHED"

echo "[3/6] 阶段2：补丁 OpenAiModelApi 等（协议选择与回退控制）..."
java -cp "$WORK/tools-out:$ASMC" PatchTool api "$WORK/classes-orig" "$PATCHED"

echo "[4/6] 阶段3：编译新增源码（OpenAiApiType/Converter/Ui/Support）..."
javac --release "$JAVA_RELEASE" -nowarn \
  -cp "$PATCHED:$PLUGIN_JAR:$PLAT:$PLIB" \
  -d "$OUT" $(find "$PROJ/src/main/java" -name "*.java")

echo "[5/6] 阶段4：补丁 RemoteModelProviderInfoPanel（UI 注入）..."
java -cp "$WORK/tools-out:$ASMC" PatchTool panel "$WORK/classes-orig" "$PATCHED"

echo "[6/6] 阶段5：组装 $DIST/aiplugin-patched.jar ..."
cp "$PLUGIN_JAR" "$DIST/aiplugin-patched.jar"
jar uf "$DIST/aiplugin-patched.jar" \
  -C "$PATCHED" "com/android/studio/ml/modelproviders/data/ProviderData\$RemoteProviderData.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/settings/RemoteModelProviderInfoPanel.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/openai/OpenAiModelApi.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/openai/OpenAiModelApi\$streamGenerateContent\$1.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/openai/OpenAiModelApiProvider.class" \
  -C "$PATCHED" "com/android/studio/ml/backends/openai/OpenAiResponsesApiV2.class" \
  -C "$OUT" "com/android/studio/ml/modelproviders/data/OpenAiApiType.class" \
  -C "$OUT" "com/android/studio/ml/modelproviders/data/OpenAiApiTypeConverter.class" \
  -C "$OUT" "com/android/studio/ml/backends/settings/OpenAiApiTypeUi.class" \
  -C "$OUT" "com/android/studio/ml/backends/settings/OpenAiApiTypeUi\$State.class" \
  -C "$OUT" "com/android/studio/ml/backends/settings/OpenAiApiTypeUi\$State\$1.class" \
  -C "$OUT" "com/android/studio/ml/backends/settings/OpenAiApiTypeUi\$1.class" \
  -C "$OUT" "com/android/studio/ml/backends/openai/OpenAiApiTypeSupport.class" \
  -C "$OUT" "com/android/studio/ml/backends/openai/OpenAiResponsesSupport.class"

unzip -t "$DIST/aiplugin-patched.jar" > /dev/null
echo "== 构建完成：$DIST/aiplugin-patched.jar =="
ls -la "$DIST/aiplugin-patched.jar"
