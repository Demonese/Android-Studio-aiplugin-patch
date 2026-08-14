#!/usr/bin/env bash
# 00_setup_tools.sh — 准备构建工具：JDK 21、CFR 反编译器、ASM 字节码库
#
# 用法：
#   ./scripts/00_setup_tools.sh            # 下载缺失的工具 jar 并做 SHA-1 校验
#   ./scripts/00_setup_tools.sh --force    # 强制重新下载全部工具 jar
#   ./scripts/00_setup_tools.sh --check    # 只校验已有 jar，不下载
#
# 环境变量：
#   MAVEN_REPO_BASE  Maven 仓库根地址，默认 https://repo1.maven.org/maven2
#                    国内网络可换镜像，例如：
#                    MAVEN_REPO_BASE=https://maven.aliyun.com/repository/public ./scripts/00_setup_tools.sh
set -euo pipefail
PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$PROJ/config.env"

MODE="download"
case "${1:-}" in
  --force) MODE="force" ;;
  --check) MODE="check" ;;
  "") ;;
  *) echo "用法: $0 [--force|--check]"; exit 2 ;;
esac

MAVEN_REPO_BASE="${MAVEN_REPO_BASE:-https://repo1.maven.org/maven2}"
TOOLS="$PROJ/tools"
mkdir -p "$TOOLS"

command -v curl >/dev/null 2>&1 || { echo "[!] 未找到 curl，请先安装（sudo apt-get install -y curl）"; exit 1; }
command -v sha1sum >/dev/null 2>&1 || { echo "[!] 未找到 sha1sum（coreutils）"; exit 1; }

# --- JDK ---
if ! command -v javac >/dev/null 2>&1; then
  echo "[!] 未找到 javac。请安装 JDK 21，例如："
  echo "    sudo apt-get install -y openjdk-21-jdk-headless"
  exit 1
fi
echo "[ok] javac: $(javac -version 2>&1)"

sha1_of() { sha1sum "$1" | awk '{print $1}'; }

# fetch <目标文件> <仓库内路径> <官方 SHA-1>
# 已存在且校验通过则跳过；校验不符或 --force 则重新下载。
# 先下到 .part 临时文件，校验通过后才替换，避免半截文件。
fetch() {
  local dest="$1" path="$2" want="$3" name
  name="$(basename "$dest")"
  if [[ -f "$dest" && "$MODE" != "force" ]]; then
    if [[ "$(sha1_of "$dest")" == "$want" ]]; then
      echo "[ok] 已存在且校验通过: $name"
      return 0
    fi
    echo "[!] 已存在但 SHA-1 不符，重新下载: $name"
  fi
  if [[ "$MODE" == "check" ]]; then
    echo "[!] 缺失或校验失败: $name（运行 $0 下载）"
    exit 1
  fi
  local url="$MAVEN_REPO_BASE/$path" tmp="$dest.part"
  echo "[*] 下载 $url"
  if ! curl -fSL --retry 3 --connect-timeout 30 -o "$tmp" "$url"; then
    rm -f "$tmp"
    echo "[!] 下载失败: $url"
    echo "    如网络受限，可换镜像：MAVEN_REPO_BASE=https://maven.aliyun.com/repository/public $0"
    exit 1
  fi
  local got
  got="$(sha1_of "$tmp")"
  if [[ "$got" != "$want" ]]; then
    rm -f "$tmp"
    echo "[!] SHA-1 校验失败: $name"
    echo "    期望 $want"
    echo "    实际 $got"
    exit 1
  fi
  mv "$tmp" "$dest"
  echo "[ok] 下载并校验通过: $name"
}

# --- CFR（反编译器，仅分析阶段需要）---
# SHA-1 取自 Maven Central 官方 .sha1（2026-08 核对）
fetch "$TOOLS/cfr-${CFR_VERSION}.jar" \
  "org/benf/cfr/${CFR_VERSION}/cfr-${CFR_VERSION}.jar" \
  "48ef4892cfe8feffddbbd0ff077735140557db74"

# --- ASM（字节码补丁/校验）---
while read -r a sha; do
  fetch "$TOOLS/${a}-${ASM_VERSION}.jar" \
    "org/ow2/asm/${a}/${ASM_VERSION}/${a}-${ASM_VERSION}.jar" "$sha"
done <<EOF
asm          f0ed132a49244b042cd0e15702ab9f2ce3cc8436
asm-tree     3a53139787663b139de76b627fca0084ab60d32c
asm-util     9e23359b598ec6b74b23e53110dd5c577adf2243
asm-analysis f97a3b319f0ed6a8cd944dc79060d3912a28985f
asm-commons  406c6a2225cfe1819f102a161e54cc16a5c24f75
EOF

echo "== 工具准备完成（$TOOLS）=="
