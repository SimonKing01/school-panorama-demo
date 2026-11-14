import { reactive } from 'vue'
import type { ClockData, MetaOptions } from './types'

// 全局响应式共享状态：页面 watch refreshKey 重新拉数据，保证各视角与统一演示时间同步。
export const store = reactive({
  refreshKey: 0,
  demoTime: '',
  clock: null as ClockData | null,
  meta: null as MetaOptions | null,
})

export function bumpRefresh(): void {
  store.refreshKey += 1
}
