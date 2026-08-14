#!/usr/bin/env bash
# 00_setup_tools.sh — 准备构建工具：JDK 21、CFR 反编译器、ASM 字节码库
set -euo pipefail
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$PROJ/config.env"

mkdir -p "$PROJ/tools"

# --- JDK ---
if ! command -v javac >/dev/null 2>&1; then
  echo "[!] 未找到 javac。请安装 JDK 21，例如："
  echo "    sudo apt-get install -y openjdk-21-jdk-headless"
  exit 1
fi
echo "[ok] javac: $(javac -version 2>&1)"

# --- CFR（反编译器，仅分析阶段需要）---
CFR_JAR="$PROJ/tools/cfr-${CFR_VERSION}.jar"
if [[ ! -f "$CFR_JAR" ]]; then
  echo "[*] 下载 CFR ${CFR_VERSION} ..."
  curl -fsSL -o "$CFR_JAR" "https://repo1.maven.org/maven2/org/benf/cfr/${CFR_VERSION}/cfr-${CFR_VERSION}.jar"
fi
echo "[ok] CFR: $CFR_JAR"

# --- ASM（字节码补丁）---
for a in asm asm-commons asm-tree asm-util asm-analysis; do
  J="$PROJ/tools/${a}-${ASM_VERSION}.jar"
  if [[ ! -f "$J" ]]; then
    echo "[*] 下载 ${a} ${ASM_VERSION} ..."
    curl -fsSL -o "$J" "https://repo1.maven.org/maven2/org/ow2/asm/${a}/${ASM_VERSION}/${a}-${ASM_VERSION}.jar"
  fi
done
echo "[ok] ASM: $PROJ/tools/asm-*-${ASM_VERSION}.jar"

echo "== 工具准备完成 =="
