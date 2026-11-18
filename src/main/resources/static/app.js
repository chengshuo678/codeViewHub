/* ============================================================
 * CodeReview Hub 前端单页应用（Vue 3，无构建步骤）
 * ============================================================ */
const { createApp, ref, reactive, computed, onMounted, onUnmounted, watch, nextTick } = Vue;

/* ---------------- 认证状态 ---------------- */
const auth = reactive({ token: localStorage.getItem('crh_token') || '', user: null });

function saveAuth(token, user) {
    auth.token = token || '';
    auth.user = user || null;
    if (auth.token) localStorage.setItem('crh_token', auth.token);
    else localStorage.removeItem('crh_token');
}

/* ---------------- API 封装 ---------------- */
const api = {
    async request(method, url, body) {
        const opt = { method, headers: {} };
        if (auth.token) opt.headers['X-Auth-Token'] = auth.token;
        if (body !== undefined) {
            opt.headers['Content-Type'] = 'application/json';
            opt.body = JSON.stringify(body);
        }
        const resp = await fetch(url, opt);
        let data = null;
        try { data = await resp.json(); } catch (e) { /* ignore */ }
        if (resp.status === 401 && !url.includes('/api/auth/login')) {
            // 会话失效：记录当前位置（登录后跳回），清理状态 → 根组件自动切换到登录页
            if (auth.token) {
                try {
                    sessionStorage.setItem('crh_redirect', location.hash || '#/');
                    sessionStorage.setItem('crh_expired', '1');
                } catch (e) { /* ignore */ }
            }
            saveAuth('', null);
            throw new Error((data && data.error) || '未登录或会话已过期，请重新登录');
        }
        if (!resp.ok) throw new Error((data && data.error) || ('请求失败 HTTP ' + resp.status));
        return data;
    },
    get: (u) => api.request('GET', u),
    post: (u, b) => api.request('POST', u, b === undefined ? {} : b),
    put: (u, b) => api.request('PUT', u, b === undefined ? {} : b),
    del: (u) => api.request('DELETE', u),
};

/* ---------------- Toast ---------------- */
const toastState = reactive({ list: [] });
let toastSeq = 0;
function toast(msg, type) {
    const id = ++toastSeq;
    toastState.list.push({ id, msg, type: type || 'error' });
    setTimeout(() => {
        const i = toastState.list.findIndex(t => t.id === id);
        if (i >= 0) toastState.list.splice(i, 1);
    }, 3800);
}
window.addEventListener('unhandledrejection', (e) => {
    toast(e.reason && e.reason.message ? e.reason.message : String(e.reason));
});

/* ---------------- 图标 ---------------- */
const svg = (inner) => '<svg viewBox="0 0 24 24">' + inner + '</svg>';
const I = {
    dashboard: svg('<rect x="3.5" y="3.5" width="7.5" height="7.5" rx="1.8"/><rect x="13" y="3.5" width="7.5" height="7.5" rx="1.8"/><rect x="3.5" y="13" width="7.5" height="7.5" rx="1.8"/><rect x="13" y="13" width="7.5" height="7.5" rx="1.8"/>'),
    rules: svg('<path d="M4 6.5l2.2 2.2L10.5 4"/><path d="M4 15.5l2.2 2.2 4.3-4.7"/><path d="M13.5 7h6.5"/><path d="M13.5 16h6.5"/>'),
    skills: svg('<path d="M12 3.5l1.8 4.4 4.4 1.8-4.4 1.8L12 15.9l-1.8-4.4-4.4-1.8 4.4-1.8z"/><path d="M18.5 15l.8 2 2 .8-2 .8-.8 2-.8-2-2-.8 2-.8z"/>'),
    settings: svg('<path d="M4 7h9"/><path d="M19 7h1"/><path d="M4 17h1"/><path d="M11 17h9"/><circle cx="16" cy="7" r="2.4"/><circle cx="8" cy="17" r="2.4"/>'),
    back: svg('<path d="M14.5 5.5L8 12l6.5 6.5"/>'),
    chevR: svg('<path d="M9.5 5.5L16 12l-6.5 6.5"/>'),
    plus: svg('<path d="M12 5v14"/><path d="M5 12h14"/>'),
    close: svg('<path d="M6 6l12 12"/><path d="M18 6L6 18"/>'),
    refresh: svg('<path d="M20 12a8 8 0 1 1-2.3-5.6"/><path d="M20 3.5V7h-3.5"/>'),
    check: svg('<path d="M4.5 12.5l5 5 10-11"/>'),
    alert: svg('<path d="M12 4l9.5 16.5h-19z"/><path d="M12 10.5v4"/><circle cx="12" cy="17.3" r=".4"/>'),
    info: svg('<circle cx="12" cy="12" r="8.5"/><path d="M12 11v5"/><circle cx="12" cy="8" r=".4"/>'),
    search: svg('<circle cx="11" cy="11" r="6.5"/><path d="M15.9 15.9L20.5 20.5"/>'),
    spark: svg('<path d="M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z"/><path d="M18.5 15l.7 1.8 1.8.7-1.8.7-.7 1.8-.7-1.8-1.8-.7 1.8-.7z"/>'),
    flow: svg('<rect x="3.5" y="4" width="7" height="5.5" rx="1.5"/><rect x="13.5" y="14.5" width="7" height="5.5" rx="1.5"/><path d="M7 9.5v4a3 3 0 0 0 3 3h3.5"/>'),
    lock: svg('<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/><circle cx="12" cy="15.2" r=".4"/>'),
    file: svg('<path d="M13 3.5H6.5a1.5 1.5 0 0 0-1.5 1.5v14a1.5 1.5 0 0 0 1.5 1.5h11a1.5 1.5 0 0 0 1.5-1.5V9z"/><path d="M13 3.5V9h5.5"/>'),
    clock: svg('<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2.5"/>'),
    trash: svg('<path d="M4.5 6.5h15"/><path d="M8 6.5V5a1.5 1.5 0 0 1 1.5-1.5h5A1.5 1.5 0 0 1 16 5v1.5"/><path d="M6.5 6.5l1 13a1.5 1.5 0 0 0 1.5 1.4h6a1.5 1.5 0 0 0 1.5-1.4l1-13"/>'),
    edit: svg('<path d="M16.8 4.2l3 3L8 19l-4 1 1-4z"/>'),
    export: svg('<path d="M12 15V4"/><path d="M8 7.5L12 3.5l4 4"/><path d="M4.5 14v4.5a2 2 0 0 0 2 2h11a2 2 0 0 0 2-2V14"/>'),
    inbox: svg('<path d="M3.5 13.5h4l1.5 3h6l1.5-3h4"/><path d="M3.5 13.5L6 5a2 2 0 0 1 1.9-1.5h8.2A2 2 0 0 1 18 5l2.5 8.5v4a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z"/>'),
    user: svg('<circle cx="12" cy="8" r="4"/><path d="M4.5 20.5c1.2-3.4 4-5 7.5-5s6.3 1.6 7.5 5"/>'),
    branch: svg('<circle cx="6.5" cy="6" r="2.3"/><circle cx="6.5" cy="18" r="2.3"/><circle cx="17.5" cy="8" r="2.3"/><path d="M6.5 8.3v7.4"/><path d="M17.5 10.3c0 3.4-3 4-6 4.6"/>'),
    zap: svg('<path d="M13 3L5 13.5h5.5L11 21l8-10.5h-5.5z"/>'),
    users: svg('<circle cx="9" cy="8.5" r="3.2"/><path d="M3.5 19.5c1-3 3-4.5 5.5-4.5s4.5 1.5 5.5 4.5"/><circle cx="17" cy="9.5" r="2.5"/><path d="M15.5 14.6c2.6.2 4.3 1.6 5 4.4"/>'),
    logout: svg('<path d="M14 4H7a1.5 1.5 0 0 0-1.5 1.5v13A1.5 1.5 0 0 0 7 20h7"/><path d="M10 12h10"/><path d="M17 8.5L20.5 12 17 15.5"/>'),
    key: svg('<circle cx="8" cy="15" r="4"/><path d="M11 12l8-8"/><path d="M16 4l4 4"/><path d="M14 6l4 4"/>'),
};

/* ---------------- 元数据 ---------------- */
const SEV_ORDER = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO'];
const SEV_LABEL = { BLOCKER: '阻断', CRITICAL: '严重', MAJOR: '主要', MINOR: '次要', INFO: '提示' };
const ST_LABEL = { QUEUED: '排队中', RUNNING: '评审中', SUCCESS: '已完成', FAILED: '失败' };
const SRC_LABEL = { MANUAL: '手动', JENKINS: 'Jenkins', API: 'API' };
const FS_LABEL = { PENDING: '待处理', CONFIRMED: '已确认', IGNORED: '已忽略', FALSE_POSITIVE: '误报' };

/* ---------------- 工具函数 ---------------- */
function fmtTime(iso) {
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d)) return iso;
    const p = (n) => String(n).padStart(2, '0');
    const now = new Date();
    const sameDay = d.toDateString() === now.toDateString();
    const base = p(d.getMonth() + 1) + '-' + p(d.getDate()) + ' ' + p(d.getHours()) + ':' + p(d.getMinutes());
    if (sameDay) return base;
    return d.getFullYear() + '-' + base;
}
function fmtDur(startIso, endIso) {
    if (!startIso || !endIso) return null;
    const ms = new Date(endIso) - new Date(startIso);
    if (isNaN(ms) || ms < 0) return null;
    const s = Math.round(ms / 1000);
    if (s < 60) return s + ' 秒';
    return Math.floor(s / 60) + ' 分 ' + (s % 60) + ' 秒';
}
function shortCommit(c) { return c ? c.slice(0, 8) : ''; }

