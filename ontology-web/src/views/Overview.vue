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

async function load(): Promise<void> {
  data.value = await api.get<OverviewData>('/overview')
}

onMounted(async () => {
  try {
    await load()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '总览加载失败'
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
        <div class="sub">对象/关系规模 · 数据源状态 · 同步状态 · 近期变化与影响摘要（演示数据 / 演示时间）</div>
      </div>
      <span class="demo-tag">演示数据</span>
    </div>

    <p v-if="error" class="error-banner">{{ error }}</p>

    <div class="grid cols-4">
      <div class="metric"><div class="label">对象实例</div><div class="value">{{ data?.counts.objects ?? '—' }}</div></div>
      <div class="metric"><div class="label">关系实例</div><div class="value">{{ data?.counts.relations ?? '—' }}</div></div>
      <div class="metric"><div class="label">对象类型</div><div class="value">{{ data?.counts.objectTypes ?? '—' }}</div></div>
      <div class="metric"><div class="label">关系类型</div><div class="value">{{ data?.counts.relationTypes ?? '—' }}</div></div>
    </div>

    <div class="grid cols-2" style="margin-top:14px">
      <div class="card">
        <h3>数据源与同步状态</h3>
        <div class="kv">
          <div class="k">来源可用性</div>
          <div class="v">
            <span :class="'badge ' + (data?.dataSource.sourceUp ? 'ok' : 'danger')">
              {{ data?.dataSource.sourceUp ? '来源可用' : '来源不可用（仍可查已同步数据）' }}
            </span>
          </div>
          <div class="k">同步状态</div><div class="v"><span class="badge info">{{ data?.dataSource.status || '—' }}</span></div>
          <div class="k">最后成功同步</div><div class="v">{{ data?.dataSource.lastSuccessSyncAt || '—' }}（真实处理时间）</div>
          <div class="k">待处理数量</div><div class="v">{{ data?.dataSource.pendingCount ?? 0 }}</div>
          <div class="k">平台版本</div><div class="v">{{ data?.sync.revision ?? '—' }}</div>
          <div class="k">演示业务时间</div><div class="v">{{ data?.sync.demoTime || '—' }} <span class="demo-tag">演示时间</span></div>
          <div class="k">错误数</div><div class="v">{{ data?.sync.errorCount ?? 0 }}</div>
        </div>
      </div>

      <div class="card">
        <h3>全局直达搜索 / 类型快捷入口</h3>
        <p class="hint">使用顶部搜索框可直达任意具体对象，无需沿学校→班级→学生逐级进入。所有类型均由模型定义动态生成。</p>
        <div style="display:flex; flex-wrap:wrap; gap:6px; margin-top:8px">
          <button v-for="item in types" :key="item.id" @click="goExplorer(item.id)">{{ item.displayName }}</button>
        </div>
        <h3 style="margin-top:16px">变化影响摘要</h3>
        <div class="kv">
          <div class="k">本身发生变化</div><div class="v">{{ data?.impactSummary.selfChanged ?? 0 }}</div>
          <div class="k">按规则计算的相关对象</div><div class="v">{{ data?.impactSummary.derivedRelated ?? 0 }}</div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>近期变化</h3>
      <div v-if="data?.recentChanges?.length">
        <ChangeItemCard v-for="item in data.recentChanges" :key="item.changeId" :item="item" />
      </div>
      <div v-else class="empty">暂无变化记录（可先在原业务系统执行一次调课）</div>
    </div>
  </div>
</template>
