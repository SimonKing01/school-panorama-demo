<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { api } from './api'
import { registerRefresher, store } from './store'
import { useModel } from './composables/useModel'
import { useRevisionPolling } from './composables/useRevisionPolling'
import RevisionBar from './components/RevisionBar.vue'
import SourceStatusBadge from './components/SourceStatusBadge.vue'
import GlobalSearch from './components/GlobalSearch.vue'
import type { SourceStatus } from './types'

const { load } = useModel()
const { poll } = useRevisionPolling()
const modelError = ref('')
let unregister: (() => void) | undefined

async function refreshStatus(): Promise<void> {
  store.sourceStatus = await api.get<SourceStatus>('/sources/status')
}

onMounted(async () => {
  try {
    await load()
  } catch (error) {
    modelError.value = error instanceof Error ? error.message : '页面信息没能加载出来，请刷新重试'
  }
  try {
    await refreshStatus()
  } catch {
    // 来源不可用时保留空状态，由徽章显示「还没接入」。
  }
  // 顶部状态本身也是一个「已打开视角」，必须刷新成功才允许 ack 新版本号。
  unregister = registerRefresher(refreshStatus)
})

onUnmounted(() => {
  if (unregister) unregister()
})
</script>

<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="brand">
        <h1>学校数据全景</h1>
        <span class="demo-tag">演示数据</span>
        <span class="demo-tag">演示时间</span>
      </div>
      <GlobalSearch />
      <div class="spacer"></div>
      <SourceStatusBadge :status="store.sourceStatus" />
    </header>

    <nav class="main-nav">
      <RouterLink to="/">全景总览</RouterLink>
      <RouterLink to="/explorer">查对象</RouterLink>
      <RouterLink to="/relations">查关系</RouterLink>
      <RouterLink to="/changes">看变化</RouterLink>
      <RouterLink to="/admin">配置中心</RouterLink>
    </nav>

    <main class="content">
      <p v-if="modelError" class="error-banner">页面信息没能加载出来：{{ modelError }}</p>
      <RevisionBar @refresh="poll" />
      <RouterView />
    </main>

    <footer class="foot">
      这里只查看已同步的数据，不会改业务；要改业务请到原业务系统 · 全部为演示数据
    </footer>
  </div>
</template>
