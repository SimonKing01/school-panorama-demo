// 真实浏览器验收：桌面 1440x900 与手机 390x844 两种宽度，覆盖完整体验链路并输出截图。
// 使用本机 Chrome + playwright-core，不依赖额外下载浏览器。
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(__dirname, '..')
const SHOTS = path.join(ROOT, 'screenshots')
const BASE = process.env.WEB_BASE || 'http://127.0.0.1:5180'
const CHROME = process.env.CHROME_PATH || 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

fs.mkdirSync(SHOTS, { recursive: true })
const results = []
const consoleErrors = []

function check(name, ok, detail = '') {
  results.push({ item: name, passed: !!ok, detail: String(detail).slice(0, 400) })
  console.log(`${ok ? '  [通过] ' : '  [失败] '}${name}${detail ? ' — ' + String(detail).slice(0, 180) : ''}`)
  return ok
}

async function apiPost(p) {
  const r = await fetch(BASE + p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' })
  return r.status
}

async function apiPostJson(p, body) {
  const r = await fetch(BASE + p, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  return { status: r.status, data: await r.json().catch(() => ({})) }
}

async function goto(page, route, shotName) {
  await page.goto(BASE + route, { waitUntil: 'domcontentloaded', timeout: 30000 })
  await page.waitForSelector('.nav-item', { timeout: 15000 }).catch(() => {})
  await page.waitForTimeout(900)
  if (shotName) await page.screenshot({ path: path.join(SHOTS, shotName), fullPage: true })
}

async function nav(page, label) {
  await page.locator('.nav-item', { hasText: label }).first().click()
  await page.waitForTimeout(1000)
}

async function overflow(page) {
  return await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    innerWidth: window.innerWidth,
    bodyWidth: document.body.scrollWidth,
  }))
}

async function firstVisibleInput(page) {
  const candidates = [
    page.getByPlaceholder(/姓名|编号|搜索/),
    page.locator('input[type="text"]'),
    page.locator('.el-input__inner'),
  ]
  for (const c of candidates) {
    try {
      const el = c.first()
      if (await el.count() && await el.isVisible()) return el
    } catch { /* 继续尝试下一个选择器 */ }
  }
  return null
}

async function clickFirstRow(page, text) {
  const candidates = [
    page.locator('.el-table__row', { hasText: text }),
    page.locator('tr', { hasText: text }),
    page.locator('.el-table__body tr', { hasText: text }),
    page.locator('.result-card', { hasText: text }),
    page.locator('.mobile-item', { hasText: text }),
    page.locator('.object-row', { hasText: text }),
    page.getByText(text, { exact: true }),
  ]
  for (const c of candidates) {
    try {
      const el = c.first()
      if (await el.count() && await el.isVisible()) {
        await el.click()
        return true
      }
    } catch { /* 继续尝试 */ }
  }
  return false
}

