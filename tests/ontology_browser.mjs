// 本体平台真实浏览器验收（沿用 tests/browser_acceptance.mjs：playwright-core + 本机 Chrome）
//
// 用法（服务启动后由主理人执行；准备阶段只编写、不运行）：
//   node tests/ontology_browser.mjs --list
//   node tests/ontology_browser.mjs                 // 默认 --all
//   node tests/ontology_browser.mjs --case pages_desktop,global_search
//
// 覆盖：桌面 1440x900 与手机 390x844；本体平台 5 个页面；全局搜索直达；
//       刷新失败重试协议；≤5 秒多视角自动更新；业务按钮跳转原业务；
//       来源不可用与新鲜度显示；截图 screenshots/ontology/；
//       结果 tests/ontology-browser-results.json（逐项 passed/failed/skipped + detail）。
//
// 页面路由依据 02-架构设计.md §7.1；接口依据 03-本体API契约.md。
// 前端 DOM 结构由实现方决定，故一律使用「多候选选择器 + 页面可见文本 + 接口数据」三重判定；
// 无法确认的项显式 skipped 并写明原因，绝不伪装为通过。
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..')
const SHOTS = path.join(ROOT, 'screenshots', 'ontology')
const RESULTS = path.join(__dirname, 'ontology-browser-results.json')

const BASE = process.env.ONTOLOGY_WEB_BASE || process.env.ONTOLOGY_WEB || 'http://127.0.0.1:5181'
const BIZ_WEB = process.env.ONTOLOGY_BIZ_WEB || 'http://127.0.0.1:5180'
const BIZ_API = process.env.ONTOLOGY_BIZ_API || 'http://127.0.0.1:18080/api'
const ONTO_API = process.env.ONTOLOGY_API || 'http://127.0.0.1:18090'
const CHROME = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const GID_STUDENT = 'gid:school-panorama:student:1'
const GID_LESSON = 'gid:school-panorama:lessonSession:3'
const QA_REASON = '本体验收专用原因：教师参加教研，改由陈思琪代课并调至第七节'

fs.mkdirSync(SHOTS, { recursive: true })

// ── 三态结果记录器 ──────────────────────────────────────────────────────────
const results = []
const consoleErrors = []
let suppressConsoleErrors = false

function add(item, status, detail, group = '', kase = null) {
  const existing = results.findIndex(x => x.item === item)
  const rec = {
    item,
    status,
    passed: status === 'passed' ? true : (status === 'failed' ? false : null),
    detail: String(detail || '').slice(0, 900),
    group,
    case: kase,
  }
  if (existing >= 0) results[existing] = rec
  else results.push(rec)
  const tag = { passed: '[通过]', failed: '[失败]', skipped: '[跳过]' }[status]
  console.log(`  ${tag} ${item}${detail ? ' — ' + String(detail).slice(0, 200) : ''}`)
  return status === 'passed'
}

function forCase(name, group) {
  return {
    check: (item, ok, detail = '') => add(item, ok ? 'passed' : 'failed', detail, group, name),
    skip: (item, reason) => add(item, 'skipped', reason, group, name),
    fail: (item, detail) => add(item, 'failed', detail, group, name),
  }
}

// ── HTTP / 页面小工具 ───────────────────────────────────────────────────────
async function apiPost(p, body = {}) {
  try {
    const r = await fetch(BIZ_API + p, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    })
    return r.status
  } catch { return -1 }
}

async function apiPostJson(p, body = {}) {
  try {
    const r = await fetch(BIZ_API + p, {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    })
    return { status: r.status, data: await r.json().catch(() => ({})) }
  } catch (e) { return { status: -1, data: { error: String(e) } } }
}

async function apiGet(p) {
  try {
    const r = await fetch(BIZ_API + p)
    return await r.json().catch(() => ({}))
  } catch { return {} }
}

async function ontoGet(p) {
  try {
    const r = await fetch(ONTO_API + '/api/ontology' + p)
    const j = await r.json().catch(() => ({}))
    return j && j.data !== undefined ? j.data : j
  } catch { return null }
}

async function ontoPost(p, body = {}) {
  try {
    const r = await fetch(ONTO_API + '/api/ontology' + p, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    })
    const j = await r.json().catch(() => ({}))
    return { status: r.status, data: j && j.data !== undefined ? j.data : j }
  } catch { return { status: 0, data: null } }
}

/**
 * 等待本体服务「重启后恢复」：每轮都先确认 /overview 返回 200，
 * 再确认目标 mappingVersion 已成为 ACTIVE；最多等 120 秒（自重启 + 投影重建）。
 */
async function waitOntologyReady(expectedVersion, timeout = 120000) {
  const deadline = Date.now() + timeout
  let last = { status: 0, version: null }
  while (Date.now() < deadline) {
    try {
      const response = await fetch(ONTO_API + '/api/ontology/overview')
      last.status = response.status
      if (response.status === 200) {
        const versions = await ontoGet('/model/versions')
        last.version = versions?.activeMappingVersion || null
        if (versions && (!expectedVersion || versions.activeMappingVersion === expectedVersion)) {
          return { ok: true, ...last }
        }
      }
    } catch {
      // 服务正在重启：连接失败是预期现象，继续等下一轮。
      last.status = 0
    }
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  return { ok: false, ...last }
}

/** 等投影重建完成：定义变更后 ActivateRunner 会异步重建，期间 objects 可能短暂为 0。 */
async function waitObjectCount(expected, timeout = 120000) {
  const deadline = Date.now() + timeout
  let count = -1
  while (Date.now() < deadline) {
    const overview = await ontoGet('/overview')
    count = overview?.counts?.objects ?? -1
    if (count === expected) return { ok: true, count }
    await new Promise(resolve => setTimeout(resolve, 1000))
  }
  return { ok: false, count }
}

/** 读取当前学生类型的定义（草稿优先），用于前后对照显示名。 */
async function currentStudentDefinition() {
  const draft = await ontoGet('/model/object-types/draft')
  const rows = draft?.objectTypes ?? []
  return rows.find(row => row.id === 'student') ?? null
}

/** 通过 API 恢复对象基线并应用：浏览器用例的兜底清理，绝不把「学生（测试）」留在演示环境。 */
async function restoreObjectBaseline() {
  const versions = await ontoGet('/model/versions')
  const draft = await currentStudentDefinition()
  if (!versions || !draft || (draft.displayName === '学生' && !versions.draft?.objectTypesFromDraft)) return { skipped: true }
  const base = versions.activeMappingVersion
  const reset = await ontoPost('/model/object-types/reset', {
    baseMappingVersion: base, confirm: true, operator: '浏览器验收',
  })
  if (reset.status !== 200 || !reset.data?.pendingMappingVersion) return { ok: false, reset: reset.status }
  const target = reset.data.pendingMappingVersion || versions.baselineMappingVersion
  let applied = await ontoPost('/model/relations/apply', {
    baseMappingVersion: base, operator: '浏览器验收',
  })
  // 刚应用过一次时重建任务可能仍在跑：等 3 秒后用最新 ACTIVE 版本重试一次。
  if (applied.status === 409) {
    await new Promise(resolve => setTimeout(resolve, 3000))
    const fresh = await ontoGet('/model/versions')
    applied = await ontoPost('/model/relations/apply', {
      baseMappingVersion: fresh?.activeMappingVersion || base, operator: '浏览器验收',
    })
  }
  if (applied.status !== 200 && applied.status !== 202) return { ok: false, apply: applied.status }
  const restored = await waitOntologyReady(target)
  const objects = await waitObjectCount(95)
  return { ok: restored.ok && objects.ok, restored: restored.ok, objects: objects.count }
}

async function goto(page, route, shotName) {
  // ontology-web 用 createWebHashHistory（src/router.ts），真实地址是 /#/xxx。
  // 旧写法直接拼 /xxx 会落到 hash='' → 重定向到总览，后续断言读到的是错误的页面（
  // 表现为 URL 变成 /relations#/、svg=0、对象探索器 0 行）。
  const url = BASE + (route.startsWith('/#') ? route : '/#' + route)
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForSelector('.nav-item, nav, aside, header, body', { timeout: 4000 }).catch(() => {})
  await page.waitForTimeout(850)
  if (shotName) await page.screenshot({ path: path.join(SHOTS, shotName), fullPage: true }).catch(() => {})
}

async function bodyText(page) {
  return await page.locator('body').innerText().catch(() => '')
}

async function overflow(page) {
  return await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
    bodyWidth: document.body.scrollWidth,
  })).catch(() => ({ scrollWidth: 99999, innerWidth: 0, bodyWidth: 99999 }))
}

function noOverflow(o) { return o.scrollWidth <= o.innerWidth + 2 }

async function nav(page, label) {
  const candidates = [
    page.locator('.nav-item', { hasText: label }),
    page.locator('nav a', { hasText: label }),
    page.getByRole('link', { name: new RegExp(label) }),
    page.getByText(label, { exact: false }),
  ]
  for (const c of candidates) {
    try {
      const el = c.first()
      if (await el.count() && await el.isVisible()) { await el.click(); await page.waitForTimeout(800); return true }
    } catch { /* 继续尝试 */ }
  }
  return false
}

async function firstVisibleInput(page) {
  const candidates = [
    page.getByPlaceholder(/搜索|姓名|编号|对象|输入/),
    page.locator('input[type="text"]'),
    page.locator('.el-input__inner'),
    page.locator('input:not([type="hidden"])'),
  ]
  for (const c of candidates) {
    try {
      const el = c.first()
      if (await el.count() && await el.isVisible()) return el
    } catch { /* 继续尝试 */ }
  }
  return null
}

async function clickFirstResult(page, text) {
  const candidates = [
    page.locator('.el-table__row', { hasText: text }),
    page.locator('tr', { hasText: text }),
    page.locator('.result-card, .search-item, .object-row, .mobile-item', { hasText: text }),
    page.getByText(text, { exact: false }),
  ]
  for (const c of candidates) {
    try {
      const el = c.first()
      if (await el.count() && await el.isVisible()) { await el.click(); return true }
    } catch { /* 继续尝试 */ }
  }
  return false
}

// ── 探索器 / 关系探索专用探针（缺陷 1/2/3 防回归用）────────────────────────
/**
 * 读对象探索器工具栏右上角「共 N 条」徽标。
 * 取不到返回 -1：断言里直接得到 -1 就能看出「徽标根本没了」，而不是被 0 悄悄带过。
 * 只认纯「找到 N 条」的徽标，避免误取手机卡片里的类型徽标。
 */
