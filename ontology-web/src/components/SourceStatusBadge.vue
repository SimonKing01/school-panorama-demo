<script setup lang="ts">
import type { SourceStatus } from '../types'

defineProps<{ status: SourceStatus | null }>()

/**
 * 同步状态的可读说明。原始值是给程序用的英文标记，按统一对照表翻译给人看；
 * 只改显示，不改 status 原值、也不改任何请求参数。
 */
function statusText(status: string): string {
  const map: Record<string, string> = {
    SYNCED: '数据已同步',
    NO_DATA: '还没有业务数据',
    NOT_CONNECTED: '还没接入',
    LAGGING: '同步落后',
    FAILED: '同步失败',
  }
  return map[status] ?? status
}

/** 不同状态用不同颜色，避免混为一谈。 */
function statusClass(status: string): string {
  const map: Record<string, string> = {
    SYNCED: 'ok',
    NO_DATA: 'warn',
    NOT_CONNECTED: 'info',
    LAGGING: 'warn',
    FAILED: 'danger',
  }
  return map[status] ?? 'muted'
}
</script>

<template>
  <div v-if="status" style="font-size:12px; display:flex; flex-wrap:wrap; gap:6px 12px; align-items:center">
    <span :class="'badge ' + statusClass(status.status)">{{ statusText(status.status) }}</span>
    <span v-if="!status.sourceUp" class="badge danger">原系统暂时连不上（已同步的数据仍能查看）</span>
    <span v-else class="badge ok">数据连接正常</span>
    <span>演示时间 <b>{{ status.demoTime || '—' }}</b></span>
    <span>上次同步成功 <b>{{ status.lastSuccessSyncAt || '—' }}</b></span>
    <span v-if="status.hint" style="color:var(--muted)">{{ status.hint }}</span>
  </div>
  <div v-else style="font-size:12px; color:var(--muted)">正在检查数据同步情况…</div>
</template>
