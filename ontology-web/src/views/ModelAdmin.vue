<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { api, qs } from '../api'
import { registerRefresher } from '../store'
import SourceStatusBadge from '../components/SourceStatusBadge.vue'
import {
  fmt,
  type ModelMappingsData,
  type ModelRelationsData,
  type ModelTypesData,
  type OutboxData,
  type SourceStatus,
  type SyncTaskData,
} from '../types'

const modelTypes = ref<ModelTypesData | null>(null)
const relations = ref<ModelRelationsData | null>(null)
const mappings = ref<ModelMappingsData | null>(null)
const status = ref<SourceStatus | null>(null)
const tasks = ref<SyncTaskData | null>(null)
const outbox = ref<OutboxData | null>(null)
const message = ref('')
const error = ref('')
const taskIdInput = ref('')
const eventIdInput = ref('')
let unregister: (() => void) | undefined

async function loadAll(): Promise<void> {
  const [typesData, relationsData, mappingsData, statusData, tasksData, outboxData] = await Promise.all([
    api.get<ModelTypesData>('/model/types'),
    api.get<ModelRelationsData>('/model/relations'),
    api.get<ModelMappingsData>('/model/mappings'),
    api.get<SourceStatus>('/sources/status'),
    api.get<SyncTaskData>('/sync/tasks' + qs({ page: 1, size: 10 })),
    api.get<OutboxData>('/admin/outbox' + qs({ page: 1, size: 20 })),
  ])
  modelTypes.value = typesData
  relations.value = relationsData
  mappings.value = mappingsData
  status.value = statusData
  tasks.value = tasksData
  outbox.value = outboxData
}

async function reload(): Promise<void> {
  try {
    await loadAll()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '管理数据加载失败'
  }
}

async function runAction(label: string, action: () => Promise<unknown>): Promise<void> {
  try {
    const result = await action()
    message.value = `${label}已提交：${fmt(result)}`
    error.value = ''
    await reload()
  } catch (e) {
    error.value = e instanceof Error ? e.message : `${label}失败`
  }
}

function fullImport(): Promise<void> {
  return runAction('全量导入', () => api.post('/import/full', {}))
}
function retryImport(): Promise<void> {
  return runAction('导入重试', () => api.post('/import/retry', { taskId: Number(taskIdInput.value || 0) }))
}
function reconcile(): Promise<void> {
  return runAction('对账', () => api.post('/reconcile', {}))
}
function rebuild(): Promise<void> {
  return runAction('重建当前投影', () => api.post('/rebuild', {}))
}
function adminRetry(): Promise<void> {
  const eventId = eventIdInput.value.trim()
  if (!eventId) {
    error.value = '请填写要重放的 eventId'
    return Promise.resolve()
  }
  return runAction('失败重试', () => api.post('/admin/retry' + qs({ eventId })))
}

onMounted(async () => {
  await reload()
  unregister = registerRefresher(loadAll)
})

