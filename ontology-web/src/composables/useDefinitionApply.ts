import { onUnmounted, ref } from 'vue'
import { api } from '../api'
import type { ApplyResult, ModelVersionsData } from '../types'

/** 应用后的四种终态：进行中 / 成功 / 待手动重启 / 超时。 */
export type ApplyPhase = 'idle' | 'applying' | 'success' | 'timeout' | 'pending-restart'

const POLL_INTERVAL_MS = 1000
const TIMEOUT_MS = 30000

function delay(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms))
}

/**
 * 「应用到运行时」后的轮询恢复（07 增量设计 §9.1）。
 *
 * <p>本体服务自退出重启期间所有请求都会失败（502 / 连不上），因此轮询对错误
 * **一律吞掉继续重试**，直到 {@code activeMappingVersion} 变成目标版本或超时 30s。
 * 不改变全局 {@code useRevisionPolling} 的刷新协议，两者互不影响。</p>
 */
export function useDefinitionApply(onSettled?: () => Promise<void> | void) {
  const phase = ref<ApplyPhase>('idle')
  const message = ref('')
  const targetVersion = ref<string | null>(null)
  const activeVersion = ref<string | null>(null)
  let stopped = false

  async function waitForRestart(): Promise<void> {
    const deadline = Date.now() + TIMEOUT_MS
    while (!stopped && Date.now() < deadline) {
      await delay(POLL_INTERVAL_MS)
      if (stopped) break
      try {
        const data = await api.get<ModelVersionsData>('/model/versions')
        activeVersion.value = data.activeMappingVersion
        if (targetVersion.value && data.activeMappingVersion === targetVersion.value) {
          phase.value = 'success'
          message.value = `已生效：${data.activeMappingVersion}`
          if (onSettled) await onSettled()
          return
        }
      } catch {
        // 服务重启中：请求必然失败，继续等下一轮。
      }
    }
    if (stopped) return
    phase.value = 'timeout'
    message.value = '应用超时：请检查容器日志或手动重启本体服务（草稿仍为待生效，可重试）'
  }

  async function apply(baseMappingVersion: string, operator = '演示管理员'): Promise<ApplyResult | null> {
    message.value = ''
    targetVersion.value = null
    try {
      const result = await api.post<ApplyResult>('/model/relations/apply', { baseMappingVersion, operator })
      targetVersion.value = result.targetMappingVersion ?? null
      if (result.noChange) {
        phase.value = 'idle'
        message.value = result.message || '草稿与当前生效版本一致，无需应用'
        return result
      }
      if (result.pendingRestart) {
        phase.value = 'pending-restart'
        message.value = result.message || '未启用自动重启，请手动重启本体服务以生效'
        return result
      }
      if (result.applying) {
        phase.value = 'applying'
        message.value = result.message || '正在应用，服务重启中…'
        void waitForRestart()
      }
      return result
    } catch (error) {
      phase.value = 'idle'
      throw error
    }
  }

  function reset(): void {
    phase.value = 'idle'
    message.value = ''
    targetVersion.value = null
  }

  onUnmounted(() => {
    stopped = true
  })

  return { phase, message, targetVersion, activeVersion, apply, reset }
}
