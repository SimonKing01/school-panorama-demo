import { reactive } from 'vue'
import type { RevisionView, SourceStatus, TypeDef } from './types'

/** 全局状态：模型定义、来源状态、平台版本与「成功后才 ack」的刷新游标。 */
export const store = reactive({
  types: [] as TypeDef[],
  mappingVersion: '' as string,
  sourceStatus: null as SourceStatus | null,
  revision: null as RevisionView | null,
  /** 只有当前页面所有视角数据刷新成功后才更新，绝不提前记住新版本号。 */
  acknowledged: -1 as number,
  lastError: '' as string,
  refreshing: false as boolean,
})

type Refresher = () => Promise<unknown>

const refreshers = new Set<Refresher>()

/** 每个已打开视角注册自己的刷新函数；返回注销函数供 onUnmounted 使用。 */
export function registerRefresher(fn: Refresher): () => void {
  refreshers.add(fn)
  return () => {
    refreshers.delete(fn)
  }
}

/**
 * 并行刷新所有视角。Promise.all 任一拒绝即整体失败，
 * useRevisionPolling 因此不会 ack 新版本号，下轮自动重试（验收一第 4 项）。
 */
export async function runRefreshAll(): Promise<void> {
  const tasks = Array.from(refreshers).map((fn) => fn())
  if (tasks.length === 0) return
  await Promise.all(tasks)
}