onUnmounted(() => {
  if (unregister) unregister()
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2>模型与接入管理</h2>
        <div class="sub">定义与版本可查 · 全量导入 / 对账 / 重建 / 同步任务 / 失败重试（E5、US-07）</div>
      </div>
      <span class="demo-tag">演示数据 / 演示时间</span>
    </div>

    <p v-if="error" class="error-banner">{{ error }}</p>
    <div v-if="message" class="card" style="background:#ecfdf5; border-color:#a7f3d0">{{ message }}</div>

    <div class="card">
      <h3>数据源状态与新鲜度</h3>
      <SourceStatusBadge :status="status" />
      <div class="kv" style="margin-top:10px">
        <div class="k">快照水位（业务 revision）</div><div class="v">{{ status?.snapshotRevision ?? '—' }}</div>
        <div class="k">当前业务 revision</div><div class="v">{{ status?.currentSourceRevision ?? '—' }}</div>
        <div class="k">待处理 / 错误 / DEAD</div>
        <div class="v">{{ status?.pendingCount ?? 0 }} / {{ status?.errorCount ?? 0 }} / {{ status?.deadCount ?? 0 }}</div>
        <div class="k">映射版本</div><div class="v">{{ status?.mappingVersion || '—' }}</div>
      </div>
      <div class="hint" style="margin-top:8px">
        四种非正常状态可区分：① 没有业务数据（已导入但源/对象数为 0）② 尚未接入（无成功导入与生效映射版本）
        ③ 同步落后（待处理&gt;0 或最后同步时间落后）④ 同步失败（存在失败/DEAD 事件）；
        「来源不可用」是叠加标记而非第五态。同步处理时间与演示业务时间分开记录。
      </div>
    </div>

    <div class="card">
      <h3>同步运维入口</h3>
      <div class="toolbar">
        <button class="primary" @click="fullImport">全量导入</button>
        <button @click="reconcile">对账</button>
        <button class="danger" @click="rebuild">重建当前投影（仅投影，历史保留）</button>
        <span class="spacer"></span>
        <label>失败任务 taskId</label>
        <input v-model="taskIdInput" type="number" style="width:110px" placeholder="任务 ID" />
        <button @click="retryImport">导入重试</button>
      </div>
      <div class="toolbar">
        <label>失败事件 eventId</label>
        <input v-model="eventIdInput" type="text" style="min-width:260px; max-width:100%" placeholder="从下方 DEAD/FAILED 列表复制" />
        <button @click="adminRetry">失败重试（代理业务侧重新排队）</button>
      </div>
      <div class="hint">所有入口调用本体 API 的运维端点；业务操作（调课/时钟等）不在本平台提供，请返回原业务系统。</div>
    </div>

    <div class="card">
      <h3>同步任务</h3>
      <table class="desktop-only">
        <thead><tr><th>任务 ID</th><th>类型</th><th>状态</th><th>快照水位</th><th>结束水位</th><th>开始时间</th><th>完成时间</th></tr></thead>
        <tbody>
          <tr v-for="task in tasks?.items ?? []" :key="task.taskId">
            <td>{{ task.taskId }}</td><td>{{ task.type }}</td>
            <td><span :class="'badge ' + (task.status === 'SUCCESS' ? 'ok' : task.status === 'FAILED' ? 'danger' : 'info')">{{ task.status }}</span></td>
            <td>{{ task.snapshotRevision }}</td><td>{{ task.endRevision }}</td>
            <td>{{ task.startedAt || '—' }}</td><td>{{ task.finishedAt || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="mobile-only">
        <div v-for="task in tasks?.items ?? []" :key="task.taskId" class="card" style="margin-bottom:8px">
          <b>#{{ task.taskId }} {{ task.type }}</b> <span class="badge info">{{ task.status }}</span>
          <div class="hint">水位 {{ task.snapshotRevision }} → {{ task.endRevision }} · {{ task.startedAt || '—' }}</div>
        </div>
      </div>
      <div v-if="!(tasks?.items ?? []).length" class="empty">暂无同步任务</div>
    </div>

    <div class="card">
      <h3>对象类型定义（{{ modelTypes?.types.length ?? 0 }} 类，映射版本 {{ modelTypes?.mappingVersion || '—' }}）</h3>
      <div class="grid cols-2">
        <div v-for="item in modelTypes?.types ?? []" :key="item.id" class="card" style="margin:0">
          <b>{{ item.displayName }}</b> <span class="badge info">{{ item.id }}</span>
          <div class="hint">{{ item.description }}</div>
          <div class="kv" style="margin-top:8px">
            <div class="k">列表字段</div><div class="v">{{ (item.listFields ?? []).map(f => f.displayName).join('、') || '—' }}</div>
            <div class="k">可搜索字段</div><div class="v">{{ (item.searchableFields ?? []).join('、') || '—' }}</div>
            <div class="k">可筛选字段</div><div class="v">{{ (item.filterableFields ?? []).join('、') || '—' }}</div>
            <div class="k">派生属性</div><div class="v">{{ (item.derivedProperties ?? []).map(d => d.name).join('、') || '—' }}</div>
            <div class="k">动态计算</div><div class="v">{{ (item.dynamicResolvers ?? []).join('、') || '—' }}</div>
            <div class="k">来源映射</div><div class="v">{{ item.sourceMapping?.sourceTable || '—' }} / {{ item.sourceMapping?.sourcePk || '—' }}</div>
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>关系类型定义（{{ relations?.relations.length ?? 0 }} 条）</h3>
      <ul class="list-plain">
        <li v-for="(rel, index) in relations?.relations ?? []" :key="index">{{ fmt(rel) }}</li>
      </ul>
      <div v-if="!(relations?.relations ?? []).length" class="empty">暂无关系定义</div>
    </div>

    <div class="card">
      <h3>来源映射与版本</h3>
      <div class="grid cols-2">
        <div>
          <b>数据源</b>
          <div v-for="(source, index) in mappings?.dataSources ?? []" :key="index" class="hint">{{ fmt(source) }}</div>
          <b style="display:block; margin-top:10px">映射版本记录</b>
          <div v-for="(version, index) in mappings?.versions ?? []" :key="index" class="hint">{{ fmt(version) }}</div>
        </div>
        <div>
          <b>已注册扩展点（策略表）</b>
          <div class="hint">Provider：{{ (mappings?.providers ?? []).map(p => p.key).join('、') || '—' }}</div>
          <div class="hint">Resolver：{{ (mappings?.resolvers ?? []).map(r => r.key).join('、') || '—' }}</div>
          <b style="display:block; margin-top:10px">对象映射</b>
          <div v-for="(mapping, index) in mappings?.objectMappings ?? []" :key="index" class="hint">{{ fmt(mapping) }}</div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>Outbox 事件（只读业务库）</h3>
      <table class="desktop-only">
        <thead><tr><th>eventId</th><th>changeKind</th><th>业务 revision</th><th>状态</th><th>尝试次数</th><th>最后错误</th><th>演示业务时间</th></tr></thead>
        <tbody>
          <tr v-for="item in outbox?.items ?? []" :key="item.eventId">
            <td class="hint">{{ item.eventId }}</td><td>{{ item.changeKind }}</td><td>{{ item.revision }}</td>
            <td><span :class="'badge ' + (item.status === 'DEAD' ? 'danger' : item.status === 'DELIVERED' ? 'ok' : 'info')">{{ item.status }}</span></td>
            <td>{{ item.attempts }}</td><td class="hint">{{ item.lastError || '—' }}</td><td class="hint">{{ item.businessTime || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="mobile-only">
        <div v-for="item in outbox?.items ?? []" :key="item.eventId" class="card" style="margin-bottom:8px">
          <b>{{ item.changeKind }}</b> <span class="badge info">{{ item.status }}</span>
          <div class="hint">{{ item.eventId }}</div>
          <div class="hint">revision {{ item.revision }} · 尝试 {{ item.attempts }} 次 · {{ item.lastError || '—' }}</div>
        </div>
      </div>
      <div v-if="!(outbox?.items ?? []).length" class="empty">暂无 Outbox 事件</div>
    </div>
  </div>
</template>
