#!/usr/bin/env bash
# ============================================================
# CodeReview Hub - Jenkins/CI 评审提交脚本
#
# 用法（在流水线 stage 中）:
#   bash ci/submit-review.sh \
#     --hub http://review-hub:8080 \
#     --repo my-project \
#     --base origin/master \
#     [--api-key XXXXX]      # 平台设置了接入密钥时必填
#     [--with-files]         # 附带变更文件全文，提升评审质量
#     [--timeout 900]        # 最长等待秒数，默认 900
#     [--author zhangsan]    # 默认取 git config user.name
#     [--repo-url http://git.example.com/group/project.git]
#                            # 仓库地址：供平台【深度扫描】克隆该分支全量代码做影响分析
#     [--req-docs "url1,url2"]    # 需求文档 wiki 链接（逗号分隔，最多 5 个，评审时抓取内容）
#     [--design-docs "url1,url2"] # 设计文档 wiki 链接（逗号分隔，最多 5 个）
#
# 说明: diff 与附带全文自动排除 pdf/word 等二进制文档（后缀清单见脚本内 EXCLUDE_EXTS）
#       基线引用本地不存在时自动从远端补拉（适配浅克隆/单分支克隆流水线），拉不到则显式报错
#
# 退出码: 0=评审通过(门禁放行)  1=门禁拦截或评审失败  2=参数/环境错误
# 依赖: bash 4+、git、curl、python3
# ============================================================
set -uo pipefail

HUB=""; REPO=""; BASE=""; API_KEY=""; WITH_FILES="no"; TIMEOUT=900; AUTHOR=""
REPO_URL=""; REQ_DOCS=""; DESIGN_DOCS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --hub) HUB="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --api-key) API_KEY="$2"; shift 2 ;;
    --with-files) WITH_FILES="yes"; shift ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    --author) AUTHOR="$2"; shift 2 ;;
    --repo-url) REPO_URL="$2"; shift 2 ;;
    --req-docs) REQ_DOCS="$2"; shift 2 ;;
    --design-docs) DESIGN_DOCS="$2"; shift 2 ;;
    *) echo "[review] 未知参数: $1"; exit 2 ;;
  esac
done

[[ -z "$HUB" || -z "$REPO" || -z "$BASE" ]] && { echo "[review] 缺少 --hub/--repo/--base 参数"; exit 2; }
command -v git     >/dev/null || { echo "[review] 缺少 git"; exit 2; }
command -v curl    >/dev/null || { echo "[review] 缺少 curl"; exit 2; }
PY=$(command -v python3 || command -v python) || { echo "[review] 缺少 python3"; exit 2; }

AUTHOR="${AUTHOR:-$(git config user.name 2>/dev/null || echo unknown)}"
BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo HEAD)
COMMIT=$(git rev-parse --short=12 HEAD 2>/dev/null || echo "")

# 不参与评审的二进制文档后缀（diff 生成与附带全文均过滤，大小写不敏感，可按需增删）
EXCLUDE_EXTS="pdf doc docx docm rtf wps xls xlsx xlsm ppt pptx"
EXCLUDE_SPEC=()
EXCLUDE_JOINED=""
if git diff HEAD -- ':(exclude,icase)*.pdf' >/dev/null 2>&1; then
  for _ext in ${EXCLUDE_EXTS}; do
    EXCLUDE_SPEC+=(":(exclude,icase)*.${_ext}")
  done
  EXCLUDE_JOINED="${EXCLUDE_SPEC[*]}"
fi
# 旧版 git 不支持 :(exclude,icase) 语法时数组留空（降级为不过滤，不中断评审）
# 注意：bash 4.3- 在 set -u 下展开空数组会报 unbound variable，故用 ${a[@]+...} 写法

# ---------- 1. 生成 diff ----------
DIFF_ERR=$(mktemp /tmp/review-diff-err.XXXXXX)
# 基线引用可能不在本地（浅克隆/单分支克隆的流水线常见），先尝试从远端补拉
if ! git rev-parse --verify -q "${BASE}^{commit}" >/dev/null 2>&1; then
  remote_name="origin"; base_branch="${BASE}"
  case "${BASE}" in */*) remote_name="${BASE%%/*}"; base_branch="${BASE#*/}" ;; esac
  git config "remote.${remote_name}.url" >/dev/null 2>&1 || remote_name="origin"
  echo "[review] 本地缺少基线引用 ${BASE}，尝试 git fetch ${remote_name} ${base_branch} ..."
  if git fetch "${remote_name}" "${base_branch}" >/dev/null 2>&1; then
    # git ≥ 1.8.4 会顺带更新远端跟踪分支；更旧版本退回 FETCH_HEAD
    git rev-parse --verify -q "${BASE}^{commit}" >/dev/null 2>&1 || BASE="FETCH_HEAD"
  fi
fi
DIFF=$(git diff "${BASE}...HEAD" -- . ${EXCLUDE_SPEC[@]+"${EXCLUDE_SPEC[@]}"} 2>"$DIFF_ERR"); rc1=$?
if [[ -z "$DIFF" ]]; then
  DIFF=$(git diff "${BASE}" -- . ${EXCLUDE_SPEC[@]+"${EXCLUDE_SPEC[@]}"} 2>>"$DIFF_ERR"); rc2=$?
fi
if [[ -z "$DIFF" ]]; then
  if [[ "${rc1}" -ne 0 && "${rc2:-1}" -ne 0 ]]; then
    echo "[review] 生成 diff 失败（base: ${BASE}）:"
    tail -n 5 "$DIFF_ERR" | sed 's/^/    /'
    rm -f "$DIFF_ERR"
    exit 2
  fi
  echo "[review] 没有检测到代码变更（base: ${BASE}），跳过评审"
  rm -f "$DIFF_ERR"
  exit 0
