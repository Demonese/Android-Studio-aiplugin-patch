#!/usr/bin/env bash
# 10_extract_jars.sh — 从 Android Studio zip 提取：
#   - aiplugin.jar（待补丁的 Gemini 插件）
#   - 编译所需平台 jar（lib/ 下）
#   - kotlin-stdlib.jar
#   - 待补丁类的原始 .class（供 ASM 读取）
set -euo pipefail
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$PROJ/config.env"

[[ -f "$AS_ZIP" ]] || { echo "[!] 找不到 Android Studio zip: $AS_ZIP"; exit 1; }

WORK="$PROJ/work"
LIB="$WORK/as/Android Studio/lib"
mkdir -p "$LIB" "$WORK/as/plugin" "$WORK/classes-orig"

echo "[*] 提取 ${PLUGIN_JAR_IN_ZIP} ..."
unzip -o -q "$AS_ZIP" "$PLUGIN_JAR_IN_ZIP" -d "$WORK/as"
cp "$WORK/as/$PLUGIN_JAR_IN_ZIP" "$WORK/as/plugin/aiplugin.jar"

echo "[*] 提取插件 lib/ 全部依赖 jar（openai SDK 等，运行时测试用）..."
unzip -o -q "$AS_ZIP" "Android Studio/plugins/gemini/lib/*.jar" -d "$WORK/as"

echo "[*] 提取平台 jar（编译用）..."
for j in "${PLATFORM_JARS[@]}"; do
  unzip -o -q "$AS_ZIP" "Android Studio/lib/$j" -d "$WORK/as"
done

echo "[*] 提取 lib/ 全部 jar（运行时测试用，约 770MB，可跳过：SKIP_FULL_LIB=1）..."
if [[ "${SKIP_FULL_LIB:-0}" == "1" ]]; then
  echo "    已跳过"
else
  unzip -o -q "$AS_ZIP" "Android Studio/lib/*.jar" -d "$WORK/as"
fi

echo "[*] 提取 kotlin-stdlib ..."
unzip -o -q "$AS_ZIP" "$KOTLIN_STDLIB_IN_ZIP" -d "$WORK/as"

echo "[*] 提取待补丁类的原始 class ..."
unzip -o -q "$WORK/as/plugin/aiplugin.jar" \
  "com/android/studio/ml/modelproviders/data/ProviderData*.class" \
  "com/android/studio/ml/backends/settings/RemoteModelProviderInfoPanel.class" \
  "com/android/studio/ml/backends/openai/OpenAiModelApi.class" \
  "com/android/studio/ml/backends/openai/OpenAiModelApi\$streamGenerateContent\$1.class" \
  "com/android/studio/ml/backends/openai/OpenAiModelApiProvider.class" \
  "com/android/studio/ml/backends/openai/OpenAiResponsesApiV2.class" \
  -d "$WORK/classes-orig"

echo "== 提取完成 =="
echo "    插件 jar: $WORK/as/plugin/aiplugin.jar"
echo "    平台 jar: $LIB"
