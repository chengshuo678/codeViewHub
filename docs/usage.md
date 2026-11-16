# 使用文档

本文档介绍 CodeReview Hub 的日常使用：Web 界面、Jenkins 集成与 REST API。

## 1. Web 界面

### 1.1 仪表盘（首页）

- 顶部 4 张统计卡：累计评审、累计发现问题、阻断问题、并发评审线程
- 任务列表：按时间倒序展示所有评审，支持按状态筛选、按仓库/分支/作者搜索
- 每行显示：仓库/分支、问题数分布（按严重级别着色）、状态、评分、提交时间
- 点击任意行进入**评审详情**
- 右上角「手动提交评审」：粘贴 git diff 输出即可发起一次评审（不依赖 Jenkins）

### 1.2 评审详情

评审详情是核心工作界面，左右分栏：

**左侧 — 评审发现**

- 每条发现包含：严重级别徽章（阻断/严重/主要/次要/提示）、命中规则、文件:行号、问题描述、修改建议
- **标记状态**：点击「确认」（真实问题）/「忽略」（不需修复）/「误报」，标记会持久化
- **备注**：每条发现下方的备注框，失焦自动保存，用于记录处理意见
- 顶部可按严重级别、处理状态过滤

**右侧 — 代码变更（diff）**

- 文件按变更分组展示，绿增红删，双行号对齐
- 有问题的新增行行尾显示红色标记（数量为该行问题数），点击标记跳转到左侧对应发现
- 点击左侧发现的「文件:行号」，diff 自动滚动到该行并高亮闪烁——**双向联动定位**
- 顶部显示质量评分环与 LLM 总结；右上角可导出 JSON、失败时可重试

**实时刷新**：评审进行中页面每 3 秒自动刷新，完成后停止。

### 1.3 评审规则

规则决定**哪些文件会被评审、以什么严格程度评审**：

- 文件模式支持 Ant 通配符（`**` 任意目录、`*` 文件名片段）、花括号语法（`**/*.{js,ts}`）、逗号分隔多模式
- 「给模型的补充提示」会注入评审提示词，引导模型聚焦该规则关注的代码模式
- 内置 14 条规则（空指针、SQL 注入、XSS、硬编码凭据等），可编辑、停用，不可删除；自定义规则可删除

### 1.4 评审技能

技能 = 评审智能体的**人格与系统提示词**（类似 Claude Skills 的角色定义）：

- 内置 3 个技能：通用代码评审（默认）、安全审计专家、性能优化专家
- 「设为默认」后，后续所有评审使用该技能的系统提示词
- 引擎会在技能提示词后自动追加当前启用的规则列表与输出格式约束，无需自己写

### 1.5 系统设置

- **大模型连接**：提供者（mock/openai）、Base URL、API Key、模型名、温度；「测试连接」一键验证
- **评审引擎**：Agent 最大轮次、单组最大 diff 行数、反思过滤开关、质量门禁阈值
- **安全**：接入密钥，配置后 Jenkins 端点必须携带 `X-API-Key`

所有设置保存后**立即生效**，无需重启。

## 2. Jenkins 集成

### 2.1 前提

1. 部署好 CodeReview Hub 并在「系统设置」接入大模型（或先用 Mock 演示）
2. 将本仓库的 `ci/submit-review.sh` 放入你的业务仓库（如 `ci/` 目录）
3. Jenkins 节点具备 `bash`、`git`、`curl`、`python3`

### 2.2 在流水线中添加 stage

```groovy
stage('AI Code Review') {
    steps {
        sh '''
        bash ci/submit-review.sh \
          --hub "$REVIEW_HUB" \
          --api-key "$REVIEW_API_KEY" \
          --repo "$JOB_BASE_NAME" \
          --base origin/master \
          --with-files
        '''
    }
}
```

完整示例见 [ci/Jenkinsfile.example](../ci/Jenkinsfile.example)（含凭据配置与纯 curl 备选方案）。

脚本参数：

| 参数 | 必填 | 说明 |
|------|------|------|
| `--hub` | 是 | 平台地址，如 `http://review-hub.internal:8080` |
| `--repo` | 是 | 仓库名（展示用） |
| `--base` | 是 | diff 基线，如 `origin/master` |
| `--api-key` | 否 | 平台配置了接入密钥时必填 |
| `--with-files` | 否 | 附带变更文件全文，显著提升评审质量（推荐开启） |
| `--timeout` | 否 | 最长等待秒数，默认 900 |
| `--author` | 否 | 默认取 `git config user.name` |

**脚本行为**：提交评审 → 轮询直到完成 → 打印摘要与详情链接 → 查询质量门禁。

**退出码**：`0` 门禁通过；`1` 门禁拦截（流水线失败，后续 stage 不再执行）；`2` 参数/环境错误。

### 2.3 质量门禁规则

