<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, gidPath, qs } from '../api'
import { registerRefresher } from '../store'
import Pagination from '../components/Pagination.vue'
import RelationGraph from '../components/RelationGraph.vue'
import type { GraphData, RelationListData } from '../types'

const route = useRoute()
const router = useRouter()

const globalIdInput = ref(String(route.query.globalId || ''))
const globalId = computed(() => globalIdInput.value.trim())
const depth = ref(Number(route.query.depth || 1))
const limit = ref(Number(route.query.limit || 50))
const page = ref(Number(route.query.page || 1))
const list = ref<RelationListData | null>(null)
const graph = ref<GraphData | null>(null)
const error = ref('')
let unregister: (() => void) | undefined

async function load(): Promise<void> {
  if (!globalId.value) {
    list.value = null
    graph.value = null
    return
  }
  const gid = gidPath(globalId.value)
  const [relations, graphData] = await Promise.all([
    api.get<RelationListData>('/objects/' + gid + '/relations'
      + qs({ direction: 'both', depth: depth.value, page: page.value, size: 20 })),
    api.get<GraphData>('/objects/' + gid + '/graph'
      + qs({ depth: depth.value, limit: limit.value, page: page.value })),
  ])
  list.value = relations
  graph.value = graphData
}

async function reload(): Promise<void> {
  try {
    await load()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '关系数据加载失败'
  }
}

function syncQuery(): void {
  void router.replace({
    name: 'relations',
    query: {
      globalId: globalId.value,
      depth: String(depth.value),
      limit: String(limit.value),
      page: String(page.value),
    },
  })
}

function apply(): void {
  page.value = 1
  syncQuery()
  void reload()
}

function setDepth(next: number): void {
  depth.value = next
  page.value = 1
  syncQuery()
  void reload()
}

function changePage(next: number): void {
  page.value = next
  syncQuery()
  void reload()
}

function openObject(target: string): void {
  void router.push({ name: 'detail', query: { globalId: target } })
}

onMounted(async () => {
  await reload()
  unregister = registerRefresher(load)
})

onUnmounted(() => {
  if (unregister) unregister()
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2>关系探索</h2>
        <div class="sub">列表 + 关系图，1/2 层展开、节点上限与分页；点关系可看成因、来源记录与映射依据</div>
      </div>
      <span class="demo-tag">演示数据</span>
    </div>

    <div class="card">
      <div class="toolbar">
        <label>中心对象 globalId</label>
        <input v-model="globalIdInput" type="text" style="min-width:280px; max-width:100%" placeholder="gid:school-panorama:student:1" />
        <button class="primary" @click="apply">展开关系</button>
        <span class="spacer"></span>
        <label>展开层数</label>
        <button :class="{ active: depth === 1 }" @click="setDepth(1)">1 层</button>
        <button :class="{ active: depth === 2 }" @click="setDepth(2)">2 层</button>
        <label>节点上限</label>
        <input v-model.number="limit" type="number" min="1" max="200" style="width:90px" />
        <button @click="apply">应用上限</button>
      </div>
      <p v-if="error" class="error-banner">{{ error }}</p>
      <div v-if="list" class="hint">关系总数 {{ list.total }} · 当前第 {{ page }} 页</div>
    </div>

    <div v-if="graph" class="card">
      <h3>关系图（纯 SVG，无外部图库）</h3>
      <RelationGraph :graph="graph" :relations="list?.items ?? []" />
    </div>

    <div class="card">
      <h3>关系列表</h3>
      <table class="desktop-only">
        <thead>
          <tr>
            <th>关系 / 展示名</th><th>起点对象</th><th>终点对象</th><th>存在原因</th>
            <th>来源表 / 主键</th><th>映射版本</th><th>业务有效起</th><th>业务有效止</th><th>状态</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="rel in list?.items ?? []" :key="rel.relationId">
            <td><b>{{ rel.displayName }}</b><div class="hint">{{ rel.relationType }}</div></td>
            <td><a href="#" @click.prevent="openObject(rel.from.globalId)">{{ rel.from.title }}</a></td>
            <td><a href="#" @click.prevent="openObject(rel.to.globalId)">{{ rel.to.title }}</a></td>
            <td class="hint">{{ rel.reason }}</td>
            <td class="hint">{{ rel.source?.sourceTable || '—' }} / {{ rel.source?.sourcePk || '—' }}</td>
            <td class="hint">{{ rel.mappingVersion || '—' }}</td>
            <td class="hint">{{ rel.validFrom || '长期' }}</td>
            <td class="hint">{{ rel.validTo || '—' }}</td>
            <td><span :class="'badge ' + (rel.active ? 'ok' : 'muted')">{{ rel.active ? '有效（新建立）' : '已结束（旧关系）' }}</span></td>
          </tr>
        </tbody>
      </table>

      <div class="mobile-only">
        <div v-for="rel in list?.items ?? []" :key="rel.relationId" class="card" style="margin-bottom:8px">
          <b>{{ rel.displayName }}</b>
          <span :class="'badge ' + (rel.active ? 'ok' : 'muted')" style="margin-left:6px">{{ rel.active ? '有效' : '已结束' }}</span>
          <div class="kv" style="margin-top:8px">
            <div class="k">两端对象</div><div class="v">{{ rel.from.title }} → {{ rel.to.title }}</div>
            <div class="k">存在原因</div><div class="v">{{ rel.reason }}</div>
            <div class="k">来源表 / 主键</div><div class="v">{{ rel.source?.sourceTable || '—' }} / {{ rel.source?.sourcePk || '—' }}</div>
            <div class="k">映射版本</div><div class="v">{{ rel.mappingVersion || '—' }}</div>
            <div class="k">有效起 / 止</div><div class="v">{{ rel.validFrom || '长期' }} / {{ rel.validTo || '—' }}</div>
          </div>
        </div>
      </div>

      <div v-if="!(list?.items ?? []).length" class="empty">暂无关系数据</div>
      <Pagination :page="page" :size="20" :total="list?.total ?? 0" @change="changePage" />
    </div>
  </div>
</template>
