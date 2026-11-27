# 使用文档

本文档介绍 CodeReview Hub 的日常使用：登录与角色、Web 界面、Jenkins 集成与 REST API。

## 1. 登录与角色

### 1.1 登录

打开平台首页即进入登录页。首次部署的默认管理员账号：

| 用户名 | 初始密码 | 角色 |
|--------|----------|------|
| `admin` | `admin123` | 管理员 |

> **请登录后立即修改密码**（侧边栏底部头像 → 修改密码）。密码以 **MD5 密文**存储在数据库中，明文不再出现在任何存储中。

会话有效期 24 小时（滑动续期，活跃使用不中断）。会话失效（过期、密码被重置、账号被禁用或降权）后，页面会在**下一次请求或切回浏览器标签时自动跳转到登录页**（顶部提示「登录已过期」），重新登录后自动回到之前浏览的页面。修改自己的密码时当前设备保持登录、其他设备自动下线。

### 1.2 角色与权限矩阵

| 功能 | 管理员 ADMIN | 普通用户 USER |
|------|:---:|:---:|
| 查看仪表盘 / 评审列表 / 评审详情 | ✓ | ✓ |
| 标记问题状态（确认/忽略/误报）与备注 | ✓ | ✓ |
| 手动提交评审 | ✓ | ✓ |
| 评审规则管理 | ✓ | — |
| 评审技能管理 | ✓ | — |
| 用户管理（新建/编辑/重置密码/禁用/删除） | ✓ | — |
| 系统设置（模型、引擎、安全） | ✓ | — |

普通用户访问管理页面时会被引导至「无访问权限」提示页；对应 API 返回 403。

### 1.3 用户管理（仅管理员）

侧边栏「用户管理」页面：

- **新建用户**：指定用户名、初始密码（6-100 位）、显示名与角色
- **编辑**：修改显示名、角色、启用状态；降权或禁用后该用户被强制下线
- **重置密码**：为任意用户设置新密码，重置后其全部会话失效
- **删除**：不可删除当前登录账号；系统至少保留一个可用的管理员账号
- 密码全部以 MD5 密文落库，界面上任何位置不展示、不回显密码

### 1.4 修改自己的密码

侧边栏底部点击头像 → 「修改密码」，输入原密码与新密码（6-100 位）即可；修改成功后当前会话保持，其他设备上的会话失效。

## 2. Web 界面

### 2.1 仪表盘（首页）

- 顶部 4 张统计卡：累计评审、累计发现问题、阻断问题、并发评审线程
- 任务列表：按时间倒序展示所有评审，支持按状态筛选、按仓库/分支/作者搜索
- 每行显示：仓库/分支、问题数分布（按严重级别着色）、状态、评分、提交时间
- 点击任意行进入**评审详情**
- 右上角「手动提交评审」：粘贴 git diff 输出即可发起一次评审（不依赖 Jenkins）；可同时填写**仓库地址**（供深度扫描克隆全仓代码）、**需求文档链接**与**设计文档链接**（每类每行一个、最多 5 个，评审时平台自动抓取 wiki 内容注入评审上下文；链接抓取失败时可用表单中的「手动粘贴文档内容」兜底）

### 2.2 评审详情

评审详情是核心工作界面，左右分栏：

**左侧 — 评审发现**

- 每条发现包含：严重级别徽章（阻断/严重/主要/次要/提示）、命中规则、文件:行号、问题描述、修改建议
- **标记状态**：点击「确认」（真实问题）/「忽略」（不需修复）/「误报」，标记会持久化
- **备注**：每条发现下方的备注框，失焦自动保存，用于记录处理意见
- 顶部可按严重级别、处理状态过滤

**右侧 — 代码变更（diff）**

- 支持两种查看模式（右上角切换，选择自动记忆）：**并排对比**（左栏旧代码、右栏新代码，删除行红色显示在左栏、新增行绿色显示在右栏）与**逐行对比**（单栏依次展示增删行）
- 文件按变更分组展示，绿增红删，双行号对齐
- 有问题的新增行行尾显示红色标记（数量为该行问题数），点击标记跳转到左侧对应发现
- 点击左侧发现的「文件:行号」，diff 自动滚动到该行并高亮闪烁——**双向联动定位**
- 顶部显示质量评分环与 LLM 总结；右上角可导出 JSON、失败时可重试

