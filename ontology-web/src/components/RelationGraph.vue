<script setup lang="ts">
import { computed, ref } from 'vue'
import type { GraphData, GraphEdge, RelationView } from '../types'

/**
 * 关系图：纯 SVG 自研，不引入 d3/echarts/图库。
 * 节点按层级环形排布；点击边展示关系成因、来源表/主键、映射版本与有效时间。
 */
const props = defineProps<{ graph: GraphData; relations?: RelationView[] }>()
const selected = ref<GraphEdge | null>(null)

const W = 900
const H = 420

const positions = computed<Record<string, { x: number; y: number }>>(() => {
  const map: Record<string, { x: number; y: number }> = {}
  const nodes = props.graph.nodes ?? []
  if (nodes.length === 0) return map
  const center = nodes.find((node) => node.globalId === props.graph.center) ?? nodes[0]
  map[center.globalId] = { x: W / 2, y: H / 2 }
  const others = nodes.filter((node) => node.globalId !== center.globalId)
  const count = Math.max(others.length, 1)
  others.forEach((node, index) => {
    const radius = node.layer >= 2 ? 185 : 130
    const angle = (2 * Math.PI * index) / count - Math.PI / 2
    map[node.globalId] = {
      x: W / 2 + radius * Math.cos(angle),
      y: H / 2 + radius * Math.sin(angle) * 0.72,
    }
  })
  return map
})

const detail = computed<RelationView | null>(() =>
  (props.relations ?? []).find((rel) => rel.relationId === selected.value?.relationId) ?? null,
)

function xOf(globalId: string): number {
  return positions.value[globalId]?.x ?? W / 2
}

function yOf(globalId: string): number {
  return positions.value[globalId]?.y ?? H / 2
}

/**
 * 节点标签：不再按字符数硬裁（「明理楼 101」曾被截成「明理楼 10」）。
 * 规则：标签越长字号越小（12/11/10/9），10 个字符以内完整显示；
 * 更长的才用「…」省略，完整文案见 SVG &lt;title&gt; 悬停提示。
 */
const MAX_FULL_CHARS = 10
const ELLIPSIS_CHARS = 9

interface NodeLabel {
  text: string
  full: string
  fontSize: number
}

function labelOf(node: { title: string; type: string }): NodeLabel {
  const full = (node.title || node.type || '').trim()
  const length = full.length
  const fontSize = length <= 4 ? 12 : length <= 6 ? 11 : length <= 8 ? 10 : 9
  const text = length <= MAX_FULL_CHARS ? full : full.slice(0, ELLIPSIS_CHARS) + '…'
  return { text, full, fontSize }
}
</script>

<template>
  <div>
    <svg
      :viewBox="`0 0 ${W} ${H}`"
      style="width:100%; height:auto; max-height:420px; background:#fbfdff; border:1px solid var(--line); border-radius:8px"
      role="img"
      aria-label="对象关系图"
    >
      <line
        v-for="edge in graph.edges ?? []"
        :key="'line-' + edge.relationId"
        :x1="xOf(edge.from)"
        :y1="yOf(edge.from)"
        :x2="xOf(edge.to)"
        :y2="yOf(edge.to)"
        :stroke="edge.active ? '#2563eb' : '#cbd5e1'"
        :stroke-width="edge.active ? 2 : 1.5"
        :stroke-dasharray="edge.active ? '' : '5 4'"
        style="cursor:pointer"
        @click="selected = edge"
      />
      <g v-for="node in graph.nodes ?? []" :key="node.globalId">
        <circle
          :cx="xOf(node.globalId)"
          :cy="yOf(node.globalId)"
          :r="node.layer === 0 ? 26 : 18"
          :fill="node.layer === 0 ? '#2563eb' : '#e0ecff'"
          :stroke="node.layer === 0 ? '#1d4ed8' : '#93c5fd'"
          stroke-width="2"
        />
        <text
          :x="xOf(node.globalId)"
          :y="yOf(node.globalId) + 4"
          text-anchor="middle"
          :font-size="labelOf(node).fontSize"
          fill="#1f2937"
        ><title>{{ labelOf(node).full }}</title>{{ labelOf(node).text }}</text>
      </g>
    </svg>
    <div class="hint" style="margin-top:6px">
      点一条连线看为什么有关系。实线＝现在有效，虚线＝已经结束；图中 {{ (graph.nodes ?? []).length }} 个对象，{{ graph.truncated ? '已达到显示上限，可翻页看更多' : '' }}。
    </div>

    <div v-if="selected" class="modal-mask" @click.self="selected = null">
      <div class="modal">
        <h3>为什么有这条关系 · {{ selected.label }}</h3>
        <div class="kv">
          <div class="k">关系名称</div><div class="v">{{ selected.label }}</div>
          <div class="k">两端对象</div><div class="v">{{ selected.from }} → {{ selected.to }}</div>
          <div class="k">存在原因</div><div class="v">{{ selected.reason }}</div>
          <div class="k">判断依据</div><div class="v">{{ selected.basis }}</div>
          <div class="k">当前状态</div><div class="v">{{ selected.active ? '目前有效' : '已经结束' }}</div>
          <template v-if="detail">
            <div class="k">数据来自哪里</div>
            <div class="v">{{ detail.source?.sourceTable || '—' }} / {{ detail.source?.sourcePk || '—' }}</div>
            <div class="k">来源记录校验码</div><div class="v" style="font-size:11px;color:#888">{{ detail.source?.sourceHash || '—' }}</div>
            <div class="k">数据批次</div><div class="v">{{ detail.mappingVersion || '—' }}</div>
            <div class="k">开始日期</div><div class="v">{{ detail.validFrom || '—（长期有效）' }}</div>
            <div class="k">结束日期</div><div class="v">{{ detail.validTo || '—' }}</div>
            <div class="k">关系来源</div><div class="v">{{ detail.generationKind === 'JOIN_RULE' ? '按规则推算' : detail.generationKind === 'FOREIGN_KEY' ? '来源表外键' : (detail.generationKind || '—') }}</div>
          </template>
        </div>
        <p class="hint">这里说明为什么两者有关系；调课后可以看到旧关系结束、新关系建立。</p>
        <button @click="selected = null">关闭</button>
      </div>
    </div>
  </div>
</template>
