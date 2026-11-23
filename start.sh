#!/bin/bash
# CodeReview Hub 启动脚本（Linux ARM 服务器使用自带 lib/jdk，其他宿主机自动回退本地 JDK）
# 用法: ./start.sh
# 可选环境变量: SERVER_PORT(默认8080)  REVIEW_WORKERS(默认2)  JAVA_OPTS(额外JVM参数)  JAVA_HOME(强制指定JDK17+)
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
JAR="$APP_HOME/code-review-hub.jar"
PID_FILE="$APP_HOME/app.pid"
LOG_DIR="$APP_HOME/logs"

# ---- 选择 Java 17+（按顺序探测，取第一个「能真正执行且版本≥17」的）----
#   1) JAVA_HOME/bin/java        —— 显式指定，最高优先
#   2) lib/jdk-*/bin/java        —— 部署包内置 JDK（Linux aarch64，内网 ARM 服务器直接可用；
#                                   非 Linux/ARM 宿主机探测失败自动跳过，如本地 macOS 开发机）
#   3) PATH 中的 java
#   4) macOS 标准安装位置（/Library 与 ~/Library 的 JavaVirtualMachines）
java_ok() {
    "$1" -version >/dev/null 2>&1 && \
    "$1" -version 2>&1 | head -1 | grep -qE 'version "(1[7-9]|[2-9][0-9])\.'
}
JAVA_BIN=""
try_java() {
    [ -n "$JAVA_BIN" ] && return 0
    [ -x "$1" ] && java_ok "$1" && JAVA_BIN="$1"
    return 0
}
if [ -n "${JAVA_HOME:-}" ]; then try_java "$JAVA_HOME/bin/java"; fi
for d in "$APP_HOME"/lib/jdk-*/; do try_java "${d}bin/java"; done
if command -v java >/dev/null 2>&1; then try_java "$(command -v java)"; fi
for d in /Library/Java/JavaVirtualMachines/*/Contents/Home \
         "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home; do try_java "$d/bin/java"; done

if [ -z "$JAVA_BIN" ]; then
    echo "[错误] 未找到可用的 Java 17+。" >&2
    echo "       部署包内置 JDK 为 Linux aarch64 版，仅适用于 Linux ARM 服务器（麒麟 V10 等）；" >&2
    echo "       当前宿主机无法执行它。请任选其一后重试：" >&2
    echo "       1) export JAVA_HOME=<本地 JDK 17 根目录>（其 bin/java 需版本 17+）" >&2
    echo "       2) 安装 JDK 17+ 到系统 PATH" >&2
    echo "       3) macOS：安装到 /Library/Java/JavaVirtualMachines/ 后重试" >&2
    exit 1
fi

[ -f "$JAR" ] || { echo "[错误] 未找到 $JAR" >&2; exit 1; }

# ---- 已在运行则退出 ----
if [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "[提示] 服务已在运行 (PID $(cat "$PID_FILE"))，如需重启请先执行 ./stop.sh"
    exit 0
fi

export SERVER_PORT="${SERVER_PORT:-8080}"
export REVIEW_WORKERS="${REVIEW_WORKERS:-2}"
MEM_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m}"

mkdir -p "$LOG_DIR" "$APP_HOME/data"
cd "$APP_HOME"

echo "JDK    : $JAVA_BIN"
echo "端口   : $SERVER_PORT   并发评审线程: $REVIEW_WORKERS"
echo "内存   : $MEM_OPTS"
echo "数据目录: $APP_HOME/data   日志: $LOG_DIR/app.log"

nohup "$JAVA_BIN" $MEM_OPTS -jar "$JAR" >> "$LOG_DIR/app.log" 2>&1 &
echo $! > "$PID_FILE"

# 等待进程确认存活（JVM 可能启动即失败）
sleep 2
if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "[错误] 进程启动失败，最近日志：" >&2
    tail -20 "$LOG_DIR/app.log" >&2
    rm -f "$PID_FILE"
    exit 1
fi

# 等待 HTTP 就绪（最多 60 秒）
echo -n "等待服务就绪"
for i in $(seq 1 30); do
    if curl -s -o /dev/null --max-time 2 "http://localhost:${SERVER_PORT}/"; then
        echo ""
        echo "已启动并就绪 (PID $(cat "$PID_FILE"))，访问 http://<本机IP>:${SERVER_PORT} （默认账号 admin/admin123）"
        exit 0
    fi
    echo -n "."
    sleep 2
done
echo ""
echo "已启动 (PID $(cat "$PID_FILE"))，但 60 秒内未就绪，请查看日志: $LOG_DIR/app.log"