**参考文档区**：提交时携带了需求/设计文档链接的任务，详情页头部下方会展示「参考文档」区——每个链接的抓取状态（已注入字数 / 抓取失败原因）一目了然，链接可直接点击跳回 wiki。

**影响分析（深度扫描）**：评审完成后，详情页右上角出现【深度扫描】按钮：

- 点击后平台用**内置纯 Java Git 客户端**克隆该分支仓库的全部代码（无需平台安装 git）
- 从 diff 提取变更符号（类/方法/函数/常量），在全仓做**反向引用查找**（谁引用了被改的符号）
- 结合引用证据由大模型产出**影响报告**：受影响模块（文件 + 风险级别 + 原因）、隐藏风险、回归测试建议
- 「静态引用命中」可展开查看每个受影响文件引用了哪些符号、多少处命中
- 任务未提供仓库地址时，点击按钮会弹出补填窗口（支持 http(s) Git 地址或 `file://` 本地路径）；http 仓库的账号/访问令牌在「系统设置 → 仓库访问」统一配置
- 扫描期间页面每 3 秒自动刷新；报告完成后可随时点击「重新深度扫描」重跑

**左侧导航栏**：点击顶部图标按钮可收起导航栏（仅保留图标），为读代码腾出更大空间，状态自动记忆。

**实时刷新**：评审进行中、深度扫描进行中时页面每 3 秒自动刷新，完成后停止。

### 2.3 评审规则

规则决定**哪些文件会被评审、以什么严格程度评审**：

- 文件模式支持 Ant 通配符（`**` 任意目录、`*` 文件名片段）、花括号语法（`**/*.{js,ts}`）、逗号分隔多模式
- 「给模型的补充提示」会注入评审提示词，引导模型聚焦该规则关注的代码模式
- 内置 14 条规则（空指针、SQL 注入、XSS、硬编码凭据等），可编辑、停用，不可删除；自定义规则可删除

### 2.4 评审技能

技能 = 评审智能体的**人格与系统提示词**（类似 Claude Skills 的角色定义）：

- 内置 3 个技能：通用代码评审（默认）、安全审计专家、性能优化专家
- 「设为默认」后，后续所有评审使用该技能的系统提示词
- 引擎会在技能提示词后自动追加当前启用的规则列表与输出格式约束，无需自己写

### 2.5 系统设置

- **大模型连接**：提供者（mock/openai）、Base URL、API Key、模型名、温度；「测试连接」一键验证
- **评审引擎**：Agent 最大轮次、单组最大 diff 行数、反思过滤开关、质量门禁阈值
- **安全**：接入密钥，配置后 Jenkins 端点必须携带 `X-API-Key`
- **仓库访问**：深度扫描克隆 http(s) Git 仓库用的用户名与密码/访问令牌（GitLab 令牌通常填 `oauth2` + 令牌）；`file://` 本地仓库无需配置

所有设置保存后**立即生效**，无需重启。

## 3. Jenkins 集成

### 3.1 前提

1. 部署好 CodeReview Hub 并在「系统设置」接入大模型（或先用 Mock 演示）
2. 将本仓库的 `ci/submit-review.sh` 放入你的业务仓库（如 `ci/` 目录）
3. Jenkins 节点具备 `bash`、`git`、`curl`、`python3`

### 3.2 在流水线中添加 stage

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

