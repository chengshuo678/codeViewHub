#!/bin/bash
# CodeReview Hub 停止脚本
set -uo pipefail

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
PID_FILE="$APP_HOME/app.pid"

if [ ! -f "$PID_FILE" ]; then
    echo "[提示] 未发现 PID 文件（$PID_FILE），服务可能未在运行"
    exit 0
fi

PID="$(cat "$PID_FILE")"
if ! kill -0 "$PID" 2>/dev/null; then
    echo "[提示] 进程 $PID 已不存在，清理 PID 文件"
    rm -f "$PID_FILE"
    exit 0
fi

echo "正在停止服务 (PID $PID)..."
kill "$PID" 2>/dev/null

# 优雅等待最多 15 秒
for i in $(seq 1 15); do
    kill -0 "$PID" 2>/dev/null || { echo "已停止"; rm -f "$PID_FILE"; exit 0; }
    sleep 1
done

echo "优雅停止超时，强制结束..."
kill -9 "$PID" 2>/dev/null
rm -f "$PID_FILE"
echo "已强制停止"
