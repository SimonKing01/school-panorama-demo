<script setup lang="ts">
import { computed } from 'vue'
import { fmt, type DynamicResult } from '../types'

const props = defineProps<{ result: DynamicResult }>()

/** 只是把扩展点 key 映射成中文标题，不是按业务对象类型分支。 */
const LABELS: Record<string, string> = {
  currentLesson: '当前课程',
  nextLesson: '下一节课',
  lessonStatusResolver: '状态（按查询时间）',
}

const label = computed(() => LABELS[props.result.key] ?? props.result.key)
const isObject = computed(
  () => typeof props.result.value === 'object'
    && props.result.value !== null
    && !Array.isArray(props.result.value),
)
const objectEntries = computed(() =>
  isObject.value ? Object.entries(props.result.value as Record<string, unknown>) : [],
)
const inputsText = computed(() => (props.result.inputs ?? []).join('、') || '—')
</script>

<template>
  <div class="card" style="margin-bottom:10px">
    <div style="display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-bottom:8px">
      <b>{{ label }}</b>
      <span class="badge info">依赖查询时间的动态结果</span>
      <span class="hint">查询时间 {{ result.queryTime || '—' }}</span>
    </div>

    <div v-if="result.value === null || result.value === undefined" class="empty">空状态（该演示时间点无符合条件的结果）</div>
    <div v-else-if="isObject" class="kv">
      <template v-for="entry in objectEntries" :key="entry[0]">
        <div class="k">{{ entry[0] }}</div>
        <div class="v">{{ fmt(entry[1]) }}</div>
      </template>
    </div>
    <div v-else class="cell-text">{{ fmt(result.value) }}</div>

    <div class="basis" style="margin-top:10px">计算依据：{{ result.basis || '—' }}</div>
    <div class="hint" style="margin-top:4px">用到的对象/字段：{{ inputsText }}</div>
  </div>
</template>