/* ---------------- 客户端 diff 解析（与后端 DiffParser 同构） ---------------- */
function parseDiff(text) {
    const files = [];
    if (!text) return files;
    const lines = text.replace(/\r\n/g, '\n').split('\n');
    let cur = null, hunk = null, oldNo = 0, newNo = 0, inBody = false;

    const unquote = (s) => {
        s = s.trim();
        if (s.startsWith('"') && s.endsWith('"') && s.length >= 2) return s.slice(1, -1);
        return s;
    };

    for (const raw of lines) {
        const line = raw;
        if (line.startsWith('diff --git ')) {
            const body = line.slice(11).trim();
            let p = body;
            const qb = body.indexOf('" b/');
            if (body.startsWith('"a/') && qb > 0) p = body.slice(qb + 4).replace(/"/g, '');
            else { const i = body.indexOf(' b/'); if (i > 0) p = body.slice(i + 3); }
            cur = { path: unquote(p), oldPath: unquote(p), type: 'MODIFY', binary: false, hunks: [], added: 0, deleted: 0 };
            files.push(cur);
            hunk = null; inBody = false;
            continue;
        }
        if (!cur) continue;
        if (line.startsWith('--- ')) {
            let p = line.slice(4).split('\t')[0];
            if (p === '/dev/null') cur.type = 'ADD'; // 旧文件为空 = 新增文件
            else cur.oldPath = unquote(p.startsWith('a/') ? p.slice(2) : p);
            inBody = false;
            continue;
        }
        if (line.startsWith('+++ ')) {
            let p = line.slice(4).split('\t')[0];
            if (p === '/dev/null') cur.type = 'DELETE';
            else cur.path = unquote(p.startsWith('b/') ? p.slice(2) : p);
            inBody = false;
            continue;
        }
        if (line.startsWith('new file mode')) { cur.type = 'ADD'; inBody = false; continue; }
        if (line.startsWith('rename from ') || line.startsWith('rename to ')) {
            cur.type = 'RENAME';
            if (line.startsWith('rename to ')) cur.path = unquote(line.slice(10));
            inBody = false;
            continue;
        }
        if (line.startsWith('Binary files') || line.startsWith('GIT binary patch')) { cur.binary = true; inBody = false; continue; }

        const m = line.match(/^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
        if (m) {
            oldNo = +m[1]; newNo = +m[2];
            hunk = { oldStart: oldNo, newStart: newNo, lines: [] };
            cur.hunks.push(hunk);
            inBody = true;
            continue;
        }
        if (!inBody || !hunk) continue;
        if (line.startsWith('+')) { hunk.lines.push({ t: 'add', oldNo: 0, newNo: newNo, text: line.slice(1) }); newNo++; cur.added++; }
        else if (line.startsWith('-')) { hunk.lines.push({ t: 'del', oldNo: oldNo, newNo: 0, text: line.slice(1) }); oldNo++; cur.deleted++; }
        else if (line.startsWith(' ')) { hunk.lines.push({ t: 'ctx', oldNo: oldNo, newNo: newNo, text: line.slice(1) }); oldNo++; newNo++; }
    }
    return files;
}

/* ---------------- 路由 ---------------- */
const route = reactive({ name: 'dashboard', params: {} });
function parseHash() {
    const h = location.hash.replace(/^#\/?/, '');
    const parts = h.split('/').filter(Boolean);
    if (parts[0] === 'reviews' && parts[1]) { route.name = 'review-detail'; route.params = { id: parts[1] }; }
    else if (parts[0] === 'rules') route.name = 'rules';
    else if (parts[0] === 'skills') route.name = 'skills';
    else if (parts[0] === 'users') route.name = 'users';
    else if (parts[0] === 'settings') route.name = 'settings';
    else { route.name = 'dashboard'; route.params = {}; }
}
window.addEventListener('hashchange', parseHash);
parseHash();

/* ---------------- 通用组件 ---------------- */
const Modal = {
    props: { title: String, wide: Boolean },
    emits: ['close'],
    template: `
    <div class="modal-mask" @click.self="$emit('close')">
      <div class="modal" :style="wide ? 'width:760px' : ''">
        <div class="modal-head">
          <h3>{{ title }}</h3>
          <button class="icon-btn" @click="$emit('close')" v-html="IC.close"></button>
        </div>
        <div class="modal-body"><slot></slot></div>
      </div>
    </div>`,
    data() { return { IC: I }; },
};

const Switch = {
    props: { modelValue: Boolean, label: String },
    emits: ['update:modelValue'],
    template: `
    <label class="switch">
      <input type="checkbox" :checked="modelValue" @change="$emit('update:modelValue', $event.target.checked)">
      <span class="track"></span>
      <span class="sw-label" v-if="label">{{ label }}</span>
    </label>`,
};

/* ============================================================
 * 登录页
 * ============================================================ */
const LoginPage = {
    template: `
  <div class="login-wrap">
    <div class="login-hero">
      <div class="hero-brand">
        <div class="logo-mark">CR</div>
        <div>
          <div class="hero-name">CodeReview Hub</div>
          <div class="hero-sub">AI 代码评审平台</div>
        </div>
      </div>
      <div class="hero-body">
        <h1 class="hero-title">让每一次提交<br>都经过 AI 评审</h1>
        <p class="hero-desc">确定性流程 + LLM 智能体的混合架构：diff 解析、规则路由、行号定位由代码保证可靠，代码质量的判断交给大模型。</p>
        <ul class="hero-feats">
          <li><span class="feat-ic" v-html="IC.spark"></span><div><b>LLM 智能体评审</b><span>模型自主读取代码上下文，输出定位到行的结构化发现</span></div></li>
          <li><span class="feat-ic" v-html="IC.flow"></span><div><b>Jenkins 流水线集成</b><span>作为 stage 自动触发评审，质量门禁按严重级别拦截</span></div></li>
          <li><span class="feat-ic" v-html="IC.lock"></span><div><b>私有大模型</b><span>兼容 vLLM / Ollama / OneAPI，代码数据不出内网</span></div></li>
          <li><span class="feat-ic" v-html="IC.settings"></span><div><b>规则与技能可配置</b><span>Web 页面自定义评审规则与智能体人格</span></div></li>
        </ul>
        <div class="hero-demo">
          <div class="demo-card demo-main">
            <div class="demo-head">
              <span class="badge sev-MAJOR">主要</span>
              <span class="demo-loc">OrderService.java:42</span>
              <span class="demo-score">78</span>
            </div>
            <div class="demo-msg">空指针风险：查询结果未判空，直接调用 .getUserId()</div>
            <div class="demo-diff"><span class="dl del">- Long uid = find(id).getUserId();</span><span class="dl add">+ Long uid = find(id).map(u -&gt; u.getUserId()).orElse(null);</span></div>
          </div>
          <div class="demo-card demo-float">
            <span class="demo-ftitle">质量门禁</span>
            <span class="demo-fval"><span class="ok-dot"></span>通过 · 无严重及以上问题</span>
          </div>
        </div>
      </div>
      <div class="hero-foot">CodeReview Hub v1.0.0 · 基于 open-code-review 设计理念</div>
    </div>
    <div class="login-panel">
      <div class="login-card">
        <div class="login-logo">
          <div class="logo-mark">CR</div>
          <div>
            <div class="login-title">CodeReview Hub</div>
            <div class="login-sub">AI 代码评审平台</div>
          </div>
        </div>
        <div class="login-expired" v-if="expired">登录已过期或已在别处失效，请重新登录</div>
        <form @submit.prevent="doLogin">
          <div class="field">
            <label>用户名</label>
            <input type="text" v-model="username" placeholder="请输入用户名" autocomplete="username">
          </div>
          <div class="field">
            <label>密码</label>
            <input type="password" v-model="password" placeholder="请输入密码" autocomplete="current-password">
          </div>
          <button type="submit" class="btn primary login-btn" :disabled="loading || !username || !password">
            {{ loading ? '登录中…' : '登  录' }}
          </button>
        </form>
        <div class="login-hint">默认管理员：admin / admin123（登录后请尽快修改密码）</div>
      </div>
    </div>
  </div>`,
    setup() {
        const username = ref('');
        const password = ref('');
        const loading = ref(false);
        const expired = ref(false);
        try {
            if (sessionStorage.getItem('crh_expired') === '1') {
                expired.value = true;
                sessionStorage.removeItem('crh_expired');
            }
        } catch (e) { /* ignore */ }

        async function doLogin() {
            loading.value = true;
            try {
                const res = await api.request('POST', '/api/auth/login', {
                    username: username.value.trim(), password: password.value,
                });
                saveAuth(res.token, res.user);
                // 恢复会话失效前的页面（无记录则回首页）
                let target = '#/';
                try {
                    target = sessionStorage.getItem('crh_redirect') || '#/';
                    sessionStorage.removeItem('crh_redirect');
                } catch (e) { /* ignore */ }
                if (location.hash !== target) location.hash = target;
                toast('欢迎回来，' + (res.user.displayName || res.user.username), 'success');
            } catch (e) {
                toast(e.message);
            } finally {
                loading.value = false;
            }
        }

        return { username, password, loading, expired, doLogin, IC: I };
    },
};

/* ============================================================
 * 仪表盘
 * ============================================================ */
const DashboardPage = {
    template: `
  <div class="page">
    <header class="page-head">
      <div>
        <h1>仪表盘</h1>
        <p class="sub">AI 代码评审总览 · 队列中 {{ stats ? stats.queueDepth : '…' }} 个任务等待评审</p>
      </div>
      <button class="btn primary" @click="showSubmit = true"><span v-html="IC.plus"></span>手动提交评审</button>
    </header>

    <section class="stat-grid" v-if="stats">
      <div class="stat-card">
        <div class="stat-val">{{ stats.totalReviews }}</div>
        <div class="stat-label">累计评审</div>
        <div class="stat-extra">完成 {{ stats.completed }} · 进行中 {{ stats.running }}</div>
      </div>
      <div class="stat-card c-green">
        <div class="stat-val">{{ stats.totalFindings }}</div>
        <div class="stat-label">累计发现问题</div>
        <div class="stat-extra">人工已确认 {{ stats.confirmedFindings }}</div>
      </div>
      <div class="stat-card c-red">
        <div class="stat-val">{{ sevN('BLOCKER') }}</div>
        <div class="stat-label">阻断问题</div>
        <div class="stat-extra">严重 {{ sevN('CRITICAL') }} · 主要 {{ sevN('MAJOR') }}</div>
      </div>
      <div class="stat-card c-amber">
        <div class="stat-val">{{ stats.activeWorkers }}</div>
        <div class="stat-label">并发评审线程</div>
        <div class="stat-extra">失败任务 {{ stats.failed }}</div>
      </div>
    </section>

    <section class="card">
      <div class="table-toolbar">
        <div class="filters">
          <div class="search-box">
            <span class="sb-ic" v-html="IC.search"></span>
            <input v-model="filter.q" placeholder="搜索仓库 / 分支 / 作者" @keyup.enter="reload">
            <button type="button" class="sb-clear" v-if="filter.q" @click="clearQ" v-html="IC.close" title="清空"></button>
          </div>
          <select v-model="filter.status" @change="reload" class="status-sel">
            <option value="">全部状态</option>
            <option v-for="(l, k) in ST_LABEL" :key="k" :value="k">{{ l }}</option>
          </select>
        </div>
        <div class="filters">
          <span class="result-count" v-if="total">共 {{ total }} 条结果</span>
          <button class="icon-btn" @click="reload" v-html="IC.refresh" title="刷新"></button>
        </div>
      </div>

      <table class="tbl" v-if="rows.length">
        <thead>
          <tr>
            <th style="width:64px">ID</th><th>仓库 / 分支</th><th style="width:110px">作者</th>
            <th style="width:90px">来源</th><th style="width:190px">问题</th>
            <th style="width:96px">状态</th><th style="width:64px">评分</th><th style="width:130px">提交时间</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rows" :key="r.id" class="rowlink" @click="go(r.id)">
            <td style="color:var(--muted);font-family:var(--mono);font-size:12px">#{{ r.id }}</td>
            <td>
              <div class="repo">{{ r.repoName }}</div>
              <div class="branch" v-if="r.branch">{{ r.branch }}</div>
            </td>
            <td style="color:var(--muted)">{{ r.author || '—' }}</td>
            <td><span class="badge" :class="'src-' + r.source">{{ SRC_LABEL[r.source] || r.source }}</span></td>
            <td>
              <div class="sev-chips">
                <span v-if="r.cntBlocker" class="sev-chip b" title="阻断">{{ r.cntBlocker }} 阻</span>
                <span v-if="r.cntCritical" class="sev-chip c" title="严重">{{ r.cntCritical }} 严</span>
                <span v-if="r.cntMajor" class="sev-chip m" title="主要">{{ r.cntMajor }} 主</span>
                <span v-if="r.cntMinor" class="sev-chip n" title="次要">{{ r.cntMinor }} 次</span>
                <span v-if="r.cntInfo" class="sev-chip i" title="提示">{{ r.cntInfo }} 提</span>
                <span v-if="!r.totalFindings" style="color:var(--success);font-size:12px">无问题</span>
              </div>
            </td>
            <td>
              <span class="badge" :class="'st-' + r.status">
                <span v-if="r.status === 'RUNNING'" class="spin-dot"></span>{{ ST_LABEL[r.status] || r.status }}
              </span>
            </td>
            <td style="font-weight:700">{{ r.score == null ? '—' : r.score }}</td>
            <td style="color:var(--muted);font-size:12.5px">{{ fmtTime(r.createdAt) }}</td>
          </tr>
        </tbody>
      </table>

      <div v-else-if="!loading" class="empty">
        <div class="empty-ic" v-html="IC.inbox"></div>
        <h4>还没有评审任务</h4>
        <p>点击右上角「手动提交评审」，或在 Jenkins 流水线中接入</p>
      </div>

      <div class="pager" v-if="total > pageSize">
        <span class="pg-info">共 {{ total }} 条 · 第 {{ page + 1 }} / {{ totalPages }} 页</span>
        <div class="pg-btns">
          <button :disabled="page === 0" @click="gotoPage(0)" title="首页">«</button>
          <button :disabled="page === 0" @click="gotoPage(page - 1)" title="上一页">‹</button>
          <button v-for="(p, i) in pageButtons" :key="i" :class="{ on: p === page }"
                  :disabled="p < 0" @click="gotoPage(p)">{{ p < 0 ? '…' : p + 1 }}</button>
          <button :disabled="page >= totalPages - 1" @click="gotoPage(page + 1)" title="下一页">›</button>
          <button :disabled="page >= totalPages - 1" @click="gotoPage(totalPages - 1)" title="末页">»</button>
        </div>
        <select v-model.number="pageSize" @change="reload" class="size-sel">
          <option :value="15">15 条/页</option>
          <option :value="30">30 条/页</option>
          <option :value="50">50 条/页</option>
        </select>
      </div>
    </section>

    <modal v-if="showSubmit" title="手动提交评审" @close="showSubmit = false">
      <div class="field">
        <label>仓库名 <span class="req">*</span></label>
        <input type="text" v-model="form.repoName" placeholder="例如 order-service">
      </div>
      <div class="form-row">
        <div class="field">
          <label>分支</label>
          <input type="text" v-model="form.branch" placeholder="feature/xxx">
        </div>
        <div class="field">
          <label>作者</label>
          <input type="text" v-model="form.author" placeholder="提交人">
        </div>
      </div>
      <div class="field">
        <label>Diff 内容 <span class="req">*</span></label>
        <textarea v-model="form.diff" class="mono" rows="12" placeholder="粘贴 git diff 输出，例如：git diff origin/main...HEAD"></textarea>
        <div class="hint">提示：提交时附带完整文件内容可提升评审质量（API 的 files 字段）</div>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="showSubmit = false">取消</button>
        <button class="btn primary" :disabled="submitting || !form.repoName || !form.diff" @click="submit">
          {{ submitting ? '提交中…' : '提交评审' }}
        </button>
      </div>
    </modal>
  </div>`,
    setup() {
        const stats = ref(null);
        const rows = ref([]);
        const loading = ref(false);
        const filter = reactive({ status: '', q: '' });
        const page = ref(0);
        const pageSize = ref(15);
        const total = ref(0);
        const showSubmit = ref(false);
        const submitting = ref(false);
        const form = reactive({ repoName: '', branch: '', author: '', diff: '' });

        const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)));
        /** 页码按钮序列（-1 表示省略号），当前页附近 + 首末页 */
        const pageButtons = computed(() => {
            const tp = totalPages.value, cur = page.value;
            if (tp <= 7) return Array.from({ length: tp }, (_, i) => i);
            const set = new Set([0, tp - 1, cur, cur - 1, cur + 1]);
            if (cur <= 3) [1, 2, 3, 4].forEach(i => set.add(i));
            if (cur >= tp - 4) [tp - 5, tp - 4, tp - 3, tp - 2].forEach(i => set.add(i));
            const arr = [...set].filter(i => i >= 0 && i < tp).sort((a, b) => a - b);
            const out = [];
            arr.forEach((n, i) => {
                if (i > 0 && n - arr[i - 1] > 1) out.push(-1);
                out.push(n);
            });
            return out;
        });

        function gotoPage(p) {
            if (p < 0 || p >= totalPages.value || p === page.value) return;
            page.value = p;
            load();
        }
        function clearQ() { filter.q = ''; reload(); }

        async function load() {
            loading.value = true;
            try {
                const p = new URLSearchParams();
                if (filter.status) p.set('status', filter.status);
                if (filter.q) p.set('q', filter.q);
                p.set('page', page.value);
                p.set('size', pageSize.value);
                const res = await api.get('/api/reviews?' + p.toString());
                rows.value = res.content;
                total.value = res.totalElements;
            } catch (e) {
                toast(e.message);
            } finally {
                loading.value = false;
            }
        }
        async function loadStats() {
            try { stats.value = await api.get('/api/reviews/stats'); } catch (e) { /* 静默 */ }
        }
        function reload() { page.value = 0; load(); loadStats(); }
        function go(id) { location.hash = '#/reviews/' + id; }
        function sevN(s) { return (stats.value && stats.value.bySeverity && stats.value.bySeverity[s]) || 0; }

        async function submit() {
            submitting.value = true;
            try {
                const res = await api.post('/api/reviews', {
                    repoName: form.repoName, branch: form.branch || null,
                    author: form.author || null, source: 'MANUAL', diff: form.diff,
                });
                toast('已提交评审任务 #' + res.id, 'success');
                showSubmit.value = false;
                location.hash = '#/reviews/' + res.id;
            } catch (e) {
                toast(e.message);
            } finally {
                submitting.value = false;
            }
        }

        let timer = null;
        onMounted(() => {
            load();
            loadStats();
            timer = setInterval(loadStats, 5000);
        });
        onUnmounted(() => clearInterval(timer));

        return {
            stats, rows, loading, filter, page, pageSize, total, totalPages, pageButtons,
            showSubmit, submitting, form,
            load, reload, go, gotoPage, clearQ, sevN, submit, IC: I, ST_LABEL, SRC_LABEL, fmtTime,
        };
    },
};

