<script setup lang="ts">
import { computed, inject, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiPost, errorText } from '../api'
import { store } from '../store'
import { fmtDateTime, toDatetimeLocal, fromDatetimeLocal } from '../utils'
import type { ClockData } from '../types'

const triggerRefresh = inject<() => Promise<void>>('triggerRefresh', async () => {})

const localTime = ref('')
const dirty = ref(false)
const running = ref(false)
const busy = ref(false)

// 轮询会不断刷新 clock，用户正在编辑输入框时不覆盖其输入。
watch(
  () => store.clock?.demoTime,
  (t) => {
    if (t && !dirty.value) localTime.value = toDatetimeLocal(t)
  },
  { immediate: true }
)
watch(
  () => store.clock?.running,
  (r) => {
    if (r !== undefined) running.value = r
  },
  { immediate: true }
)

const presets = computed(() => store.clock?.presets ?? [])
const clockText = computed(() =>
  store.clock ? `${fmtDateTime(store.clock.demoTime)}${running.value ? ' （跟随真实时间）' : ''}` : '加载中…'
)

async function call(path: string, body?: unknown): Promise<void> {
  busy.value = true
  try {
    const c = await apiPost<ClockData>(path, body)
    store.clock = c
    store.demoTime = c.demoTime
    dirty.value = false
    ElMessage.success('演示时间已更新，各视角即将刷新')
    await triggerRefresh()
  } catch (e) {
    ElMessage.error(errorText(e))
    // clock 可能被上一次 watch 同步，恢复为真实状态
    running.value = store.clock?.running ?? false
  } finally {
    busy.value = false
  }
}

function applyTime(): void {
  const t = fromDatetimeLocal(localTime.value)
  if (!t) {
    ElMessage.warning('请选择演示时间')
    return
  }
  void call('/demo/clock', { demoTime: t })
}

function applyPreset(demoTime: string): void {
  void call('/demo/clock', { demoTime })
}

function onRunningChange(val: string | number | boolean): void {
  void call('/demo/clock', { running: Boolean(val) })
}

function syncToday(): void {
  void call('/demo/clock/sync-today')
}

async function reset(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将恢复课表初始快照、清除演示期间新增的变更记录，并把演示时间恢复到当天 10:15。确定继续？',
      '恢复演示数据',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await call('/demo/reset')
}
</script>

<template>
  <div class="timebar">
    <span class="timebar-label">演示时间控制</span>
    <span class="timebar-clock">{{ clockText }}</span>

    <div class="timebar-actions">
      <input
        v-model="localTime"
        type="datetime-local"
        class="time-input"
        aria-label="演示日期与时间"
        @input="dirty = true"
      />
      <el-button size="small" type="primary" :loading="busy" :disabled="busy" @click="applyTime">应用时间</el-button>
      <span class="timebar-switch">
        <el-switch v-model="running" :disabled="busy" @change="onRunningChange" />
        跟随真实时间
      </span>
    </div>

    <div class="preset-group">
      <el-button
        v-for="p in presets"
        :key="p.label"
        class="preset-btn"
        size="small"
        :disabled="busy"
        @click="applyPreset(p.demoTime)"
      >
        {{ p.label }}
      </el-button>
    </div>

    <div class="timebar-actions">
      <el-button size="small" :disabled="busy" @click="syncToday">同步到今天</el-button>
      <el-button size="small" type="danger" plain :disabled="busy" @click="reset">恢复演示数据</el-button>
    </div>
  </div>
</template>
