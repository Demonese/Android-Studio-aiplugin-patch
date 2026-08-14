#!/usr/bin/env bash
# 20_decompile.sh — 用 CFR 反编译 aiplugin.jar 到 work/decompiled（分析用，可重复执行）
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/common.sh"

require_tools
CFR_JAR="$PROJ/tools/cfr-${CFR_VERSION}.jar"
[[ -f "$PLUGIN_JAR" ]] || { echo "[!] 缺少 $PLUGIN_JAR，先运行 10_extract_jars.sh"; exit 1; }

OUT="$WORK/decompiled"
mkdir -p "$OUT"
echo "[*] 反编译 aiplugin.jar -> $OUT （约 1-2 分钟）"
java -Xmx4g -jar "$CFR_JAR" "$PLUGIN_JAR" --outputdir "$OUT" --silent true || true

N=$(find "$OUT" -name "*.java" | wc -l)
echo "== 反编译完成：$N 个 .java 文件 =="
echo "   关键文件："
echo "   - $OUT/com/android/studio/ml/backends/openai/OpenAiModelApi.java   (Responses/Completions fallback 逻辑)"
echo "   - $OUT/com/android/studio/ml/backends/settings/RemoteModelProviderInfoPanel.java (设置界面)"
echo "   - $OUT/com/android/studio/ml/modelproviders/data/ProviderData.java (RemoteProviderData 数据模型)"
