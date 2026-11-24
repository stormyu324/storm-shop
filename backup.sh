#!/usr/bin/env bash
# Storm 商城一键备份（macOS / Linux）：备份数据库和上传的图片，商城运行中也可以备份
# 用法：./backup.sh          macOS 也可以直接双击 backup.command
# 备份保存在 backups 文件夹，默认保留最近 30 份；可在 config.env 里用 BACKUP_DIR、BACKUP_KEEP 修改

cd "$(dirname "$0")" || exit 1

# ---------- 找到 Java 21 或更高版本（和 start.sh 相同） ----------
java_major() {
  local v
  v="$("$1" -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')"
  v="${v%%.*}"
  case "$v" in ''|*[!0-9]*) echo 0 ;; *) echo "$v" ;; esac
}

JAVA_OK=""
candidates=()
[ -n "$JAVA_HOME" ] && candidates+=("$JAVA_HOME")
if [ -x /usr/libexec/java_home ]; then
  # macOS：即使默认 Java 是旧版本，也能找到已安装的 21+
  jh="$(/usr/libexec/java_home -v 21+ 2>/dev/null)" && candidates+=("$jh")
fi
for home in "${candidates[@]}"; do
  if [ -x "$home/bin/java" ] && [ "$(java_major "$home/bin/java")" -ge 21 ]; then
    export JAVA_HOME="$home"
    JAVA_OK="$home/bin/java"
    break
  fi
done
if [ -z "$JAVA_OK" ] && command -v java >/dev/null 2>&1 && [ "$(java_major java)" -ge 21 ]; then
  unset JAVA_HOME   # 用 PATH 里的 java，避免指向旧版本的 JAVA_HOME 干扰
  JAVA_OK="java"
fi
if [ -z "$JAVA_OK" ]; then
  echo
  echo "没有找到 Java 21 或更高版本，请先安装："
  if [ "$(uname)" = "Darwin" ]; then
    echo "  方式一（已装 Homebrew）：brew install openjdk@21"
    echo "  方式二：到 https://adoptium.net 下载 Temurin 21（选 macOS .pkg），双击安装"
  else
    echo "  Ubuntu / Debian：sudo apt install openjdk-21-jdk"
    echo "  其他系统：到 https://adoptium.net 下载 Temurin 21"
  fi
  echo "安装后重新运行本脚本。"
  exit 1
fi

"$JAVA_OK" tools/Backup.java
