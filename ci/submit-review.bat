@echo off
rem = r"""
rem ============================================================
rem CodeReview Hub - Jenkins/CI 评审提交脚本（Windows bat 版，行为与 submit-review.sh 一致）
rem
rem 用法（参数与 submit-review.sh 完全一致）:
rem   ci\submit-review.bat ^
rem     --hub http://review-hub:8080 ^
rem     --repo my-project ^
rem     --base origin/master ^
rem     [--api-key XXXXX]      # 平台设置了接入密钥时必填
rem     [--with-files]         # 附带变更文件全文，提升评审质量
rem     [--timeout 900]        # 最长等待秒数，默认 900
rem     [--author zhangsan]    # 默认取 git config user.name
rem     [--repo-url http://git.example.com/group/project.git]
rem                            # 仓库地址：供平台【深度扫描】克隆该分支全量代码做影响分析
rem     [--req-docs "url1,url2"]    # 需求文档 wiki 链接（逗号分隔，最多 5 个，评审时抓取内容）
rem     [--design-docs "url1,url2"] # 设计文档 wiki 链接（逗号分隔，最多 5 个）
rem
rem 各环境调用方式:
rem   cmd:         ci\submit-review.bat --hub ... --base origin/master --with-files
rem   PowerShell:  .\ci\submit-review.bat --hub ... --base origin/master --with-files
rem   Git Bash:    ./ci/submit-review.bat --hub ... --base origin/master --with-files
rem                （bash 不搜索当前目录，直接敲文件名会报 command not found，必须带 ./ 或路径前缀）
rem   Jenkins:     bat 'ci\\submit-review.bat --hub ... --base origin/master --with-files'
rem   WSL:         建议改用 sh 版：bash ci/submit-review.sh ...
rem
rem 说明: diff 与附带全文自动排除 pdf/word 等二进制文档（后缀清单见脚本内 EXCLUDE_EXTS）
rem
rem 退出码: 0=评审通过(门禁放行)  1=门禁拦截或评审失败  2=参数/环境错误
rem 依赖: git、python 3.6+（无需 curl）
rem ============================================================
chcp 65001 >nul
set "PYTHONIOENCODING=utf-8"
python  -c "import sys" >nul 2>nul && (python -x "%~f0" %* & exit /b)
py -3    -c "import sys" >nul 2>nul && (py -3 -x "%~f0" %* & exit /b)
python3  -c "import sys" >nul 2>nul && (python3 -x "%~f0" %* & exit /b)
echo [review] 未找到 python，请先安装 Python 3.6 及以上版本
exit /b 2
"""
# ---- 以下为 Python 实现：bat 启动器以 python -x 执行本文件（-x 跳过首行，上方块整体是一个字符串）----
import json
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

# 强制 UTF-8 输出，保证中文/符号在 Jenkins 控制台正常显示
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def die(msg, code=2):
    print("[review] " + msg)
    sys.exit(code)


# ---------- 1. 解析参数 ----------
hub = repo = base = api_key = author = ""
repo_url = req_docs = design_docs = ""
with_files = False
timeout_sec = 900

args = sys.argv[1:]
i = 0
while i < len(args):
    a = args[i]
    if a in ("--hub", "--repo", "--base", "--api-key", "--timeout", "--author",
             "--repo-url", "--req-docs", "--design-docs"):
        if i + 1 >= len(args):
            die("参数 %s 缺少取值" % a)
        v = args[i + 1]
        i += 2
        if a == "--hub":
            hub = v
        elif a == "--repo":
            repo = v
        elif a == "--base":
            base = v
        elif a == "--api-key":
            api_key = v
        elif a == "--author":
            author = v
        elif a == "--repo-url":
            repo_url = v
        elif a == "--req-docs":
            req_docs = v
        elif a == "--design-docs":
            design_docs = v
        else:
            try:
                timeout_sec = int(v)
            except ValueError:
                die("--timeout 必须为整数")
    elif a == "--with-files":
        with_files = True
        i += 1
    else:
        die("未知参数: " + a)

if not (hub and repo and base):
    die("缺少 --hub/--repo/--base 参数")
if not shutil.which("git"):
    die("缺少 git")


def git(*git_args):
    r = subprocess.run(["git"] + list(git_args), capture_output=True)
    return r.stdout.decode("utf-8", "replace")


