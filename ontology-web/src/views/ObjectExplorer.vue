<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, qs } from '../api'
import { registerRefresher, store } from '../store'
import { useModel } from '../composables/useModel'
import TypeNav from '../components/TypeNav.vue'
import ObjectTable from '../components/ObjectTable.vue'
import Pagination from '../components/Pagination.vue'
import type { ObjectListData } from '../types'

const route = useRoute()
const router = useRouter()
const { types, listFields, typeOf, displayName } = useModel()

const activeType = computed(() => String(route.query.type || store.types[0]?.id || ''))
const keyword = ref(String(route.query.q || ''))
const page = ref(Number(route.query.page || 1))
const size = ref(Number(route.query.size || 20))
const filters = ref<Record<string, string>>({})
const data = ref<ObjectListData | null>(null)
const error = ref('')
const loading = ref(false)
/**
 * 本次操作的可见反馈（缺陷 2）。
 * 条件为空时接口返回的数据与当前完全一致，页面毫无变化，用户会判定按钮失灵，
 * 因此「应用筛选 / 清空」都要明确回一行结果说明。
 */
const notice = ref('')
/**
 * 请求序号（缺陷 2 竞态保护）。
 * 快速连点类型 / 分页时，先发出的旧请求可能后到并覆盖新结果，
 * 表现出来同样是「点了按钮但列表没变」——与按钮失灵无法区分。
 * 只有序号等于最新一次请求的响应才允许写进 data。
 */
let requestSeq = 0
/**
 * 上一次「真正发出去的请求」的参数指纹（去重用）。
 * 自己 syncQuery() 促成的 URL 变化会回来触发 route.query 监听，如果不去重就会
 * 每次点按钮打两次接口、分页还会闪一下 —— 所以只对「参数真的不同」的 URL 变化补查询。
 */
let lastRequestKey = ''
let unregister: (() => void) | undefined

const fields = computed(() => listFields(activeType.value))

/**
 * 某类型真正可用的筛选项：模型声明可筛 且 投影里真有该属性。
 *
 * <p>为什么不能直接照抄 filterableFields：里面混着外键（schoolId / classId / teacherId），
 * 而投影对象属性里没有这些键（属性只有 code/name/gender/className… 那批真列）。
 * 按一个不存在的属性筛选，SQL 里 attributes->>'classId' 恒不匹配 → 永远 0 条，
 * 用户填一次就判定「探索器坏了」。把死控件摆出来比不摆更糟，所以这里做一次交集：
 * 只保留在该类型 detailFields 里 origin=SOURCE 的属性。</p>
 *
 * <p>技术主键 id 因此也被自然排除（模型属性里没有 id），沿用上一轮「不暴露技术主键」的结论。
 * 外键筛选后续应以「按关系筛选」的方式实现（选一个对象再筛它的关联对象），不在本轮范围。</p>
 */
function filterFieldsOf(type: string): string[] {
  const typeDef = typeOf(type)
  const sourceFields = new Set(
    (typeDef?.detailFields ?? []).filter((item) => item.origin === 'SOURCE').map((item) => item.name),
  )
  return (typeDef?.filterableFields ?? []).filter((name) => sourceFields.has(name))
}

const filterFields = computed(() => filterFieldsOf(activeType.value))

/**
 * 把 URL 上的平铺筛选还原成 filters（只认该类型白名单字段）。
 * 外部改 URL 时必须整体重建，不能沿用旧条件；否则切类型时会把上一个类型的筛选带过来。
 */
function filtersFromQuery(type: string): Record<string, string> {
  const result: Record<string, string> = {}
  for (const name of filterFieldsOf(type)) {
    const value = route.query[name]
    if (typeof value === 'string' && value) result[name] = value
  }
  return result
}

/** 筛选标签优先用模型定义里的中文 displayName，取不到才退回字段名（模型接口不变，纯显示层处理）。 */
function filterLabel(name: string): string {
  const type = typeOf(activeType.value)
  const field = [...(type?.listFields ?? []), ...(type?.detailFields ?? [])]
    .find((item) => item.name === name)
  return field?.displayName ?? name
}

/** 一组请求参数的指纹：字符串相同即视为「同一个查询」，用于 route.query 监听去重。 */
function requestKeyOf(
  type: string,
  q: string,
  pageNo: number,
  sizeNo: number,
  active: Record<string, string>,
): string {
  return JSON.stringify([type, q, pageNo, sizeNo, active])
}