- 评审发现中，**严重级别 ≥ 门禁阈值**（默认 CRITICAL）的问题会导致门禁不通过
- 阈值可在「系统设置 → 评审引擎」调整：BLOCKER（最宽松，仅阻断级拦截）/ CRITICAL（推荐）/ MAJOR / MINOR / INFO（最严格）
- 评审执行失败（如模型不可用）**不会**阻断流水线，仅在控制台提示——避免基础设施故障卡死业务发布
- 门禁只看评审结果本身；人工在 Web 上事后标记「误报」不会改变已构建流水线的结论，但为下次评审治理提供依据

### 2.4 回调通知（可选）

提交评审时传入 `callbackUrl`，评审完成后平台会 POST 以下 JSON 到该地址，便于对接企业 IM 机器人等：

```json
{ "id": 42, "status": "SUCCESS", "score": 82, "summary": "…", "findings": 5 }
```

## 3. REST API 参考

所有 API 均返回 JSON。`/api/jenkins/**` 在配置接入密钥后需携带 `X-API-Key` 请求头。

### 3.1 提交评审

```bash
curl -X POST http://localhost:8080/api/reviews \
  -H 'Content-Type: application/json' \
  -d '{
    "repoName": "order-service",
    "branch": "feature/notify",
    "commitId": "a1b2c3d",
    "author": "zhangsan",
    "diff": "<git diff 输出>",
    "files": { "src/A.java": "<完整文件内容，可选>" },
    "callbackUrl": "http://your-im-webhook（可选）"
  }'
```

返回 `{"id": 1, "status": "QUEUED"}`。`diff` 上限 4MB，`files` 序列化后上限 16MB。

### 3.2 任务与结果

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/reviews?status=&q=&page=0&size=15` | 分页列表（status 状态筛选，q 搜索仓库/分支/作者） |
| GET | `/api/reviews/{id}` | 详情：任务元数据 + diff 全文 + 全部发现 |
| PUT | `/api/reviews/{id}/findings/{fid}` | 更新发现：`{"status":"CONFIRMED","remark":"..."}`，status 可选 PENDING/CONFIRMED/IGNORED/FALSE_POSITIVE |
| POST | `/api/reviews/{id}/retry` | 失败重试 |
| GET | `/api/reviews/{id}/gate` | 质量门禁：`{"pass":true,"reason":"…","blockers":0,"criticals":0}` |
| GET | `/api/reviews/stats` | 仪表盘统计 |

### 3.3 Jenkins 专用端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/jenkins/submit` | 提交评审（source 强制为 JENKINS） |
| GET | `/api/jenkins/reviews/{id}` | 轻量轮询：状态/评分/摘要/问题计数 |
| GET | `/api/jenkins/reviews/{id}/gate` | 质量门禁 |

### 3.4 规则 / 技能 / 设置

| 方法 | 路径 | 说明 |
|------|------|------|
| GET / POST | `/api/rules` | 规则列表 / 新建（name、filePattern、severity、description、prompt、enabled） |
| PUT / DELETE | `/api/rules/{id}` | 编辑 / 删除（内置规则不可删除） |
| GET / POST | `/api/skills` | 技能列表 / 新建（name、description、systemPrompt、enabled） |
| PUT / DELETE | `/api/skills/{id}` | 编辑 / 删除（内置与默认技能不可删除） |
| PUT | `/api/skills/{id}/default` | 设为默认技能 |
| GET / PUT | `/api/settings` | 读取 / 更新设置（API Key 仅写不读） |
| POST | `/api/settings/test-llm` | 模型连通性测试 |
| DELETE | `/api/settings/hub-api-key`、`/api/settings/llm-api-key` | 清空密钥 |

## 4. 评审流程说明（了解引擎如何工作）

1. **提交**：diff（及可选文件内容包）入库，任务进入队列，立即返回任务 ID
2. **排队**：worker 线程按顺序消费（并发数 = `review.workers`）
3. **确定性预处理**：解析 diff → 过滤二进制/纯删除文件 → 规则路由（Ant 通配 + 花括号展开）→ 文件分组
4. **Agent 评审**：模型收到「技能提示词 + 命中规则 + 分组 diff」，可调用 `file_read` / `code_search` 工具读取文件内容与搜索代码（最多 `maxRounds` 轮），最终输出严格 JSON 的发现列表
5. **行号定位**：模型给出的行号被吸附到该文件真实的新增行上（避免行号漂移）
6. **反思过滤**（可关）：将全部发现连同 ±5 行代码上下文再交给模型核对，过滤臆测与重复项
7. **落库与通知**：发现持久化、统计回填、触发 callback（如有）

## 5. Mock 模式说明

默认提供者 `mock` 不依赖任何模型，用于：

- 部署后立即验证全链路（提交、队列、Agent 工具调用循环、行号定位、门禁、Web 页面）
- 演示与培训环境

Mock 会真实走完 Agent 工具调用循环并生成 2 条定位准确的示例发现（内容标注 [Mock]）。接入真实模型后，在「系统设置」切换提供者即可。
