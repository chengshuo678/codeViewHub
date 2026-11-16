# CodeReview Hub — AI 代码评审平台

基于 [open-code-review](https://github.com/alibaba/open-code-review)（阿里巴巴开源）的设计理念，用 **Java** 重新实现的服务端代码评审平台。

核心思路与 open-code-review 一致——**确定性流程 + LLM 智能体** 的混合架构：

- **确定性部分**（代码负责，保证可靠）：diff 解析、文件筛选、规则路由、文件分组、行号定位、反思上下文提取
- **智能体部分**（大模型负责，负责判断）：通过 `file_read` / `code_search` 工具主动补齐上下文，输出结构化评审发现，再经**反思过滤**去除误报

```
┌──────────────┐  diff + 文件内容包   ┌────────────────────────────────────┐
│   Jenkins     │ ─────────────────▶ │          CodeReview Hub            │
│   (stage)     │ ◀── 轮询/门禁 ──── │   Spring Boot 3 · 单 JAR · H2      │
└──────────────┘                     │                                    │
                                     │  提交 ──▶ 异步队列 ──▶ 评审引擎      │
┌──────────────┐                     │            │                        │
│  Web 浏览器   │ ─────────────────▶ │   ┌────────┴─────────┐              │
│ 配置/结果/备注 │                    │   │ DiffParser 解析    │              │
└──────────────┘                     │   │ RuleMatcher 路由   │              │
                                     │   │ Agent 循环(工具调用)│              │
                                     │   │ 行号定位 → 反思过滤 │              │
                                     │   └────────┬─────────┘              │
                                     │     OpenAI 兼容私有大模型             │
                                     │     (vLLM / Ollama / OneAPI…)      │
                                     └────────────────────────────────────┘
```

## 功能特性

| 特性 | 说明 |
|------|------|
| 服务端部署 | Java 17 + Spring Boot 3 单 JAR，内嵌 H2 数据库，零外部依赖 |
| Jenkins 集成 | 提供专用 API 与 `ci/submit-review.sh`，可作为流水线 stage，支持质量门禁（按严重级别决定是否放行） |
| 私有大模型 | 兼容 OpenAI Chat Completions 协议（vLLM / Ollama / OneAPI / DashScope 兼容模式等），Web 页面在线配置与连通性测试 |
| 异步排队评审 | 内存队列 + 可配置并发 worker，提交即返回，服务重启自动恢复未完成任务，支持 callback 回调 |
| 规则/技能 Web 配置 | 14 条内置规则（Ant 通配符 + 花括号语法）、3 个内置技能（系统提示词人格），全部可编辑/停用/新建 |
| 结果 Web 管理 | 美观的仪表盘与评审详情页；发现问题可标记 **确认 / 忽略 / 误报** 并添加备注；diff 与问题行双向联动定位 |
| 开箱即用 | 内置 Mock 模型提供者，不接模型也能完整体验评审流程 |

## 快速开始

### 1. 构建（或直接使用已发布 JAR）

```bash
# 需要 JDK 17+ 与 Maven 3.6.3+
mvn package -DskipTests
# 产物: target/code-review-hub.jar
```

### 2. 启动

```bash
java -jar target/code-review-hub.jar
# 或指定并发评审线程数
REVIEW_WORKERS=4 java -jar code-review-hub.jar
```

### 3. 访问

浏览器打开 <http://localhost:8080>

- 首次启动自带 **3 个技能、14 条规则**，模型提供者默认为 **Mock**（无需配置即可体验）
- 接入真实私有大模型：**系统设置 → 大模型连接**，填入 Base URL 与模型名，点「测试连接」

Docker 部署：

```bash
docker compose up -d
```

## 文档索引

| 文档 | 内容 |
|------|------|
| [部署文档](docs/deployment.md) | 环境要求、JAR/Docker/systemd 部署、全部配置项、私有大模型接入、备份与升级 |
| [使用文档](docs/usage.md) | Web 页面使用、Jenkins 集成完整示例、REST API 参考、质量门禁规则 |

## 目录结构

```
code-review-hub/
├── ci/
│   ├── submit-review.sh        # CI 提交脚本（Jenkins stage 调用）
│   └── Jenkinsfile.example     # Jenkinsfile 集成示例
├── docs/
│   ├── deployment.md           # 部署文档
│   └── usage.md                # 使用文档
├── src/main/java/com/reviewhub/
│   ├── api/                    # REST 控制器（评审/规则/技能/设置/Jenkins）
│   ├── boot/                   # 种子数据
│   ├── domain/                 # 实体与枚举
│   ├── engine/                 # 评审引擎核心
│   │   ├── diff/               # 统一 diff 解析（确定性）
│   │   ├── llm/                # LLM 客户端（OpenAI 兼容 + Mock）
│   │   ├── ReviewEngine.java   # Agent 循环 / 行号定位 / 反思过滤
│   │   ├── RuleMatcher.java    # 规则路由（Ant 通配 + 花括号展开）
│   │   └── ReviewQueueService.java  # 异步队列与回调
│   ├── repo/                   # Spring Data JPA
│   └── service/                # 设置服务
├── src/main/resources/static/  # Web 前端（Vue 3 本地化，无构建步骤）
├── src/test/                   # 单元测试（DiffParser / RuleMatcher）
├── Dockerfile
└── docker-compose.yml
```

## 与 open-code-review 的关系

open-code-review 是 Go 编写的 CLI 工具，在终端/CI 中就近执行评审。本项目借鉴其核心架构思想（混合评审管线、行号定位、反思过滤），将其改造为**中心化的 Java 服务端平台**，补齐了它不具备的能力：Web 配置界面、评审结果数据库与状态管理、异步排队、多项目集中接入。两者定位不同，可以互补。

## License

Apache-2.0（与 open-code-review 保持一致）
