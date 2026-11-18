import { computed, onMounted, onUnmounted } from 'vue'
import { api } from '../api'
import { runRefreshAll, store } from '../store'
import type { RevisionView } from '../types'

/**
 * 短轮询协议（架构 §4.7 / 验收一第 4 项）：
 * 每 ~1s 拉 /revision；只有当**所有已打开视角都刷新成功**后才 ack 记住新版本号。
 * 取版本失败或任一视角刷新失败都不 ack，下一轮继续触发刷新，
 * 不会因「提前记住了新版本号」而永久停留在旧数据。
 */
export function useRevisionPolling(intervalMs = 1000) {
  let timer: number | undefined

  async function poll(): Promise<void> {
    let srv: RevisionView
    try {
      srv = await api.get<RevisionView>('/revision')
    } catch {
      // 取版本失败：下轮重试，acknowledged 不动。
      return
    }
    store.revision = srv
    if (srv.revision <= store.acknowledged) return
    store.refreshing = true
    try {
      await runRefreshAll()
      store.acknowledged = srv.revision
      store.lastError = ''
    } catch (error) {
      store.lastError = error instanceof Error ? error.message : '更新页面数据失败，会自动重试'
      // ★ acknowledged 保持不变，下一轮 srv.revision > acknowledged 会再次触发刷新。
    } finally {
      store.refreshing = false
    }
  }

  const onVisible = (): void => {
    if (document.visibilityState === 'visible') void poll()
  }

  onMounted(() => {
    void poll()
    timer = window.setInterval(() => void poll(), intervalMs)
    document.addEventListener('visibilitychange', onVisible)
  })

  onUnmounted(() => {
    if (timer !== undefined) window.clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisible)
  })

  return { poll, acknowledged: computed(() => store.acknowledged) }
}