author = author or git("config", "user.name").strip() or "unknown"
branch = git("rev-parse", "--abbrev-ref", "HEAD").strip() or "HEAD"
commit = git("rev-parse", "--short=12", "HEAD").strip()

# 不参与评审的二进制文档后缀（diff 生成与附带全文均过滤，大小写不敏感，可按需增删）
EXCLUDE_EXTS = "pdf doc docx docm rtf wps xls xlsx xlsm ppt pptx".split()
EXCLUDE_SPEC = [":(exclude,icase)*." + e for e in EXCLUDE_EXTS]

# ---------- 2. 生成 diff ----------
diff = git("diff", base + "...HEAD", "--", ".", *EXCLUDE_SPEC)
if not diff:
    diff = git("diff", base, "--", ".", *EXCLUDE_SPEC)
if not diff:
    print("[review] 没有检测到代码变更（base: %s），跳过评审" % base)
    sys.exit(0)
print("[review] 仓库=%s 分支=%s 提交=%s diff=%d 行" % (repo, branch, commit, diff.count("\n")))

# ---------- 3. 附带变更文件全文 ----------
files = {}
if with_files:
    names = git("-c", "core.quotepath=false", "diff", "--name-only", "--diff-filter=ACMR",
                base + "...HEAD", "--", ".", *EXCLUDE_SPEC).splitlines()
    for name in names[:100]:                      # 最多携带 100 个文件
        try:
            with open(name, "rb") as f:
                raw = f.read(512 * 1024 + 1)
            if len(raw) > 512 * 1024 or b"\x00" in raw:
                continue                          # 超限或二进制文件跳过
            files[name] = raw.decode("utf-8")     # 非 UTF-8 文本将抛异常被跳过
        except (OSError, UnicodeDecodeError):
            pass


# ---------- 4. 提交（urllib 无需 curl） ----------
def call(method, path, payload=None):
    url = hub.rstrip("/") + path
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8") if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if api_key:
        req.add_header("X-API-Key", api_key)
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        try:
            detail = e.read().decode("utf-8", "replace").strip()
        except Exception:
            detail = ""
        raise RuntimeError("HTTP %s %s" % (e.code, detail or e.reason))
    except urllib.error.URLError as e:
        raise RuntimeError(str(e.reason))


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

try:
    resp = call("POST", "/api/jenkins/submit", payload)
except Exception as e:
    die("提交失败: " + str(e))
task_id = resp.get("id")
if not task_id:
    die("提交失败: " + json.dumps(resp, ensure_ascii=False))
print("[review] 已提交，任务 #%s，等待评审完成…" % task_id)

# ---------- 5. 轮询直到完成 ----------
start = time.time()
info = {}
status = ""
while True:
    if time.time() - start >= timeout_sec:
        break
    time.sleep(5)
    try:
        info = call("GET", "/api/jenkins/reviews/%s" % task_id)
    except Exception as e:
        die("轮询异常: " + str(e))
    status = info.get("status", "")
    if status in ("SUCCESS", "FAILED"):
        break
    if status not in ("QUEUED", "RUNNING"):
        die("轮询异常: 未知状态 %r" % status)
    print("[review] %ds 状态: %s" % (int(time.time() - start), status))
if status not in ("SUCCESS", "FAILED"):
    print("[review] 评审超时（%ss），任务 #%s 仍在 %s" % (timeout_sec, task_id, status or "未知"))
    sys.exit(1)

# ---------- 6. 输出摘要与门禁结论 ----------
print("[review] 评审%s，结果摘要:" % status)
print("  评分: %s" % info.get("score", "-"))
print("  总结: %s" % (info.get("summary") or "-"))
print("  问题: %s 个（阻断 %s / 严重 %s）" % (
    info.get("findings", 0), info.get("blockers", 0), info.get("criticals", 0)))
print("[review] 详情: %s/#/reviews/%s" % (hub.rstrip("/"), task_id))

try:
    gate = call("GET", "/api/jenkins/reviews/%s/gate" % task_id)
except Exception as e:
    print("[review] ❌ 质量门禁查询失败: %s" % e)
    sys.exit(1)
if gate.get("pass"):
    print("[review] ✅ 质量门禁通过: %s" % (gate.get("reason") or ""))
    sys.exit(0)
print("[review] ❌ 质量门禁未通过: %s" % (gate.get("reason") or ""))
sys.exit(1)
