<script setup lang="ts">
import { computed, inject, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { apiGet, apiPost, errorText } from '../api'
import { store } from '../store'
import { fmtDateTime } from '../utils'
import type { ChangeItem, ChangesData } from '../types'
import ImpactPanel from '../components/ImpactPanel.vue'
import { useMobile } from '../composables/useMobile'

const route = useRoute()
const router = useRouter()
const isMobile = useMobile()
const triggerRefresh = inject<() => Promise<void>>('triggerRefresh', async () => {})

const schoolId = ref<number | ''>('')
const items = ref<ChangeItem[]>([])
const drawerVisible = ref(false)
const detail = ref<ChangeItem | null>(null)
const detailLoading = ref(false)

const schools = computed(() => store.meta?.schools ?? [])

const FIELD_LABELS: Record<string, string> = {
  className: '班级',
  courseName: '课程',
  teacherName: '教师',
  classroomName: '教室',
  lessonDate: '日期',
  startTime: '开始时间',
  endTime: '结束时间',
  notice: '课前通知',
}
function fieldLabel(k: string): string {
  return FIELD_LABELS[k] || k
}

function diffSummary(c: ChangeItem): string {
  if (!c.diff?.length) return `${c.className} ${c.courseName}`
  return c.diff.map((d) => `${d.label}：${d.before || '—'} → ${d.after || '—'}`).join('；')
}

async function load(): Promise<void> {
  try {
    const d = await apiGet<ChangesData>('/changes', {
      schoolId: schoolId.value === '' ? undefined : schoolId.value,
      limit: 50,
    })
    items.value = d.items
  } catch {
    // 后端启动期间静默
  }
}

async function openById(id: number): Promise<void> {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await apiGet<ChangeItem>(`/changes/${id}`)
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    detailLoading.value = false
  }
}

function open(c: ChangeItem): void {
  void openById(c.id)
}

function viewSchedule(): void {
  if (!detail.value) return
  const raw = detail.value.before?.lessonDate
  const date = typeof raw === 'string' ? raw : ''
  void router.push({
    path: '/schedule',
    query: { schoolId: String(detail.value.schoolId), date: date || undefined },
  })
}

async function resetDemo(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '将恢复课表初始快照、清除演示期间新增的变更记录，并把演示时间恢复到当天 10:15。确定继续？',
      '恢复演示数据',
      { type: 'warning', confirmButtonText: '恢复', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    await apiPost('/demo/reset')
    ElMessage.success('演示数据已恢复')
    drawerVisible.value = false
    await triggerRefresh()
    await load()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

watch(schoolId, () => void load())
watch(() => store.refreshKey, () => void load())
watch(
  () => route.query.id,
  (v) => {
    if (v) void openById(Number(v))
  }
)
onMounted(async () => {
  await load()
  if (route.query.id) await openById(Number(route.query.id))
})
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-header">
        <h2 class="card-title">变更记录</h2>
        <span class="card-sub">共 {{ items.length }} 条 · 点击行查看完整前后对照与影响范围</span>
      </div>
      <div class="toolbar">
        <el-select v-model="schoolId" clearable placeholder="全部学校" style="width: 200px">
          <el-option v-for="s in schools" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
      </div>

      <div v-if="!isMobile">
        <el-table :data="items" size="small" style="width: 100%" stripe row-class-name="clickable-row" @row-click="open">
          <el-table-column prop="changeType" label="类型" width="100" />
          <el-table-column label="对象" min-width="150">
            <template #default="{ row }">{{ row.className }} {{ row.courseName }}</template>
          </el-table-column>
          <el-table-column label="修改前后摘要" min-width="260">
            <template #default="{ row }"><span class="small">{{ diffSummary(row) }}</span></template>
          </el-table-column>
          <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
          <el-table-column label="时间" width="160">
            <template #default="{ row }">{{ fmtDateTime(row.occurredAt) }}</template>
          </el-table-column>
          <el-table-column label="受影响" width="110">
            <template #default="{ row }">
              <span class="small">{{ row.affectedStudentCount }} 名学生</span>
            </template>
          </el-table-column>
          <el-table-column label="教师" min-width="120">
            <template #default="{ row }">
              <span class="small">{{ (row.impact?.teacherNames || []).join('、') || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" size="small" link @click.stop="open(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!items.length" class="empty">暂无变更记录</div>
      </div>

      <div v-else class="mobile-list">
        <div v-for="c in items" :key="c.id" class="mobile-item" @click="open(c)">
          <div class="mobile-item-title">
            <span>{{ c.changeType }} · {{ c.className }} {{ c.courseName }}</span>
            <span class="muted small">{{ fmtDateTime(c.occurredAt) }}</span>
          </div>
          <div class="mobile-item-row">修改：{{ diffSummary(c) }}</div>
          <div class="mobile-item-row">原因：{{ c.reason }}</div>
          <div class="mobile-item-row">
            受影响 {{ c.affectedStudentCount }} 名学生 ｜ 教师 {{ (c.impact?.teacherNames || []).join('、') || '—' }}
          </div>
        </div>
        <div v-if="!items.length" class="empty">暂无变更记录</div>
      </div>
    </div>

    <el-drawer
      v-model="drawerVisible"
      :size="isMobile ? '100%' : '560px'"
      :title="detail ? `变更详情 #${detail.id}` : '变更详情'"
      direction="rtl"
    >
      <div v-loading="detailLoading">
        <template v-if="detail">
          <div class="card" style="box-shadow: none">
            <div class="bold" style="font-size: 16px">
              {{ detail.changeType }} · {{ detail.className }} {{ detail.courseName }}
            </div>
            <div class="muted small">课表编号 {{ detail.sessionCode }} · 操作人 {{ detail.operator }}</div>
            <div class="muted small">发生时间 {{ fmtDateTime(detail.occurredAt) }}</div>
            <div style="margin-top: 8px"><span class="chip">原因</span> {{ detail.reason }}</div>
          </div>

          <div class="card" style="box-shadow: none">
            <div class="card-header"><h2 class="card-title">修改前后完整对照</h2></div>
            <div class="grid-2">
              <div>
                <div class="bold" style="margin-bottom: 6px">调整前</div>
                <div v-for="(v, k) in detail.before || {}" :key="'b-' + k" class="mobile-item-row">
                  {{ fieldLabel(String(k)) }}：{{ v }}
                </div>
              </div>
              <div>
                <div class="bold" style="margin-bottom: 6px">调整后</div>
                <div v-for="(v, k) in detail.after || {}" :key="'a-' + k" class="mobile-item-row">
                  {{ fieldLabel(String(k)) }}：{{ v }}
                </div>
              </div>
            </div>
          </div>

          <div class="card" style="box-shadow: none">
            <ImpactPanel
              :diff="detail.diff || []"
              :impact="detail.impact || null"
              :conflicts="[]"
              :valid="true"
              hide-valid-banner
              show-links
              title="差异明细与受影响对象（可跳转）"
            />
          </div>

          <div class="flex flex-wrap gap-sm">
            <el-button type="primary" @click="viewSchedule">查看该次课表</el-button>
            <el-button type="danger" plain @click="resetDemo">恢复演示数据</el-button>
          </div>
        </template>
        <div v-else-if="!detailLoading" class="empty">未找到该变更记录</div>
      </div>
    </el-drawer>
  </div>
</template>