async function explorerTotal(page) {
  const texts = await page.locator('.badge.info').allInnerTexts().catch(() => [])
  for (const t of texts) {
    const m = String(t).trim().match(/^(?:共|找到)\s*(\d+)\s*条$/)
    if (m) return Number(m[1])
  }
  return -1
}

/** 表格数据行数：只数桌面表格 tbody（手机卡片列表是同一份数据的另一层渲染，数进来会翻倍）。 */
async function desktopRowCount(page) {
  return await page.locator('table.desktop-only tbody tr').count().catch(() => 0)
}

/** 轮询等「共 N 条」变成期望值：请求是异步的，固定 sleep 既慢又会在慢机器上误判。 */
async function waitExplorerTotal(page, expected, timeout = 8000) {
  const started = Date.now()
  while (Date.now() - started < timeout) {
    if (await explorerTotal(page) === expected) return true
    await page.waitForTimeout(120)
  }
  return false
}

/**
 * 当前类型渲染出来的筛选框标签集合。
 * 取 input[placeholder="筛选 X"] 的前缀：placeholder 与可见 <label> 同源于 filterLabel()，
 * 既覆盖了「用户能看见的标签」，也不依赖 <label> 在 DOM 里的相对位置。
 */
async function filterLabels(page) {
  return await page.evaluate(() => Array.from(
    document.querySelectorAll('.toolbar input[placeholder^="按"][placeholder$="筛选"]'),
  ).map(i => String(i.getAttribute('placeholder') || '').replace(/^按/, '').replace(/筛选$/, '').trim())).catch(() => [])
}

/** 采集页面发出的请求 URL（返回的数组是活的，断言时取长度差即可）。 */
function trackRequests(page, predicate) {
  const hits = []
  page.on('request', r => {
    const u = r.url()
    if (predicate(u)) hits.push(u)
  })
  return hits
}

/**
 * 只截取命中位置附近的片段。
 * 整页 bodyText 开头永远是导航与版本条，直接 slice(0,220) 得到的 detail 里看不到被测文案，
 * 失败时等于没有线索 —— 断言 detail 必须能一眼看出「哪里不对」。
 */
function snip(text, re, width = 140) {
  const s = String(text || '').replace(/\s+/g, ' ')
  const m = s.match(re)
  if (!m) return s.slice(0, width) + (s.length > width ? '…' : '')
  const start = Math.max(0, m.index - 30)
  const head = start > 0 ? '…' : ''
  return head + s.slice(start, start + width)
}

/** 读某类提示块（如结果说明 .basis / 错误条 .error-banner）的可见文本，取不到返回 ''。 */
async function blockText(page, selector) {
  return await page.locator(selector).first().innerText().catch(() => '') || ''
}

// 通用详情路由（与 ontology-web/src/router.ts 一致；hash 模式）
const DETAIL_URL_RE = /\/detail\?globalId=/

function objectRoute(gidValue) {
  // 契约 §2.1 + 工程师口径：globalId 统一 encodeURIComponent（%3A），不混用裸 ':'。
  // 路由形式以 ontology-web/src/router.ts 为准：hash 模式 + /detail?globalId=xxx。
  // 旧写法 '/object/<gid>' 在 router 里没有对应记录，会被通配规则重定向到总览。
  return '/detail?globalId=' + encodeURIComponent(gidValue)
}

async function gotoObject(page, gidValue, shotName) {
  const route = objectRoute(gidValue)
  try {
    await goto(page, route)
    // 就绪判据用「属性区已渲染（.kv .k）」，不要用整页文本里的对象名：
    // 详情页底部还有「最近变化」区块，历史记录里会出现任意对象名，
    // 用对象名判定会在页面还是旧数据/半渲染时提前返回（旧写法即因此误判）。
    await page.waitForSelector('.kv .k', { timeout: 8000 }).catch(() => {})
    const rendered = await page.evaluate(
      () => !!document.querySelector('.kv .k')).catch(() => false)
    const t = rendered ? await bodyText(page) : ''
    if (rendered) {
      if (shotName) await page.screenshot({ path: path.join(SHOTS, shotName), fullPage: true }).catch(() => {})
      return { ok: true, route, text: t }
    }
  } catch { /* 由调用方按 URL / 文本判定 */ }
  return { ok: false, route, text: '' }
}

/**
 * 读取详情页「当前属性」里某个显示名的值（.kv 下 .k + .v 成对渲染）。
 * 不读整页文本：页面上同时有「最近变化」，历史记录会让旧教师名一直存在，
 * 用它判断「当前值」会得到错误结论（auto_update_5s 前置断言曾因此恒失败）。
 */
async function attrValue(page, label) {
  return await page.evaluate((lab) => {
    const keys = Array.from(document.querySelectorAll('.kv .k'))
    for (const k of keys) {
      if ((k.textContent || '').trim() === lab) {
        const v = k.nextElementSibling
        return v ? (v.textContent || '').trim() : null
      }
    }
    return null
  }, label).catch(() => null)
}

function watchConsole(page, tag) {
  page.on('pageerror', e => { if (!suppressConsoleErrors) consoleErrors.push(tag + ' pageerror: ' + e.message) })
  page.on('console', m => {
    if (!suppressConsoleErrors && m.type() === 'error') consoleErrors.push(tag + ' console: ' + m.text().slice(0, 200))
  })
}

// ── 用例登记表 ─────────────────────────────────────────────────────────────
const CASES = [
  { name: 'pages_desktop', title: '桌面 1440x900：5 个页面齐全与截图', group: '桌面' },
  { name: 'pages_mobile', title: '手机 390x844：5 个页面齐全、无横向溢出', group: '手机' },
  { name: 'model_driven_views', title: '模型驱动：列表字典 / 详情数组两种 attributes 结构分别覆盖', group: '模型驱动' },
  { name: 'global_search', title: '全局搜索直达任意对象（含教室，无专用页面）', group: '交互' },
  { name: 'auto_update_5s', title: '≤5 秒多视角自动更新（详情 + 变化与影响）', group: '交互' },
  { name: 'refresh_retry', title: '刷新失败重试协议（不提前 ack 版本号）', group: '交互' },
  { name: 'business_jump', title: '业务按钮跳转原业务入口并沿用原校验', group: '交互' },
  { name: 'source_freshness', title: '来源不可用与新鲜度/最后成功同步时间显示', group: '状态' },
  { name: 'display_regression', title: '展示缺陷防回归：变化差异无空行 / 关系页空态引导', group: '缺陷修复' },
  // 本轮 BugFix 回归新增（工程师修复「按钮按不动 / 关系页空白」后的永久断言）
  { name: 'explorer_single_page_no_pager', title: '缺陷 1：单页时不渲染首页/上一页/下一页/末页，保留「共 N 条」', group: '缺陷修复' },
  { name: 'explorer_filter_alias_works', title: '缺陷 2：筛选真的生效（filters=<JSON>）、徽标=行数、清空回到全量', group: '缺陷修复' },
  { name: 'explorer_filter_fields_whitelisted', title: '缺陷 2：筛选项白名单（filterableFields ∩ SOURCE 属性）逐类型', group: '缺陷修复' },
  { name: 'relations_default_picker', title: '缺陷 3：直接打开关系页即有可点对象列表并展开列表+图', group: '缺陷修复' },
  { name: 'relations_empty_gid_hint', title: '缺陷 3：globalId 为空时给提示且不发 /relations 请求', group: '缺陷修复' },
  { name: 'explorer_url_change_refresh', title: '缺陷 2：同标签页换 URL 后标题/表格/条数一起刷新且只发 1 次请求', group: '缺陷修复' },
  // 对象类型在线编辑（C 档）：编号顺延 26，走完草稿→应用→新名可见→恢复基线闭环
  { name: 'object_type_online_edit', title: '26-对象类型在线编辑：草稿→应用→探索器新名→恢复基线', group: '在线编辑' },
]

const PAGES = [
  { route: '/', title: '全景总览', shot: '总览', keywords: ['总览', '对象', '关系', '同步', '搜索'], nav: '全景总览' },
  { route: '/explorer', title: '查对象', shot: '对象探索器', keywords: ['对象', '类别', '列表', '学生', '教师'], nav: '查对象' },
  { route: '/relations', title: '查关系（列表 + 图）', shot: '关系探索', keywords: ['关系', '图', '查看'], nav: '查关系' },
  { route: '/changes', title: '看变化', shot: '变化与影响', keywords: ['变化', '关联', '来源', '时间'], nav: '看变化' },
  { route: '/admin', title: '配置中心', shot: '模型与接入管理', keywords: ['对象类别', '关系类别', '数据源', '读取', '同步'], nav: '配置中心' },
]

async function checkFivePages(page, ctx, label, R, withShots = true) {
  for (let i = 0; i < PAGES.length; i++) {
    const p = PAGES[i]
    const shotName = withShots ? `${String(i + 1).padStart(2, '0')}-${p.shot}-${label}.png` : null
    await goto(page, p.route, shotName)
    const t = await bodyText(page)
    const hit = p.keywords.filter(k => t.includes(k))
    R.check(`${label}·${p.title} 页面可进入且展示中文内容（${hit.length}/${p.keywords.length} 个关键词）`,
      t.length > 0 && hit.length >= 2,
      `URL=${page.url()} 命中=${hit.join('/')} 文本长度=${t.length}`)
    const o = await overflow(page)
    R.check(`${label}·${p.title} 无横向溢出`, noOverflow(o), JSON.stringify(o))
  }
  // 关系页必须同时具备「列表」与「图」两种表现
  await goto(page, '/relations')
  const relText = await bodyText(page)
  const svgCount = await page.locator('svg').count().catch(() => 0)
  const graphLike = /关系图|图谱|节点|展开/.test(relText) || svgCount > 0
  R.check(`${label}·关系探索同时提供关系列表与关系图（svg=${svgCount}）`, graphLike,
    `文本命中=${/关系图|图谱|节点|展开/.test(relText)}, svg=${svgCount}`)
  if (svgCount === 0 && !/关系图|图谱|节点/.test(relText)) {
    R.skip(`${label}·关系图 1/2 层展开与点边看 reason`,
      '未检出 svg 或关系图文案，无法定位图交互元素；需主理人确认前端关系图选择器')
  } else {
    R.check(`${label}·关系图具备展开层数/节点上限控件（1 层 2 层）`,
      /1 层|2 层|一层|两层|最多显示|看几层|层数|depth/i.test(relText),
      `文本片段=${relText.slice(0, 180)}`)
  }
}