/* ============================================================
 * 评审详情
 * ============================================================ */
const ReviewDetailPage = {
    template: `
  <div class="page" v-if="r">
    <a class="back-link" href="#/"><span v-html="IC.back"></span>返回</a>

    <header class="card detail-head">
      <div class="d-main">
        <h1>
          {{ r.repoName }}
          <span class="branch-chip" v-if="r.branch">{{ r.branch }}</span>
          <span class="badge" :class="'st-' + r.status">
            <span v-if="r.status === 'RUNNING'" class="spin-dot"></span>{{ ST_LABEL[r.status] }}
          </span>
        </h1>
        <div class="meta">
          <span>#{{ r.id }}</span>
          <span v-if="r.commitId">提交 <b class="mono">{{ r.commitId.slice(0, 10) }}</b></span>
          <span v-if="r.author">作者 <b>{{ r.author }}</b></span>
          <span>来源 <b>{{ SRC_LABEL[r.source] || r.source }}</b></span>
          <span v-if="r.jenkinsJob">Jenkins <b>{{ r.jenkinsJob }} #{{ r.jenkinsBuild }}</b></span>
          <span>{{ fmtTime(r.createdAt) }}</span>
          <span v-if="duration">耗时 {{ duration }}</span>
        </div>
        <div class="summary-box" v-if="r.summary">{{ r.summary }}</div>
      </div>
      <div class="d-side">
        <div class="score-wrap" v-if="r.score != null">
          <div class="score-ring">
            <svg width="76" height="76">
              <circle cx="38" cy="38" r="31" fill="none" stroke="#E9EDF5" stroke-width="7"/>
              <circle cx="38" cy="38" r="31" fill="none" :stroke="scoreColor" stroke-width="7"
                      stroke-linecap="round" :stroke-dasharray="ringDash" style="transition:stroke-dasharray .6s"/>
            </svg>
            <div class="val" :style="'color:' + scoreColor">{{ r.score }}</div>
          </div>
          <div class="score-label">质量评分</div>
        </div>
        <div class="d-actions">
          <button class="btn ghost sm" @click="exportJson"><span v-html="IC.export"></span>导出 JSON</button>
          <button class="btn ghost sm" v-if="r.status === 'FAILED'" @click="retry"><span v-html="IC.refresh"></span>重试评审</button>
          <button class="btn ghost sm" @click="reload"><span v-html="IC.refresh"></span>刷新</button>
        </div>
      </div>
    </header>

    <div class="banner running" v-if="isActive">
      <span class="spinner"></span>
      {{ r.status === 'QUEUED' ? '排队等待中，前方约 ' + (r.queueDepth || 0) + ' 个任务' : 'AI 评审进行中，页面每 3 秒自动刷新…' }}
    </div>
    <div class="banner error" v-if="r.status === 'FAILED' && r.error">
      <span v-html="IC.alert"></span><span>{{ r.error }}</span>
    </div>

    <div class="detail-grid">
      <section class="findings-col">
        <div class="findings-inner">
          <div class="col-head">
            <h2>评审发现 <span class="count">{{ filtered.length }}</span></h2>
            <div class="filters">
              <select v-model="fSev" style="width:104px">
                <option value="">全部级别</option>
                <option v-for="s in SEV_ORDER" :key="s" :value="s">{{ SEV_LABEL[s] }}</option>
              </select>
              <select v-model="fStatus" style="width:104px">
                <option value="">全部状态</option>
                <option v-for="(l, k) in FS_LABEL" :key="k" :value="k">{{ l }}</option>
              </select>
            </div>
          </div>

          <div class="finding-card" v-for="f in filtered" :key="f.id" :id="'fl-' + f.id">
            <div class="f-top">
              <span class="badge" :class="'sev-' + f.severity">{{ f.severityLabel }}</span>
              <span class="rule-tag">{{ f.rule || '通用' }}</span>
              <a class="loc" @click="scrollToLine(f)" :title="f.filePath + ':' + f.lineNo">{{ f.filePath }}:{{ f.lineNo }}</a>
              <span class="f-status" :class="'fs-' + f.status">{{ f.statusLabel }}</span>
            </div>
            <div class="f-msg">{{ f.message }}</div>
            <details class="f-sug" v-if="f.suggestion">
              <summary>修改建议</summary>
              <div>{{ f.suggestion }}</div>
            </details>
            <div class="f-actions" v-if="r.status !== 'RUNNING'">
              <button :class="{ 'on-confirmed': f.status === 'CONFIRMED' }" @click="setStatus(f, 'CONFIRMED')">确认</button>
              <button :class="{ 'on-ignored': f.status === 'IGNORED' }" @click="setStatus(f, 'IGNORED')">忽略</button>
              <button :class="{ 'on-false_positive': f.status === 'FALSE_POSITIVE' }" @click="setStatus(f, 'FALSE_POSITIVE')">误报</button>
            </div>
            <textarea class="f-remark" v-model="remarks[f.id]" placeholder="添加备注…（失焦保存）"
                      v-if="r.status !== 'RUNNING'"></textarea>
            <div class="f-time" v-if="f.updatedAt">更新于 {{ fmtTime(f.updatedAt) }}</div>
          </div>

          <div v-if="!filtered.length" class="empty small">
            {{ r.status === 'SUCCESS' ? '没有符合条件的问题' : '评审完成后展示发现' }}
          </div>
        </div>
      </section>

      <section class="card diff-col">
        <div class="col-head">
          <h2>代码变更 <span class="count">{{ diffFiles.length }} 个文件</span></h2>
          <div class="diff-switch">
            <button :class="{ on: diffMode === 'split' }" @click="setDiffMode('split')">并排对比</button>
            <button :class="{ on: diffMode === 'unified' }" @click="setDiffMode('unified')">逐行对比</button>
          </div>
        </div>
        <div class="diff-body">
          <div class="diff-file" v-for="(df, fi) in diffFiles" :key="fi" :class="{ closed: !openMap[df.path] }">
            <div class="df-head" @click="toggleFile(df.path)">
              <span class="df-arrow">▼</span>
              <span class="df-path">{{ df.path }}</span>
              <span class="df-stats"><i class="add">+{{ df.added }}</i><i class="del">-{{ df.deleted }}</i></span>
            </div>
            <div v-show="openMap[df.path]">
              <!-- 两栏并排：左=旧版本，右=新版本 -->
              <template v-if="diffMode === 'split'">
                <template v-for="(row, ri) in splitRows(df)" :key="ri">
                  <div v-if="row.hunk" class="sline shunk">
                    <span class="lc">@@ -{{ row.oldStart }} +{{ row.newStart }} @@</span>
                  </div>
                  <div v-else class="sline" :data-f="fi" :data-l="row.r ? row.r.newNo : null">
                    <div class="scol" :class="row.l ? row.l.t : ''">
                      <span class="ln">{{ row.l ? (row.l.oldNo || '') : '' }}</span>
                      <span class="lc">{{ row.l ? row.l.text : '' }}</span>
                    </div>
                    <div class="scol" :class="row.r ? row.r.t : ''">
                      <span class="ln">{{ row.r ? (row.r.newNo || '') : '' }}</span>
                      <span class="lc">{{ row.r ? row.r.text : '' }}</span>
                      <button class="fmarker" v-if="row.r && markerCount(df, row.r)" :title="markerTitle(df, row.r)"
                              @click.stop="scrollToFindings(df, row.r)">{{ markerCount(df, row.r) }}</button>
                    </div>
                  </div>
                </template>
              </template>
              <!-- 单栏逐行 -->
              <template v-else>
                <template v-for="(l, li) in flatLines(df)" :key="li">
                  <div v-if="l.hunk" class="dline hunk">
                    <span class="ln old"></span><span class="ln new"></span>
                    <span class="lc">@@ -{{ l.oldStart }} +{{ l.newStart }} @@</span>
                  </div>
                  <div v-else class="dline" :class="l.t" :data-f="fi" :data-l="l.newNo">
                    <span class="ln old">{{ l.oldNo || '' }}</span>
                    <span class="ln new">{{ l.newNo || '' }}</span>
                    <span class="lc">{{ l.text }}</span>
                    <button class="fmarker" v-if="markerCount(df, l)" :title="markerTitle(df, l)"
                            @click.stop="scrollToFindings(df, l)">{{ markerCount(df, l) }}</button>
                  </div>
                </template>
              </template>
            </div>
          </div>
          <div v-if="!diffFiles.length" class="empty small">无 diff 内容</div>
        </div>
      </section>
    </div>
  </div>
  <div v-else class="page"><div class="empty"><div class="empty-ic spinner"></div><h4>加载中…</h4></div></div>`,
    setup() {
        const r = ref(null);
        const remarks = reactive({});
        const fSev = ref('');
        const fStatus = ref('');
        const openMap = reactive({});

        const idRef = computed(() => route.params.id);

        async function load() {
            try {
                r.value = await api.get('/api/reviews/' + idRef.value);
                r.value.findings.forEach(f => {
                    if (remarks[f.id] === undefined) remarks[f.id] = f.remark || '';
                });
                diffFiles.value.forEach(f => {
                    if (!(f.path in openMap)) openMap[f.path] = true;
                });
            } catch (e) {
                toast(e.message);
            }
        }

        const diffFiles = ref([]);
        watch(() => r.value && r.value.diffText, (t) => { diffFiles.value = parseDiff(t); }, { immediate: false });
        watch(() => r.value && r.value.id, () => {
            diffFiles.value = parseDiff(r.value ? r.value.diffText : '');
            Object.keys(openMap).forEach(k => delete openMap[k]);
            diffFiles.value.forEach(f => { openMap[f.path] = true; });
        });

        const isActive = computed(() => r.value && (r.value.status === 'QUEUED' || r.value.status === 'RUNNING'));
        const duration = computed(() => r.value ? fmtDur(r.value.startedAt, r.value.finishedAt) : null);
        const scoreColor = computed(() => {
            const s = r.value ? r.value.score : null;
            if (s == null) return '#98A5B8';
            if (s >= 85) return '#16A34A';
            if (s >= 70) return '#D97706';
            return '#DC2626';
        });
        const ringDash = computed(() => {
            const s = r.value ? (r.value.score || 0) : 0;
            const c = 2 * Math.PI * 31;
            return (c * s / 100).toFixed(1) + ' ' + c.toFixed(1);
        });

        const filtered = computed(() => {
            if (!r.value) return [];
            return r.value.findings.filter(f =>
                (!fSev.value || f.severity === fSev.value) &&
                (!fStatus.value || f.status === fStatus.value));
        });

        function findingsAt(df, line) {
            return (r.value ? r.value.findings : []).filter(f =>
                f.lineNo === line.newNo && matchPath(df, f.filePath));
        }
        function matchPath(df, p) {
            if (df.path === p) return true;
            return df.path.endsWith(p) || df.path.endsWith('/' + p);
        }
        function markerCount(df, l) {
            return l.t === 'add' ? findingsAt(df, l).length : 0;
        }
        function markerTitle(df, l) {
            return findingsAt(df, l).map(f => f.severityLabel + '：' + f.message).join('\n');
        }
        function flatLines(df) {
            const out = [];
            df.hunks.forEach(h => {
                out.push({ hunk: true, oldStart: h.oldStart, newStart: h.newStart });
                h.lines.forEach(l => out.push(l));
            });
            return out;
        }
        /** diff 查看模式：split=两栏并排 / unified=单栏逐行（localStorage 记忆） */
        const diffMode = ref(localStorage.getItem('crh_diff_mode') === 'unified' ? 'unified' : 'split');
        function setDiffMode(m) {
            diffMode.value = m;
            try { localStorage.setItem('crh_diff_mode', m); } catch (e) { /* ignore */ }
        }
        /** 并排模式：把 hunk 行配对成左右两栏行（左=旧代码，右=新代码），context 双栏同显 */
        function splitRows(df) {
            const out = [];
            df.hunks.forEach(h => {
                out.push({ hunk: true, oldStart: h.oldStart, newStart: h.newStart });
                let dels = [], adds = [];
                const flush = () => {
                    const n = Math.max(dels.length, adds.length);
                    for (let i = 0; i < n; i++) out.push({ l: dels[i] || null, r: adds[i] || null });
                    dels = []; adds = [];
                };
                h.lines.forEach(l => {
                    if (l.t === 'del') dels.push(l);
                    else if (l.t === 'add') adds.push(l);
                    else { flush(); out.push({ l: l, r: l }); }
                });
                flush();
            });
            return out;
        }
        function toggleFile(path) { openMap[path] = !openMap[path]; }

        function fileIndexOf(path) {
            const idx = diffFiles.value.findIndex(df => matchPath(df, path));
            return idx >= 0 ? idx : 0;
        }
        function scrollToLine(f) {
            const fi = fileIndexOf(f.filePath);
            openMap[diffFiles.value[fi].path] = true;
            nextTick(() => {
                const el = document.querySelector('[data-f="' + fi + '"][data-l="' + f.lineNo + '"]');
                if (el) {
                    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    el.classList.remove('flash');
                    void el.offsetWidth;
                    el.classList.add('flash');
                }
            });
        }
        function scrollToFindings(df, l) {
            const fs = findingsAt(df, l);
            if (fs.length) {
                const el = document.getElementById('fl-' + fs[0].id);
                if (el) {
                    el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                    el.classList.remove('flash');
                    void el.offsetWidth;
                    el.classList.add('flash');
                }
            }
        }

        async function setStatus(f, status) {
            try {
                const upd = await api.put('/api/reviews/' + idRef.value + '/findings/' + f.id, { status });
                Object.assign(f, upd);
                toast('已标记为「' + upd.statusLabel + '」', 'success');
            } catch (e) { toast(e.message); }
        }
        async function saveRemark(f) {
            const v = remarks[f.id] || '';
            if ((f.remark || '') === v.trim()) return;
            try {
                const upd = await api.put('/api/reviews/' + idRef.value + '/findings/' + f.id, { remark: v });
                Object.assign(f, upd);
                toast('备注已保存', 'success');
            } catch (e) { toast(e.message); }
        }
        async function retry() {
            try {
                await api.post('/api/reviews/' + idRef.value + '/retry');
                toast('已重新入队', 'success');
                load();
            } catch (e) { toast(e.message); }
        }
        function reload() { load(); }

        function exportJson() {
            const blob = new Blob([JSON.stringify({ review: r.value, generatedAt: new Date().toISOString() }, null, 2)], { type: 'application/json' });
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = 'review-' + idRef.value + '.json';
            a.click();
            URL.revokeObjectURL(a.href);
        }

        let timer = null;
        onMounted(() => {
            load();
            timer = setInterval(() => { if (isActive.value) load(); }, 3000);
        });
        onUnmounted(() => clearInterval(timer));
        watch(idRef, () => { r.value = null; diffFiles.value = []; load(); });

        return {
            r, remarks, fSev, fStatus, openMap, diffFiles, filtered, isActive, duration,
            scoreColor, ringDash, IC: I, ST_LABEL, SRC_LABEL, SEV_ORDER, SEV_LABEL, FS_LABEL,
            fmtTime, flatLines, toggleFile, markerCount, markerTitle, scrollToLine, scrollToFindings,
            setStatus, saveRemark, retry, reload, exportJson, diffMode, setDiffMode, splitRows,
        };
    },
};