/** 所有查询条件都放在 URL query 里，筛选/分页后返回仍保留上下文（E2）。 */
function currentQuery(): Record<string, string> {
  const query: Record<string, string> = {
    type: activeType.value,
    page: String(page.value),
    size: String(size.value),
  }
  if (keyword.value.trim()) query.q = keyword.value.trim()
  for (const [key, value] of Object.entries(filters.value)) {
    if (value) query[key] = value
  }
  return query
}

/**
 * @param typeOverride 切换类型时显式传入目标类型。
 *   不能依赖 activeType（它是 route.query.type 的 computed）——router.replace 是异步的，
 *   调用后立刻读 computed 仍会拿到旧值，点击按钮就会“按不动”。
 */
async function load(typeOverride?: string): Promise<void> {
  const type = typeOverride || activeType.value
  if (!type) return
  const params: Record<string, unknown> = {
    type,
    q: keyword.value.trim(),
    page: page.value,
    size: size.value,
  }
  const activeFilters: Record<string, string> = {}
  for (const [key, value] of Object.entries(filters.value)) {
    if (value) activeFilters[key] = value
  }
  // 契约 03 §2.6 写法一：筛选统一塞进 filters=<JSON>。
  // 平铺写法（?gender=男）历史上一直被服务端静默丢弃，JSON 形式与 filters 参数一一对应、
  // 也不会和 type/q/page/size 等控制参数混淆；URL 里仍保留平铺写法以便刷新后恢复筛选条件。
  if (Object.keys(activeFilters).length > 0) params.filters = JSON.stringify(activeFilters)
  lastRequestKey = requestKeyOf(type, keyword.value.trim(), page.value, size.value, activeFilters)
  const ticket = ++requestSeq
  loading.value = true
  try {
    const result = await api.get<ObjectListData>('/objects' + qs(params))
    // 过期响应整体丢弃（连失败一起丢弃），否则旧结果会盖掉用户刚点出来的新结果。
    if (ticket !== requestSeq) return
    data.value = result
  } catch (e) {
    if (ticket !== requestSeq) return
    throw e
  } finally {
    if (ticket === requestSeq) loading.value = false
  }
}

function syncQuery(): void {
  void router.replace({ name: 'explorer', query: currentQuery() })
}

async function reload(typeOverride?: string): Promise<void> {
  try {
    await load(typeOverride)
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '没能查出对象列表，请检查一下条件或稍后重试'
  }
}

/**
 * 从 URL 恢复筛选上下文（E2）：整体重建，只认该类型白名单字段，
 * 避免把别的类型的键或被隐藏的技术字段（id / 外键）带回来。
 */
function restoreFiltersFromQuery(): void {
  filters.value = filtersFromQuery(activeType.value)
}

onMounted(async () => {
  restoreFiltersFromQuery()
  await reload()
  unregister = registerRefresher(load)
})

/**
 * 模型定义由父级 App 在 onMounted 里拉取，可能晚于本视图挂载完成：
 * 那一刻 activeType 为空、filterFields 也为空 —— load() 直接返回、URL 上的筛选也没还原，
 * 页面永久停在「共 0 条」，刷新后筛选条件还会静默丢失。
 * 类型到位后补一次「还原条件 + 加载」，让这个入口不再靠时序运气。
 */
watch(
  () => types.value.length,
  (count) => {
    if (count > 0 && !data.value) {
      restoreFiltersFromQuery()
      void reload()
    }
  },
)

/**
 * 外部改 URL 时补一次查询。
 *
 * <p>URL 是这套页面的唯一上下文来源（type/q/page/size + 平铺筛选），但视图原来只在挂载
 * 与点击时加载数据。于是同一标签页里把地址栏换成另一个 ?type=… 时，标题类型会跟着变、
 * 表格却还是旧数据 —— 观感上与「点了没反应」是同一类问题，必须补上。</p>
 *
 * <p>去重：syncQuery() 自己促成的 URL 变化也会回到这里，用 lastRequestKey 记录
 * 「上一次真正发出请求的参数」，相同就跳过，避免一次点击打两次接口、分页闪一下。</p>
 */