**Windows 节点**：提供等价的 [ci/submit-review.bat](../ci/submit-review.bat)（参数与 sh 版完全一致，依赖 git 与 Python 3.6+，无需 curl），stage 中改用 `bat 'ci\\submit-review.bat --hub ... --base origin/master --with-files'`。该文件为 bat/Python 单文件混合脚本，行为（含二进制文档排除、退出码）与 sh 版保持一致。命令行手动调用时注意：cmd 下 `ci\submit-review.bat ...`；PowerShell 需 `.\` 前缀；**Git Bash/WSL 下直接敲文件名会报 `command not found`**（bash 不搜索当前目录），需带 `./` 路径前缀，或直接改用 `bash ci/submit-review.sh ...`。

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
| `--repo-url` | 否 | 仓库 Git 地址（http(s)），供评审详情页【深度扫描】克隆该分支全量代码做影响分析 |
| `--req-docs` | 否 | 需求文档 wiki 链接，逗号分隔（最多 5 个，评审时平台抓取内容注入上下文） |
| `--design-docs` | 否 | 设计文档 wiki 链接，逗号分隔（最多 5 个） |

示例：携带文档与仓库地址提交——

```groovy
stage('AI Code Review') {
    steps {
        sh '''
        bash ci/submit-review.sh \
          --hub "$REVIEW_HUB" \
          --api-key "$REVIEW_API_KEY" \
          --repo "$JOB_BASE_NAME" \
          --base origin/master \
          --with-files \
          --repo-url "$GIT_URL" \
          --req-docs "http://wiki.internal/requirement/123,http://wiki.internal/requirement/456" \
          --design-docs "http://wiki.internal/design/789"
        '''
    }
}
```

**脚本行为**：提交评审 → 轮询直到完成 → 打印摘要与详情链接 → 查询质量门禁。

**文件过滤**：脚本对当前分支工作区执行 `git diff BASE...HEAD`（无需切换分支，Jenkins 检出的即目标分支）；diff 与 `--with-files` 附带全文会自动排除二进制文档（`pdf doc docx docm rtf wps xls xlsx xlsm ppt pptx`，大小写不敏感），后缀清单可在脚本内 `EXCLUDE_EXTS` 按需增删。**BASE 引用本地不存在时脚本会自动从远端补拉**（如缺少 `origin/master` 则执行 `git fetch origin master`，旧版 git 顺带退回 `FETCH_HEAD`），适配浅克隆/单分支克隆的流水线；补拉失败会显式报错退出（退出码 2）而非静默跳过评审。

**退出码**：`0` 门禁通过；`1` 门禁拦截（流水线失败，后续 stage 不再执行）；`2` 参数/环境错误。

### 3.3 质量门禁规则

- 评审发现中，**严重级别 ≥ 门禁阈值**（默认 CRITICAL）的问题会导致门禁不通过
- 阈值可在「系统设置 → 评审引擎」调整：BLOCKER（最宽松，仅阻断级拦截）/ CRITICAL（推荐）/ MAJOR / MINOR / INFO（最严格）
- 评审执行失败（如模型不可用）**不会**阻断流水线，仅在控制台提示——避免基础设施故障卡死业务发布
- 门禁只看评审结果本身；人工在 Web 上事后标记「误报」不会改变已构建流水线的结论，但为下次评审治理提供依据

### 3.4 回调通知（可选）

提交评审时传入 `callbackUrl`，评审完成后平台会 POST 以下 JSON 到该地址，便于对接企业 IM 机器人等：

```json
{ "id": 42, "status": "SUCCESS", "score": 82, "summary": "…", "findings": 5 }
```

## 4. REST API 参考

所有 API 均返回 JSON。鉴权方式：

- **Web/程序化调用**：登录 `POST /api/auth/login` 获取 token，后续请求携带 `X-Auth-Token` 请求头；会话 24 小时滑动过期
- **Jenkins 服务间调用**：`/api/jenkins/**` 使用 `X-API-Key`（在「系统设置 → 安全」配置，未配置时放行，适合纯内网）

规则、技能、设置、用户管理端点要求 **ADMIN 角色**，普通用户调用返回 403。

### 4.1 认证与用户端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/auth/login` | 登录：`{"username":"admin","password":"..."}` → `{"token":"...","user":{...}}` |
| POST | `/api/auth/logout` | 退出登录（销毁当前 token） |
| GET | `/api/auth/me` | 当前登录用户信息 |
| POST | `/api/auth/change-password` | 修改自己的密码：`{"oldPassword":"...","newPassword":"..."}` |
| GET / POST | `/api/users` | 用户列表 / 新建用户（仅管理员） |
| PUT / DELETE | `/api/users/{id}` | 编辑用户（displayName/role/enabled）/ 删除（仅管理员） |
| PUT | `/api/users/{id}/reset-password` | 重置用户密码（仅管理员），重置后强制下线 |

登录示例：

```bash
# 登录取 token
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")

# 携带 token 调用业务 API
curl -H "X-Auth-Token: $TOKEN" http://localhost:8080/api/reviews
```

### 4.2 提交评审

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
    "callbackUrl": "http://your-im-webhook（可选）",
    "requirementDocs": ["http://wiki.internal/req/123", "http://wiki.internal/req/456"],
    "designDocs": ["http://wiki.internal/design/789"],
    "requirementDocText": "链接抓取失败时的手动粘贴兜底（可选，≤50k 字符）",
    "designDocText": null,
    "repoUrl": "http://git.internal/group/order-service.git"
  }'
```

返回 `{"id": 1, "status": "QUEUED"}`。`diff` 上限 4MB，`files` 序列化后上限 16MB。文档链接仅支持 http/https、每类最多 5 个；`repoUrl` 支持 http(s) 与 `file://`（本地仓库），供深度扫描克隆。

### 4.3 任务与结果

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/reviews?status=&q=&page=0&size=15` | 分页列表（status 状态筛选，q 搜索仓库/分支/作者） |
| GET | `/api/reviews/{id}` | 详情：任务元数据 + diff 全文 + 全部发现 |
| PUT | `/api/reviews/{id}/findings/{fid}` | 更新发现：`{"status":"CONFIRMED","remark":"..."}`，status 可选 PENDING/CONFIRMED/IGNORED/FALSE_POSITIVE |
| POST | `/api/reviews/{id}/retry` | 失败重试 |
| POST | `/api/reviews/{id}/deep-scan` | 触发深度扫描（仓库级影响分析）。请求体可选 `{"repoUrl":"...","branch":"..."}` 用于补填仓库地址；任务需已结束评审且提供了仓库地址。详情响应中的 `deepScanStatus`（NONE/QUEUED/RUNNING/SUCCESS/FAILED）与 `deepScanReport`（影响报告 JSON）可轮询 |
| GET | `/api/reviews/{id}/gate` | 质量门禁：`{"pass":true,"reason":"…","blockers":0,"criticals":0}` |
| GET | `/api/reviews/stats` | 仪表盘统计 |

### 4.4 Jenkins 专用端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/jenkins/submit` | 提交评审（source 强制为 JENKINS） |
| GET | `/api/jenkins/reviews/{id}` | 轻量轮询：状态/评分/摘要/问题计数 |
| GET | `/api/jenkins/reviews/{id}/gate` | 质量门禁 |

### 4.5 规则 / 技能 / 设置（仅管理员）

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

## 5. 评审流程说明（了解引擎如何工作）

1. **提交**：diff（及可选文件内容包、需求/设计文档链接）入库，任务进入队列，立即返回任务 ID
2. **排队**：worker 线程按顺序消费（并发数 = `review.workers`）
3. **文档抓取**：服务端抓取需求/设计文档链接内容（HTML 转纯文本，单篇截断 2 万字符、合计 6 万字符），失败自动标注并降级为仅链接提示
4. **确定性预处理**：解析 diff → 过滤二进制/纯删除文件 → 规则路由（Ant 通配 + 花括号展开）→ 文件分组
5. **Agent 评审**：模型收到「技能提示词 + 命中规则 + 需求/设计文档上下文 + 分组 diff」，可调用 `file_read` / `code_search` 工具读取文件内容与搜索代码（最多 `maxRounds` 轮），最终输出严格 JSON 的发现列表
6. **行号定位**：模型给出的行号被吸附到该文件真实的新增行上（避免行号漂移）
7. **反思过滤**（可关）：将全部发现连同 ±5 行代码上下文再交给模型核对，过滤臆测与重复项
8. **落库与通知**：发现持久化、统计回填、触发 callback（如有）

深度扫描（人工触发）独立于上述流程：克隆全仓 → 符号提取与反向引用 → LLM 影响报告落库。

## 6. Mock 模式说明

默认提供者 `mock` 不依赖任何模型，用于：

- 部署后立即验证全链路（提交、队列、Agent 工具调用循环、行号定位、门禁、Web 页面）
- 演示与培训环境

Mock 会真实走完 Agent 工具调用循环并生成 2 条定位准确的示例发现（内容标注 [Mock]）。接入真实模型后，在「系统设置」切换提供者即可。