fi
DIFF_LINES=$(printf '%s' "$DIFF" | wc -l | tr -d ' ')
echo "[review] 仓库=${REPO} 分支=${BRANCH} 提交=${COMMIT} diff=${DIFF_LINES} 行"

# ---------- 2. 组装 JSON 并提交（借助 python 做安全转义） ----------
REQ_FILE=$(mktemp /tmp/review-req.XXXXXX.json)
DIFF_FILE=$(mktemp /tmp/review-diff.XXXXXX)
trap 'rm -f "$REQ_FILE" "$DIFF_FILE" "$DIFF_ERR"' EXIT
printf '%s' "$DIFF" > "$DIFF_FILE"
"$PY" - "$REQ_FILE" "$DIFF_FILE" "$REPO" "$BRANCH" "$COMMIT" "$AUTHOR" "$WITH_FILES" "$BASE" "$EXCLUDE_JOINED" \
     "$REPO_URL" "$REQ_DOCS" "$DESIGN_DOCS" <<'PYEOF'
import json, subprocess, sys

out_path, diff_path, repo, branch, commit, author, with_files = sys.argv[1:8]
base, exclude_joined = sys.argv[8], sys.argv[9]
repo_url, req_docs, design_docs = sys.argv[10], sys.argv[11], sys.argv[12]
with open(diff_path, encoding="utf-8") as f:
    diff = f.read()

files = {}
if with_files == "yes":
    names = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=ACMR",
         base + "...HEAD", "--", "."] + exclude_joined.split(),
        capture_output=True, text=True).stdout.splitlines()
    for name in names[:100]:                      # 最多携带 100 个文件
        try:
            with open(name, encoding="utf-8") as f:
                content = f.read(512 * 1024)      # 单文件最大 512KB
            if len(content) < 512 * 1024 and "\x00" not in content:
                files[name] = content
        except (OSError, UnicodeDecodeError):
            pass                                  # 二进制或不可读文件跳过

payload = {"repoName": repo, "branch": branch, "commitId": commit,
           "author": author, "source": "JENKINS", "diff": diff}
if files:
    payload["files"] = files
if repo_url:
    payload["repoUrl"] = repo_url
# 需求/设计文档 wiki 链接（逗号分隔 → 数组）
def split_links(s):
    return [x.strip() for x in (s or "").replace("，", ",").split(",") if x.strip()]
req_list, design_list = split_links(req_docs), split_links(design_docs)
if req_list:
    payload["requirementDocs"] = req_list
if design_list:
    payload["designDocs"] = design_list

with open(out_path, "w", encoding="utf-8") as f:
    json.dump(payload, f, ensure_ascii=False)
PYEOF

# 统一的 curl 封装（自动携带密钥，避免空数组在 set -u 下的兼容问题）
hub_curl() {
  if [[ -n "$API_KEY" ]]; then
    curl -s -H "X-API-Key: ${API_KEY}" "$@"
  else
    curl -s "$@"
  fi
}

RESP=$(hub_curl -X POST "${HUB}/api/jenkins/submit" \
  -H 'Content-Type: application/json' \
  --data-binary "@${REQ_FILE}")
TASK_ID=$(printf '%s' "$RESP" | "$PY" -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null)
if [[ -z "$TASK_ID" ]]; then
  echo "[review] 提交失败: ${RESP}"
  exit 2
fi
echo "[review] 已提交，任务 #${TASK_ID}，等待评审完成…"

# ---------- 3. 轮询直到完成 ----------
SECONDS=0
while (( SECONDS < TIMEOUT )); do
  sleep 5
  STATUS_JSON=$(hub_curl "${HUB}/api/jenkins/reviews/${TASK_ID}")
  STATUS=$(printf '%s' "$STATUS_JSON" | "$PY" -c 'import json,sys; print(json.load(sys.stdin).get("status",""))' 2>/dev/null)
  case "$STATUS" in
    SUCCESS|FAILED) break ;;
    QUEUED|RUNNING) echo "[review] ${SECONDS}s 状态: ${STATUS}" ;;
    *) echo "[review] 轮询异常: ${STATUS_JSON}"; exit 2 ;;
  esac
done
if [[ "$STATUS" != "SUCCESS" && "$STATUS" != "FAILED" ]]; then
  echo "[review] 评审超时（${TIMEOUT}s），任务 #${TASK_ID} 仍在 ${STATUS}"
  exit 1
fi

# ---------- 4. 输出摘要与门禁结论 ----------
echo "[review] 评审${STATUS}，结果摘要:"
printf '%s' "$STATUS_JSON" | "$PY" -c '
import json, sys
d = json.load(sys.stdin)
print("  评分: %s" % d.get("score", "-"))
print("  总结: %s" % (d.get("summary") or "-"))
print("  问题: %s 个（阻断 %s / 严重 %s）" % (d.get("findings", 0), d.get("blockers", 0), d.get("criticals", 0)))
'
echo "[review] 详情: ${HUB}/#/reviews/${TASK_ID}"

GATE=$(hub_curl "${HUB}/api/jenkins/reviews/${TASK_ID}/gate")
PASS=$(printf '%s' "$GATE" | "$PY" -c 'import json,sys; print(json.load(sys.stdin).get("pass",""))' 2>/dev/null)
REASON=$(printf '%s' "$GATE" | "$PY" -c 'import json,sys; print(json.load(sys.stdin).get("reason",""))' 2>/dev/null)
if [[ "$PASS" == "True" ]]; then
  echo "[review] ✅ 质量门禁通过: ${REASON}"
  exit 0
else
  echo "[review] ❌ 质量门禁未通过: ${REASON}"
  exit 1
fi