/* ============================================================
 * 规则管理
 * ============================================================ */
const RulesPage = {
    template: `
  <div class="page">
    <header class="page-head">
      <div>
        <h1>评审规则</h1>
        <p class="sub">规则决定哪些文件会被评审、以什么严格程度评审（规则路由为确定性逻辑）</p>
      </div>
      <button class="btn primary" @click="openCreate"><span v-html="IC.plus"></span>新建规则</button>
    </header>

    <div class="notice"><span v-html="IC.info"></span>
      <span>文件模式使用 Ant 通配符（** 匹配任意目录，* 匹配文件名片段），逗号分隔多个。规则会作为约束注入评审提示词，仅报告规则范畴内的问题。</span>
    </div>

    <section class="card">
      <table class="tbl" v-if="rows.length">
        <thead>
          <tr>
            <th>规则</th><th>说明</th><th style="width:210px">文件模式</th>
            <th style="width:90px">默认级别</th><th style="width:90px">启用</th><th style="width:110px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.id">
            <td>
              <b>{{ row.name }}</b> <span v-if="row.builtin" class="builtin-tag">内置</span>
            </td>
            <td style="color:var(--muted);font-size:12.5px">{{ row.description || '—' }}</td>
            <td><span class="mono-chip">{{ row.filePattern }}</span></td>
            <td><span class="badge" :class="'sev-' + row.severity">{{ SEV_LABEL[row.severity] }}</span></td>
            <td><toggle-switch :model-value="row.enabled" @update:model-value="v => toggle(row, v)"></toggle-switch></td>
            <td>
              <div style="display:flex;gap:2px">
                <button class="icon-btn" @click="openEdit(row)" v-html="IC.edit" title="编辑"></button>
                <button class="icon-btn" v-if="!row.builtin" @click="del(row)" v-html="IC.trash" title="删除"></button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty"><h4>暂无规则</h4><p>点击右上角新建</p></div>
    </section>

    <modal v-if="editing" :title="form.id ? '编辑规则' : '新建规则'" @close="editing = false">
      <div class="form-row">
        <div class="field">
          <label>规则名 <span class="req">*</span></label>
          <input type="text" v-model="form.name" placeholder="例如：空指针防护">
        </div>
        <div class="field">
          <label>默认严重级别</label>
          <select v-model="form.severity">
            <option v-for="s in SEV_ORDER" :key="s" :value="s">{{ SEV_LABEL[s] }}</option>
          </select>
        </div>
      </div>
      <div class="field">
        <label>文件模式 <span class="req">*</span></label>
        <input type="text" v-model="form.filePattern" class="mono" placeholder="**/*.java,**/*.kt">
        <div class="hint">Ant 通配符，逗号分隔多个</div>
      </div>
      <div class="field">
        <label>说明</label>
        <input type="text" v-model="form.description" placeholder="一句话描述该规则关注点">
      </div>
      <div class="field">
        <label>给模型的补充提示</label>
        <textarea v-model="form.prompt" rows="5" placeholder="该规则具体关注哪些代码模式，将注入评审提示词"></textarea>
      </div>
      <div class="field">
        <toggle-switch :model-value="form.enabled" @update:model-value="v => form.enabled = v" label="启用该规则"></toggle-switch>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="editing = false">取消</button>
        <button class="btn primary" :disabled="!form.name || !form.filePattern" @click="save">保存</button>
      </div>
    </modal>
  </div>`,
    setup() {
        const rows = ref([]);
        const editing = ref(false);
        const form = reactive({ id: null, name: '', description: '', filePattern: '', severity: 'MAJOR', prompt: '', enabled: true });

        async function load() {
            try { rows.value = await api.get('/api/rules'); } catch (e) { toast(e.message); }
        }
        function openCreate() {
            Object.assign(form, { id: null, name: '', description: '', filePattern: '', severity: 'MAJOR', prompt: '', enabled: true });
            editing.value = true;
        }
        function openEdit(row) {
            Object.assign(form, {
                id: row.id, name: row.name, description: row.description || '',
                filePattern: row.filePattern || '', severity: row.severity,
                prompt: row.prompt || '', enabled: row.enabled,
            });
            editing.value = true;
        }
        async function save() {
            try {
                const body = {
                    name: form.name, description: form.description, filePattern: form.filePattern,
                    severity: form.severity, prompt: form.prompt, enabled: form.enabled,
                };
                if (form.id) await api.put('/api/rules/' + form.id, body);
                else await api.post('/api/rules', body);
                toast('规则已保存', 'success');
                editing.value = false;
                load();
            } catch (e) { toast(e.message); }
        }
        async function toggle(row, v) {
            try {
                await api.put('/api/rules/' + row.id, {
                    name: row.name, description: row.description, filePattern: row.filePattern,
                    severity: row.severity, prompt: row.prompt, enabled: v,
                });
                row.enabled = v;
            } catch (e) { toast(e.message); }
        }
        async function del(row) {
            if (!confirm('确认删除规则「' + row.name + '」？')) return;
            try {
                await api.del('/api/rules/' + row.id);
                toast('已删除', 'success');
                load();
            } catch (e) { toast(e.message); }
        }
        onMounted(load);
        return { rows, editing, form, openCreate, openEdit, save, toggle, del, IC: I, SEV_ORDER, SEV_LABEL };
    },
};

