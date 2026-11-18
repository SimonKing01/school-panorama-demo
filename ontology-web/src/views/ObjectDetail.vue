<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, gidPath, qs } from '../api'
import { registerRefresher, store } from '../store'
import { useModel } from '../composables/useModel'
import ObjectDetailPanel from '../components/ObjectDetailPanel.vue'
import type { ObjectDetail } from '../types'

const route = useRoute()
const router = useRouter()
const { displayName } = useModel()

const globalId = computed(() => String(route.query.globalId || ''))
const queryTime = ref(String(route.query.queryTime || ''))
const detail = ref<ObjectDetail | null>(null)
const error = ref('')
let unregister: (() => void) | undefined

async function load(): Promise<void> {
  if (!globalId.value) {
    detail.value = null
    return
  }
  const params: Record<string, unknown> = {}
  if (queryTime.value.trim()) params.queryTime = queryTime.value.trim()
  detail.value = await api.get<ObjectDetail>(
    '/objects/' + gidPath(globalId.value) + qs(params),
  )
}

async function reload(): Promise<void> {
  try {
    await load()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '没能读出这个对象的信息，请稍后重试'
  }
}

function syncQuery(): void {
  const query: Record<string, string> = { globalId: globalId.value }
  if (queryTime.value.trim()) query.queryTime = queryTime.value.trim()
  // 保留从探索器带过来的筛选/分页上下文
  for (const [key, value] of Object.entries(route.query)) {
    if (key !== 'globalId' && key !== 'queryTime' && typeof value === 'string') query[key] = value
  }
  void router.replace({ name: 'detail', query })
}

function applyTime(): void {
  syncQuery()
  void reload()
}

function resetTime(): void {
  queryTime.value = ''
  syncQuery()
  void reload()
}

/** 验收六的四个时间边界：不依赖任何数据写入，动态结果随演示时间变化（F6）。 */
function setPreset(time: string): void {
  const demo = store.revision?.demoTime || store.sourceStatus?.demoTime || ''
  let date = demo.slice(0, 10)
  if (!date) {
    const now = new Date()
    date = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
  }
  queryTime.value = `${date}T${time}:00`
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

function open(globalIdValue: string): void {
  void router.push({ name: 'detail', query: { globalId: globalIdValue } })
}

function goRelations(): void {
  void router.push({ name: 'relations', query: { globalId: globalId.value } })
}

function goChanges(): void {
  void router.push({ name: 'changes', query: { globalId: globalId.value } })
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2>对象详情</h2>
        <div class="sub">这里显示这个对象的信息；显示哪些内容由配置决定</div>
      </div>
      <span class="demo-tag">演示数据 / 演示时间</span>
    </div>

    <div class="card">
      <div class="toolbar">
        <label>按哪个时间点查看 <small class="mono">（格式：年-月-日 时:分:秒，如 2026-11-14T10:15:00）</small></label>
        <input v-model="queryTime" type="text" placeholder="不填就按当前演示时间显示" style="min-width:230px" />
        <button @click="applyTime" title="填好时间后点这里，看看那个时间点这个对象是什么情况">按这个时间查看</button>
        <button @click="resetTime" title="点这里回到当前的演示时间">用当前演示时间</button>
        <span class="spacer"></span>
        <span class="hint">快速试试这些时间点：</span>
        <button @click="setPreset('07:30')" title="看早上 7:30 的情况">07:30</button>
        <button @click="setPreset('10:15')" title="看上午 10:15 的情况">10:15</button>
        <button @click="setPreset('10:45')" title="看上午 10:45 的情况">10:45</button>
        <button @click="setPreset('17:00')" title="看下午 5:00 的情况">17:00</button>
      </div>
      <div class="hint">这些按钮只是换个时间点查看，不会改动任何数据；当前课程、下一节课、状态会跟着变。</div>
    </div>

    <p v-if="error" class="error-banner">{{ error }}</p>
    <div v-if="!globalId" class="empty">请先到总览或「查对象」里选一个对象</div>
    <ObjectDetailPanel
      v-else-if="detail"
      :detail="detail"
      :type-name="displayName(detail.type)"
      @open="open"
      @relations="goRelations"
      @changes="goChanges"
    />
    <div v-else class="loading">正在读取这个对象的信息…</div>
  </div>
</template>
