<script setup lang="ts">
import type { SourceStatus } from '../types'

defineProps<{ status: SourceStatus | null }>()

/** 五态 + 来源不可用叠加标记；不同状态用不同颜色，避免混为一谈（E6）。 */
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
    <span :class="'badge ' + statusClass(status.status)">{{ status.statusLabel || status.status }}</span>
    <span v-if="!status.sourceUp" class="badge danger">来源不可用（仍可查已同步数据）</span>
    <span v-else class="badge ok">来源可用</span>
    <span>演示时间 <b>{{ status.demoTime || '—' }}</b></span>
    <span>最后成功同步 <b>{{ status.lastSuccessSyncAt || '—' }}</b></span>
    <span v-if="status.hint" style="color:var(--muted)">{{ status.hint }}</span>
  </div>
  <div v-else style="font-size:12px; color:var(--muted)">数据源状态读取中…</div>
</template>
