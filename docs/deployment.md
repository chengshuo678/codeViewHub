# 部署文档

本文档介绍 CodeReview Hub 的完整部署流程。工具为**单 JAR + 内嵌 H2 数据库**架构，无外部依赖，5 分钟即可完成部署。

## 1. 环境要求

| 组件 | 要求 |
|------|------|
| JDK | 17 或 21（运行 JAR） |
| Maven | 3.6.3+（仅构建时需要） |
| 内存 | 建议 ≥ 2GB（评审大 diff 时 LLM 交互占用） |
| 磁盘 | 1GB+（JAR 约 50MB，H2 数据库随评审量增长） |
| 网络 | 服务需能访问私有大模型端点；Jenkins/浏览器需能访问本服务 |

若使用 Docker 部署，只需要 Docker 20+，无需 JDK。

## 2. 方式一：JAR 部署（推荐用于虚机）

### 2.1 构建

```bash
git clone <本仓库>
cd code-review-hub
mvn package -DskipTests
# 产物: target/code-review-hub.jar
```

> 内网无 Maven 中央仓库权限时，可配置内部 Nexus 镜像，或在有网的机器构建后拷贝 JAR。

### 2.2 启动

```bash
# 最简启动（默认端口 8080，数据存于 ./data）
java -jar code-review-hub.jar

# 生产建议：指定内存、端口与并发数
java -Xms512m -Xmx2g \
     -Dserver.port=8080 \
     -jar code-review-hub.jar
```

环境变量方式（Spring Boot 宽松绑定）：

```bash
SERVER_PORT=9090 \
REVIEW_WORKERS=4 \
java -jar code-review-hub.jar
```

### 2.3 首次登录与账号安全

服务首次启动时会自动创建默认管理员（仅当用户表为空时）：

- 用户名 `admin`，初始密码 `admin123`，角色 ADMIN
- 启动日志会提示：`已创建默认管理员账号 admin（初始密码 admin123，请尽快修改）`

**上线前必做**：用该账号登录后立即「修改密码」。密码以 **MD5 密文**存储（`app_user.password` 列），数据库与界面中均无明文。会话 token 有效期 24 小时（滑动续期）；重置密码 / 禁用 / 降权会使目标用户全部会话立即失效。

### 2.4 systemd 托管（生产）

`/etc/systemd/system/code-review-hub.service`：

```ini
[Unit]
Description=CodeReview Hub
After=network.target

[Service]
User=apps
WorkingDirectory=/opt/review-hub
ExecStart=/usr/bin/java -Xms512m -Xmx2g -jar code-review-hub.jar
Restart=always
RestartSec=5
Environment=REVIEW_WORKERS=4

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now code-review-hub
sudo systemctl status code-review-hub      # 查看状态
journalctl -u code-review-hub -f           # 查看日志
```

## 3. 方式二：Docker 部署

```bash
cd code-review-hub
docker compose up -d          # 构建并启动，数据持久化在 ./data
docker compose logs -f        # 查看日志
```

或直接 docker run：

```bash
docker build -t code-review-hub:1.0.0 .
docker run -d --name review-hub \
  -p 8080:8080 \
  -v /opt/review-hub/data:/app/data \
  -e REVIEW_WORKERS=4 \
  --restart unless-stopped \
  code-review-hub:1.0.0
```

### 3.1 内网离线部署（无外网环境）

内网机器无法访问 Docker Hub 与 Maven 中央仓库，采用「**外网构建 → 导出镜像 tar → 拷入内网 → 导入运行**」。

#### 第一步：在外网机器构建并导出镜像

任意一台有外网的 Mac / Linux / Windows（装有 Docker）：

```bash
git clone https://github.com/chengshuo678/codeViewHub.git
cd codeViewHub/code-review-hub

# 内网是 x86_64 服务器（绝大多数情况）
docker build -t code-review-hub:1.0.0 .

# 若内网是 ARM 服务器（鲲鹏/飞腾等信创机器），在 x86 构建机上改用：
# docker build --platform linux/arm64 -t code-review-hub:1.0.0 .

# 导出为 tar 文件（约 250MB）
docker save -o code-review-hub.tar code-review-hub:1.0.0
```

> 用 U 盘 / scp / 内部文件服务把 `code-review-hub.tar` 拷贝到内网 Linux 机器。

#### 第二步：内网机器离线安装 Docker Engine

内网机器**只需安装 Docker**（JDK、数据库均已打包在镜像内）。两种方式任选：

**方式 A：官方静态二进制包（推荐，零依赖）**

在外网下载 `https://download.docker.com/linux/static/stable/x86_64/docker-27.3.1.tgz`（ARM 机器选 `aarch64/` 目录），拷入内网后：

