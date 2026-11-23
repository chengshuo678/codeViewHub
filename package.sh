#!/bin/bash
# CodeReview Hub 部署包打包脚本（在有外网的构建机上运行）
# 产物: dist/code-review-hub-linux-aarch64.tar.gz
#   内含: code-review-hub.jar + lib/jdk(AArch64) + start.sh + stop.sh
#   目标: 麒麟 V10 等 aarch64 Linux，解压后 ./start.sh 即可运行，宿主机无需安装 Java
set -euo pipefail

cd "$(dirname "$0")"
APP_DIR="$(pwd)"
DIST="$APP_DIR/dist"
PKG_NAME="code-review-hub-linux-aarch64"

# ---- 探测 Maven 与 JDK17（用于编译构建；注意必须 17，Spring Boot 3 最低要求）----
MVN="${MVN:-}"
if [ -z "$MVN" ]; then
    if [ -x "$HOME/common/maven39/bin/mvn" ]; then MVN="$HOME/common/maven39/bin/mvn"
    elif command -v mvn >/dev/null 2>&1; then MVN="mvn"
    else echo "[错误] 未找到 mvn，请安装 Maven 或用 MVN=/path/to/mvn 指定" >&2; exit 1; fi
fi

PICKED_JDK=""
for d in "$HOME"/common/jdk-17*/*.jdk/Contents/Home "$HOME"/common/jdk-17*/ /usr/lib/jvm/*17*; do
    [ -x "$d/bin/java" ] && PICKED_JDK="$d" && break
done
# 环境变量 JAVA_HOME 仅当确实是 17 时才采用（避免误用低版本 JDK）
if [ -z "$PICKED_JDK" ] && [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    if "$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -q 'version "17'; then
        PICKED_JDK="$JAVA_HOME"
    fi
fi
[ -n "$PICKED_JDK" ] || { echo "[错误] 未找到 JDK17（用于编译），请设置 JAVA_HOME 指向 JDK 17" >&2; exit 1; }
export JAVA_HOME="$PICKED_JDK"
echo "JAVA_HOME=$JAVA_HOME"
echo "MVN      =$MVN"

# ---- 1. 构建 JAR ----
echo "==> [1/4] Maven 构建..."
"$MVN" -q package -DskipTests
[ -f "$APP_DIR/target/code-review-hub.jar" ] || { echo "[错误] 构建产物缺失" >&2; exit 1; }

# ---- 2. 检查 lib 下的 AArch64 JDK ----
JDK_DIR=""
for d in "$APP_DIR"/lib/jdk-*/; do
    [ -L "${d%/}" ] && continue    # 跳过软链（本地开发机辅助 JDK，不进部署包）
    [ -x "$d/bin/java" ] && JDK_DIR="${d%/}" && break
done
if [ -z "$JDK_DIR" ]; then
    echo "[错误] lib/ 下未找到 JDK。请先放置 Linux aarch64 版 JDK 17 并解压为 lib/jdk-*/" >&2
    echo "       下载: https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/linux/" >&2
    exit 1
fi
echo "使用 JDK: $JDK_DIR"

# ---- 3. 组装部署目录 ----
echo "==> [2/4] 组装部署目录..."
rm -rf "$DIST/$PKG_NAME"
mkdir -p "$DIST/$PKG_NAME/lib"
cp "$APP_DIR/target/code-review-hub.jar" "$DIST/$PKG_NAME/"
cp "$APP_DIR/start.sh" "$APP_DIR/stop.sh" "$DIST/$PKG_NAME/"
chmod +x "$DIST/$PKG_NAME/start.sh" "$DIST/$PKG_NAME/stop.sh"
cp -R "$JDK_DIR" "$DIST/$PKG_NAME/lib/"

# ---- 4. 打 tar 包 ----
echo "==> [3/4] 生成 tar.gz..."
tar -czf "$DIST/$PKG_NAME.tar.gz" -C "$DIST" "$PKG_NAME"

echo "==> [4/4] 完成"
echo "部署包: $DIST/$PKG_NAME.tar.gz ($(du -h "$DIST/$PKG_NAME.tar.gz" | cut -f1))"
echo ""
echo "内网部署（麒麟 V10 aarch64）:"
echo "  1) 拷贝 $PKG_NAME.tar.gz 到内网机器"
echo "  2) tar xzf $PKG_NAME.tar.gz && cd $PKG_NAME"
echo "  3) ./start.sh        # 启动（自动使用自带 JDK）"
echo "  4) ./stop.sh         # 停止"
echo "  端口/并发可通过环境变量覆盖: SERVER_PORT=9090 REVIEW_WORKERS=4 ./start.sh"
