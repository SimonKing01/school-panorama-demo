<script setup lang="ts">
import { fmt, type ChangeItem } from '../types'

defineProps<{ item: ChangeItem }>()

function roleLabel(role: string): string {
  const map: Record<string, string> = {
    SELF_CHANGED: '本身发生变化',
    DERIVED_RELATED: '按规则计算出的相关对象',
  }
  return map[role] ?? role
}

function roleClass(role: string): string {
  return role === 'SELF_CHANGED' ? 'danger' : 'muted'
}

function originLabel(origin: string): string {
  const map: Record<string, string> = {
    EVENT: '业务事件',
    RECONCILE: '对账修复（非业务操作）',
    REBUILD: '重建运行记录',
  }
  return map[origin] ?? origin
}

/**
 * 关系条目的人类可读文案：底座写入的是
 * {relationId, relationType, displayName, fromGlobalId, fromTitle, toGlobalId, toTitle, reason}，
 * 这里按「展示名 → 两端标题 → 类型标识 → 全局 ID」逐级回退，任何字段缺失都不会出现空白或 JSON。
 */
function fmtRelation(rel: Record<string, unknown>): string {
  const pick = (key: string): string => {
    const value = rel?.[key]
    if (value === null || value === undefined) return ''
    return String(value).trim()
  }
  const name = pick('displayName') || pick('relationType') || '关系'
  const from = pick('fromTitle') || pick('fromGlobalId') || '未知对象'
  const to = pick('toTitle') || pick('toGlobalId') || '未知对象'
  const type = pick('relationType')
  const arrow = `${from} → ${to}`
  return type ? `${name}：${arrow}（关系类型 ${type}）` : `${name}：${arrow}`
}
</script>

<template>
  <div class="card" style="margin-bottom:10px">
    <div style="display:flex; flex-wrap:wrap; gap:8px; align-items:center">
      <b>#{{ item.changeId }} · {{ item.changeKind }}</b>
      <span :class="'badge ' + (item.origin === 'EVENT' ? 'info' : 'warn')">{{ originLabel(item.origin) }}</span>
      <span class="hint">本身变化对象：{{ item.selfObject?.title || item.selfObject?.globalId || '—' }}</span>
    </div>

    <div class="grid cols-2" style="margin-top:10px">
      <div>
        <div class="hint">演示业务时间（可回拨，业务事实）</div>
        <div>{{ item.businessTime || '—（对账/重建不伪造发生时间）' }}</div>
      </div>
      <div>
        <div class="hint">同步处理时间（真实单调，运维时钟）</div>
        <div>{{ item.processedAt || '—' }}</div>
      </div>
    </div>

    <div style="margin-top:8px; font-size:13px">
      原因：{{ item.reason || '—' }} ·
      操作人：{{ item.operator || '—（不伪造操作人）' }} ·
      来源事件：{{ item.eventId || '—' }} ·
      映射版本：{{ item.mappingVersion || '—' }}
    </div>

    <div v-if="(item.diff ?? []).length" style="margin-top:10px">
      <b>属性前后差异</b>
      <ul class="list-plain">
        <li v-for="(entry, index) in item.diff ?? []" :key="index">
          {{ entry.label }}：<span class="badge muted">{{ fmt(entry.before) }}</span>
          → <span class="badge ok">{{ fmt(entry.after) }}</span>
        </li>
      </ul>
    </div>

    <div v-if="(item.relationsAdded ?? []).length" style="margin-top:8px">
      <b>新建立关系</b>
      <div v-for="(rel, index) in item.relationsAdded ?? []" :key="'a' + index" class="hint">
        {{ fmtRelation(rel) }}
      </div>
    </div>
    <div v-if="(item.relationsRemoved ?? []).length" style="margin-top:8px">
      <b>已结束关系</b>
      <div v-for="(rel, index) in item.relationsRemoved ?? []" :key="'r' + index" class="hint">
        {{ fmtRelation(rel) }}
      </div>
    </div>

    <div v-if="(item.impact ?? []).length" style="margin-top:10px">
      <b>变化影响判定（邻接对象不被一律标为业务状态已改变）</b>
      <ul class="list-plain">
        <li v-for="(entry, index) in item.impact ?? []" :key="index">
          <span :class="'badge ' + roleClass(entry.role)">{{ roleLabel(entry.role) }}</span>
          {{ entry.title || entry.globalId }}
          <div class="hint">判断依据：{{ entry.basis || '—' }}</div>
        </li>
      </ul>
    </div>
  </div>
</template>
