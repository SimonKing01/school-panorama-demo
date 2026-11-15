<script setup lang="ts">
import { computed } from 'vue'
import { store } from '../store'

const emit = defineEmits<{ (e: 'refresh'): void }>()
const revision = computed(() => store.revision)
</script>

<template>
  <div class="card" style="display:flex; flex-wrap:wrap; gap:6px 16px; align-items:center; font-size:13px; padding:10px 14px">
    <span>平台版本 <b>{{ revision?.revision ?? '—' }}</b></span>
    <span>已确认版本 <b>{{ store.acknowledged }}</b></span>
    <span>演示时间 <b>{{ revision?.demoTime || '—' }}</b> <span class="demo-tag">演示时间</span></span>
    <span>最后成功同步 <b>{{ revision?.lastSuccessSyncAt || '—' }}</b></span>
    <span>待处理 <b>{{ revision?.pendingCount ?? 0 }}</b></span>
    <span class="spacer"></span>
    <span v-if="store.refreshing" class="badge info">正在刷新所有视角…</span>
    <span v-if="store.lastError" class="badge danger">刷新失败，将自动重试：{{ store.lastError }}</span>
    <button @click="emit('refresh')">立即重试刷新</button>
  </div>
</template>