// ── 各用例 ─────────────────────────────────────────────────────────────────
async function casePagesDesktop(ctx) {
  const R = forCase('pages_desktop', '桌面')
  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop')
  try {
    await checkFivePages(page, ctx, '桌面', R, true)
    // 通用对象详情（模型驱动，同一套视图）
    const d = await gotoObject(page, GID_STUDENT, '06-通用对象详情-桌面.png')
    R.check('通用对象详情可打开（由 #/detail?globalId= 动态渲染）', d.ok && DETAIL_URL_RE.test(page.url()),
      `route=${d.route} / URL=${page.url()}`)
    if (d.ok) {
      const t = await bodyText(page)
      R.check('详情含当前属性 / 动态结果 / 正反向关联 / 最近变化 / 来源与映射',
        /来源|映射/.test(t) && /关联|关系|班级/.test(t) && /演示时间|当前|下一节|动态/.test(t),
        `文本片段=${t.slice(0, 220)}`)
      R.check('详情附「返回原业务对象」入口', /原业务|返回|去调课/.test(t), t.slice(0, 160))
      const o = await overflow(page)
      R.check('桌面对象详情无横向溢出', noOverflow(o), JSON.stringify(o))
    } else {
      R.skip('通用对象详情字段与「返回原业务」入口',
        '未能定位到可用的对象详情路由，需主理人确认前端 #/detail?globalId= 实际路径')
    }
  } finally { await page.close() }
}

async function casePagesMobile(ctx) {
  const R = forCase('pages_mobile', '手机')
  const m = await ctx.mobile.newPage()
  watchConsole(m, 'mobile')
  try {
    await checkFivePages(m, ctx, '手机', R, true)
    const navOk = await nav(m, '查对象')
    R.check('手机端导航可见可点', navOk || (await m.locator('.nav-item, nav a, aside a').count()) >= 4,
      `nav 命中=${navOk}`)
    const input = await firstVisibleInput(m)
    R.check('手机端搜索输入框可见', !!input)
    if (input) {
      await input.fill('陈一诺')
      await input.press('Enter').catch(() => {})
      await m.waitForTimeout(1100)
      const t = await bodyText(m)
      R.check('手机端全局搜索可直接查到对象', t.includes('陈一诺'), t.slice(0, 160))
      const o = await overflow(m)
      R.check('手机端搜索结果无横向溢出', noOverflow(o), JSON.stringify(o))
      await m.screenshot({ path: path.join(SHOTS, '06-全局搜索-手机.png'), fullPage: true }).catch(() => {})
    }
    const d = await gotoObject(m, GID_STUDENT, '07-对象详情-手机.png')
    if (d.ok) {
      const o = await overflow(m)
      R.check('手机端对象详情无横向溢出', noOverflow(o), JSON.stringify(o))
    } else {
      R.skip('手机端对象详情无横向溢出', '未能定位对象详情路由，需主理人确认前端路径')
    }
  } finally { await m.close() }
}

async function caseModelDrivenViews(ctx) {
  const R = forCase('model_driven_views', '模型驱动')
  // 主理人裁决 3：列表 items[].attributes 是字典、详情 attributes 是数组，分别断言。
  const types = await ontoGet('/model/types')
  const defs = types && Array.isArray(types.types) ? types.types : []
  const studentDef = defs.find(t => t.id === 'student')
  R.check('GET /model/types 返回类型定义（listFields / detailFields / searchableFields）',
    !!studentDef && !!(studentDef.listFields || studentDef.detailFields)
      && Array.isArray(studentDef.searchableFields),
    `已注册类型=${defs.map(t => t.id).join('/')}；student.listFields=${JSON.stringify(studentDef && studentDef.listFields).slice(0, 120)}`)

  const listData = await ontoGet('/objects?type=student&size=5')
  const row = listData && Array.isArray(listData.items) ? listData.items[0] : null
  const rowAttrs = row && row.attributes
  const isDict = !!rowAttrs && !Array.isArray(rowAttrs) && typeof rowAttrs === 'object'
  R.check('列表 items[].attributes 为对象字典（列表组件按 listFields 动态渲染）',
    isDict,
    `total=${listData && listData.total} / 类型=${Array.isArray(rowAttrs) ? 'array' : typeof rowAttrs} / keys=${isDict ? Object.keys(rowAttrs).slice(0, 8).join(',') : '-'}`)

  const detail = await ontoGet('/objects/' + encodeURIComponent(GID_STUDENT))
  const detAttrs = detail && detail.attributes
  const isArr = Array.isArray(detAttrs)
  const wellFormed = isArr && detAttrs.every(a => a && typeof a === 'object' && 'name' in a && 'value' in a)
  R.check('详情 attributes 为数组 [{name,displayName,value,origin,provider}]（详情组件按数组渲染）',
    isArr && wellFormed,
    `类型=${isArr ? 'array(' + detAttrs.length + ')' : typeof detAttrs}；样例=${JSON.stringify(isArr ? detAttrs[0] : null).slice(0, 160)}`)
  const origins = new Set(isArr ? detAttrs.map(a => a.origin).filter(Boolean) : [])
  R.check('详情属性带 origin，可区分 SOURCE / DERIVED 与来源追溯',
    origins.size > 0, `origins=${[...origins].join('/')}`)

  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-model')
  try {
    // 视角①：通用对象列表（字典驱动）
    await goto(page, '/explorer?type=student', '17-模型驱动列表-桌面.png')
    const listText = await bodyText(page)
    const tableRows = await page.locator('.el-table__row, tr, .object-row, .result-card').count().catch(() => 0)
    R.check('通用对象探索器按定义渲染列表（学生列表可见且有行）',
      /学生|学号|姓名|陈一诺/.test(listText) && tableRows > 0,
      `行数=${tableRows} / 文本片段=${listText.slice(0, 160)}`)
    if (studentDef) {
      const fields = (studentDef.listFields || [])
        .map(f => (f && f.displayName) || (typeof f === 'string' ? f : null)).filter(Boolean)
      const hit = fields.filter(f => listText.includes(f))
      R.check('列表列名来自 /model/types 的 listFields（模型驱动，无类型专用分支）',
        fields.length === 0 || hit.length > 0,
        `定义列=${fields.slice(0, 8).join('/')} / 页面命中=${hit.slice(0, 8).join('/')}`)
    } else {
      R.skip('列表列名来自 listFields', '未取到 student 类型定义，无法比对列名')
    }

    // 视角②：通用对象详情（数组驱动）
    const d = await gotoObject(page, GID_STUDENT, '18-模型驱动详情-桌面.png')
    R.check('同一套通用详情路由可打开（#/detail?globalId=）',
      d.ok && DETAIL_URL_RE.test(page.url()), `URL=${page.url()}`)
    if (d.ok) {
      const t = await bodyText(page)
      R.check('详情页展示属性区 + 来源/映射 + 动态结果（详情数组驱动）',
        /来源|映射/.test(t) && /演示时间|当前|下一节|动态/.test(t) && /陈一诺/.test(t),
        t.slice(0, 220))
      R.check('列表与详情由同一套通用路由渲染（URL 无 student/classroom 等类型专用路径）',
        DETAIL_URL_RE.test(page.url()) && !/\/student\/|\/classroom\/|\/teacher\//.test(page.url()),
        page.url())
    } else {
      R.skip('详情页属性区 / 来源 / 动态结果（数组驱动）',
        '未能定位通用对象详情路由，需主理人确认前端 #/detail?globalId= 实际路径')
    }
  } finally { await page.close() }
}

async function caseGlobalSearch(ctx) {
  const R = forCase('global_search', '交互')
  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-search')
  try {
    await goto(page, '/', '08-全局搜索入口-桌面.png')
    const input = await firstVisibleInput(page)
    R.check('总览页提供全局搜索入口', !!input, '无需沿学校→班级→学生逐级进入')
    if (!input) {
      R.skip('全局搜索直达具体对象（含教室类型）', '未找到可见搜索框，需主理人确认前端搜索组件选择器')
      return
    }
    // ① 学生：按姓名直达
    await input.fill('陈一诺')
    await input.press('Enter').catch(() => {})
    await page.waitForTimeout(1200)
    const t1 = await bodyText(page)
    R.check('按姓名全局搜索直达学生 陈一诺', t1.includes('陈一诺'), t1.slice(0, 180))
    const clicked = await clickFirstResult(page, '陈一诺')
    await page.waitForTimeout(900)
    R.check('点击搜索结果可直达通用对象详情（#/detail?globalId=）',
      clicked && /\/detail\?globalId=/.test(page.url()), `clicked=${clicked} URL=${page.url()}`)
    await page.screenshot({ path: path.join(SHOTS, '09-全局搜索直达详情-桌面.png'), fullPage: true }).catch(() => {})

    // ② 教室：后接入类型也走同一套搜索 + 通用详情（验收四）
    await goto(page, '/')
    const cls = await ontoGet('/model/types')
    const hasClassroom = !!cls && Array.isArray(cls.types) && cls.types.some(t => t.id === 'classroom')
    const opts = await apiGet('/meta/options')
    const roomName = (opts.classrooms && opts.classrooms[0] && opts.classrooms[0].name) || '多功能报告厅'
    const sr = await ontoGet('/search?q=' + encodeURIComponent(roomName))
    const roomHits = sr && Array.isArray(sr.items) ? sr.items.filter(x => x.type === 'classroom') : []
    R.check('接口层：教室可被全局搜索命中（/model/types 含 classroom）',
      hasClassroom && roomHits.length > 0,
      `classroom 定义=${hasClassroom} / 命中 ${roomHits.length} 条 / 关键词=${roomName}`)
    const input2 = await firstVisibleInput(page)
    if (input2 && hasClassroom) {
      await input2.fill(roomName)
      await input2.press('Enter').catch(() => {})
      await page.waitForTimeout(1200)
      const t2 = await bodyText(page)
      R.check('页面层：教室可被全局搜索直达（无需教室专用页面）', t2.includes(roomName),
        t2.slice(0, 180))
      const roomGid = roomHits.length ? roomHits[0].globalId : null
      if (roomGid) {
        const d = await gotoObject(page, roomGid, '10-教室通用详情-桌面.png')
        R.check('教室打开的是同一套通用对象详情（非教室专用页面）',
          d.ok && DETAIL_URL_RE.test(page.url()), `URL=${page.url()}`)
        if (d.ok) {
          const t3 = await bodyText(page)
          R.check('教室通用详情含来源/映射/关联对象', /来源|映射/.test(t3) && /关联|关系/.test(t3),
            t3.slice(0, 200))
        }
      } else {
        R.skip('教室通用详情与关系图展开', '接口未返回教室 globalId，无法构造 #/detail 路由')
      }
    } else if (!hasClassroom) {
      R.skip('页面层：教室全局搜索直达与通用详情',
        'classroom 类型尚未接入（批次④未完成），前置不成立；接入后重跑 --case global_search')
    } else {
      R.skip('页面层：教室全局搜索直达', '未能定位第二个搜索框')
    }
  } finally { await page.close() }
}

