<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
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
let unregister: (() => void) | undefined

const fields = computed(() => listFields(activeType.value))
const filterFields = computed(() => typeOf(activeType.value)?.filterableFields ?? [])

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

async function load(): Promise<void> {
  if (!activeType.value) return
  const params: Record<string, unknown> = {
    type: activeType.value,
    q: keyword.value.trim(),
    page: page.value,
    size: size.value,
  }
  for (const [key, value] of Object.entries(filters.value)) {
    if (value) params[key] = value
  }
  data.value = await api.get<ObjectListData>('/objects' + qs(params))
}

function syncQuery(): void {
  void router.replace({ name: 'explorer', query: currentQuery() })
}

async function reload(): Promise<void> {
  try {
    await load()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '对象列表加载失败'
  }
}

onMounted(async () => {
  // 从 URL 恢复筛选上下文
  for (const key of filterFields.value) {
    const value = route.query[key]
    if (typeof value === 'string' && value) filters.value[key] = value
  }
  await reload()
  unregister = registerRefresher(load)
})

onUnmounted(() => {
  if (unregister) unregister()
})

function selectType(id: string): void {
  page.value = 1
  filters.value = {}
  syncQuery()
  void reload()
}

function applyFilter(): void {
  page.value = 1
  syncQuery()
  void reload()
}

function clearFilters(): void {
  keyword.value = ''
  filters.value = {}
  page.value = 1
  syncQuery()
  void reload()
}

function changePage(next: number): void {
  page.value = next
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
        <h2>通用对象探索器</h2>
        <div class="sub">类型、列表列、可搜索/可筛选字段全部来自模型定义；当前类型：{{ displayName(activeType) }}</div>
      </div>
      <span class="demo-tag">演示数据</span>
    </div>

    <div class="card">
      <TypeNav :types="types" :active="activeType" @select="selectType" />
    </div>

    <div class="card">
      <div class="toolbar">
        <label>搜索</label>
        <input v-model="keyword" type="text" placeholder="按模型定义的可搜索字段过滤" @keyup.enter="applyFilter" />
        <template v-for="key in filterFields" :key="key">
          <label>{{ key }}</label>
          <input v-model="filters[key]" type="text" :placeholder="'筛选 ' + key" style="width:130px" />
        </template>
        <button class="primary" @click="applyFilter">应用筛选</button>
        <button @click="clearFilters">清空</button>
        <span class="spacer"></span>
        <span class="hint">共 {{ data?.total ?? 0 }} 条</span>
      </div>

      <p v-if="error" class="error-banner">{{ error }}</p>
      <ObjectTable :fields="fields" :rows="data?.items ?? []" @open="open" />
      <Pagination
        :page="page"
        :size="size"
        :total="data?.total ?? 0"
        @change="changePage"
      />
    </div>
  </div>
</template>