/* ============================================================
 * 技能管理
 * ============================================================ */
const SkillsPage = {
    template: `
  <div class="page">
    <header class="page-head">
      <div>
        <h1>评审技能</h1>
        <p class="sub">技能 = 评审智能体的人格与系统提示词，每次评审使用默认技能</p>
      </div>
      <button class="btn primary" @click="openCreate"><span v-html="IC.plus"></span>新建技能</button>
    </header>

    <section class="skill-grid">
      <div class="card skill-card" v-for="s in rows" :key="s.id">
        <div class="sk-head">
          <h3>{{ s.name }}</h3>
          <span v-if="s.defaulted" class="default-tag">默认</span>
          <span v-if="s.builtin" class="builtin-tag">内置</span>
        </div>
        <div class="skill-desc">{{ s.description || '无描述' }}</div>
        <div class="skill-prompt">{{ s.systemPrompt }}</div>
        <div class="skill-foot">
          <toggle-switch :model-value="s.enabled" @update:model-value="v => toggle(s, v)"></toggle-switch>
          <div class="ops">
            <button class="btn ghost sm" v-if="!s.defaulted && s.enabled" @click="setDefault(s)">设为默认</button>
            <button class="icon-btn" @click="openEdit(s)" v-html="IC.edit" title="编辑"></button>
            <button class="icon-btn" v-if="!s.builtin && !s.defaulted" @click="del(s)" v-html="IC.trash" title="删除"></button>
          </div>
        </div>
      </div>
    </section>

    <modal v-if="editing" :title="form.id ? '编辑技能' : '新建技能'" wide @close="editing = false">
      <div class="form-row">
        <div class="field">
          <label>技能名 <span class="req">*</span></label>
          <input type="text" v-model="form.name" placeholder="例如：安全审计专家">
        </div>
        <div class="field">
          <label>描述</label>
          <input type="text" v-model="form.description" placeholder="一句话说明该技能定位">
        </div>
      </div>
      <div class="field">
        <label>系统提示词 <span class="req">*</span></label>
        <textarea v-model="form.systemPrompt" rows="12" placeholder="定义评审智能体的角色、原则与关注重点…"></textarea>
        <div class="hint">将作为 system prompt 注入，引擎会自动追加规则列表与输出格式约束</div>
      </div>
      <div class="field">
        <toggle-switch :model-value="form.enabled" @update:model-value="v => form.enabled = v" label="启用该技能"></toggle-switch>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="editing = false">取消</button>
        <button class="btn primary" :disabled="!form.name || !form.systemPrompt" @click="save">保存</button>
      </div>
    </modal>
  </div>`,
    setup() {
        const rows = ref([]);
        const editing = ref(false);
        const form = reactive({ id: null, name: '', description: '', systemPrompt: '', enabled: true });

        async function load() {
            try { rows.value = await api.get('/api/skills'); } catch (e) { toast(e.message); }
        }
        function openCreate() {
            Object.assign(form, { id: null, name: '', description: '', systemPrompt: '', enabled: true });
            editing.value = true;
        }
        function openEdit(s) {
            Object.assign(form, { id: s.id, name: s.name, description: s.description || '', systemPrompt: s.systemPrompt, enabled: s.enabled });
            editing.value = true;
        }
        async function save() {
            try {
                const body = { name: form.name, description: form.description, systemPrompt: form.systemPrompt, enabled: form.enabled };
                if (form.id) await api.put('/api/skills/' + form.id, body);
                else await api.post('/api/skills', body);
                toast('技能已保存', 'success');
                editing.value = false;
                load();
            } catch (e) { toast(e.message); }
        }
        async function toggle(s, v) {
            try {
                await api.put('/api/skills/' + s.id, { name: s.name, description: s.description, systemPrompt: s.systemPrompt, enabled: v });
                s.enabled = v;
            } catch (e) { toast(e.message); }
        }
        async function setDefault(s) {
            try {
                await api.put('/api/skills/' + s.id + '/default');
                toast('已将「' + s.name + '」设为默认技能', 'success');
                load();
            } catch (e) { toast(e.message); }
        }
        async function del(s) {
            if (!confirm('确认删除技能「' + s.name + '」？')) return;
            try {
                await api.del('/api/skills/' + s.id);
                toast('已删除', 'success');
                load();
            } catch (e) { toast(e.message); }
        }
        onMounted(load);
        return { rows, editing, form, openCreate, openEdit, save, toggle, setDefault, del, IC: I };
    },
};

