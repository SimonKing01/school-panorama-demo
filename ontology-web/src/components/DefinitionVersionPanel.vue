<script setup lang="ts">
import { computed } from 'vue'
import type { ModelVersionsData } from '../types'
import type { ApplyPhase } from '../composables/useDefinitionApply'

/**
 * 定义状态条 + 版本时间线（07 增量设计 §9.1）。
 *
 * 三件事必须显式可见：① 当前生效版本与来源；② 草稿是否「待生效」；
 * ③ 上次启动是否因 overlay 非法回退过基线（绝不静默）。
 */
const props = defineProps<{
  versions: ModelVersionsData | null
  busy: boolean
  applyPhase: ApplyPhase
  applyMessage: string
}>()

const emit = defineEmits<{
  (event: 'apply'): void
  (event: 'reset'): void
}>()

const pendingApply = computed(() => Boolean(props.versions?.draft?.pendingApply))
const fallbackOccurred = computed(() => Boolean(props.versions?.lastFallback?.occurred))
const applying = computed(() => props.applyPhase === 'applying')

function sourceLabel(source?: string | null): string {
  if (source === 'OVERLAY') return '在线改动'
  if (source === 'OVERLAY_FALLBACK') return '回退基线'
  return '打包基线'
}

function statusClass(status: string): string {
  if (status === 'ACTIVE') return 'badge ok'
  if (status === 'PENDING') return 'badge warn'
  return 'badge muted'
}

function statusLabel(status: string): string {
  if (status === 'ACTIVE') return '生效中'
  if (status === 'PENDING') return '待生效'
  if (status === 'SUPERSEDED') return '已作废'
  return status
}
</script>

<template>
  <div>
    <div class="kv">
      <div class="k">生效版本</div>
      <div class="v">
        <b>{{ versions?.activeMappingVersion || '—' }}</b>
        <span class="badge info" style="margin-left:6px">{{ sourceLabel(versions?.activeDefinitionSource) }}</span>
      </div>
      <!-- 「保留版本与操作人」：谁签字生效的必须看得见，不能只显示 system。 -->
      <div class="k">生效版本签署</div>
      <div class="v">
        <b>{{ versions?.activeOperator || 'system' }}</b>
        <span class="hint" style="margin-left:6px">{{ versions?.activeNote || '' }}</span>
      </div>
      <div class="k">打包基线版本</div>
      <div class="v">{{ versions?.baselineMappingVersion || '—' }}</div>
      <div class="k">对象类型草稿</div>
      <div class="v">
        <template v-if="versions?.draft?.objectTypesFromDraft">
          {{ versions.draft.objectTypes?.length ?? 0 }} 类（在线改动待生效）
        </template>
        <span v-else class="hint">无（沿用打包基线）</span>
      </div>
      <div class="k">草稿版本</div>
      <div class="v">
        <template v-if="versions?.draft?.exists">
          {{ versions.draft.draftMappingVersion || '—' }}
          <span v-if="pendingApply" class="badge warn" style="margin-left:6px">待生效 ●</span>
          <span v-else class="badge ok" style="margin-left:6px">与生效版本一致</span>
          <span class="hint" style="margin-left:6px">
            {{ versions.draft.savedBy ? versions.draft.savedBy + ' 保存' : '' }} {{ versions.draft.savedAt || '' }}
          </span>
        </template>
        <template v-else-if="pendingApply">
          已恢复打包基线 · 目标版本 {{ versions?.draft?.pendingTargetVersion }}
          <span class="badge warn" style="margin-left:6px">待生效 ●</span>
          <span class="hint" style="margin-left:6px">
            {{ versions?.draft?.savedBy ? versions?.draft?.savedBy + ' 操作' : '' }} {{ versions?.draft?.savedAt || '' }}
          </span>
        </template>
        <span v-else class="hint">无草稿（未检测到在线改动）</span>
      </div>
    </div>

    <p v-if="fallbackOccurred" class="error-banner" style="margin-top:10px">
      ⚠ 上次启动曾回退基线：{{ versions?.lastFallback?.reason || 'overlay 非法' }}
      （{{ versions?.lastFallback?.at || '时间未知' }}）——坏文件已隔离为
      <code>{{ versions?.lastFallback?.fileName || 'relation-types.yaml' }}.invalid.*</code>，请修正后重新保存。
    </p>

    <div class="toolbar" style="margin-top:10px">
      <button class="primary" :disabled="busy || applying || !pendingApply" @click="emit('apply')">
        {{ applying ? '正在让改动生效（服务重启中…）' : '让改动生效' }}
      </button>
      <button class="danger" :disabled="busy || applying || !versions?.draft?.exists" @click="emit('reset')">
        撤销在线修改
      </button>
      <span v-if="applyMessage" class="hint" :class="{ 'apply-ok': applyPhase === 'success', 'apply-bad': applyPhase === 'timeout' }">
        {{ applyMessage }}
      </span>
    </div>

    <h4 style="margin:12px 0 6px; font-size:13px; color:var(--muted)">版本时间线</h4>
    <table class="desktop-only">
      <thead>
        <tr><th>映射版本</th><th>状态</th><th>来源</th><th>操作人</th><th>说明</th><th>时间</th></tr>
      </thead>
      <tbody>
        <tr v-for="row in versions?.versions ?? []" :key="row.mappingVersion">
          <td>{{ row.mappingVersion }}</td>
          <td><span :class="statusClass(row.status)">{{ statusLabel(row.status) }}</span></td>
          <td>{{ sourceLabel(row.definitionSource) }}</td>
          <td>{{ row.operator || '—' }}</td>
          <td class="hint">{{ row.note || '—' }}</td>
          <td>{{ row.activatedAt || '—' }}</td>
        </tr>
      </tbody>
    </table>
    <div class="mobile-only">
      <div v-for="row in versions?.versions ?? []" :key="row.mappingVersion" class="card" style="margin:0 0 8px">
        <b>{{ row.mappingVersion }}</b>
        <span :class="statusClass(row.status)" style="margin-left:6px">{{ statusLabel(row.status) }}</span>
        <div class="hint">{{ sourceLabel(row.definitionSource) }} · {{ row.operator || '—' }} · {{ row.activatedAt || '—' }}</div>
        <div class="hint">{{ row.note || '—' }}</div>
      </div>
    </div>
    <div v-if="!(versions?.versions ?? []).length" class="empty">暂无版本记录</div>
  </div>
</template>

<style scoped>
.apply-ok {
  color: var(--ok);
}

.apply-bad {
  color: var(--danger);
}
</style>
