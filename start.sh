#!/bin/bash
# CodeReview Hub 启动脚本（使用自带 lib/jdk，宿主机无需安装 Java）
# 用法: ./start.sh
# 可选环境变量: SERVER_PORT(默认8080)  REVIEW_WORKERS(默认2)  JAVA_OPTS(额外JVM参数)
set -euo pipefail

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
JAR="$APP_HOME/code-review-hub.jar"
PID_FILE="$APP_HOME/app.pid"
LOG_DIR="$APP_HOME/logs"

# ---- 自动探测 lib 下的 JDK（取字典序第一个 jdk-* 目录）----
JAVA_BIN=""
for d in "$APP_HOME"/lib/jdk-*/; do
    [ -x "$d/bin/java" ] && JAVA_BIN="$d/bin/java" && break
done
if [ -z "$JAVA_BIN" ]; then
    echo "[错误] 未在 lib/ 下找到 JDK（期望 lib/jdk-*/bin/java）" >&2
    echo "       请确认部署包完整，或手动放置 aarch64 版 JDK 17 到 lib/ 目录" >&2
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
