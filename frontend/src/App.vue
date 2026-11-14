<script setup lang="ts">
import { onMounted, onUnmounted, provide } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet } from './api'
import { store, bumpRefresh } from './store'
import type { ClockData, MetaOptions, RevisionData } from './types'
import DemoTimeBar from './components/DemoTimeBar.vue'

const route = useRoute()
const router = useRouter()

const navItems = [
  { path: '/', label: '全局总览' },
  { path: '/search', label: '对象查询' },
  { path: '/objects', label: '全局列表' },
  { path: '/schedule', label: '课表管理' },
  { path: '/changes', label: '变更记录' },
]

function isActive(path: string): boolean {
  if (path === '/') return route.path === '/'
  if (path === '/objects') return route.path === '/objects' || route.path.startsWith('/object/')
  return route.path.startsWith(path)
}

function go(path: string): void {
  if (route.fullPath !== path) router.push(path)
}

async function loadClock(): Promise<void> {
  try {
    const c = await apiGet<ClockData>('/demo/clock')
    store.clock = c
    store.demoTime = c.demoTime
  } catch {
    // 顶部时间条保留上次数据，下一轮轮询再补
  }
}

async function loadMeta(): Promise<void> {
  try {
    store.meta = await apiGet<MetaOptions>('/meta/options')
  } catch {
    // 筛选项为空时页面仍可展示主数据
  }
}

// revision 或 demoTime 变化后刷新共享数据，并递增 refreshKey 通知所有页面重载。
async function refreshAll(): Promise<void> {
  await Promise.all([loadClock(), loadMeta()])
  bumpRefresh()
}

provide('triggerRefresh', refreshAll)

let lastRevision = -1
let lastDemoTime = ''
let timer: ReturnType<typeof setInterval> | null = null

async function poll(): Promise<void> {
  try {
    const r = await apiGet<RevisionData>('/revision')
    if (lastRevision < 0) {
      lastRevision = r.revision
      lastDemoTime = r.demoTime
      // 没有基线时补一次刷新，避免漏掉加载期间的变化
      await refreshAll()
      return
    }
    if (r.revision !== lastRevision || r.demoTime !== lastDemoTime) {
      lastRevision = r.revision
      lastDemoTime = r.demoTime
      await refreshAll()
    }
  } catch {
    // 网络抖动不影响下一轮
  }
}

// 后台标签页会被浏览器节流定时器；用户切回该视角时立即补一次刷新。
function onVisibilityChange(): void {
  if (document.visibilityState === 'visible') void refreshAll()
}

onMounted(async () => {
  await refreshAll()
  // 挂载时先记录一次数据版本基线，避免「页面加载完成 ~ 首次轮询」之间发生的调课被漏掉。
  try {
    const initial = await apiGet<RevisionData>('/revision')
    lastRevision = initial.revision
    lastDemoTime = initial.demoTime
  } catch {
    // 取不到基线时由首次轮询兜底
  }
  timer = setInterval(poll, 3000)
  document.addEventListener('visibilitychange', onVisibilityChange)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  document.removeEventListener('visibilitychange', onVisibilityChange)
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="topbar-row">
        <div>
          <h1 class="brand-title">学校业务对象全景演示</h1>
          <div class="brand-sub">学生 · 教师 · 班级 · 课程 · 教室 · 课表 一体化业务视角</div>
        </div>
        <span class="badge-demo">演示数据 · 全部为虚拟数据</span>
      </div>
      <DemoTimeBar />
    </header>

    <nav class="nav">
      <div class="nav-inner">
        <div
          v-for="n in navItems"
          :key="n.path"
          class="nav-item"
          :class="{ active: isActive(n.path) }"
          @click="go(n.path)"
        >
          {{ n.label }}
        </div>
      </div>
    </nav>

    <main class="main">
      <div class="global-note">
        <div class="tagline">
          当前课程 / 下一节课按统一演示时间计算；所有视角共用同一份课表数据，演示时间或调课变更后各页面 5 秒内自动同步。
        </div>
      </div>
      <router-view />
    </main>
  </div>
</template>
