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