```bash
tar xzf docker-27.3.1.tgz
sudo cp docker/* /usr/local/bin/

# 注册 systemd 服务
sudo tee /etc/systemd/system/docker.service >/dev/null <<'EOF'
[Unit]
Description=Docker Application Container Engine
After=network-online.target

[Service]
ExecStart=/usr/local/bin/dockerd
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now docker
docker --version   # 出现版本号即成功
```

**方式 B：CentOS / RHEL rpm 包**

在外网 `https://download.docker.com/linux/centos/` 对应版本目录下载 5 个包后拷入内网：

```bash
sudo yum localinstall -y docker-ce-*.rpm docker-ce-cli-*.rpm \
    containerd.io-*.rpm docker-buildx-plugin-*.rpm docker-compose-plugin-*.rpm
sudo systemctl enable --now docker
```

> 离线导入运行不依赖任何镜像仓库与外部网络；若内网有私有仓库（Harbor 等），另行配置 `/etc/docker/daemon.json` 的 `registry-mirrors`。

#### 第三步：导入镜像并启动

```bash
# 导入镜像
docker load -i code-review-hub.tar

# 准备数据目录并启动
sudo mkdir -p /data/code-review-hub
docker run -d --name code-review-hub \
  --restart unless-stopped \
  -p 8080:8080 \
  -v /data/code-review-hub:/app/data \
  -e REVIEW_WORKERS=2 \
  code-review-hub:1.0.0

# 验证
docker ps                       # STATUS 应为 Up
curl http://localhost:8080/     # 返回 HTML 即成功
```

防火墙放行端口（按需）：

```bash
sudo firewall-cmd --permanent --add-port=8080/tcp && sudo firewall-cmd --reload
```

#### 第四步：访问与后续运维

- 浏览器打开 `http://<内网IP>:8080`，用 admin / admin123 登录后立即修改密码
- 私有大模型：Web「系统设置 → 大模型连接」填**内网模型地址**（如 `http://10.x.x.x:8000/v1`，兼容 vLLM / Ollama / OneAPI）
- 看日志：`docker logs -f code-review-hub`
- **升级版本**：外网重新 build + save → 内网 `docker load` → `docker rm -f code-review-hub` → 用新镜像 tag 重跑第三步命令；保留 `/data/code-review-hub` 目录即保留全部评审数据
- **备份**：只需备份 `/data/code-review-hub` 目录（H2 数据库文件）

## 4. 方式三：自带 JDK 部署包（内网无 Docker，适用麒麟 V10 aarch64）

针对内网 Linux（尤其信创环境，如银河麒麟 ky10 aarch64）提供**免安装 Java、免 Docker** 的部署包：aarch64 版 JDK 17 与应用 JAR 一起打包，解压即用。

### 4.1 构建部署包（外网机器执行）

项目已内置 `package.sh`、`start.sh`、`stop.sh` 与 aarch64 JDK（`lib/jdk-17.0.21+9`，Temurin Linux aarch64 版）：

```bash
./package.sh
# 产物: dist/code-review-hub-linux-aarch64.tar.gz（约 240MB）
```

若 lib/ 下没有 JDK，从镜像下载解压后再打包：

```bash
curl -LO https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/aarch64/linux/OpenJDK17U-jdk_aarch64_linux_hotspot_17.0.21_9.tar.gz
mkdir -p lib && tar xzf OpenJDK17U-*.tar.gz -C lib/
```

> x86_64 内网机器同理：下载 `x86_64/linux/` 下的 JDK 解压到 lib/，重新执行 `./package.sh`。

### 4.2 内网机器部署

```bash
tar xzf code-review-hub-linux-aarch64.tar.gz
cd code-review-hub-linux-aarch64

./start.sh     # 启动：自动探测 lib/jdk-*，无需安装 Java；脚本会等待 HTTP 就绪
./stop.sh      # 优雅停止（15 秒超时后强制结束）
```

| 常用操作 | 命令 |
|----------|------|
| 覆盖端口/并发 | `SERVER_PORT=9090 REVIEW_WORKERS=4 ./start.sh` |
| 调整 JVM 内存 | `JAVA_OPTS="-Xms1g -Xmx2g" ./start.sh` |
| 查看日志 | `tail -f logs/app.log` |
| 重启 | `./stop.sh && ./start.sh` |

- 首次启动自动创建 admin/admin123，登录后立即修改密码
- 目录说明：`data/`（H2 数据库，升级保留即数据不丢）、`logs/app.log`（运行日志）、`app.pid`（进程号）
- 升级版本：用新包中的 `code-review-hub.jar` 替换旧文件后重启即可

## 5. 配置项说明