async function caseAutoUpdate5s(ctx) {
  const R = forCase('auto_update_5s', '交互')
  const resetCode = await apiPost('/demo/reset')
  R.check('前置：恢复初始演示数据且本体服务已同步', resetCode === 200, `HTTP ${resetCode}`)
  await new Promise(r => setTimeout(r, 1500))
  // 再重建一次投影：契约 §2.7 规定「已结束」的关系也要展示（标 已结束），
  // 前几轮用例反复换过教师，页面文本会天然带历史教师名，让「初始不含新教师」这条前置
  // 断言失去意义。重建后投影只剩当前有效关系，观察窗口才干净。
  const rb = await ontoPost('/rebuild')
  R.check('前置：重建投影以清掉历史失效边（干净观察现场）', rb.status === 200,
    `HTTP ${rb.status}`)
  await new Promise(r => setTimeout(r, 2500))
  const clock = await apiGet('/demo/clock')
  const day = (clock.demoTime || '').slice(0, 10)

  const w1 = await ctx.desktop.newPage()   // 已打开视角①：对象详情
  const w2 = await ctx.desktop.newPage()   // 已打开视角②：变化与影响
  watchConsole(w1, 'watcher-detail')
  watchConsole(w2, 'watcher-changes')
  try {
    const d = await gotoObject(w1, GID_LESSON)
    await goto(w2, '/changes')
    await w1.bringToFront()
    const teacherBefore = await attrValue(w1, '授课教师')
    R.check('观察视角初始的「授课教师」为 张明远（旧状态）',
      !!teacherBefore && teacherBefore.includes('张明远'),
      `授课教师=${teacherBefore}；` +
      '（只读当前属性，不读整页文本：页面底部「最近变化」会保留历史教师名）')

    const t0 = Date.now()
    const adj = await apiPostJson('/schedule/3/adjust', {
      teacherId: 5, classroomId: 6, lessonDate: day,
      startTime: '15:30', endTime: '16:15', reason: QA_REASON + ' #浏览器5秒',
    })
    const commitMs = Date.now() - t0
    R.check('外部（原业务）完成一次成功调课', adj.status === 200, `HTTP ${adj.status}`)

    let seen1 = false, seen2 = false, e1 = null, e2 = null
    while (Date.now() - t0 < 7000) {
      if (!seen1) {
        const teacher = await attrValue(w1, '授课教师')
        const start = await attrValue(w1, '开始时间')
        if (teacher && teacher.includes('陈思琪') && start && start.includes('15:30')) {
          seen1 = true; e1 = Date.now() - t0
        }
      }
      if (!seen2) {
        const t = await bodyText(w2)
        if (t.includes('陈思琪') || t.includes('#浏览器5秒')) { seen2 = true; e2 = Date.now() - t0 }
      }
      if (seen1 && seen2) break
      await w1.waitForTimeout(300)
    }
    const cond = { commitMs, detailSec: e1 === null ? null : +(e1 / 1000).toFixed(2),
      changesSec: e2 === null ? null : +(e2 / 1000).toFixed(2), pollIntervalSec: 0.3,
      condition: '本地无故障 / headless Chrome 已禁用后台节流 / 两个已打开视角' }
    R.check('已打开视角①对象详情在 5 秒内自动更新', seen1 && e1 <= 5000, JSON.stringify(cond))
    R.check('已打开视角②变化与影响在 5 秒内自动更新', seen2 && e2 <= 5000, JSON.stringify(cond))
    R.check('从业务提交成功到多视角全部更新 ≤5 秒（记录真实耗时与条件）',
      seen1 && seen2 && Math.max(e1, e2) <= 5000, JSON.stringify(cond))
    await w1.screenshot({ path: path.join(SHOTS, '11-5秒自动更新-对象详情.png'), fullPage: true }).catch(() => {})
    await w2.screenshot({ path: path.join(SHOTS, '12-5秒自动更新-变化与影响.png'), fullPage: true }).catch(() => {})
  } finally {
    await w1.close(); await w2.close()
  }
}

async function caseRefreshRetry(ctx) {
  const R = forCase('refresh_retry', '交互')
  const resetCode = await apiPost('/demo/reset')
  R.check('前置：恢复初始演示数据', resetCode === 200, `HTTP ${resetCode}`)
  await new Promise(r => setTimeout(r, 1500))
  const clock = await apiGet('/demo/clock')
  const day = (clock.demoTime || '').slice(0, 10)

  const w = await ctx.desktop.newPage()
  watchConsole(w, 'watcher-retry')
  const matcher = (u) => {
    const s = u.href || String(u)
    return /\/api\/ontology\/(changes|objects|relations|graph|overview|search)/.test(s)
      && !/\/revision/.test(s)
  }
  let armed = false, injected = 0
  const handler = async (route) => {
    if (armed && injected < 1) {
      injected += 1
      await route.fulfill({ status: 503, contentType: 'application/json',
        body: JSON.stringify({ code: 'INTERNAL_ERROR', data: null, message: 'QA 注入的一次刷新失败' }) })
      return
    }
    await route.continue()
  }
  try {
    await w.route(matcher, handler)
    const d = await gotoObject(w, GID_LESSON, '13-刷新失败注入前-桌面.png')
    // 只看「当前属性」的授课教师：整页文本里必然含历史变更记录（页面底部「最近变化」），
    // 用 bodyText 判「当前是否为旧数据」会恒失败（旧写法即因此误报）。
    const teacherNow = await attrValue(w, '授课教师')
    R.check('观察视角已打开（注入失败前为旧数据）',
      d.ok && !!teacherNow && !teacherNow.includes('陈思琪'),
      `route=${d.route} 授课教师=${teacherNow}`)

    suppressConsoleErrors = true     // 人为注入的 503 不计入「无 JS 错误」断言
    const t0 = Date.now()
    const adj = await apiPostJson('/schedule/3/adjust', {
      teacherId: 5, classroomId: 6, lessonDate: day,
      startTime: '15:30', endTime: '16:15', reason: QA_REASON + ' #刷新失败重试',
    })
    armed = true                     // 只在业务提交成功之后才注入一次刷新失败
    R.check('业务调课提交成功（用于触发一次版本刷新）', adj.status === 200, `HTTP ${adj.status}`)

    let recovered = false, elapsed = 0
    while (Date.now() - t0 < 10000) {
      const t = await bodyText(w)
      if (t.includes('陈思琪') && t.includes('15:30')) { recovered = true; break }
      await w.waitForTimeout(350)
    }
    elapsed = Date.now() - t0
    if (injected === 0) {
      R.skip('注入刷新失败后自动重试、不因提前记住新版本号停留旧数据',
        `未能命中本体只读请求（injected=0），无法构造刷新失败；需主理人确认前端实际请求前缀`
        + `（期望含 /api/ontology/...）。当前页面 URL=${w.url()}`)
    } else {
      R.check('刷新失败已被真实注入（某个视角返回 503）', injected >= 1,
        `注入次数=${injected}`)
      R.check('刷新失败后自动重试并恢复到最新数据（不提前 ack 版本号）', recovered,
        `耗时 ${elapsed}ms，恢复=${recovered}；若失败说明前端提前记住了新版本号`)
    }
    await w.screenshot({ path: path.join(SHOTS, '14-刷新失败重试恢复.png'), fullPage: true }).catch(() => {})
  } finally {
    suppressConsoleErrors = false
    await w.unroute(matcher, handler).catch(() => {})
    await w.close()
  }
}

async function caseBusinessJump(ctx) {
  const R = forCase('business_jump', '交互')
  // 接口侧佐证：03 契约 §2.6 backToBusinessUrl
  // globalId 统一 URL 编码（与前端 encodeURIComponent 一致），不再尝试裸 ':'
  const detail = await ontoGet('/objects/' + encodeURIComponent(GID_STUDENT))
  const backUrl = detail && detail.backToBusinessUrl
  R.check('接口提供 backToBusinessUrl，指向原业务入口 :5180',
    !!backUrl && String(backUrl).includes('5180'), `backToBusinessUrl=${backUrl}`)

  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-jump')
  try {
    const d = await gotoObject(page, GID_STUDENT)
    if (!d.ok) {
      R.skip('页面业务按钮跳转原业务', '未能定位通用对象详情路由，需主理人确认前端路径')
      return
    }
    const t = await bodyText(page)
    const candidates = [
      page.getByRole('link', { name: /返回原业务|去调课|原业务/ }),
      page.getByRole('button', { name: /返回原业务|去调课|原业务/ }),
      page.locator('a', { hasText: /原业务|调课/ }),
      page.getByText(/返回原业务对象|去调课/, { exact: false }),
    ]
    let el = null, href = null
    for (const c of candidates) {
      try {
        const x = c.first()
        if (await x.count() && await x.isVisible()) {
          el = x
          href = await x.getAttribute('href').catch(() => null)
          break
        }
      } catch { /* 继续尝试 */ }
    }
    R.check('详情页存在「返回原业务对象 / 去调课」入口', !!el || /原业务|去调课/.test(t),
      `元素=${!!el} / 文本命中=${/原业务|去调课/.test(t)}`)
    if (href) {
      R.check('业务按钮指向原业务页面（沿用原有校验，不在平台内改投影）',
        href.includes('5180') || href.includes('/schedule'),
        `href=${href}`)
    } else if (el) {
      // 无 href 时真实点击并捕获导航到 :5180
      let jumped = false
      try {
        const p = page.waitForURL(/5180|\/schedule/, { timeout: 6000 }).then(() => true).catch(() => false)
        await el.click({ timeout: 4000 }).catch(() => {})
        jumped = await p
      } catch { /* 保持 false */ }
      R.check('点击业务按钮后跳转到原业务页面', jumped,
        `跳转=${jumped} / 当前 URL=${page.url()}`)
    } else {
      R.skip('业务按钮跳转原业务（点击验证）',
        '页面未找到可点击的业务按钮元素，需主理人确认按钮文案/选择器；接口侧 backToBusinessUrl 已单独校验')
    }
    // 平台只读：同步完成前投影不被平台写入（此处仅核对详情仍可读）
    R.check('平台页面为只读展示（无平台内写入入口伪装业务成功）',
      !/平台内提交调课|直接修改投影/.test(t), t.slice(0, 160))
    await page.screenshot({ path: path.join(SHOTS, '15-业务按钮跳转-桌面.png'), fullPage: true }).catch(() => {})
  } finally { await page.close() }
}

