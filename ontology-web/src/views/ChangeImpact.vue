<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, qs } from '../api'
import { registerRefresher } from '../store'
import ChangeItemCard from '../components/ChangeItemCard.vue'
import Pagination from '../components/Pagination.vue'
import type { ChangeListData } from '../types'

const route = useRoute()
const router = useRouter()

const globalId = ref(String(route.query.globalId || ''))
const role = ref(String(route.query.role || ''))
const page = ref(Number(route.query.page || 1))
// 每页条数可由 URL 控制（默认 10）：需要一次看较多历史（如排查某条对账/重建记录）时，
// 用 ?size=100 即可，不必在 10 条一页里反复翻页。
const size = computed(() => {
  const value = Number(route.query.size || 10)
  return Number.isFinite(value) && value > 0 ? Math.min(value, 200) : 10
})
const data = ref<ChangeListData | null>(null)
const error = ref('')
let unregister: (() => void) | undefined

async function load(): Promise<void> {
  data.value = await api.get<ChangeListData>('/changes' + qs({
    globalId: globalId.value.trim(),
    role: role.value,
    page: page.value,
    size: size.value,
  }))
}

async function reload(): Promise<void> {
  try {
    await load()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '变化记录加载失败'
  }
}

function syncQuery(): void {
  const query: Record<string, string> = { page: String(page.value) }
  if (globalId.value.trim()) query.globalId = globalId.value.trim()
  if (role.value) query.role = role.value
  void router.replace({ name: 'changes', query })
}

function apply(): void {
  page.value = 1
  syncQuery()
  void reload()
}

function setRole(next: string): void {
  role.value = next
  page.value = 1
  syncQuery()
  void reload()
}

function changePage(next: number): void {
  page.value = next
  syncQuery()
  void reload()
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
        <h2>变化与影响</h2>
        <div class="sub">属性前后差异 / 关系增减 / 来源事件 / 两类时间分开显示；旧老师与新老师两侧均可查同一次变化</div>
      </div>
      <span class="demo-tag">演示数据 / 演示时间</span>
    </div>

    <div class="card">
      <div class="toolbar">
        <label>对象 globalId</label>
        <input v-model="globalId" type="text" style="min-width:280px; max-width:100%" placeholder="留空查看全部变化" />
        <button class="primary" @click="apply">查询</button>
        <span class="spacer"></span>
        <label>影响角色</label>
        <button :class="{ active: role === '' }" @click="setRole('')">全部</button>
        <button :class="{ active: role === 'SELF_CHANGED' }" @click="setRole('SELF_CHANGED')">本身变化</button>
        <button :class="{ active: role === 'DERIVED_RELATED' }" @click="setRole('DERIVED_RELATED')">计算出的相关对象</button>
      </div>
      <div class="hint">
        提示：一次老师替换会同时写入旧教师与新教师作为关系端点，分别传入两者 globalId 都能查到同一个 changeId。
        对账/重建修复的历史 operator 与 businessTime 为空，不伪造操作人与发生时间。
      </div>
      <p v-if="error" class="error-banner">{{ error }}</p>
    </div>

    <div v-if="(data?.items ?? []).length">
      <ChangeItemCard v-for="item in data?.items ?? []" :key="item.changeId" :item="item" />
    </div>
    <div v-else class="card empty">暂无变化记录</div>

    <div class="card">
      <Pagination :page="page" :size="size" :total="data?.total ?? 0" @change="changePage" />
    </div>
  </div>
</template>