### 5.1 启动配置（application.yml / 环境变量，修改后需重启）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| `server.port` | `SERVER_PORT` | 8080 | HTTP 端口 |
| `review.workers` | `REVIEW_WORKERS` | 2 | 并发评审线程数，即同时评审的任务数 |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/review-hub` | H2 数据库文件位置 |

### 5.2 运行时配置（Web「系统设置」页，保存即生效，无需重启）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `llm.provider` | mock | `mock` 演示模式 / `openai` 真实模型 |
| `llm.baseUrl` | （空） | OpenAI 兼容端点，如 `http://10.0.0.5:8000/v1` |
| `llm.apiKey` | （空） | 模型密钥（多数私有部署无需填写） |
| `llm.model` | （空） | 模型名，如 `Qwen2.5-72B-Instruct` |
| `llm.temperature` | 0.1 | 采样温度，评审建议低温 |
| `review.maxRounds` | 6 | Agent 工具调用最大轮次 |
| `review.groupMaxLines` | 600 | 单组文件 diff 行数上限（超出自动分组） |
| `review.reflection` | true | 评审后反思过滤（二次核对去除误报，消耗额外 token） |
| `review.gateSeverity` | CRITICAL | 质量门禁阈值：该级别及以上问题将阻断流水线 |
| `security.apiKey` | （空） | Jenkins 接入密钥，配置后 `/api/jenkins/**` 必须携带 `X-API-Key` 请求头 |

## 6. 接入私有大模型

平台兼容一切 **OpenAI Chat Completions** 协议端点。常见私有大模型部署方式的接入示例：

| 部署方式 | Base URL 示例 | 说明 |
|----------|---------------|------|
| vLLM | `http://<host>:8000/v1` | 启动需开启 `--enable-auto-tool-choice --tool-call-parser hermes`（按模型选择 parser）以支持工具调用 |
| Ollama | `http://<host>:11434/v1` | 需 `OLLAMA_HOST=0.0.0.0` 允许外部访问 |
| OneAPI / NewAPI 网关 | `http://<host>:3000/v1` | 填写网关分发的令牌作为 API Key |
| DashScope 兼容模式 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 公有云按量付费方案 |

**操作步骤**：Web「系统设置 → 大模型连接」→ 提供者选 `openai` → 填 Base URL / 模型名 / API Key → 点击「测试连接」，返回延迟与模型回复即接入成功。

> 模型能力建议：优先选择支持 **Function Calling（工具调用）** 的模型（如 Qwen2.5-Instruct 系列），可获得完整 Agent 评审能力；不支持工具调用的模型会自动降级为纯 diff 评审模式，依然可用。

## 7. 数据存储与备份

- 数据保存在 H2 文件数据库：`<工作目录>/data/review-hub.mv.db`
- **备份**：停止服务后拷贝整个 `data/` 目录即可（或使用 H2 的 `BACKUP TO 'xx.zip'` SQL）
- **恢复**：将备份的 `data/` 目录放回工作目录后启动
- 容量预估：单次评审（含 diff 与文件包）约占 10KB~2MB，10 万次评审约 20~50GB，超大规模时联系管理员归档历史任务

## 8. 升级

1. 备份 `data/` 目录
2. 替换 JAR（或重新 `docker compose build`）
3. 重启服务——Hibernate 自动执行 schema 升级，内置种子数据仅在缺失时补插，**不会覆盖**你在 Web 页面上的任何配置修改

## 9. 常见问题

**Q: 启动报端口占用？**
`SERVER_PORT=9090 java -jar code-review-hub.jar` 换端口。

**Q: 评审一直失败，提示模型连接错误？**
「系统设置 → 测试连接」排查网络与凭据；确认 baseUrl 填到 `/v1`；容器内部访问宿主机模型时注意使用宿主机真实 IP 而非 `localhost`。

**Q: 评审很慢？**
1) 降低 `review.maxRounds`；2) 关闭 `review.reflection`；3) 增大 `REVIEW_WORKERS` 提高并发；4) 检查模型服务负载。

**Q: 忘记了接入密钥？**
数据库 `app_setting` 表中 `security.apiKey` 即为密钥；或清空该行即关闭鉴权。

**Q: 忘记了管理员密码？**
两种恢复方式：
1. 用另一个管理员账号登录，在「用户管理」中重置该账号密码
2. 停止服务，用 H2 控制台（或直接改库）将 `app_user` 表中对应用户的 `password` 更新为新密码的 MD5 值（32 位小写十六进制），例如 `echo -n "newpass123" | md5`（macOS：`md5 -qs "newpass123"`），重启后用新密码登录

**Q: 如何查看数据库？**
启动后访问 `http://<host>:8080/h2-console`，JDBC URL 填 `jdbc:h2:file:./data/review-hub`，用户名 `sa`，密码空。
