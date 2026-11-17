<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
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
/** 未填 globalId 时把焦点交给输入框，让用户知道该改哪里（缺陷 3）。 */
const globalIdField = ref<HTMLInputElement | null>(null)
const depth = ref(Number(route.query.depth || 1))
const limit = ref(Number(route.query.limit || 50))
const page = ref(Number(route.query.page || 1))
const list = ref<RelationListData | null>(null)
const graph = ref<GraphData | null>(null)
const error = ref('')
/**
 * 上一次「真正发出去的请求」的参数指纹（去重用）。
 * syncQuery() 自己造成的 URL 变化会回来触发 route.query 监听，不去重就会一次点击打两次接口。
 */
let lastRequestKey = ''
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
/**
 * 选中对象即填入中心对象并展开关系（与示例按钮共用）。
 *
 * <p>这里不再清空 pickerResults：起始列表是「未选中心对象」空态里的唯一出路，
 * 选过一次就把它丢掉，用户随后清空 globalId 时会退回没有入口的空白页 ——
 * 那正是本次要修掉的观感。列表本身渲染在 v-if="!globalId" 的卡片里，
 * 选中对象后卡片自然隐藏，无需手动清空。</p>
 */
function pickObject(gid: string): void {
  globalIdInput.value = gid
  pickerKeyword.value = ''
  void apply()
}

/** 起始列表不足这么多条时视为「接近空白」，退化为全部类型。 */
const STARTER_MIN_ROWS = 8

/** 起始列表当前覆盖的类型范围，避免用户误以为列出来的都是同一类型。 */
const pickerScope = computed(() => (pickerType.value ? displayName(pickerType.value) : '全部类型'))

/** 有输入关键字时标题切到「搜索结果」，避免把筛选结果也叫「从下列对象开始探索」。 */
const pickerTitle = computed(() => (pickerKeyword.value.trim() ? '搜索结果' : '从下列对象开始探索'))

/**
 * 未选中心对象时自动铺出起始对象列表（缺陷 3）。
 * 原来这里只有一张引导卡 + 一整屏空白，用户没有可点的起点，
 * 加上输入框 placeholder 长得像已填好的值，就会得出「关系探索是空的」。
 * 复用已有的 /objects 搜索（searchObjects），不新增接口。
 *
 * 默认取首个对象类型（types[0].id）。但演示数据里首个类型「学校」只有 2 条对象，
 * 只铺 2 行依然接近空白 —— 因此在首个类型不足一屏时自动退化为「全部类型」，
 * 保证用户一进页面就有足够可点的入口（这正是本缺陷的原始诉求）。
 */
async function loadStarter(): Promise<void> {
  if (!pickerType.value && types.value.length) pickerType.value = types.value[0].id
  await searchObjects()
  const tooFew = (pickerResults.value?.total ?? 0) < STARTER_MIN_ROWS
  if (tooFew && !pickerKeyword.value.trim() && pickerType.value) {
    pickerType.value = ''
    await searchObjects()
  }
}

/** 一组请求参数的指纹：字符串相同即视为「同一个查询」，用于 route.query 监听去重。 */
function relationKey(gid: string, d: number, l: number, pageNo: number): string {
  return JSON.stringify([gid, d, l, pageNo])
}

async function load(): Promise<void> {
  if (!globalId.value) {
    list.value = null
    graph.value = null
    lastRequestKey = relationKey('', depth.value, limit.value, page.value)
    return
  }
  // 记录指纹：route.query 监听据此判断这次 URL 变化是不是自己发起的（避免重复请求）
  lastRequestKey = relationKey(globalId.value, depth.value, limit.value, page.value)
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

/**
 * 原实现里 globalId 为空时静默 return：用户点了「展开关系」页面毫无变化，
 * 只看到「请先选择中心对象」和空白，就判定按钮坏了（缺陷 3）。
 * 现在显式报错、把焦点交给输入框，且明确不发请求（避免无意义的 /relations 调用）。
 */
async function apply(): Promise<void> {
  if (!globalId.value) {
    error.value = '请先输入 globalId，或从下方对象列表中选择一个对象'
    globalIdField.value?.focus()
    // 空态卡片里必须始终有可点入口：首次进入时 loadStarter 可能因模型未就绪而没铺上列表，
    // 这里补一次，避免「提示有了、下面还是空的」。
    if (!pickerResults.value) void loadStarter()
    return
  }
  page.value = 1
  error.value = ''
  syncQuery()
  await reload()
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
  void loadStarter()
  await reload()
  unregister = registerRefresher(load)
})

/**
 * 模型定义由父级 App 在 onMounted 里拉取，可能晚于本视图挂载完成：
 * 类型到位后补一次默认类型与起始列表，否则开场列表会退化成「全部类型」。
 */
watch(
  () => types.value.length,
  (count) => {
    if (count > 0 && !pickerType.value) void loadStarter()
  },
)

/**
 * 外部改 URL（同一标签页换地址、粘贴带 globalId/depth/page 的链接）时补一次查询。
 * 原来数据只在挂载与点击时加载：URL 换成另一个中心对象后，输入框外的页面毫无变化，
 * 同样会被当成「按钮失灵」。lastRequestKey 用来跳过 syncQuery 自己造成的变化，避免重复请求。
 */
watch(
  () => route.query,
  (query) => {
    const nextGid = String(query.globalId || '').trim()
    const nextDepth = Number(query.depth || 1)
    const nextLimit = Number(query.limit || 50)
    const nextPage = Number(query.page || 1)
    if (relationKey(nextGid, nextDepth, nextLimit, nextPage) === lastRequestKey) {
      return
    }
    globalIdInput.value = nextGid
    depth.value = nextDepth
    limit.value = nextLimit
    page.value = nextPage
    error.value = ''
    void reload()
  },
)

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
        <span class="hint">未填写时点「展开关系」不会发起查询</span>
        <input
          ref="globalIdField"
          v-model="globalIdInput"
          type="text"
          style="min-width:280px; max-width:100%"
          placeholder="例如：gid:school-panorama:student:1（可粘贴，也可从下方选择对象）"
        />
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
      <div v-if="pickerLoading && !pickerResults" class="loading">正在加载对象列表…</div>
      <div v-if="pickerResults">
        <h3 style="margin-top:12px">{{ pickerTitle }}</h3>
        <div class="hint">
          当前范围：{{ pickerScope }} · 共 {{ pickerResults.total }} 条（点击任意一行，即以该对象为中心展开关系）
        </div>
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
