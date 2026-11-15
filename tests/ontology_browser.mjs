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

const BASE = process.env.ONTOLOGY_WEB_BASE || 'http://127.0.0.1:5181'
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

async function goto(page, route, shotName) {
  await page.goto(BASE + route, { waitUntil: 'domcontentloaded', timeout: 30000 })
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

function objectRoute(gidValue) {
  // 契约 §2.1 + 工程师口径：globalId 统一 encodeURIComponent（%3A），不混用裸 ':'，
  // 避免偶发 404（裸 ':' 与 %3A 均可命中 @PathVariable，但脚本统一编码）。
  return '/object/' + encodeURIComponent(gidValue)
}

async function gotoObject(page, gidValue, shotName) {
  const route = objectRoute(gidValue)
  try {
    await goto(page, route)
    const t = await bodyText(page)
    if (t && (t.includes('陈一诺') || t.includes('LS-003') || t.includes('来源') || t.includes('对象详情'))) {
      if (shotName) await page.screenshot({ path: path.join(SHOTS, shotName), fullPage: true }).catch(() => {})
      return { ok: true, route, text: t }
    }
  } catch { /* 由调用方按 URL / 文本判定 */ }
  return { ok: false, route, text: '' }
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
]

const PAGES = [
  { route: '/', title: '全景总览', shot: '总览', keywords: ['总览', '对象', '关系', '同步', '搜索'], nav: '全景总览' },
  { route: '/explorer', title: '通用对象探索器', shot: '对象探索器', keywords: ['对象', '类型', '列表', '学生', '教师'], nav: '对象探索器' },
  { route: '/relations', title: '关系探索（列表 + 图）', shot: '关系探索', keywords: ['关系', '图', '展开'], nav: '关系探索' },
  { route: '/changes', title: '变化与影响', shot: '变化与影响', keywords: ['变化', '影响', '来源', '时间'], nav: '变化与影响' },
  { route: '/admin', title: '模型与接入管理', shot: '模型与接入管理', keywords: ['模型', '映射', '数据源', '导入', '对账', '同步'], nav: '模型与接入管理' },
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
      /1 层|2 层|一层|两层|节点上限|层数|depth/i.test(relText),
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
    R.check('通用对象详情可打开（由 /object/:globalId 动态渲染）', d.ok && /\/object\//.test(page.url()),
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
        '未能定位到可用的对象详情路由，需主理人确认前端 /object/:globalId 实际路径')
    }
  } finally { await page.close() }
}

async function casePagesMobile(ctx) {
  const R = forCase('pages_mobile', '手机')
  const m = await ctx.mobile.newPage()
  watchConsole(m, 'mobile')
  try {
    await checkFivePages(m, ctx, '手机', R, true)
    const navOk = await nav(m, '对象探索器')
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
    R.check('同一套通用详情路由可打开（/object/:globalId）',
      d.ok && /\/object\//.test(page.url()), `URL=${page.url()}`)
    if (d.ok) {
      const t = await bodyText(page)
      R.check('详情页展示属性区 + 来源/映射 + 动态结果（详情数组驱动）',
        /来源|映射/.test(t) && /演示时间|当前|下一节|动态/.test(t) && /陈一诺/.test(t),
        t.slice(0, 220))
      R.check('列表与详情由同一套通用路由渲染（URL 无 student/classroom 等类型专用路径）',
        /\/object\//.test(page.url()) && !/\/student\/|\/classroom\/|\/teacher\//.test(page.url()),
        page.url())
    } else {
      R.skip('详情页属性区 / 来源 / 动态结果（数组驱动）',
        '未能定位通用对象详情路由，需主理人确认前端 /object/:globalId 实际路径')
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
    R.check('点击搜索结果可直达通用对象详情（/object/）',
      clicked && /\/object\//.test(page.url()), `clicked=${clicked} URL=${page.url()}`)
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
          d.ok && /\/object\//.test(page.url()), `URL=${page.url()}`)
        if (d.ok) {
          const t3 = await bodyText(page)
          R.check('教室通用详情含来源/映射/关联对象', /来源|映射/.test(t3) && /关联|关系/.test(t3),
            t3.slice(0, 200))
        }
      } else {
        R.skip('教室通用详情与关系图展开', '接口未返回教室 globalId，无法构造 /object/ 路由')
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
    const before = await bodyText(w1)
    R.check('观察视角初始不含新教师 陈思琪（旧状态）', !before.includes('陈思琪'),
      `张明远=${before.includes('张明远')} 陈思琪=${before.includes('陈思琪')}；`
      + '观察对象为 LS-003 授课安排本身，属性变化必然可见')

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
        const t = await bodyText(w1)
        if (t.includes('陈思琪') && t.includes('15:30')) { seen1 = true; e1 = Date.now() - t0 }
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
    R.check('观察视角已打开（注入失败前为旧数据）', d.ok && !(await bodyText(w)).includes('陈思琪'),
      `route=${d.route}`)

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
      /最后成功同步|已同步|新鲜度|同步时间/.test(t), t.slice(0, 220))
    R.check('两类时间分开显示（演示时间 vs 最后成功同步时间）',
      /演示时间/.test(t) && /最后成功同步|同步时间/.test(t),
      `演示时间=${/演示时间/.test(t)} 同步时间=${/最后成功同步|同步时间/.test(t)}`)
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

const HANDLERS = {
  pages_desktop: casePagesDesktop,
  pages_mobile: casePagesMobile,
  model_driven_views: caseModelDrivenViews,
  global_search: caseGlobalSearch,
  auto_update_5s: caseAutoUpdate5s,
  refresh_retry: caseRefreshRetry,
  business_jump: caseBusinessJump,
  source_freshness: caseSourceFreshness,
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