/* ============================================================
 * 系统设置
 * ============================================================ */
const SettingsPage = {
    template: `
  <div class="page">
    <header class="page-head">
      <div>
        <h1>系统设置</h1>
        <p class="sub">模型连接、评审引擎参数与安全配置（保存后立即生效，无需重启）</p>
      </div>
    </header>

    <div class="settings-grid">
      <section class="card settings-section">
        <h2><span v-html="IC.zap"></span>大模型连接</h2>
        <p class="sec-sub">兼容 OpenAI Chat Completions 协议的私有大模型（vLLM、Ollama、OneAPI、DashScope 兼容模式等）</p>

        <div class="field">
          <label>提供者</label>
          <select v-model="llm.provider">
            <option value="mock">Mock（内置演示，无需模型）</option>
            <option value="openai">OpenAI 兼容端点（私有大模型）</option>
          </select>
        </div>
        <div class="field">
          <label>Base URL</label>
          <input type="text" v-model="llm.baseUrl" placeholder="http://your-model:8000/v1">
          <div class="hint">填到 /v1 即可，平台自动拼接 /chat/completions</div>
        </div>
        <div class="form-row">
          <div class="field">
            <label>模型名称</label>
            <input type="text" v-model="llm.model" placeholder="例如 Qwen2.5-72B-Instruct">
          </div>
          <div class="field">
            <label>Temperature</label>
            <input type="text" v-model="llm.temperature" placeholder="0.1">
          </div>
        </div>
        <div class="field">
          <label>API Key
            <span v-if="orig.apiKeySet" class="key-set">已配置</span>
          </label>
          <div style="display:flex;gap:8px">
            <input type="password" v-model="llm.apiKey" placeholder="留空表示不修改" autocomplete="new-password">
            <button class="btn ghost sm" v-if="orig.apiKeySet" @click="clearLlmKey">清除</button>
          </div>
        </div>

        <div style="display:flex;gap:10px;align-items:center">
          <button class="btn primary" @click="saveLlm">保存模型配置</button>
          <button class="btn ghost" :disabled="testing" @click="testLlm">{{ testing ? '测试中…' : '测试连接' }}</button>
        </div>
        <div v-if="testRes" class="test-result" :class="testRes.ok ? 'ok' : 'bad'">
          {{ testRes.ok ? '连接成功，耗时 ' + testRes.latencyMs + 'ms，模型回复：' + testRes.reply : '连接失败：' + testRes.error }}
        </div>
      </section>

      <div>
        <section class="card settings-section" style="margin-bottom:16px">
          <h2><span v-html="IC.rules"></span>评审引擎</h2>
          <p class="sec-sub">调整评审深度与质量门禁阈值</p>
          <div class="form-row">
            <div class="field">
              <label>Agent 最大轮次</label>
              <input type="number" v-model.number="engine.maxRounds" min="1" max="20">
            </div>
            <div class="field">
              <label>单组最大 diff 行数</label>
              <input type="number" v-model.number="engine.groupMaxLines" min="100" max="5000">
            </div>
          </div>
          <div class="field">
            <label>反思过滤</label>
            <toggle-switch :model-value="engine.reflection" @update:model-value="v => engine.reflection = v"
                    label="评审后二次核对发现，过滤误报（消耗额外 token）"></toggle-switch>
          </div>
          <div class="field">
            <label>质量门禁阈值</label>
            <select v-model="engine.gateSeverity">
              <option value="BLOCKER">阻断（BLOCKER）及以上才拦截流水线</option>
              <option value="CRITICAL">严重（CRITICAL）及以上拦截（推荐）</option>
              <option value="MAJOR">主要（MAJOR）及以上拦截</option>
              <option value="MINOR">次要（MINOR）及以上拦截</option>
              <option value="INFO">任何问题都拦截</option>
            </select>
            <div class="hint">Jenkins 门禁接口据此判断是否放行</div>
          </div>
          <button class="btn primary" @click="saveEngine">保存引擎配置</button>
        </section>

        <section class="card settings-section">
          <h2><span v-html="IC.settings"></span>安全</h2>
          <p class="sec-sub">Jenkins 接入密钥；配置后 /api/jenkins/** 必须携带 X-API-Key 请求头</p>
          <div class="field">
            <label>接入密钥
              <span v-if="orig.hubApiKeySet" class="key-set">已配置</span>
            </label>
            <div style="display:flex;gap:8px">
              <input type="password" v-model="sec.hubApiKey" placeholder="留空表示不修改" autocomplete="new-password">
              <button class="btn ghost sm" v-if="orig.hubApiKeySet" @click="clearHubKey">清除</button>
            </div>
            <div class="hint">内网环境可不配置（默认开放）</div>
          </div>
          <button class="btn primary" @click="saveSec">保存安全配置</button>
        </section>
      </div>
    </div>
  </div>`,
    setup() {
        const llm = reactive({ provider: 'mock', baseUrl: '', apiKey: '', model: '', temperature: '0.1' });
        const engine = reactive({ maxRounds: 6, groupMaxLines: 600, reflection: true, gateSeverity: 'CRITICAL' });
        const sec = reactive({ hubApiKey: '' });
        const orig = reactive({ apiKeySet: false, hubApiKeySet: false });
        const testing = ref(false);
        const testRes = ref(null);

        async function load() {
            try {
                const v = await api.get('/api/settings');
                llm.provider = v.provider; llm.baseUrl = v.baseUrl; llm.model = v.model; llm.temperature = v.temperature;
                engine.maxRounds = v.maxRounds; engine.groupMaxLines = v.groupMaxLines;
                engine.reflection = v.reflection; engine.gateSeverity = v.gateSeverity;
                orig.apiKeySet = v.apiKeySet; orig.hubApiKeySet = v.hubApiKeySet;
            } catch (e) { toast(e.message); }
        }
        async function saveLlm() {
            try {
                const body = { provider: llm.provider, baseUrl: llm.baseUrl, model: llm.model, temperature: llm.temperature };
                if (llm.apiKey) body.apiKey = llm.apiKey;
                await api.put('/api/settings', body);
                llm.apiKey = '';
                toast('模型配置已保存', 'success');
                load();
            } catch (e) { toast(e.message); }
        }
        async function saveEngine() {
            try {
                await api.put('/api/settings', {
                    maxRounds: engine.maxRounds, groupMaxLines: engine.groupMaxLines,
                    reflection: engine.reflection, gateSeverity: engine.gateSeverity,
                });
                toast('引擎配置已保存', 'success');
            } catch (e) { toast(e.message); }
        }
        async function saveSec() {
            try {
                if (sec.hubApiKey) await api.put('/api/settings', { hubApiKey: sec.hubApiKey });
                sec.hubApiKey = '';
                toast('安全配置已保存', 'success');
                load();
            } catch (e) { toast(e.message); }
        }
        async function clearLlmKey() {
            try { await api.del('/api/settings/llm-api-key'); toast('已清除模型密钥', 'success'); load(); } catch (e) { toast(e.message); }
        }
        async function clearHubKey() {
            try { await api.del('/api/settings/hub-api-key'); toast('已清除接入密钥', 'success'); load(); } catch (e) { toast(e.message); }
        }
        async function testLlm() {
            testing.value = true;
            testRes.value = null;
            try {
                if (llm.apiKey) await api.put('/api/settings', { apiKey: llm.apiKey });
                if (llm.baseUrl || llm.model) await api.put('/api/settings', { baseUrl: llm.baseUrl, model: llm.model, provider: llm.provider });
                testRes.value = await api.post('/api/settings/test-llm');
                load();
            } catch (e) {
                testRes.value = { ok: false, error: e.message };
            } finally {
                testing.value = false;
            }
        }
        onMounted(load);
        return { llm, engine, sec, orig, testing, testRes, saveLlm, saveEngine, saveSec, clearLlmKey, clearHubKey, testLlm, IC: I };
    },
};