async function caseSourceFreshness(ctx) {
  const R = forCase('source_freshness', '状态')
  const st = await ontoGet('/sources/status')
  R.check('接口 /sources/status 可读且含 sourceUp / status / lastSuccessSyncAt / demoTime',
    !!st && 'sourceUp' in st && 'status' in st && 'lastSuccessSyncAt' in st && 'demoTime' in st,
    JSON.stringify(st || {}).slice(0, 260))

  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-status')
  try {
    await goto(page, '/', '16-来源状态与新鲜度-桌面.png')
    const t = await bodyText(page)
    R.check('顶栏/页面显示最后成功同步时间与数据新鲜度',
      /上次同步成功|最后成功同步|已同步|同步/.test(t), t.slice(0, 220))
    R.check('两类时间分开显示（演示时间 vs 最后成功同步时间）',
      /演示时间/.test(t) && /上次同步成功|最后成功同步|同步/.test(t),
      `演示时间=${/演示时间/.test(t)} 同步时间=${/上次同步成功|最后成功同步|同步/.test(t)}`)
    if (st && st.sourceUp === false) {
      R.check('原业务停机时显示「来源不可用」且仍可查已同步对象',
        /来源不可用|不可用/.test(t) && /对象|学生|教师/.test(t), t.slice(0, 220))
      R.check('原业务停机时仍显示最后成功同步时间',
        /最后成功同步|同步时间/.test(t) && !!st.lastSuccessSyncAt,
        `lastSuccessSyncAt=${st.lastSuccessSyncAt}`)
      const sr = await ontoGet('/search?q=' + encodeURIComponent('陈一诺'))
      R.check('来源不可用时接口仍返回已同步数据（底座独立运行）',
        !!sr && Array.isArray(sr.items) && sr.items.length > 0,
        `搜索结果 ${sr && sr.items ? sr.items.length : 0} 条`)
    } else {
      R.skip('原业务停机时「来源不可用」标记与仍可查已同步对象',
        `sourceUp=${st && st.sourceUp}（仍为可用）；需主理人先停止原业务服务(backend)后重跑 `
        + `--case source_freshness。接口侧契约由 --case source_unavailable 覆盖`)
    }
    R.check('可区分四态与新鲜度（状态标签/待处理数/错误与重试入口可见）',
      /已同步|尚未接入|没有业务数据|同步落后|同步失败/.test(t) || /待处理|重试/.test(t),
      `status=${st && st.status} / statusLabel=${st && st.statusLabel}`)
  } finally { await page.close() }
}

/**
 * 展示缺陷防回归（缺陷 A / B）：
 * - A：变化记录「属性前后差异」不得再渲染出「：/ （空）→ （空）」式的空行
 *   （历史落库的旧结构 diff 也必须走对象级清单或 note 兜底，绝不出现空 label 行）；
 * - B：关系探索页不带 globalId 时必须给引导 + 对象选择器，而不是空表格「暂无关系数据」。
 */
async function caseDisplayRegression(ctx) {
  const R = forCase('display_regression', '缺陷修复')
  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-display')
  try {
    // ── 缺陷 A：变化页差异明细不允许空行 ──────────────────────────────────
    // 带 size=100 一次取较多记录：对账/重建这类记录会被后续几十次调课挤出首页，
    // 只验首页会漏掉它们（这正是当初这条缺陷没被发现的盲区）。
    await goto(page, '/changes?page=1&size=100', '18-变化记录差异渲染-桌面.png')
    await page.waitForSelector('.card', { timeout: 8000 }).catch(() => {})
    await page.waitForTimeout(1200)
    const badDiffLines = await page.evaluate(() => {
      // 逐行检查变化卡片里的差异/影响清单：空行 = 只有冒号/箭头/占位符、左侧无字段名的行。
      const lines = Array.from(document.querySelectorAll('.list-plain li'))
        .map(li => (li.textContent || '').replace(/\s+/g, ' ').trim())
      return lines.filter(t =>
        t === '' ||
        /^[：:→\s]*$/.test(t) ||
        /^[：:]?\s*(—|（空）|无|undefined)?\s*→\s*(—|（空）|无|undefined)?\s*$/.test(t))
    }).catch(() => null)
    if (badDiffLines === null) {
      R.skip('变化记录差异明细无空行', '页面未能渲染（.list-plain 不可读），需确认服务状态')
    } else {
      R.check('变化记录「属性前后差异」不出现空行（空 label + 空前后值）',
        badDiffLines.length === 0,
        `检出空行 ${badDiffLines.length} 条${badDiffLines.length ? '，样例=' + JSON.stringify(badDiffLines.slice(0, 3)) : ''}`)
    }
    // 历史旧结构（#84/#87 类 RECONCILE_FIX）必须有实质内容，而不是整块静默消失。
    // 判据不能靠「已修正 / 投影缺失」这类关键词：早期记录里前后值相同的假差异会被前端
    // 过滤掉，剩下的可能是字段级差异行或关系 note，未必命中任何固定词。
    // 真正要保证的是：渲染出来的每一条都有实质文案，或者给出「无实质变化」的显式提示。
    const reconcileInfo = await page.evaluate(() => {
      const cards = Array.from(document.querySelectorAll('.card'))
      for (const c of cards) {
        if ((c.textContent || '').includes('对账修复')) {
          const lis = Array.from(c.querySelectorAll('.list-plain li'))
            .map(li => (li.textContent || '').replace(/\s+/g, ' ').trim())
          return { lis, noopHint: /无实质业务属性变化/.test(c.textContent || '') }
        }
      }
      return null
    }).catch(() => null)
    const reconcileOk = reconcileInfo !== null
      && (reconcileInfo.noopHint
        || (reconcileInfo.lis.length > 0 && reconcileInfo.lis.every(t => t.length > 0)))
    R.check('变化记录对账条目均有实质文案（不留空行、不静默消失）',
      reconcileOk,
      `条目数=${reconcileInfo ? reconcileInfo.lis.length : 'null'} 无实质变化提示=${reconcileInfo ? reconcileInfo.noopHint : 'null'} 样例=${reconcileInfo ? JSON.stringify(reconcileInfo.lis.slice(0, 2)) : 'null'}`)

    // ── 缺陷 B：关系页空态必须是引导 + 选择器，不是空表格 ────────────────
    await goto(page, '/relations', '19-关系页空态引导-桌面.png')
    await page.waitForTimeout(1200)
    const emptyText = await bodyText(page)
    R.check('关系页未选对象时显示引导文案（请先选一个起点对象）',
      emptyText.includes('请先选一个起点对象'),
      `文本片段=${emptyText.slice(0, 200)}`)
    R.check('关系页未选对象时不再显示空表格「暂无关系数据」',
      !emptyText.includes('暂无关系数据'),
      `命中「暂无关系数据」=${emptyText.includes('暂无关系数据')}`)
    const hasSelect = await page.locator('select').count().catch(() => 0)
    R.check('关系页空态提供对象选择器（类型下拉 + 关键字 + 快捷示例）',
      /全部类别|快捷示例/.test(emptyText) && hasSelect > 0,
      `全部类别=${emptyText.includes('全部类别')} / 快捷示例=${emptyText.includes('快捷示例')} / select=${hasSelect}`)

    // ── 快捷示例按钮可直接进入关系列表 + 图 ─────────────────────────────
    const exampleBtn = page.getByRole('button', { name: /学生|课次|班级/ }).first()
    const hasExample = await exampleBtn.count().catch(() => 0)
    if (hasExample) {
      await exampleBtn.click().catch(() => {})
      await page.waitForTimeout(2500)
      const loadedText = await bodyText(page)
      const svgCount = await page.locator('svg').count().catch(() => 0)
      R.check('点击快捷示例后加载出关系列表与关系图',
        /关系总数/.test(loadedText) && (svgCount > 0 || /关系图/.test(loadedText)),
        `关系总数=${/关系总数/.test(loadedText)} / svg=${svgCount} / URL=${page.url()}`)
      R.check('选中对象后不出现空态引导卡片与「暂无关系数据」',
        !loadedText.includes('请先选一个起点对象') && !loadedText.includes('暂无关系数据'),
        `引导=${loadedText.includes('请先选一个起点对象')} / 暂无=${loadedText.includes('暂无关系数据')}`)
      await page.screenshot({ path: path.join(SHOTS, '20-关系页示例进入-桌面.png'), fullPage: true }).catch(() => {})
    } else {
      R.skip('点击快捷示例进入关系列表与图', '快捷示例按钮未渲染（示例对象可能不存在），需确认演示数据')
    }
  } finally { await page.close() }
}

/**
 * 缺陷 1 防回归：只有一页时不再渲染「首页/上一页/下一页/末页」。
 *
 * <p>原实现这 4 个按钮常驻但全部 disabled，外观与可点按钮差别很小；用户点了没反应，
 * 结论就是「分页按钮坏了」。修法是 pages<=1 时不渲染按钮、保留「共 N 条」计数文本。
 * 因此这里必须同时断言两件事：计数文本还在（用户仍知道总数）、按钮真的不在 DOM 里。</p>
 */
