<script setup lang="ts">
import { computed } from 'vue'
import { fmt, type ChangeItem, type DiffEntry } from '../types'

const props = defineProps<{ item: ChangeItem }>()

// 变化类型显示中文，数据里仍是英文标识。
function kindLabel(kind: string): string {
  const map: Record<string, string> = {
    ADJUST: '调课', CREATE: '新增', UPDATE: '修改', DELETE: '删除',
    RECONCILE: '系统自动修复', REBUILD: '重新生成', EVENT: '业务操作',
  }
  return map[kind] || kind
}

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

/** 对账/重建来源的中文动作词：按 diff 条目的 kind 映射，未知 kind 回退 note。 */
const DIFF_ACTIONS: Record<string, string> = {
  MISSING: '投影缺失，已补建对象与关系',
  ORPHAN: '来源已无，已墓碑化',
  RELATION_DIFF: '关系与来源不一致，已按稳定 ID 重建',
  FIELD_DIFF: '属性快照与来源不一致，已修正',
  FIELD_DIFF_SUMMARY: '属性快照与来源不一致，已修正（超出展示上限部分见备注）',
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

/**
 * 字段级形态判定（缺陷 A 修复核心）：事件来源与对账新结构都是字段级，
 * 判据是「有 label 或 before/after 任一存在」。绝不允许把缺失这三者的条目
 * 渲染成「： （空）→ （空）」的空行。
 */
function isFieldDiff(entry: DiffEntry): boolean {
  return Boolean(entry.label) || entry.before !== undefined || entry.after !== undefined
}

/** 对象级清单的动作词：kind 映射 → note → 通用兜底，逐级回退。 */
function diffAction(entry: DiffEntry): string {
  if (entry.kind && DIFF_ACTIONS[entry.kind]) return DIFF_ACTIONS[entry.kind]
  const note = typeof entry.note === 'string' ? entry.note.trim() : ''
  return note || '已按来源修正'
}

/** 对象级条目的备注：note 存在且与动作词不同才展示，避免重复文案。 */
function diffNote(entry: DiffEntry): string {
  const note = typeof entry.note === 'string' ? entry.note.trim() : ''
  return note && note !== diffAction(entry) ? note : ''
}

/** 兜底文案：既无字段级三件套、又无 globalId/note 的历史脏数据。 */
const NO_DETAIL_TEXT = '（此记录未提供可展示的差异明细）'

/**
 * 差异条目里出现的不同对象数（缺陷 A4）：对账修复一次可能涉及几十个对象，
 * 「本身变化对象」只展示 primaryGid 一个，>1 时需要明确标注，避免误导。
 */
const distinctDiffObjects = computed(() => {
  const gids = new Set<string>()
  for (const entry of props.item.diff ?? []) {
    if (typeof entry.globalId === 'string' && entry.globalId) gids.add(entry.globalId)
  }
  return gids.size
})

/**
 * 可展示的差异条目（缺陷 A5，历史数据兜底）。
 *
 * 早期对账记录里混着「前后值完全相同」的条目：投影从 JSONB 反序列化的数值是 Long、
 * 按来源重算得到的是 Integer，Objects.equals 判为不等，但序列化后看起来一模一样
 * （典型形态 `studentCount：8 → 8`）。这类条目没有任何信息量，只会让人怀疑系统坏了。
 * 后端已修正产生逻辑，但**已落库的历史记录无法重写**（审计只增不减），因此在此过滤掉。
 */
const visibleDiff = computed(() => {
  const all = props.item.diff ?? []
  return all.filter((entry) => {
    if (entry.before === undefined || entry.after === undefined) return true
    if (entry.before === entry.after) return false
    if (typeof entry.before !== 'object' && typeof entry.after !== 'object') {
      return String(entry.before) !== String(entry.after)
    }
    return true
  })
})

/** 原始有差异条目但全部无实质变化时的提示（避免区块整块消失让人以为丢数据）。 */
const onlyNoopDiff = computed(
  () => (props.item.diff ?? []).length > 0 && visibleDiff.value.length === 0,
)
</script>

<template>
  <div class="card" style="margin-bottom:10px">
    <div style="display:flex; flex-wrap:wrap; gap:8px; align-items:center">
      <b>第 {{ item.changeId }} 条变化 · {{ kindLabel(item.changeKind) }}</b>
      <span :class="'badge ' + (item.origin === 'EVENT' ? 'info' : 'warn')">{{ originLabel(item.origin) }}</span>
      <span class="hint">直接改动的对象：{{ item.selfObject?.title || item.selfObject?.globalId || '—' }}</span>
      <span v-if="distinctDiffObjects > 1" class="hint">（本记录涉及 {{ distinctDiffObjects }} 个对象）</span>
    </div>

    <div class="grid cols-2" style="margin-top:10px">
      <div>
        <div class="hint">业务发生时间（演示用，可回拨）</div>
        <div>{{ item.businessTime || '—（系统自动修复的记录没有发生时间）' }}</div>
      </div>
      <div>
        <div class="hint">平台实际处理时间</div>
        <div>{{ item.processedAt || '—' }}</div>
      </div>
    </div>

    <div style="margin-top:8px; font-size:13px">
      原因：{{ item.reason || '—' }} ·
      操作人：{{ item.operator || '—（系统自动修复，没有操作人）' }} ·
      来源记录：{{ item.eventId || '—' }} ·
      数据批次：{{ item.mappingVersion || '—' }}
    </div>

    <div v-if="(item.diff ?? []).length" style="margin-top:10px">
      <b>改了哪些信息</b>
      <div v-if="onlyNoopDiff" class="hint" style="margin-top:4px">
        （系统只是对齐了内部数据，没有真正改业务内容）
      </div>
      <ul v-else class="list-plain">
        <li v-for="(entry, index) in visibleDiff" :key="index">
          <template v-if="isFieldDiff(entry)">
            {{ entry.label || entry.field || '变更字段' }}：<span class="badge muted">{{ fmt(entry.before) }}</span>
            → <span class="badge ok">{{ fmt(entry.after) }}</span>
          </template>
          <template v-else-if="entry.globalId">
            <b>{{ entry.title || entry.globalId }}</b>
            <span class="badge warn" style="margin-left:6px">{{ diffAction(entry) }}</span>
            <div v-if="diffNote(entry)" class="hint">{{ diffNote(entry) }}</div>
          </template>
          <template v-else>
            {{ entry.note || NO_DETAIL_TEXT }}
          </template>
        </li>
      </ul>
    </div>

    <div v-if="(item.relationsAdded ?? []).length" style="margin-top:8px">
      <b>新增的关联</b>
      <div v-for="(rel, index) in item.relationsAdded ?? []" :key="'a' + index" class="hint">
        {{ fmtRelation(rel) }}
      </div>
    </div>
    <div v-if="(item.relationsRemoved ?? []).length" style="margin-top:8px">
      <b>结束的关联</b>
      <div v-for="(rel, index) in item.relationsRemoved ?? []" :key="'r' + index" class="hint">
        {{ fmtRelation(rel) }}
      </div>
    </div>

    <div v-if="(item.impact ?? []).length" style="margin-top:10px">
      <b>受影响的对象（只列真正相关的）</b>
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