/* ============================================================
 * 用户管理（仅管理员）
 * ============================================================ */
const UsersPage = {
    template: `
  <div class="page">
    <header class="page-head">
      <div>
        <h1>用户管理</h1>
        <p class="sub">平台账号与角色权限管理，密码以 MD5 密文存储</p>
      </div>
      <button class="btn primary" @click="openCreate"><span v-html="IC.plus"></span>新建用户</button>
    </header>

    <div class="notice"><span v-html="IC.info"></span>
      <span>管理员可管理规则、技能、系统设置与用户；普通用户可查看评审结果并标记问题状态与备注。重置密码或禁用账号后，该用户会被强制下线。</span>
    </div>

    <section class="card">
      <table class="tbl" v-if="rows.length">
        <thead>
          <tr>
            <th>用户名</th><th>显示名</th><th style="width:100px">角色</th>
            <th style="width:80px">状态</th><th style="width:150px">创建时间</th><th style="width:150px">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in rows" :key="u.id">
            <td>
              <b>{{ u.username }}</b>
              <span v-if="u.id === me.id" class="builtin-tag" style="margin-left:6px">当前账号</span>
            </td>
            <td style="color:var(--muted)">{{ u.displayName || '—' }}</td>
            <td><span class="badge" :class="'role-' + u.role">{{ u.roleLabel }}</span></td>
            <td><toggle-switch :model-value="u.enabled" @update:model-value="v => toggle(u, v)"></toggle-switch></td>
            <td style="color:var(--muted);font-size:12.5px">{{ fmtTime(u.createdAt) }}</td>
            <td>
              <div style="display:flex;gap:2px">
                <button class="icon-btn" @click="openEdit(u)" v-html="IC.edit" title="编辑"></button>
                <button class="icon-btn" @click="openReset(u)" v-html="IC.key" title="重置密码"></button>
                <button class="icon-btn" v-if="u.id !== me.id" @click="del(u)" v-html="IC.trash" title="删除"></button>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty"><h4>暂无用户</h4></div>
    </section>

    <modal v-if="editing" :title="form.id ? '编辑用户' : '新建用户'" @close="editing = false">
      <div class="form-row">
        <div class="field">
          <label>用户名 <span class="req" v-if="!form.id">*</span></label>
          <input type="text" v-model="form.username" :disabled="!!form.id" placeholder="登录账号">
        </div>
        <div class="field">
          <label>显示名</label>
          <input type="text" v-model="form.displayName" placeholder="例如：张三">
        </div>
      </div>
      <div class="field" v-if="!form.id">
        <label>初始密码 <span class="req">*</span></label>
        <input type="password" v-model="form.password" placeholder="6-100 位" autocomplete="new-password">
      </div>
      <div class="field">
        <label>角色</label>
        <select v-model="form.role">
          <option value="USER">普通用户（查看评审、标记问题）</option>
          <option value="ADMIN">管理员（全部权限）</option>
        </select>
      </div>
      <div class="field" v-if="form.id">
        <toggle-switch :model-value="form.enabled" @update:model-value="v => form.enabled = v" label="启用账号"></toggle-switch>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="editing = false">取消</button>
        <button class="btn primary" :disabled="!form.username || (!form.id && !form.password)" @click="save">保存</button>
      </div>
    </modal>

    <modal v-if="resetting" :title="'重置密码：' + resetUser.username" @close="resetting = false">
      <div class="field">
        <label>新密码 <span class="req">*</span></label>
        <input type="password" v-model="newPassword" placeholder="6-100 位" autocomplete="new-password">
        <div class="hint">重置后该用户的全部登录会话将失效，需用新密码重新登录</div>
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="resetting = false">取消</button>
        <button class="btn primary" :disabled="!newPassword || newPassword.length < 6" @click="doReset">确认重置</button>
      </div>
    </modal>
  </div>`,
    setup() {
        const rows = ref([]);
        const editing = ref(false);
        const resetting = ref(false);
        const resetUser = ref(null);
        const newPassword = ref('');
        const form = reactive({ id: null, username: '', displayName: '', password: '', role: 'USER', enabled: true });

        const me = computed(() => auth.user || { id: -1 });

        async function load() {
            try { rows.value = await api.get('/api/users'); } catch (e) { toast(e.message); }
        }
        function openCreate() {
            Object.assign(form, { id: null, username: '', displayName: '', password: '', role: 'USER', enabled: true });
            editing.value = true;
        }
        function openEdit(u) {
            Object.assign(form, { id: u.id, username: u.username, displayName: u.displayName || '', password: '', role: u.role, enabled: u.enabled });
            editing.value = true;
        }
        function openReset(u) {
            resetUser.value = u;
            newPassword.value = '';
            resetting.value = true;
        }
        async function save() {
            try {
                if (form.id) {
                    await api.put('/api/users/' + form.id, { displayName: form.displayName, role: form.role, enabled: form.enabled });
                } else {
                    await api.post('/api/users', { username: form.username.trim(), password: form.password, displayName: form.displayName, role: form.role });
                }
                toast('用户已保存', 'success');
                editing.value = false;
                load();
            } catch (e) { toast(e.message); }
        }
        async function doReset() {
            try {
                await api.put('/api/users/' + resetUser.value.id + '/reset-password', { newPassword: newPassword.value });
                toast('已重置 ' + resetUser.value.username + ' 的密码', 'success');
                resetting.value = false;
            } catch (e) { toast(e.message); }
        }
        async function toggle(u, v) {
            try {
                await api.put('/api/users/' + u.id, { displayName: u.displayName, role: u.role, enabled: v });
                u.enabled = v;
            } catch (e) { toast(e.message); }
        }
        async function del(u) {
            if (!confirm('确认删除用户「' + u.username + '」？')) return;
            try {
                await api.del('/api/users/' + u.id);
                toast('已删除', 'success');
                load();
            } catch (e) { toast(e.message); }
        }
        onMounted(load);
        return { rows, editing, resetting, resetUser, newPassword, form, me, openCreate, openEdit, openReset, save, doReset, toggle, del, IC: I, fmtTime };
    },
};