async function caseExplorerSinglePageNoPager(ctx) {
  const R = forCase('explorer_single_page_no_pager', '缺陷修复')
  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-pager')
  try {
    // 学校类型只有 2 条对象 → 必然单页
    await goto(page, '/explorer?type=school', '21-单页不渲染翻页按钮-桌面.png')
    await waitExplorerTotal(page, 2)
    const total = await explorerTotal(page)
    const rows = await desktopRowCount(page)
    const text = await bodyText(page)
    R.check('学校类型（2 条）保留「找到 2 条」计数文本',
      total === 2 && /(?:共|找到)\s*2\s*条/.test(text), `徽标=${total} / 表格行数=${rows}`)
    R.check('学校类型表格渲染 2 行（结构：table.desktop-only tbody tr）',
      rows === 2, `行数=${rows} / 徽标=${total}`)
    // 分页区的真实结构：计数 span 的父容器里不应有任何 <button>
    const pager = await page.evaluate(() => {
      const span = Array.from(document.querySelectorAll('span'))
        .find(s => /(?:共|找到)\s*\d+\s*条\s*\/\s*第\s*\d+\s*页/.test(s.textContent || ''))
      if (!span) return null
      const box = span.parentElement
      return {
        text: (span.textContent || '').replace(/\s+/g, ' ').trim(),
        buttons: Array.from(box.querySelectorAll('button')).map(b => (b.textContent || '').trim()),
      }
    })
    R.check('分页区显示「找到 2 条 / 第 1 页 / 一共 1 页」',
      !!pager && /一共\s*1\s*页/.test(pager.text), pager ? pager.text : '未找到分页计数容器（结构可能已变）')
    const pagerButtons = pager ? pager.buttons : ['<分页区未定位>']
    R.check('只有一页时「首页/上一页/下一页/末页」四个按钮都不渲染（DOM 按钮数=0）',
      pagerButtons.length === 0, `分页区按钮=${JSON.stringify(pagerButtons)}`)
    // 双保险：整页也不应存在名为「下一页」的可点元素
    const domNext = await page.getByRole('button', { name: '下一页', exact: true }).count().catch(() => -1)
    R.check('全页 getByRole(「下一页」) 计数为 0', domNext === 0, `「下一页」元素数=${domNext}`)
  } finally { await page.close() }
}

/**
 * 缺陷 2 防回归：筛选必须真的生效，且「应用/清空」要有看得见的反馈。
 *
 * <p>真根因在服务端：Spring 把未声明的平铺参数（?gender=男）静默丢弃，筛选恒返回全量，
 * 表现与「按钮按不动」完全一致。前端改用契约 03 §2.6 的 filters=<JSON> 首选写法。
 * 本用例同时守住三件事：条数真的变了、徽标与表格行数一致、请求确实走了 filters= 参数。</p>
 */
