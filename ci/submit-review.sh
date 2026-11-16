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
#
# 退出码: 0=评审通过(门禁放行)  1=门禁拦截或评审失败  2=参数/环境错误
# 依赖: bash 4+、git、curl、python3
# ============================================================
set -uo pipefail

HUB=""; REPO=""; BASE=""; API_KEY=""; WITH_FILES="no"; TIMEOUT=900; AUTHOR=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --hub) HUB="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    --base) BASE="$2"; shift 2 ;;
    --api-key) API_KEY="$2"; shift 2 ;;
    --with-files) WITH_FILES="yes"; shift ;;
    --timeout) TIMEOUT="$2"; shift 2 ;;
    --author) AUTHOR="$2"; shift 2 ;;
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

# ---------- 1. 生成 diff ----------
DIFF=$(git diff "${BASE}...HEAD" 2>/dev/null)
if [[ -z "$DIFF" ]]; then
  DIFF=$(git diff "${BASE}" 2>/dev/null)
fi
if [[ -z "$DIFF" ]]; then
  echo "[review] 没有检测到代码变更（base: ${BASE}），跳过评审"
  exit 0
fi
DIFF_LINES=$(printf '%s' "$DIFF" | wc -l | tr -d ' ')
echo "[review] 仓库=${REPO} 分支=${BRANCH} 提交=${COMMIT} diff=${DIFF_LINES} 行"

# ---------- 2. 组装 JSON 并提交（借助 python 做安全转义） ----------
REQ_FILE=$(mktemp /tmp/review-req.XXXXXX.json)
DIFF_FILE=$(mktemp /tmp/review-diff.XXXXXX)
trap 'rm -f "$REQ_FILE" "$DIFF_FILE"' EXIT
printf '%s' "$DIFF" > "$DIFF_FILE"
"$PY" - "$REQ_FILE" "$DIFF_FILE" "$REPO" "$BRANCH" "$COMMIT" "$AUTHOR" "$WITH_FILES" <<'PYEOF'
import json, subprocess, sys

out_path, diff_path, repo, branch, commit, author, with_files = sys.argv[1:8]
with open(diff_path, encoding="utf-8") as f:
    diff = f.read()

files = {}
if with_files == "yes":
    names = subprocess.run(["git", "diff", "--name-only", "--diff-filter=ACMR"],
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
