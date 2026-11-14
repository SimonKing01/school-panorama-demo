<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet } from '../api'
import { store } from '../store'
import { debounce, typeLabel } from '../utils'
import type { ObjectRow, ObjectsData } from '../types'
import LessonSummary from '../components/LessonSummary.vue'
import { useMobile } from '../composables/useMobile'

const route = useRoute()
const router = useRouter()
const isMobile = useMobile()

const tabs = [
  { value: 'student', label: '学生' },
  { value: 'teacher', label: '教师' },
  { value: 'class', label: '班级' },
  { value: 'school', label: '学校' },
  { value: 'course', label: '课程' },
  { value: 'classroom', label: '教室' },
]

// 进入时读取 query 参数，支持 /objects?type=teacher&schoolId=1 直达筛选结果
const type = ref(String(route.query.type || 'student'))
const schoolId = ref<number | ''>(route.query.schoolId ? Number(route.query.schoolId) : '')
const classId = ref<number | ''>(route.query.classId ? Number(route.query.classId) : '')
const keyword = ref(String(route.query.q || ''))
const items = ref<ObjectRow[]>([])
const total = ref(0)
const loading = ref(false)

const schools = computed(() => store.meta?.schools ?? [])
const classes = computed(() => {
  const all = store.meta?.classes ?? []
  return schoolId.value === '' ? all : all.filter((c) => c.schoolId === schoolId.value)
})

function onSchoolChange(): void {
  classId.value = ''
}

function open(row: ObjectRow): void {
  void router.push(`/object/${row.type}/${row.id}`)
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const d = await apiGet<ObjectsData>('/objects', {
      type: type.value,
      schoolId: schoolId.value === '' ? undefined : schoolId.value,
      classId: classId.value === '' ? undefined : classId.value,
      q: keyword.value.trim() || undefined,
    })
    items.value = d.items
    total.value = d.total
  } catch {
    // 后端启动期间静默
  } finally {
    loading.value = false
  }
}

const debouncedLoad = debounce(() => void load(), 300)

type QueryShape = Record<string, string | undefined>
function targetQuery(): QueryShape {
  return {
    type: type.value || undefined,
    schoolId: schoolId.value === '' ? undefined : String(schoolId.value),
    classId: classId.value === '' ? undefined : String(classId.value),
    q: keyword.value.trim() || undefined,
  }
}
function norm(v: unknown): string {
  return v === undefined || v === null || Array.isArray(v) ? '' : String(v)
}
function sameQuery(t: QueryShape): boolean {
  const q = route.query
  return (
    norm(q.type) === norm(t.type) &&
    norm(q.schoolId) === norm(t.schoolId) &&
    norm(q.classId) === norm(t.classId) &&
    norm(q.q) === norm(t.q)
  )
}

// 状态变化写回 URL，便于刷新复现与分享
watch([type, schoolId, classId, keyword], () => {
  const t = targetQuery()
  if (!sameQuery(t)) void router.replace({ path: '/objects', query: t })
  debouncedLoad()
})

// 外部导航携带新 query 时（如总览统计卡跳转）同步到表单
watch(() => route.fullPath, () => {
  const nt = String(route.query.type || 'student')
  const ns = route.query.schoolId ? Number(route.query.schoolId) : ''
  const nc = route.query.classId ? Number(route.query.classId) : ''
  const nq = String(route.query.q || '')
  if (nt !== type.value) type.value = nt
  if (ns !== schoolId.value) schoolId.value = ns
  if (nc !== classId.value) classId.value = nc
  if (nq !== keyword.value.trim()) keyword.value = nq
})

watch(() => store.refreshKey, () => void load())
onMounted(() => void load())
</script>

<template>
  <div class="page">
    <div class="card">
      <el-tabs v-model="type">
        <el-tab-pane v-for="t in tabs" :key="t.value" :label="t.label" :name="t.value" />
      </el-tabs>

      <div class="toolbar">
        <el-select
          v-model="schoolId"
          clearable
          placeholder="全部学校"
          style="width: 190px"
          @change="onSchoolChange"
        >
          <el-option v-for="s in schools" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
        <el-select v-model="classId" clearable placeholder="全部班级" style="width: 190px">
          <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-input v-model="keyword" clearable placeholder="姓名 / 编号 / 名称关键字" style="width: 220px" />
        <span class="muted small">共 {{ total }} 个对象 · 点击行进入详情</span>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <h2 class="card-title">{{ typeLabel(type) }}列表</h2>
        <span class="card-sub">课程卡片内包含教师、时间、地点与状态</span>
      </div>

      <div v-if="!isMobile">
        <el-table
          v-loading="loading"
          :data="items"
          style="width: 100%"
          row-class-name="clickable-row"
          @row-click="open"
        >
          <el-table-column label="对象" min-width="140">
            <template #default="{ row }">
              <div class="bold">{{ row.name }}</div>
              <div class="muted small">{{ row.code }}</div>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ typeLabel(row.type) }}</template>
          </el-table-column>
          <el-table-column label="归属 / 补充" min-width="190">
            <template #default="{ row }">
              <div>{{ row.schoolName }}</div>
              <div class="muted small">{{ row.className || '—' }} {{ row.subject }} {{ row.extra }}</div>
            </template>
          </el-table-column>
          <el-table-column label="当前课程" min-width="220">
            <template #default="{ row }">
              <LessonSummary :lesson="row.currentLesson" empty-text="当前无课" />
            </template>
          </el-table-column>
          <el-table-column label="下一节课" min-width="220">
            <template #default="{ row }">
              <LessonSummary :lesson="row.nextLesson" empty-text="今日课程已全部结束" />
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-else class="mobile-list">
        <div v-for="row in items" :key="`${row.type}-${row.id}`" class="mobile-item" @click="open(row)">
          <div class="mobile-item-title">
            <span>{{ row.name }} <span class="muted small">{{ row.code }}</span></span>
            <span class="chip">{{ typeLabel(row.type) }}</span>
          </div>
          <div class="mobile-item-row">{{ row.schoolName }} {{ row.className }} {{ row.subject }} {{ row.extra }}</div>
          <div style="margin-top: 8px">
            <div class="muted small bold">当前课程</div>
            <LessonSummary :lesson="row.currentLesson" empty-text="当前无课" />
          </div>
          <div style="margin-top: 8px">
            <div class="muted small bold">下一节课</div>
            <LessonSummary :lesson="row.nextLesson" empty-text="今日课程已全部结束" />
          </div>
        </div>
        <div v-if="!items.length && !loading" class="empty">暂无匹配对象</div>
      </div>

      <div v-if="!isMobile && !items.length && !loading" class="empty">暂无匹配对象</div>
    </div>
  </div>
</template>
