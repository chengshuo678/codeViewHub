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

## 4. 配置项说明

### 4.1 启动配置（application.yml / 环境变量，修改后需重启）

| 配置项 | 环境变量 | 默认值 | 说明 |
|--------|----------|--------|------|
| `server.port` | `SERVER_PORT` | 8080 | HTTP 端口 |
| `review.workers` | `REVIEW_WORKERS` | 2 | 并发评审线程数，即同时评审的任务数 |
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/review-hub` | H2 数据库文件位置 |

### 4.2 运行时配置（Web「系统设置」页，保存即生效，无需重启）

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

## 5. 接入私有大模型

平台兼容一切 **OpenAI Chat Completions** 协议端点。常见私有大模型部署方式的接入示例：

| 部署方式 | Base URL 示例 | 说明 |
|----------|---------------|------|
| vLLM | `http://<host>:8000/v1` | 启动需开启 `--enable-auto-tool-choice --tool-call-parser hermes`（按模型选择 parser）以支持工具调用 |
| Ollama | `http://<host>:11434/v1` | 需 `OLLAMA_HOST=0.0.0.0` 允许外部访问 |
| OneAPI / NewAPI 网关 | `http://<host>:3000/v1` | 填写网关分发的令牌作为 API Key |
| DashScope 兼容模式 | `https://dashscope.aliyuncs.com/compatible-mode/v1` | 公有云按量付费方案 |

**操作步骤**：Web「系统设置 → 大模型连接」→ 提供者选 `openai` → 填 Base URL / 模型名 / API Key → 点击「测试连接」，返回延迟与模型回复即接入成功。

> 模型能力建议：优先选择支持 **Function Calling（工具调用）** 的模型（如 Qwen2.5-Instruct 系列），可获得完整 Agent 评审能力；不支持工具调用的模型会自动降级为纯 diff 评审模式，依然可用。

## 6. 数据存储与备份

- 数据保存在 H2 文件数据库：`<工作目录>/data/review-hub.mv.db`
- **备份**：停止服务后拷贝整个 `data/` 目录即可（或使用 H2 的 `BACKUP TO 'xx.zip'` SQL）
- **恢复**：将备份的 `data/` 目录放回工作目录后启动
- 容量预估：单次评审（含 diff 与文件包）约占 10KB~2MB，10 万次评审约 20~50GB，超大规模时联系管理员归档历史任务

## 7. 升级

1. 备份 `data/` 目录
2. 替换 JAR（或重新 `docker compose build`）
3. 重启服务——Hibernate 自动执行 schema 升级，内置种子数据仅在缺失时补插，**不会覆盖**你在 Web 页面上的任何配置修改

## 8. 常见问题

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