watch(
  () => route.query,
  (query) => {
    const nextType = String(query.type || store.types[0]?.id || '')
    const nextKeyword = String(query.q || '')
    const nextPage = Number(query.page || 1)
    const nextSize = Number(query.size || 20)
    const nextFilters = filtersFromQuery(nextType)
    if (requestKeyOf(nextType, nextKeyword, nextPage, nextSize, nextFilters) === lastRequestKey) {
      return
    }
    keyword.value = nextKeyword
    page.value = nextPage
    size.value = nextSize
    // 筛选条件按新 query 整体重建：不沿用旧条件，否则切类型会把上一个类型的筛选带过来。
    filters.value = nextFilters
    notice.value = ''
    error.value = ''
    void reload(nextType)
  },
)

onUnmounted(() => {
  if (unregister) unregister()
})

/**
 * 切换对象类型（缺陷 D）。
 *
 * 原实现接收 id 却从不使用：syncQuery() 与 load() 都读 activeType（route.query.type 的 computed），
 * 而 router.replace 是异步的，调用后 computed 仍是旧值 —— 点击类型按钮页面毫无反应。
 * 现在改为直接把 id 写进 URL 并显式传给 reload，不再依赖 computed 的更新时序。
 */
function selectType(id: string): void {
  page.value = 1
  filters.value = {}
  keyword.value = ''
  notice.value = ''
  error.value = ''
  void router.replace({ name: 'explorer', query: { type: id, page: '1', size: String(size.value) } })
  void reload(id)
}

/** 是否填了任何查询条件：条件为空时接口结果与当前一致，必须额外给一行提示（缺陷 2）。 */
function hasConditions(): boolean {
  if (keyword.value.trim()) return true
  return Object.values(filters.value).some((value) => (value ?? '').trim() !== '')
}

/**
 * 提示里的条数说明。
 * 分页时一屏只显示 size 条（学生 32 条却只有 20 行），只写「共 32 条」会让人
 * 拿表格行数去对、以为条数算错；这里把「本页显示」一并写出来，数字对得上。
 */
function totalHint(): string {
  const total = data.value?.total ?? 0
  const shown = data.value?.items?.length ?? 0
  return shown < total ? `找到 ${total} 条，本页显示 ${shown} 条` : `找到 ${total} 条`
}

async function applyFilter(): Promise<void> {
  page.value = 1
  const conditional = hasConditions()
  notice.value = ''
  syncQuery()
  await reload()
  if (error.value) return
  notice.value = conditional
    ? `已经按你填的条件查找，${totalHint()}`
    : `你没有填条件，这里显示全部 ${totalHint()}`
}

async function clearFilters(): Promise<void> {
  keyword.value = ''
  filters.value = {}
  page.value = 1
  notice.value = ''
  syncQuery()
  await reload()
  if (error.value) return
  notice.value = `已经清除搜索条件，显示全部 ${totalHint()}`
}

function changePage(next: number): void {
  page.value = next
  notice.value = ''
  syncQuery()
  void reload()
}

function open(globalId: string): void {
  void router.push({ name: 'detail', query: { globalId, ...currentQuery() } })
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2>查对象</h2>
        <div class="sub">显示哪些列、能搜哪些字段由配置决定；当前查看：{{ displayName(activeType) }}</div>
      </div>
      <span class="demo-tag">演示数据</span>
    </div>

    <div class="card">
      <TypeNav :types="types" :active="activeType" @select="selectType" />
    </div>

    <div class="card">
      <div class="toolbar">
        <label>搜索</label>
        <input v-model="keyword" type="text" placeholder="输入姓名、编号或名称查找" @keyup.enter="applyFilter" />
        <template v-for="key in filterFields" :key="key">
          <label>{{ filterLabel(key) }}</label>
          <input v-model="filters[key]" type="text" :placeholder="'按' + filterLabel(key) + '筛选'" style="width:130px" />
        </template>
        <button class="primary" :disabled="loading" @click="applyFilter" title="填好上面的条件后点这里查找，会重新查出符合条件的对象">
          {{ loading ? '查找中…' : '查找' }}
        </button>
        <button :disabled="loading" @click="clearFilters" title="点这里清除上面填的所有搜索和筛选条件，重新显示全部对象">清除搜索条件</button>
        <span class="spacer"></span>
        <span class="badge info">找到 {{ data?.total ?? 0 }} 条</span>
      </div>

      <p v-if="error" class="error-banner">{{ error }}</p>
      <p v-else-if="notice" class="basis" style="margin:0 0 10px">{{ notice }}</p>
      <ObjectTable :fields="fields" :rows="data?.items ?? []" :loading="loading" @open="open" />
      <Pagination
        :page="page"
        :size="size"
        :total="data?.total ?? 0"
        @change="changePage"
      />
    </div>
  </div>
</template>