async function caseExplorerFilterAliasWorks(ctx) {
  const R = forCase('explorer_filter_alias_works', '缺陷修复')
  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-filter')
  try {
    const objReqs = trackRequests(page, u => u.includes('/ontology-api/objects?'))
    await goto(page, '/explorer?type=student', '22-学生性别筛选前后-桌面.png')
    await waitExplorerTotal(page, 32)
    const baseTotal = await explorerTotal(page)

    const input = page.locator('.toolbar input[placeholder*="性别"]').first()
    const hasInput = await input.count().catch(() => 0)
    R.check('学生类型提供「性别」筛选框（标签用中文 displayName）',
      hasInput > 0, `性别输入框数量=${hasInput}；页面文本含「性别」=${(await bodyText(page)).includes('性别')}`)

    let filtered = -1
    let rows = 0
    let notice = ''
    if (hasInput > 0) {
      await input.fill('男')
      await page.locator('.toolbar button.primary').filter({ hasText: '查找' }).first().click().catch(() => {})
      // 等徽标到位而不是固定 sleep：徽标与表格是同一份 data 渲染，等它就是等数据
      await waitExplorerTotal(page, 16)
      filtered = await explorerTotal(page)
      rows = await desktopRowCount(page)
      notice = await blockText(page, 'p.basis')
      await page.screenshot({ path: path.join(SHOTS, '22b-学生性别筛选结果-桌面.png'), fullPage: true }).catch(() => {})
    }
    R.check('「性别=男」应用筛选后条数减少（16 < 32）',
      filtered === 16 && filtered < baseTotal, `筛选前=${baseTotal} / 筛选后=${filtered}`)
    R.check('筛选后「共 N 条」与表格行数一致（16=16）',
      filtered === rows && filtered === 16, `徽标=${filtered} / 行数=${rows}`)
    R.check('筛选后给出结果说明文案（已经按你填的条件查找 + 16 条）',
      /已经按你填的条件查找/.test(notice) && /16\s*条/.test(notice),
      `说明块=${notice || '（未渲染 p.basis）'}`)
    // 契约 03 §2.6 首选写法：filters=<JSON>；平铺写法被服务端丢弃正是本轮真根因
    const filteredReq = [...objReqs].reverse().find(u => u.includes('filters=')) || ''
    R.check('筛选请求带 filters=<JSON> 参数（不是被静默丢弃的平铺写法）',
      /filters=(%7B|\{)/.test(filteredReq), filteredReq || '未捕获到带 filters 的 /objects 请求')

    let cleared = -1
    let clearedRows = 0
    let clearedNotice = ''
    if (hasInput > 0) {
      await page.getByRole('button', { name: '清除搜索条件', exact: true }).first().click().catch(() => {})
      await waitExplorerTotal(page, 32)
      cleared = await explorerTotal(page)
      clearedRows = await desktopRowCount(page)
      clearedNotice = await blockText(page, 'p.basis')
    }
    R.check('点「清空」后回到全量 32 条', cleared === 32, `清空后徽标=${cleared}（筛选前=${baseTotal}）`)
    R.check('清空后本页显示 20 行且版式说明「已经清除搜索条件、32 条」',
      clearedRows === 20 && /已经清除搜索条件/.test(clearedNotice) && /32\s*条/.test(clearedNotice),
      `行数=${clearedRows} / 说明块=${clearedNotice || '（未渲染 p.basis）'}`)
  } finally { await page.close() }
}

/**
 * 缺陷 2 防回归（白名单）：筛选项只保留 filterableFields ∩ 真实存在的 SOURCE 属性。
 *
 * <p>schoolId / classId / teacherId 是外键，投影里没有这些键；id 是技术主键（模型属性里没有）。
 * 按这些字段筛选 SQL 恒不匹配 → 永远 0 条，用户填一次就判定「探索器坏了」。
 * 预期：学校 0 / 班级 1（年级）/ 学生 1（性别）/ 教师 1（授课科目）/
 * 课程 2（科目、类别）/ 具体授课安排 1（上课日期）/ 教室 2（教室类型、楼栋）。</p>
 */
async function caseExplorerFilterFieldsWhitelisted(ctx) {
  const R = forCase('explorer_filter_fields_whitelisted', '缺陷修复')
  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-whitelist')
  try {
    const expected = {
      school: [],
      class: ['年级'],
      student: ['性别'],
      teacher: ['授课科目'],
      course: ['科目', '类别'],
      lessonSession: ['上课日期'],
      classroom: ['教室类型', '楼栋'],
    }
    // 用 /model/types 复算一次白名单：确认前端是「模型 filterableFields ∩ origin=SOURCE」算出来的，
    // 而不是把期望值硬编码进组件（哪天字段改名/新增，这一项会先失败）。
    const model = await ontoGet('/model/types')
    const defs = model && Array.isArray(model.types) ? model.types : []
    const derived = {}
    for (const def of defs) {
      const sourceNames = new Set((def.detailFields || [])
        .filter(f => f && f.origin === 'SOURCE').map(f => f.name))
      const labels = {}
      for (const f of def.detailFields || []) if (f && f.name) labels[f.name] = f.displayName
      derived[def.id] = (def.filterableFields || [])
        .filter(n => sourceNames.has(n)).map(n => labels[n] || n)
    }
    R.check('筛选项 = 模型 filterableFields ∩ origin=SOURCE（按 displayName 落标签）',
      Object.keys(expected).every(t => JSON.stringify(derived[t]) === JSON.stringify(expected[t])),
      `模型复算=${JSON.stringify(derived)} / 期望=${JSON.stringify(expected)}`)

    const allLabels = []
    for (const [type, want] of Object.entries(expected)) {
      await goto(page, '/explorer?type=' + type)
      await page.waitForTimeout(600)
      const labels = await filterLabels(page)
      allLabels.push(...labels)
      const text = await bodyText(page)
      const visible = want.every(w => text.includes(w))
      const bar = await blockText(page, '.toolbar')
      R.check(`${type} 筛选栏标签集合 = [${want.join('、') || '空'}] 且可见`,
        JSON.stringify(labels) === JSON.stringify(want) && visible,
        `实际标签=${JSON.stringify(labels)} / 期望=${JSON.stringify(want)} / 可见=${visible} / 工具栏=${bar.slice(0, 120)}`)
    }
    // 死控件断言：外键与技术主键绝不能再出现在筛选栏
    const bad = allLabels.filter(l => /学校\s*ID|班级\s*ID|教师\s*ID/i.test(l)
      || /^(id|Id|ID)$/.test(l) || /^(schoolId|classId|teacherId)$/.test(l))
    R.check('任何类型都不出现「学校ID/班级ID/教师ID/id」筛选标签',
      bad.length === 0, `全部标签=${JSON.stringify(allLabels)} / 命中=${JSON.stringify(bad)}`)
  } finally { await page.close() }
}

/**
 * 缺陷 3 防回归：直接打开关系页不能是空白。
 *
 * <p>原实现在未选中心对象时只渲染引导卡 + 一整屏空白，没有可点的起点，
 * 用户直接得出「关系探索是空的」。现在自动铺出首类型对象列表（不足 8 条时退化为全部类型），
 * 点任意一行即以该对象为中心展开关系列表与关系图。</p>
 */
async function caseRelationsDefaultPicker(ctx) {
  const R = forCase('relations_default_picker', '缺陷修复')
  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-rel-default')
  try {
    await goto(page, '/relations', '23-关系页默认对象列表-桌面.png')
    await page.waitForSelector('.list-plain li', { timeout: 8000 }).catch(() => {})
    const rows = await page.locator('.list-plain li').count().catch(() => 0)
    const text = await bodyText(page)
    R.check('直接打开 /#/relations 就有可点的对象列表（≥8 行）',
      rows >= 8, `对象列表行数=${rows} / 首行=${(await page.locator('.list-plain li').first().innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 80)}`)
    R.check('起始列表给出标题与范围说明（从下列对象开始探索 / 当前范围）',
      /从下列对象开始探索/.test(text) && /当前显示/.test(text), snip(text, /从下列对象开始探索/))

    let relRows = 0
    let circles = 0
    let afterText = ''
    if (rows > 0) {
      await page.locator('.list-plain li').first().click().catch(() => {})
      await page.waitForFunction(
        () => document.querySelectorAll('table.desktop-only tbody tr').length > 0,
        { timeout: 8000 },
      ).catch(() => {})
      await page.waitForTimeout(500)
      relRows = await desktopRowCount(page)
      circles = await page.locator('svg circle').count().catch(() => 0)
      afterText = await bodyText(page)
      await page.screenshot({ path: path.join(SHOTS, '23b-关系页点选对象后-桌面.png'), fullPage: true }).catch(() => {})
    }
    R.check('点第一行即以该对象为中心展开关系（关系列表有行）',
      relRows > 0, `关系列表行数=${relRows}`)
    R.check('关系图渲染出节点（svg circle > 0）',
      circles > 0, `svg circle=${circles} / 关系图区块=${/关系图/.test(afterText)}`)
    R.check('展开后 URL 带 globalId 且页面显示「关系总数」',
      /globalId=/.test(page.url()) && /关系总数/.test(afterText),
      `URL=${page.url()} / 关系总数=${/关系总数/.test(afterText)}`)
  } finally { await page.close() }
}

/**
 * 缺陷 3 防回归（空 globalId）：点「展开关系」必须给明确提示且不发请求。
 *
 * <p>原实现 globalId 为空时静默 return：页面毫无变化、只剩空白，用户只能判定按钮坏了。
 * 现在显式报错并把焦点交给输入框，且明确不发 /relations 请求（避免无意义调用）。
 * 用例先用带 globalId 的正常路径确认计数器有效，再清空后点击——否则「0 次请求」可能只是计数器没挂上。</p>
 */
async function caseRelationsEmptyGidHint(ctx) {
  const R = forCase('relations_empty_gid_hint', '缺陷修复')
  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-rel-empty')
  try {
    const relReqs = trackRequests(page,
      u => u.includes('/ontology-api/objects/') && /\/relations(\?|$)/.test(u))
    await goto(page, '/relations?globalId=' + encodeURIComponent(GID_STUDENT),
      '24-关系页清空globalId提示-桌面.png')
    await page.waitForFunction(
      () => document.querySelectorAll('table.desktop-only tbody tr').length > 0,
      { timeout: 8000 },
    ).catch(() => {})
    const baseline = relReqs.length

    const input = page.locator('.card .toolbar input[placeholder*="姓名或编号"]').last()
    const hasInput = await input.count().catch(() => 0)
    let after = baseline
    let errText = ''
    let banner = 0
    let focused = false
    if (hasInput > 0) {
      await input.fill('')
      await page.getByRole('button', { name: '查看关系', exact: true }).first().click().catch(() => {})
      await page.waitForTimeout(700)
      after = relReqs.length
      errText = await blockText(page, '.error-banner')
      banner = await page.locator('.error-banner').count().catch(() => 0)
      focused = await page.evaluate(() => {
        const el = document.activeElement
        return !!el && el.tagName === 'INPUT' && /姓名或编号/.test(el.getAttribute('placeholder') || '')
      }).catch(() => false)
    }
    R.check('正常路径（带 globalId）确实请求了 /relations（计数器有效性基线 > 0）',
      baseline > 0, `基线请求数=${baseline}`)
    R.check('对象编号为空时点「查看关系」给出选择提示',
      /请先从下面选一个对象/.test(errText), `提示文本=${errText || '（未渲染 .error-banner）'}`)
    R.check('对象编号为空时点「查看关系」不发 /relations 请求',
      after === baseline, `点击前=${baseline} / 点击后=${after}`)
    R.check('提示以错误条渲染（.error-banner）且焦点回到对象编号输入框',
      banner > 0 && focused, `.error-banner 数量=${banner} / 焦点在输入框=${focused}`)
  } finally { await page.close() }
}

/**
 * 缺陷 2 防回归（外部 URL 变化）：同一标签页换 URL，标题/表格/条数必须一起变。
 *
 * <p>原实现只在挂载与点击时加载：地址栏换成 ?type=class 后标题跟着变、表格还是旧数据——
 * 与「点了没反应」是同一类观感问题。现在 watch(route.query) 补一次查询，并用请求指纹去重，
 * 保证一次 URL 变化只补发 1 次请求（否则一次点击打两次接口、分页还会闪）。</p>
 */
async function caseExplorerUrlChangeRefresh(ctx) {
  const R = forCase('explorer_url_change_refresh', '缺陷修复')
  const page = await ctx.desktop.newPage()
  watchConsole(page, 'desktop-url-change')
  try {
    await goto(page, '/explorer?type=student', '25-同标签页换URL前-桌面.png')
    await waitExplorerTotal(page, 32)
    const beforeTotal = await explorerTotal(page)
    // 等挂载期的 revision 轮询首轮刷新落定，否则它打的那次 /objects 会被算进本次增量
    await page.waitForTimeout(1500)
    const objReqs = trackRequests(page, u => u.includes('/ontology-api/objects?'))
    const base = objReqs.length

    let navOk = true
    try {
      await page.goto(BASE + '/#/explorer?type=class', { waitUntil: 'domcontentloaded', timeout: 30000 })
    } catch {
      // hash-only 变化属于同文档跳转，个别环境会报导航中断：退化为直接改 hash，语义等价
      navOk = await page.evaluate(() => { window.location.hash = '#/explorer?type=class'; return true })
        .catch(() => false)
    }
    await waitExplorerTotal(page, 4)
    const afterTotal = await explorerTotal(page)
    const rows = await desktopRowCount(page)
    const sub = await blockText(page, '.page-head .sub')
    await page.screenshot({ path: path.join(SHOTS, '25b-同标签页换URL后-桌面.png'), fullPage: true }).catch(() => {})
    const fired = objReqs.length - base

    R.check('同一标签页把 URL 换成 ?type=class 后标题跟随（当前类型：班级）',
      /当前查看：\s*班级/.test(sub), `导航=${navOk} / URL=${page.url()} / 副标题=${sub || '（未渲染 .page-head .sub）'}`)
    R.check('表格数据跟随刷新（班级 4 行，不是学生 20 行）',
      rows === 4, `行数=${rows} / 换 URL 前徽标=${beforeTotal} 后=${afterTotal}`)
    R.check('「共 N 条」与表格行数三者一致（标题/表格/条数=4）',
      afterTotal === 4 && afterTotal === rows, `徽标=${afterTotal} / 行数=${rows}`)
    R.check('一次 URL 变化只补发 1 次 /objects 请求（去重生效）',
      fired === 1, `本次 /objects 请求数=${fired} / 最近 URL=${JSON.stringify(objReqs.slice(-3))}`)
  } finally { await page.close() }
}

/**
 * 26-对象类型在线编辑闭环（C 档）。
 *
 * 步骤全部走真实 UI 与接口：编辑学生 displayName → 保存草稿 → 应用（自重启）→
 * 等服务恢复 → 探索器按钮显示新名 → 恢复对象基线 → 再应用 → 回到「学生」。
 * 每步都轮询 /api/ontology/overview 直到 200，最长 120 秒。
 */
async function caseObjectTypeOnlineEdit(ctx) {
  const R = forCase('object_type_online_edit', '在线编辑')
  const page = await ctx.desktop.newPage()
  watchConsole(page, '26-对象在线编辑')
  try {
    // 26-1 前置：确保从打包基线开始，且 95 个对象完整。
    let versions = await ontoGet('/model/versions')
    let student = await currentStudentDefinition()
    if (!versions || !student || versions.draft?.exists || student.displayName !== '学生') {
      await restoreObjectBaseline()
      versions = await ontoGet('/model/versions')
      student = await currentStudentDefinition()
      const ready = await waitOntologyReady(versions?.baselineMappingVersion)
      R.check('26-1 前置：已回到打包基线且服务恢复 200', ready.ok,
        `overview=${ready.status} / active=${ready.version}`)
    }
    const overviewBefore = await ontoGet('/overview')
    R.check('26-1 前置：学生类型显示名为「学生」且 95 个对象在', student?.displayName === '学生'
      && overviewBefore?.counts?.objects === 95,
    `displayName=${student?.displayName} / objects=${overviewBefore?.counts?.objects}`)
    const baseVersion = versions.activeMappingVersion

    // 26-2 管理页 UI 编辑学生 displayName 并保存草稿。
    await goto(page, '/admin', '26-管理页-编辑前.png')
    await page.locator('[data-testid="edit-object-student"]').waitFor({ timeout: 15000 })
    await page.locator('[data-testid="edit-object-student"]').click()
    const dialog = page.locator('.modal-mask .modal').filter({ hasText: '编辑对象类别' })
    const displayInput = dialog.locator('.field')
      .filter({ hasText: '显示名称（按钮和菜单上给用户看的名字）' })
      .locator('input[type="text"]')
    await displayInput.fill('学生（测试）')
    await page.screenshot({ path: path.join(SHOTS, '26-学生编辑弹窗.png'), fullPage: true }).catch(() => {})
    await dialog.locator('button.primary').click()
    // 保存是异步请求：轮询到草稿真的出现再断言，别读点击前的快照。
    let saved = false
    for (let i = 0; i < 30; i++) {
      const v = await ontoGet('/model/versions')
      if (v?.draft?.objectTypesFromDraft === true) { saved = true; break }
      await new Promise(resolve => setTimeout(resolve, 400))
    }
    R.check('26-2 界面出现保存成功提示', /已保存草稿/.test((await bodyText(page))), '提示未出现')

    const draftVersions = await ontoGet('/model/versions')
    const draftStudent = await currentStudentDefinition()
    R.check('26-2 保存后产生对象草稿且版本变化', draftVersions?.draft?.objectTypesFromDraft === true
      && draftVersions.draft.draftMappingVersion !== baseVersion,
    `fromDraft=${draftVersions?.draft?.objectTypesFromDraft} / draft=${draftVersions?.draft?.draftMappingVersion}`)
    R.check('26-2 草稿中 displayName 已改为「学生（测试）」', draftStudent?.displayName === '学生（测试）',
      `displayName=${draftStudent?.displayName}`)

    // 26-3 点顶部「应用到运行时」，等自重启完成。
    const target = draftVersions.draft.draftMappingVersion
    // 自重启窗口内浏览器必然收到 502：这是预期中断，不计入 JS 运行错误。
    suppressConsoleErrors = true
    await page.locator('button.primary').filter({ hasText: '让改动生效' }).first().click()
    const restored = await waitOntologyReady(target)
    suppressConsoleErrors = false
    R.check('26-3 应用后服务重启恢复且目标版本生效（≤120s）', restored.ok,
      `overview=${restored.status} / active=${restored.version} / target=${target}`)
    const typesAfter = await ontoGet('/model/types')
    const studentAfter = (typesAfter?.types ?? []).find(row => row.id === 'student')
    R.check('26-3 /model/types 返回新展示名', studentAfter?.displayName === '学生（测试）',
      `displayName=${studentAfter?.displayName}`)

    // 26-4 探索器按钮显示新名，数据仍完整。
    await goto(page, '/explorer?type=student', '26-探索器-新名.png')
    // hash 路由跳转不会重新挂载 App；强制刷新一次，确保模型定义确实来自重启后的服务。
    await page.reload({ waitUntil: 'domcontentloaded' })
    await waitExplorerTotal(page, 32)
    const explorerText = await bodyText(page)
    const overviewAfter = await ontoGet('/overview')
    R.check('26-4 探索器类型按钮显示「学生（测试）」且学生 32 条', explorerText.includes('学生（测试）')
      && (await explorerTotal(page)) === 32, `文本命中=${explorerText.includes('学生（测试）')}`)
    R.check('26-4 应用后 95 个对象仍完整', overviewAfter?.counts?.objects === 95,
      `objects=${overviewAfter?.counts?.objects}`)

    // 26-5 恢复对象基线：只删对象 overlay，再应用到运行时。
    await goto(page, '/admin', '26-管理页-待恢复.png')
    await page.getByRole('button', { name: /撤销对象类别的在线修改/ }).click()
    await page.getByRole('button', { name: /确认恢复对象基线/ }).click()
    // 确认按钮是异步请求：轮询到草稿真的消失再断言，避免读到点击前的快照。
    let resetVersions = null
    for (let i = 0; i < 30; i++) {
      resetVersions = await ontoGet('/model/versions')
      if (resetVersions?.draft?.objectTypesFromDraft === false
        && Boolean(resetVersions?.draft?.pendingTargetVersion)) break
      await new Promise(resolve => setTimeout(resolve, 500))
    }
    R.check('26-5 恢复基线后无对象草稿且待生效目标为基线', resetVersions?.draft?.objectTypesFromDraft === false
      && Boolean(resetVersions?.draft?.pendingTargetVersion),
    `fromDraft=${resetVersions?.draft?.objectTypesFromDraft} / target=${resetVersions?.draft?.pendingTargetVersion}`)

    const baseAfterReset = resetVersions.activeMappingVersion
    suppressConsoleErrors = true
    await page.locator('button.primary').filter({ hasText: '让改动生效' }).first().click()
    const restoredBase = await waitOntologyReady(resetVersions.draft.pendingTargetVersion)
    suppressConsoleErrors = false
    R.check('26-5 再次应用后服务恢复且回到基线版本（≤120s）', restoredBase.ok
      && restoredBase.version === resetVersions.baselineMappingVersion,
    `overview=${restoredBase.status} / active=${restoredBase.version} / baseline=${resetVersions.baselineMappingVersion}`)
    const finalTypes = await ontoGet('/model/types')
    const finalStudent = (finalTypes?.types ?? []).find(row => row.id === 'student')
    const finalObjects = await waitObjectCount(95)
    R.check('26-6 展示名恢复「学生」且 95 个对象在', finalStudent?.displayName === '学生'
      && finalObjects.ok,
    `displayName=${finalStudent?.displayName} / objects=${finalObjects.count} / base=${baseAfterReset}`)
  } finally {
    suppressConsoleErrors = false
    // 兜底：无论断言结果如何，都不把「学生（测试）」留在演示环境。
    const cleaned = await restoreObjectBaseline()
    const after = await currentStudentDefinition()
    R.check('26-7 收尾：演示环境对象定义已恢复基线', cleaned.skipped === true || cleaned.ok === true,
      `cleanup=${JSON.stringify(cleaned)} / displayName=${after?.displayName}`)
    await page.screenshot({ path: path.join(SHOTS, '26-收尾-基线.png'), fullPage: true }).catch(() => {})
    await page.close()
  }
}

const HANDLERS = {
  pages_desktop: casePagesDesktop,
  pages_mobile: casePagesMobile,
  model_driven_views: caseModelDrivenViews,
  global_search: caseGlobalSearch,
  auto_update_5s: caseAutoUpdate5s,
  refresh_retry: caseRefreshRetry,
  business_jump: caseBusinessJump,
  source_freshness: caseSourceFreshness,
  display_regression: caseDisplayRegression,
  explorer_single_page_no_pager: caseExplorerSinglePageNoPager,
  explorer_filter_alias_works: caseExplorerFilterAliasWorks,
  explorer_filter_fields_whitelisted: caseExplorerFilterFieldsWhitelisted,
  relations_default_picker: caseRelationsDefaultPicker,
  relations_empty_gid_hint: caseRelationsEmptyGidHint,
  explorer_url_change_refresh: caseExplorerUrlChangeRefresh,
  object_type_online_edit: caseObjectTypeOnlineEdit,
}

function parseArgs() {
  const argv = process.argv.slice(2)
  const out = { list: false, all: true, cases: [] }
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--list') out.list = true
    else if (argv[i] === '--all') out.all = true
    else if (argv[i] === '--case' && argv[i + 1]) { out.cases = argv[++i].split(',').map(s => s.trim()).filter(Boolean) }
  }
  return out
}