/* ---------------- 无权限提示 ---------------- */
const NoPerm = {
    template: `
  <div class="page">
    <div class="empty">
      <div class="empty-ic" v-html="IC.alert"></div>
      <h4>无访问权限</h4>
      <p>该页面仅管理员可见，请联系管理员开通权限</p>
    </div>
  </div>`,
    data() { return { IC: I }; },
};

/* ============================================================
 * 根组件（登录门卫 + 布局）
 * ============================================================ */
const App = {
    template: `
  <div class="layout" v-if="auth.user" :class="{ collapsed: sideCollapsed }">
    <aside class="sidebar">
      <div class="logo">
        <div class="logo-mark">CR</div>
        <div class="logo-txt">
          <div class="logo-name">CodeReview Hub</div>
          <div class="logo-sub">AI 代码评审平台</div>
        </div>
        <button class="side-toggle" @click="toggleSide" :title="sideCollapsed ? '展开导航栏' : '收起导航栏'">
          <span v-html="sideCollapsed ? IC.chevR : IC.back"></span>
        </button>
      </div>
      <nav>
        <a v-for="n in visibleNavs" :key="n.name" :href="'#/' + n.hash" :class="{ active: route.name === n.name }" :title="n.label">
          <span class="nav-ic" v-html="n.icon"></span><span class="nav-label">{{ n.label }}</span>
        </a>
      </nav>
      <div class="sidebar-foot">
        <div class="user-zone">
          <div class="user-menu" v-if="menuOpen">
            <a @click="openChangePw"><span v-html="IC.key"></span>修改密码</a>
            <a @click="doLogout"><span v-html="IC.logout"></span>退出登录</a>
          </div>
          <div class="user-chip" @click="menuOpen = !menuOpen">
            <span class="user-ava">{{ avatarChar }}</span>
            <div class="user-meta">
              <div class="user-name">{{ auth.user.displayName || auth.user.username }}</div>
              <div class="user-role">{{ auth.user.roleLabel }}</div>
            </div>
            <span class="user-caret">▲</span>
          </div>
          <div class="ver-line"><span class="dot"></span>v1.0.0 · 服务运行中</div>
        </div>
      </div>
    </aside>
    <main class="main">
      <component :is="view"></component>
    </main>

    <modal v-if="showChangePw" title="修改密码" @close="showChangePw = false">
      <div class="field">
        <label>原密码 <span class="req">*</span></label>
        <input type="password" v-model="pw.old" autocomplete="current-password">
      </div>
      <div class="field">
        <label>新密码 <span class="req">*</span></label>
        <input type="password" v-model="pw.neo" placeholder="6-100 位" autocomplete="new-password">
      </div>
      <div class="field">
        <label>确认新密码 <span class="req">*</span></label>
        <input type="password" v-model="pw.repeat" autocomplete="new-password">
      </div>
      <div class="modal-foot">
        <button class="btn ghost" @click="showChangePw = false">取消</button>
        <button class="btn primary" :disabled="!pw.old || !pw.neo || pw.neo.length < 6" @click="submitChangePw">保存</button>
      </div>
    </modal>
  </div>

  <login-page v-else-if="!booting"></login-page>
  <div v-else class="boot-splash"><div class="spinner"></div></div>

  <div class="toasts">
    <div class="toast" v-for="t in toastState.list" :key="t.id" :class="t.type">
      <span v-html="t.type === 'success' ? IC.check : IC.alert"></span>{{ t.msg }}
    </div>
  </div>`,
    setup() {
        const booting = ref(true);
        const menuOpen = ref(false);
        const showChangePw = ref(false);
        const pw = reactive({ old: '', neo: '', repeat: '' });

        /** 侧栏收起状态（localStorage 记忆，收起后仅留图标，读代码空间更大） */
        const sideCollapsed = ref(localStorage.getItem('crh_side_collapsed') === '1');
        function toggleSide() {
            sideCollapsed.value = !sideCollapsed.value;
            try { localStorage.setItem('crh_side_collapsed', sideCollapsed.value ? '1' : '0'); } catch (e) { /* ignore */ }
        }

        const navs = [
            { name: 'dashboard', hash: '', label: '仪表盘', icon: I.dashboard },
            { name: 'rules', hash: 'rules', label: '评审规则', icon: I.rules, admin: true },
            { name: 'skills', hash: 'skills', label: '评审技能', icon: I.skills, admin: true },
            { name: 'users', hash: 'users', label: '用户管理', icon: I.users, admin: true },
            { name: 'settings', hash: 'settings', label: '系统设置', icon: I.settings, admin: true },
        ];
        const views = {
            'dashboard': DashboardPage,
            'review-detail': ReviewDetailPage,
            'rules': RulesPage,
            'skills': SkillsPage,
            'users': UsersPage,
            'settings': SettingsPage,
        };
        const ADMIN_ONLY = ['rules', 'skills', 'users', 'settings'];

        const visibleNavs = computed(() =>
            navs.filter(n => !n.admin || (auth.user && auth.user.role === 'ADMIN')));
        const view = computed(() => {
            if (!auth.user) return LoginPage;
            if (ADMIN_ONLY.includes(route.name) && auth.user.role !== 'ADMIN') return NoPerm;
            return views[route.name] || DashboardPage;
        });
        const avatarChar = computed(() => {
            const s = auth.user ? (auth.user.displayName || auth.user.username || '?') : '?';
            return s.trim().charAt(0).toUpperCase();
        });

        onMounted(async () => {
            if (auth.token) {
                try {
                    auth.user = await api.get('/api/auth/me');
                } catch (e) {
                    /* 401 时已清理会话，展示登录页 */
                }
            }
            booting.value = false;
        });

        // 会话主动探测：页面重新可见/获得焦点时节流校验一次，
        // 挂机期间会话过期（24h）或被管理员重置时，无需任何操作即自动跳回登录页
        let lastProbe = 0;
        async function probeSession() {
            if (!auth.token || document.visibilityState !== 'visible') return;
            if (Date.now() - lastProbe < 60 * 1000) return;
            lastProbe = Date.now();
            try { auth.user = await api.get('/api/auth/me'); } catch (e) { /* 401 已自动跳登录页 */ }
        }
        document.addEventListener('visibilitychange', probeSession);
        window.addEventListener('focus', probeSession);
        onUnmounted(() => {
            document.removeEventListener('visibilitychange', probeSession);
            window.removeEventListener('focus', probeSession);
        });

        function openChangePw() {
            menuOpen.value = false;
            pw.old = ''; pw.neo = ''; pw.repeat = '';
            showChangePw.value = true;
        }
        async function submitChangePw() {
            if (pw.neo !== pw.repeat) {
                toast('两次输入的新密码不一致');
                return;
            }
            try {
                await api.post('/api/auth/change-password', { oldPassword: pw.old, newPassword: pw.neo });
                toast('密码已修改', 'success');
                showChangePw.value = false;
            } catch (e) { toast(e.message); }
        }
        async function doLogout() {
            menuOpen.value = false;
            try { await api.post('/api/auth/logout'); } catch (e) { /* 忽略 */ }
            saveAuth('', null);
            location.hash = '#/';
            toast('已退出登录', 'success');
        }

        return {
            auth, booting, menuOpen, showChangePw, pw,
            visibleNavs, view, avatarChar, route, toastState,
            openChangePw, submitChangePw, doLogout, IC: I,
            sideCollapsed, toggleSide,
        };
    },
};

const app = createApp(App);
app.component('modal', Modal);
app.component('toggle-switch', Switch);
app.component('login-page', LoginPage);
app.mount('#app');