async function run() {
  console.log('浏览器验收地址：' + BASE)
  const browser = await chromium.launch({
    headless: true,
    executablePath: fs.existsSync(CHROME) ? CHROME : undefined,
    args: [
      '--no-sandbox', '--disable-dev-shm-usage',
      // 让「已打开的其他视角」按用户可见窗口行为运行，避免无头浏览器后台节流干扰验收
      '--disable-background-timer-throttling',
      '--disable-backgrounding-occluded-windows',
      '--disable-renderer-backgrounding',
    ],
  })

  try {
    const resetCode = await apiPost('/api/demo/reset')
    check('验收前恢复演示数据接口可用', resetCode === 200, `HTTP ${resetCode}`)

    // ---------- 桌面 ----------
    const desktop = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 })
    const page = await desktop.newPage()
    page.on('pageerror', e => consoleErrors.push('desktop pageerror: ' + e.message))
    page.on('console', m => { if (m.type() === 'error') consoleErrors.push('desktop console: ' + m.text().slice(0, 200)) })

    console.log('\n=== 桌面端 1440x900：主要页面截图 ===')
    await goto(page, '/', '01-全局总览-桌面.png')
    check('总览页显示标题与演示数据标识',
      await page.getByText('学校业务对象全景演示').count() > 0 && await page.getByText(/演示数据/).count() > 0)
    check('总览页显示统计卡片', await page.getByText(/学生|教师|班级/).count() > 0)
    check('总览页显示统一演示时间', await page.getByText(/演示时间/).count() > 0)
    const ovBox = await overflow(page)
    check('桌面总览无横向溢出', ovBox.scrollWidth <= ovBox.innerWidth + 2, JSON.stringify(ovBox))

    await goto(page, '/objects', '02-全局列表-桌面.png')
    check('全局列表页可访问', await page.getByText(/当前课程|下一节课|全局列表/).count() > 0)

    await goto(page, '/changes', '03-变更记录-桌面.png')
    check('变更记录页显示原因或时间', await page.getByText(/原因|时间|变更/).count() > 0)

    await goto(page, '/schedule', '04-课表管理-桌面.png')
    check('课表管理页展示 LS-003', await page.getByText('LS-003').count() > 0)
    check('课表管理页展示状态列', await page.getByText(/正在上课|即将开始|已结束/).count() > 0)

    console.log('\n=== 桌面端：搜索 → 详情 → 调课 → 变更记录 完整链路 ===')
    await nav(page, '对象查询')
    const input = await firstVisibleInput(page)
    check('对象查询页有搜索输入框', !!input)
    if (input) {
      await input.fill('陈一诺')
      await input.press('Enter')
      await page.waitForTimeout(1200)
    }
    const bodyText = await page.locator('body').innerText()
    check('搜索结果直接显示 陈一诺', bodyText.includes('陈一诺'), bodyText.slice(0, 0))
    check('搜索结果直接显示下一节课教师/时间/科目（无须进菜单）',
      bodyText.includes('赵建国') && bodyText.includes('11:00') && bodyText.includes('物理'),
      `赵建国=${bodyText.includes('赵建国')} 11:00=${bodyText.includes('11:00')} 物理=${bodyText.includes('物理')}`)
    await page.screenshot({ path: path.join(SHOTS, '05-对象查询-桌面.png'), fullPage: true })

    const clicked = await clickFirstRow(page, '陈一诺')
    await page.waitForTimeout(1200)
    check('点击搜索结果可进入对象详情', clicked && /\/object\//.test(page.url()), page.url())
    const detailText = await page.locator('body').innerText()
    check('详情页显示基本信息与关联对象',
      detailText.includes('陈一诺') && /关联|所在班级|课表/.test(detailText))
    await page.screenshot({ path: path.join(SHOTS, '06-对象详情-桌面.png'), fullPage: true })

    await goto(page, '/schedule')
    const adjustBtn = page.locator('tr', { hasText: 'LS-003' }).getByText(/调整/).first()
    let opened = false
    try {
      if (await adjustBtn.count() && await adjustBtn.isVisible()) {
        await adjustBtn.click()
        opened = true
      }
    } catch { /* 退化到全局按钮 */ }
    if (!opened) {
      try {
        await page.getByText(/调整/).first().click()
        opened = true
      } catch { /* 记录失败 */ }
    }
    await page.waitForTimeout(800)
    check('课表管理可打开调课弹窗', opened && await page.locator('.el-dialog').count() > 0)

    if (opened) {
      // 优先使用页面提供的推荐调课方案，避免下拉控件差异影响验收。
      const preset = page.getByText(/一键填入推荐调课方案|推荐调课方案/).first()
      if (await preset.count()) {
        await preset.click()
        await page.waitForTimeout(900)
        check('可一键填入推荐调课方案', true)
      } else {
        check('可一键填入推荐调课方案', false, '未找到推荐方案按钮，需手工填表验证')
      }
      const dialogText = await page.locator('.el-dialog').innerText().catch(() => '')
      check('提交前影响面板展示修改前后差异与受影响人数',
        /影响面板|修改前后差异/.test(dialogText) && /受影响学生|8 人|受影响/.test(dialogText),
        dialogText.slice(0, 200))
      await page.screenshot({ path: path.join(SHOTS, '07-调课影响面板-桌面.png'), fullPage: true })

      const submit = page.locator('.el-dialog').getByRole('button', { name: /提交|确认调整|确认调课|保存/ }).first()
      if (await submit.count()) {
        await submit.click()
        await page.waitForTimeout(1800)
        const afterText = await page.locator('body').innerText()
        check('调课提交后页面立即刷新并出现新结果',
          /陈思琪|多功能报告厅|15:30/.test(afterText),
          `陈思琪=${afterText.includes('陈思琪')} 15:30=${afterText.includes('15:30')}`)
        await page.screenshot({ path: path.join(SHOTS, '08-调课成功-桌面.png'), fullPage: true })
      } else {
        check('调课弹窗有提交按钮', false)
      }
    }

    await goto(page, '/changes', '09-变更记录-调课后-桌面.png')
    const changeText = await page.locator('body').innerText()
    check('变更记录页出现本次调课原因', changeText.includes('教师参加教研，调至第七节并更换教室'),
      `命中=${changeText.includes('教师参加教研，调至第七节并更换教室')}`)

    // 短轮询联动：保持一个已打开的学生详情视角，从外部完成调课，5 秒内应自动同步。
    console.log('\n=== 已打开视角短轮询同步（≤5 秒） ===')
    await apiPost('/api/demo/reset')
    const watcher = await desktop.newPage()
    await goto(watcher, '/object/student/1')
    await watcher.bringToFront()
    const beforeText = await watcher.locator('body').innerText()
    check('观察视角初始为原教师 张明远', beforeText.includes('张明远') && !beforeText.includes('陈思琪'))
    const clockRes = await fetch(BASE + '/api/demo/clock').then(r => r.json())
    const day = (clockRes.demoTime || '').slice(0, 10)
    const t0 = Date.now()
    const adjusted = await apiPostJson('/api/schedule/3/adjust', {
      teacherId: 5, classroomId: 6, lessonDate: day,
      startTime: '15:30', endTime: '16:15', reason: '短轮询联动验收：教师参加教研调至第七节',
    })
    check('外部视角调课成功', adjusted.status === 200, `HTTP ${adjusted.status}`)
    let synced = false
    let elapsed = 0
    while (Date.now() - t0 < 5200) {
      await watcher.waitForTimeout(350)
      const t = await watcher.locator('body').innerText()
      if (t.includes('陈思琪') && t.includes('15:30')) { synced = true; break }
    }
    elapsed = Date.now() - t0
    check('其他已打开视角通过短轮询在 5 秒内同步', synced && elapsed <= 5000,
      `耗时 ${elapsed}ms，同步=${synced}`)
    await watcher.screenshot({ path: path.join(SHOTS, '15-短轮询同步-学生详情.png'), fullPage: true })
    await watcher.close()

    await desktop.close()

    // 手机端从初始演示数据开始，避免桌面端的调课结果影响后续验证。
    await apiPost('/api/demo/reset')

    // ---------- 手机 ----------
    console.log('\n=== 手机端 390x844：搜索 / 详情 / 调课 ===')
    const mobile = await browser.newContext({
      viewport: { width: 390, height: 844 }, deviceScaleFactor: 1,
      isMobile: true, hasTouch: true,
    })
    const m = await mobile.newPage()
    m.on('pageerror', e => consoleErrors.push('mobile pageerror: ' + e.message))
    m.on('console', msg => { if (msg.type() === 'error') consoleErrors.push('mobile console: ' + msg.text().slice(0, 200)) })

    await goto(m, '/', '10-全局总览-手机.png')
    const mOv = await overflow(m)
    check('手机总览无横向溢出', mOv.scrollWidth <= mOv.innerWidth + 2, JSON.stringify(mOv))
    check('手机端导航可见可点', await m.locator('.nav-item').count() >= 5)

    await nav(m, '对象查询')
    const mInput = await firstVisibleInput(m)
    check('手机端搜索输入框可见', !!mInput)
    if (mInput) {
      await mInput.fill('STU-0001')
      await mInput.press('Enter')
      await m.waitForTimeout(1200)
    }
    const mText = await m.locator('body').innerText()
    check('手机端按编号可查到对象及下一节课',
      mText.includes('陈一诺') && /11:00|物理/.test(mText))
    const mOv2 = await overflow(m)
    check('手机端搜索结果无横向溢出', mOv2.scrollWidth <= mOv2.innerWidth + 2, JSON.stringify(mOv2))
    await m.screenshot({ path: path.join(SHOTS, '11-对象查询-手机.png'), fullPage: true })

    const mClicked = await clickFirstRow(m, '陈一诺')
    await m.waitForTimeout(1200)
    check('手机端可进入对象详情', mClicked && /\/object\//.test(m.url()), m.url())
    const mDetail = await overflow(m)
    check('手机端详情页无横向溢出', mDetail.scrollWidth <= mDetail.innerWidth + 2, JSON.stringify(mDetail))
    await m.screenshot({ path: path.join(SHOTS, '12-对象详情-手机.png'), fullPage: true })

    await nav(m, '课表管理')
    await m.waitForTimeout(800)
    let mOpened = false
    for (const sel of [
      m.locator('.mobile-item', { hasText: 'LS-003' }).getByText(/调整/),
      m.locator('tr', { hasText: 'LS-003' }).getByText(/调整/),
      m.getByRole('button', { name: '调整' }),
    ]) {
      try {
        const b = sel.first()
        if (await b.count() && await b.isVisible()) { await b.click(); mOpened = true; break }
      } catch { /* 继续尝试下一个选择器 */ }
    }
    await m.waitForTimeout(700)
    check('手机端可打开调课弹窗', mOpened && await m.locator('.el-dialog').count() > 0)
    if (mOpened) {
      const box = await m.locator('.el-dialog').boundingBox()
      check('手机端调课弹窗宽度适配屏幕', !!box && box.width <= 392, box ? JSON.stringify(box) : 'no box')
      const dText = await m.locator('.el-dialog').innerText().catch(() => '')
      check('手机端影响面板可见', /影响面板|修改前后差异|受影响/.test(dText), dText.slice(0, 120))

      // 手机端也要真正完成一次调课，而不只是打开弹窗。
      const mPreset = m.locator('.el-dialog').getByText(/一键填入推荐调课方案/).first()
      if (await mPreset.count()) {
        await mPreset.click()
        await m.waitForTimeout(1000)
      }
      const mPreview = await m.locator('.el-dialog').innerText().catch(() => '')
      check('手机端提交前显示差异与受影响人数',
        /修改前后差异/.test(mPreview) && /受影响学生/.test(mPreview), mPreview.slice(0, 160))
      const mSubmit = m.locator('.el-dialog').getByRole('button', { name: /确认调整/ }).first()
      let mSuccess = false
      if (await mSubmit.count() && await mSubmit.isEnabled()) {
        await mSubmit.click()
        await m.waitForTimeout(1800)
        const after = await m.locator('.el-dialog').innerText().catch(() => '')
        mSuccess = /调课成功|变更记录已写入/.test(after)
        check('手机端可完成调课提交并看到成功反馈', mSuccess, after.slice(0, 160))
      } else {
        check('手机端可完成调课提交并看到成功反馈', false, '提交按钮不可用')
      }
      await m.screenshot({ path: path.join(SHOTS, '14-手机端调课成功.png'), fullPage: true })
    }
    await m.screenshot({ path: path.join(SHOTS, '13-调课弹窗-手机.png'), fullPage: true })
    const mOv3 = await overflow(m)
    check('手机端课表/弹窗无横向溢出', mOv3.scrollWidth <= mOv3.innerWidth + 2, JSON.stringify(mOv3))

    await mobile.close()

    // ---------- 补充界面交互 ----------
    console.log('\n=== 桌面端补充交互：统计跳转 / 空状态 / 变更详情 / 冲突提示 ===')
    await apiPost('/api/demo/reset')
    const extra = await browser.newContext({ viewport: { width: 1440, height: 900 } })
    const x = await extra.newPage()
    x.on('pageerror', e => consoleErrors.push('extra pageerror: ' + e.message))
    x.on('console', msg => { if (msg.type() === 'error') consoleErrors.push('extra console: ' + msg.text().slice(0, 200)) })

    await goto(x, '/', '16-总览统计卡片-桌面.png')
    await x.locator('.stat-card', { hasText: '学生' }).first().click()
    await x.waitForTimeout(900)
    check('总览统计项点击进入对应筛选结果', /\/objects\?type=student/.test(x.url()), x.url())

    await goto(x, '/')
    await x.locator('.preset-btn', { hasText: '全部课程结束 17:00' }).first().click()
    await x.waitForTimeout(1400)
    const emptyText = await x.locator('body').innerText()
    check('课程全部结束后总览显示明确空状态',
      /今日课程已全部结束|课程已全部结束|尚未到上课时间/.test(emptyText),
      emptyText.slice(0, 160))
    await x.screenshot({ path: path.join(SHOTS, '17-课程结束空状态-桌面.png'), fullPage: true })
    await x.locator('.preset-btn', { hasText: '第三节课中 10:15' }).first().click()
    await x.waitForTimeout(1000)

    await goto(x, '/changes')
    const firstRow = x.locator('.el-table__row').first()
    if (await firstRow.count()) await firstRow.click()
    await x.waitForTimeout(1000)
    const drawerText = await x.locator('.el-drawer').innerText().catch(() => '')
    check('变更记录抽屉展示修改前后完整对照、原因与受影响对象',
      /修改前后完整对照|修改前|修改后/.test(drawerText) && /原因/.test(drawerText) && /受影响/.test(drawerText),
      drawerText.slice(0, 180))
    await x.screenshot({ path: path.join(SHOTS, '18-变更记录详情-桌面.png'), fullPage: true })

    await goto(x, '/schedule')
    const xAdjust = x.locator('tr', { hasText: 'LS-003' }).getByText(/调整/).first()
    if (await xAdjust.count()) await xAdjust.click()
    await x.waitForTimeout(700)
    const dlg = x.locator('.el-dialog')
    const reasonBox = dlg.locator('textarea').first()
    if (await reasonBox.count()) await reasonBox.fill('界面验收：测试班级时间冲突')
    const startBox = dlg.locator('input[aria-label="开始时间"]').first()
    const endBox = dlg.locator('input[aria-label="结束时间"]').first()
    if (await startBox.count()) await startBox.fill('11:00')
    if (await endBox.count()) await endBox.fill('11:45')
    await x.waitForTimeout(1400)
    const conflictText = await dlg.innerText().catch(() => '')
    check('界面逐条展示冲突具体原因（班级时间冲突）',
      /班级.*已有/.test(conflictText) || /班级.*冲突/.test(conflictText),
      conflictText.slice(-220))
    check('冲突存在时提交按钮被禁用',
      !(await dlg.getByRole('button', { name: /确认调整/ }).first().isEnabled().catch(() => true)))
    await x.screenshot({ path: path.join(SHOTS, '19-冲突校验提示-桌面.png'), fullPage: true })
    await extra.close()

    // 交付现场：恢复初始演示状态
    const finalReset = await apiPost('/api/demo/reset')
    check('验收结束恢复初始演示数据', finalReset === 200, `HTTP ${finalReset}`)
    check('浏览器控制台无 JS 运行错误', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))
  } finally {
    await browser.close()
  }

  const passed = results.filter(r => r.passed).length
  const failed = results.length - passed
  console.log(`\n浏览器验收合计 ${results.length} 项：通过 ${passed}，失败 ${failed}`)
  fs.writeFileSync(path.join(__dirname, 'browser-results.json'), JSON.stringify({
    time: new Date().toISOString(), base: BASE, passed, failed, consoleErrors, results,
  }, null, 2), 'utf-8')
  console.log('结果已写入 tests/browser-results.json')
  process.exit(failed === 0 ? 0 : 1)
}

run().catch(e => {
  console.error('浏览器验收脚本异常：', e)
  // 中途异常也要留下已完成的验收证据，便于定位问题而不是静默失败。
  const passed = results.filter(r => r.passed).length
  results.push({ item: '浏览器验收流程完整执行', passed: false, detail: String(e).slice(0, 400) })
  try {
    fs.writeFileSync(path.join(__dirname, 'browser-results.json'), JSON.stringify({
      time: new Date().toISOString(), base: BASE, passed,
      failed: results.length - passed, consoleErrors, results,
    }, null, 2), 'utf-8')
  } catch { /* 忽略写盘失败 */ }
  process.exit(2)
})
