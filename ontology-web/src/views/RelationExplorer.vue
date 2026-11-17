<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, gidPath, qs } from '../api'
import { registerRefresher } from '../store'
import { useModel } from '../composables/useModel'
import Pagination from '../components/Pagination.vue'
import RelationGraph from '../components/RelationGraph.vue'
import type { GraphData, ObjectDetail, ObjectListData, RelationListData } from '../types'

const route = useRoute()
const router = useRouter()
const { types, displayName } = useModel()

const globalIdInput = ref(String(route.query.globalId || ''))
const globalId = computed(() => globalIdInput.value.trim())
const depth = ref(Number(route.query.depth || 1))
const limit = ref(Number(route.query.limit || 50))
const page = ref(Number(route.query.page || 1))
const list = ref<RelationListData | null>(null)
const graph = ref<GraphData | null>(null)
const error = ref('')
let unregister: (() => void) | undefined

// ── 对象选择器（缺陷 B 修复）：类型下拉 + 关键字 + 结果列表，复用 /objects 接口 ──
const pickerType = ref('')
const pickerKeyword = ref('')
const pickerResults = ref<ObjectListData | null>(null)
const pickerError = ref('')
const pickerLoading = ref(false)

/** 快捷示例按钮：gid 骨架固定，标题从对象详情实时解析（对象不存在则自动隐藏）。 */
const examples = ref<{ gid: string; label: string }[]>([])

const EXAMPLE_GIDS = [
  'gid:school-panorama:student:1',
  'gid:school-panorama:lessonSession:3',
  'gid:school-panorama:class:1',
]

async function loadExamples(): Promise<void> {
  const resolved: { gid: string; label: string }[] = []
  for (const gid of EXAMPLE_GIDS) {
    try {
      const detail = await api.get<ObjectDetail>('/objects/' + gidPath(gid))
      resolved.push({ gid, label: `${displayName(detail.type)} ${detail.title || gid}` })
    } catch {
      // 示例对象不存在（演示数据被改写）时直接跳过，不展示死链接。
    }
  }
  examples.value = resolved
}

/** 与「通用对象探索器」同一套 /objects 接口：类型 + 关键字 + 分页，不另造轮子。 */
async function searchObjects(): Promise<void> {
  pickerLoading.value = true
  try {
    const params: Record<string, unknown> = { page: 1, size: 10 }
    if (pickerType.value) params.type = pickerType.value
    if (pickerKeyword.value.trim()) params.q = pickerKeyword.value.trim()
    pickerResults.value = await api.get<ObjectListData>('/objects' + qs(params))
    pickerError.value = ''
  } catch (e) {
    pickerError.value = e instanceof Error ? e.message : '对象搜索失败'
  } finally {
    pickerLoading.value = false
  }
}

/** 选中对象即填入中心对象并展开关系（与示例按钮共用）。 */
function pickObject(gid: string): void {
  globalIdInput.value = gid
  pickerResults.value = null
  pickerKeyword.value = ''
  apply()
}

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
  void loadExamples()
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

    <!-- 未选中心对象：引导 + 对象选择器（缺陷 B 修复：不再渲染空表格骨架） -->
    <div v-if="!globalId" class="card">
      <h3>请先选择中心对象</h3>
      <div class="hint">可在此搜索对象，或从「通用对象探索器」点选后进入本页。</div>
      <div class="toolbar" style="margin-top:8px">
        <label>对象类型</label>
        <select v-model="pickerType">
          <option value="">全部类型</option>
          <option v-for="t in types" :key="t.id" :value="t.id">{{ t.displayName }}</option>
        </select>
        <label>关键字</label>
        <input v-model="pickerKeyword" type="text" placeholder="姓名 / 编号 / 名称" style="min-width:200px" @keyup.enter="searchObjects" />
        <button class="primary" :disabled="pickerLoading" @click="searchObjects">{{ pickerLoading ? '搜索中' : '搜索对象' }}</button>
      </div>
      <p v-if="pickerError" class="error-banner">{{ pickerError }}</p>
      <div v-if="pickerResults">
        <div class="hint">命中 {{ pickerResults.total }} 条（点击选中作为中心对象）</div>
        <div v-if="!pickerResults.items.length" class="empty">没有匹配的对象</div>
        <ul class="list-plain">
          <li v-for="row in pickerResults.items" :key="row.globalId" style="cursor:pointer" @click="pickObject(row.globalId)">
            <b>{{ row.title || row.globalId }}</b>
            <span class="badge info" style="margin-left:6px">{{ displayName(row.type) }}</span>
            <span class="hint" style="margin-left:6px">{{ row.globalId }}</span>
          </li>
        </ul>
      </div>
      <div style="margin-top:10px">
        <span class="hint">快捷示例：</span>
        <button v-for="ex in examples" :key="ex.gid" style="margin-right:6px" @click="pickObject(ex.gid)">{{ ex.label }}</button>
      </div>
    </div>

    <div v-if="graph" class="card">
      <h3>关系图（纯 SVG，无外部图库）</h3>
      <RelationGraph :graph="graph" :relations="list?.items ?? []" />
    </div>

    <div v-if="globalId" class="card">
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

      <div v-if="!list" class="empty">正在加载关系数据…</div>
      <div v-else-if="!(list.items ?? []).length" class="empty">
        该对象在 {{ depth }} 层展开下暂无关系{{ depth === 1 ? '（可尝试切到 2 层）' : '' }}
      </div>
      <Pagination v-if="list" :page="page" :size="20" :total="list.total" @change="changePage" />
    </div>
  </div>
</template>