function printCases() {
  console.log('本体平台浏览器验收 —— 用例清单（%d 项）', CASES.length)
  console.log('-'.repeat(92))
  for (const c of CASES) console.log(`  ${c.name.padEnd(20)} [${c.group}] ${c.title}`)
  console.log('-'.repeat(92))
  console.log('用法： node tests/ontology_browser.mjs [--all | --case a,b | --list]')
  console.log('截图： screenshots/ontology/  结果： tests/ontology-browser-results.json')
}

async function run() {
  const args = parseArgs()
  if (args.list) { printCases(); return 0 }
  const unknown = args.cases.filter(x => !HANDLERS[x])
  if (unknown.length) { console.error('未知 --case：' + unknown.join(', ')); return 2 }
  const selected = args.cases.length ? args.cases : CASES.map(c => c.name)

  console.log('本体平台浏览器验收地址：' + BASE)
  console.log('原业务：' + BIZ_API + ' / ' + BIZ_WEB + ' | 本体 API：' + ONTO_API)
  const browser = await chromium.launch({
    headless: true,
    executablePath: fs.existsSync(CHROME) ? CHROME : undefined,
    args: [
      '--no-sandbox', '--disable-dev-shm-usage',
      '--disable-background-timer-throttling',
      '--disable-backgrounding-occluded-windows',
      '--disable-renderer-backgrounding',
    ],
  })
  let desktop, mobile
  try {
    desktop = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
    mobile = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1,
      isMobile: true, hasTouch: true })
    const ctx = { browser, desktop, mobile }

    for (const name of selected) {
      console.log(`\n=== ${name} · ${CASES.find(c => c.name === name).title} ===`)
      try { await HANDLERS[name](ctx) }
      catch (e) {
        forCase(name, '异常').fail(name + ' · 用例执行异常',
          `${e && e.name}: ${e && e.message}（若服务未就绪请检查容器状态，不视为源码缺陷）`)
      }
    }

    // 本次未执行的用例显式标记 skipped
    const seen = new Set(results.map(x => x.case).filter(Boolean))
    for (const c of CASES) {
      if (seen.has(c.name)) continue
      add('[未执行] ' + c.title, 'skipped',
        `本次未包含该浏览器用例；执行方式：node tests/ontology_browser.mjs --case ${c.name}`,
        c.group, c.name)
    }

    const finalReset = await apiPost('/demo/reset')
    add('验收结束恢复初始演示数据', finalReset === 200 ? 'passed' : 'failed',
      `HTTP ${finalReset}`, '收尾', 'cleanup')
    const realErrors = consoleErrors.filter(x => !/503|QA 注入/.test(x))
    add('浏览器控制台无 JS 运行错误', realErrors.length === 0 ? 'passed' : 'failed',
      realErrors.slice(0, 3).join(' | ') || '无', '收尾', 'cleanup')
  } finally {
    await desktop?.close().catch(() => {})
    await mobile?.close().catch(() => {})
    await browser.close()
  }

  const passed = results.filter(r => r.status === 'passed').length
  const failed = results.filter(r => r.status === 'failed').length
  const skipped = results.filter(r => r.status === 'skipped').length
  console.log(`\n浏览器验收合计 ${results.length} 项：通过 ${passed}，失败 ${failed}，跳过 ${skipped}`)
  fs.writeFileSync(RESULTS, JSON.stringify({
    time: new Date().toISOString(), base: BASE,
    bases: { bizApi: BIZ_API, bizWeb: BIZ_WEB, ontologyApi: ONTO_API, ontologyWeb: BASE },
    selected,
    summary: { total: results.length, passed, failed, skipped, hasSkipped: skipped > 0 },
    consoleErrors, results,
  }, null, 2), 'utf-8')
  console.log('结果已写入 tests/ontology-browser-results.json')
  return failed === 0 ? 0 : 1
}

run().then(code => process.exit(code)).catch(e => {
  console.error('浏览器验收脚本异常：', e)
  add('浏览器验收流程完整执行', 'failed', String(e && e.stack || e).slice(0, 600), '异常', 'fatal')
  const passed = results.filter(r => r.status === 'passed').length
  const failed = results.filter(r => r.status === 'failed').length
  const skipped = results.filter(r => r.status === 'skipped').length
  try {
    fs.writeFileSync(RESULTS, JSON.stringify({
      time: new Date().toISOString(), base: BASE,
      summary: { total: results.length, passed, failed, skipped, hasSkipped: skipped > 0 },
      consoleErrors, results,
    }, null, 2), 'utf-8')
  } catch { /* 忽略写盘失败 */ }
  process.exit(2)
})
