<script setup lang="ts">
import { computed } from 'vue'
import { store } from '../store'

const emit = defineEmits<{ (e: 'refresh'): void }>()
const revision = computed(() => store.revision)

/**
 * 已确认批次的可读说明。
 * 初始值 -1 表示「还没有确认过任何更新」，这是内部哨兵值，不能裸显示给用户，
 * 否则用户会以为页面更新到了一个叫 -1 的神秘批次。
 */
const acknowledgedText = computed(() => {
  const value = store.acknowledged
  if (value === -1 || value === null || value === undefined) return '还没有确认任何更新'
  return `第 ${value} 批`
})
</script>

<template>
  <div class="card" style="display:flex; flex-wrap:wrap; gap:6px 16px; align-items:center; font-size:13px; padding:10px 14px">
    <span>当前数据批次 <b>{{ revision?.revision ?? '—' }}</b></span>
    <span>页面已更新到：<b>{{ acknowledgedText }}</b></span>
    <span>演示时间 <b>{{ revision?.demoTime || '—' }}</b> <span class="demo-tag">演示时间</span></span>
    <span>上次同步成功 <b>{{ revision?.lastSuccessSyncAt || '—' }}</b></span>
    <span>等待同步 <b>{{ revision?.pendingCount ?? 0 }}</b> 条</span>
    <span class="spacer"></span>
    <span v-if="store.refreshing" class="badge info">正在重新加载各页面…</span>
    <span v-if="store.lastError" class="badge danger">重新加载失败，会自动重试：{{ store.lastError }}</span>
    <button @click="emit('refresh')">立即重新加载数据</button>
  </div>
</template>
