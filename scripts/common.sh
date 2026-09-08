#!/usr/bin/env bash
# common.sh — 供其他脚本 source 的公共函数/变量
set -euo pipefail
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$PROJ/config.env"

WORK="$PROJ/work"
DIST="$PROJ/dist"
LIB="$WORK/as/Android Studio/lib"
PLUGIN_JAR="$WORK/as/plugin/aiplugin.jar"
KOTLIN_STDLIB="$WORK/as/$KOTLIN_STDLIB_IN_ZIP"

# Java 启动参数：stdout 编码固定 UTF-8。
# 容器 locale 不完整时 JVM 的 native.encoding 退化为 US-ASCII，而 JDK 18+（JEP 400）
# 的 System.out 编码跟随 stdout.encoding（而非 file.encoding），测试输出的中文会变 '?'。
# 所有 java 运行统一带该参数（JDK 17 及以下不认识该属性，静默忽略，无副作用）。
JAVA_ENC="-Dstdout.encoding=UTF-8"

# 编译/校验用 classpath（平台 jar + kotlin-stdlib + annotations）
platform_cp() {
  local cp=""
  for j in "${PLATFORM_JARS[@]}"; do
    cp+="$LIB/$j:"
  done
  cp+="$KOTLIN_STDLIB"
  echo "$cp"
}

# 运行时测试用 classpath（lib/ 下全部 jar，模拟 IDE 环境）
full_lib_cp() {
  local all
  all=$(ls "$LIB"/*.jar 2>/dev/null | tr '\n' ':')
  echo "${all%:}"
}

# 插件自身依赖 jar（openai SDK 等，plugins/gemini/lib）
plugin_lib_cp() {
  local all
  all=$(ls "$WORK/as/Android Studio/plugins/gemini/lib"/*.jar 2>/dev/null | tr '\n' ':')
  echo "${all%:}"
}

asm_cp() {
  echo "$PROJ/tools/asm-${ASM_VERSION}.jar:$PROJ/tools/asm-tree-${ASM_VERSION}.jar:$PROJ/tools/asm-util-${ASM_VERSION}.jar:$PROJ/tools/asm-analysis-${ASM_VERSION}.jar"
}

# 确保构建所需工具 jar 存在；缺失时自动运行 00_setup_tools.sh 下载（含 SHA-1 校验）
require_tools() {
  local j missing=0
  local need=("$PROJ/tools/cfr-${CFR_VERSION}.jar")
  local a
  for a in asm asm-tree asm-util asm-analysis; do
    need+=("$PROJ/tools/${a}-${ASM_VERSION}.jar")
  done
  for j in "${need[@]}"; do
    [[ -f "$j" ]] || { missing=1; break; }
  done
  if [[ "$missing" -eq 1 ]]; then
    echo "[*] tools/ 缺少工具 jar，自动运行 00_setup_tools.sh 下载 ..."
    bash "$PROJ/scripts/00_setup_tools.sh"
  fi
}
