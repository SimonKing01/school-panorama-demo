<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { api } from '../api'
import { registerRefresher } from '../store'
import { useModel } from '../composables/useModel'
import ChangeItemCard from '../components/ChangeItemCard.vue'
import type { OverviewData } from '../types'

const router = useRouter()
const { types } = useModel()
const data = ref<OverviewData | null>(null)
const error = ref('')
let unregister: (() => void) | undefined

/** 同步状态的英文标记翻译给人看；只改显示，不改原值。 */
function syncStatusText(status: string): string {
  const map: Record<string, string> = {
    SYNCED: '数据已同步',
    NO_DATA: '还没有业务数据',
    NOT_CONNECTED: '还没接入',
    LAGGING: '同步落后',
    FAILED: '同步失败',
  }
  return map[status] ?? status
}

async function load(): Promise<void> {
  data.value = await api.get<OverviewData>('/overview')
}

onMounted(async () => {
  try {
    await load()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '总览加载失败，请刷新重试'
  }
  unregister = registerRefresher(load)
})

onUnmounted(() => {
  if (unregister) unregister()
})

function goExplorer(typeId: string): void {
  void router.push({ name: 'explorer', query: { type: typeId } })
}

function goDetail(globalId: string): void {
  void router.push({ name: 'detail', query: { globalId } })
}
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2>全景总览</h2>
        <div class="sub">有多少对象和关系 · 数据同步是否正常 · 最近发生了哪些变化（演示数据 / 演示时间）</div>
      </div>
      <span class="demo-tag">演示数据</span>
    </div>

    <p v-if="error" class="error-banner">{{ error }}</p>

    <div class="grid cols-4">
      <div class="metric"><div class="label">对象条数</div><div class="value">{{ data?.counts.objects ?? '—' }}</div></div>
      <div class="metric"><div class="label">关系条数</div><div class="value">{{ data?.counts.relations ?? '—' }}</div></div>
      <div class="metric"><div class="label">对象类别数</div><div class="value">{{ data?.counts.objectTypes ?? '—' }}</div></div>
      <div class="metric"><div class="label">关系类别数</div><div class="value">{{ data?.counts.relationTypes ?? '—' }}</div></div>
    </div>

    <div class="grid cols-2" style="margin-top:14px">
      <div class="card">
        <h3>数据同步情况</h3>
        <div class="kv">
          <div class="k">数据连接情况</div>
          <div class="v">
            <span :class="'badge ' + (data?.dataSource.sourceUp ? 'ok' : 'danger')">
              {{ data?.dataSource.sourceUp ? '数据连接正常' : '原系统暂时连不上（已同步的数据仍能查看）' }}
            </span>
          </div>
          <div class="k">同步状态</div><div class="v"><span class="badge info">{{ syncStatusText(data?.dataSource.status || '') || '—' }}</span></div>
          <div class="k">上次同步成功</div><div class="v">{{ data?.dataSource.lastSuccessSyncAt || '—' }}（真实处理时间）</div>
          <div class="k">等待同步条数</div><div class="v">{{ data?.dataSource.pendingCount ?? 0 }}</div>
          <div class="k">当前数据批次</div><div class="v">{{ data?.sync.revision ?? '—' }}</div>
          <div class="k">演示时间</div><div class="v">{{ data?.sync.demoTime || '—' }} <span class="demo-tag">演示时间</span></div>
          <div class="k">同步失败次数</div><div class="v">{{ data?.sync.errorCount ?? 0 }}</div>
        </div>
      </div>

      <div class="card">
        <h3>直接找某个对象 / 按类别查看</h3>
        <p class="hint">在顶部搜索框输入姓名或编号就能直接找到，不用一级一级点。点下面的类别按钮，可以按类别看列表。</p>
        <div style="display:flex; flex-wrap:wrap; gap:6px; margin-top:8px">
          <button v-for="item in types" :key="item.id" @click="goExplorer(item.id)">{{ item.displayName }}</button>
        </div>
        <h3 style="margin-top:16px">变化影响概览</h3>
        <div class="kv">
          <div class="k">对象本身改了</div><div class="v">{{ data?.impactSummary.selfChanged ?? 0 }}</div>
          <div class="k">按规则算出的相关对象</div><div class="v">{{ data?.impactSummary.derivedRelated ?? 0 }}</div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>最近的变化</h3>
      <div v-if="data?.recentChanges?.length">
        <ChangeItemCard v-for="item in data.recentChanges" :key="item.changeId" :item="item" />
      </div>
      <div v-else class="empty">暂时没有变化记录（可以先到原业务系统调一次课）</div>
    </div>
  </div>
</template>
