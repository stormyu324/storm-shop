#!/usr/bin/env bash
# Storm 商城一键启动（macOS / Linux）
# 用法：./start.sh          macOS 也可以直接双击 start.command
# 配置：把 config.example.env 复制为 config.env，在里面填写密码、API 密钥等

cd "$(dirname "$0")" || exit 1

# ---------- 1. 读取 config.env（已在环境里设置的变量优先） ----------
if [ -f config.env ]; then
  echo "读取配置：config.env"
  while IFS='=' read -r key value || [ -n "$key" ]; do
    key="$(printf '%s' "$key" | tr -d '[:space:]')"
    value="${value%$'\r'}"
    case "$key" in ''|\#*) continue ;; esac
    case "$key" in *[!A-Za-z0-9_]*) echo "  忽略无效的配置行：$key"; continue ;; esac
    # 去掉值两边的引号
    case "$value" in \"*\") value="${value#\"}"; value="${value%\"}" ;; \'*\') value="${value#\'}"; value="${value%\'}" ;; esac
    eval "already=\${$key+x}"   # key 已校验只含字母数字下划线
    if [ -z "$already" ]; then
      export "$key=$value"
    fi
  done < config.env
fi

# ---------- 2. 找到 Java 21 或更高版本 ----------
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
echo "使用 Java $(java_major "$JAVA_OK")"

# ---------- 3. 检查端口 ----------
PORT="${PORT:-8080}"
export PORT
URL="http://localhost:$PORT/"
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  echo
  echo "端口 $PORT 已被占用：可能商城已经在运行（浏览器打开 $URL 看看），"
  echo "或者被别的程序占用。可以在 config.env 里设置 PORT=8081 换一个端口。"
  exit 1
fi

# ---------- 4. 启动成功后自动打开浏览器 ----------
open_browser() {
  for _ in $(seq 1 600); do
    sleep 1
    if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
      sleep 1
      echo
      echo ">>> 商城已启动：$URL    管理后台：${URL}admin"
      echo ">>> 按 Ctrl + C 停止"
      echo
      if command -v open >/dev/null 2>&1 && [ "$(uname)" = "Darwin" ]; then
        open "$URL"
      elif command -v xdg-open >/dev/null 2>&1; then
        xdg-open "$URL" >/dev/null 2>&1
      fi
      return
    fi
  done
}
if [ "${NO_BROWSER:-}" != "1" ]; then
  open_browser &
fi

# ---------- 5. 启动 ----------
chmod +x mvnw 2>/dev/null
echo "正在启动……第一次运行需要下载依赖，可能要几分钟，请耐心等待。"
exec ./mvnw spring-boot:run
