<script setup lang="ts">
import { computed, inject, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ApiError, apiGet, apiPost, errorText } from '../api'
import { store } from '../store'
import { debounce, fmtDateTime, statusTagType } from '../utils'
import type {
  AdjustBody,
  AdjustData,
  LessonView,
  MetaClassroom,
  MetaTeacher,
  PreviewData,
  ScheduleData,
  ScheduleDetail,
} from '../types'
import ImpactPanel from '../components/ImpactPanel.vue'
import { useMobile } from '../composables/useMobile'

const isMobile = useMobile()
const triggerRefresh = inject<() => Promise<void>>('triggerRefresh', async () => {})

const filters = reactive({
  schoolId: '' as number | '',
  classId: '' as number | '',
  teacherId: '' as number | '',
  date: '' as string,
  status: '' as string,
})

const data = ref<ScheduleData | null>(null)
const items = computed<LessonView[]>(() => data.value?.items ?? [])
const stats = computed(() => data.value?.stats ?? { total: 0, ongoing: 0, upcoming: 0, finished: 0 })

const schools = computed(() => store.meta?.schools ?? [])
const allClasses = computed(() => store.meta?.classes ?? [])
const allTeachers = computed(() => store.meta?.teachers ?? [])
const classes = computed(() =>
  filters.schoolId === '' ? allClasses.value : allClasses.value.filter((c) => c.schoolId === filters.schoolId)
)
const teachers = computed(() =>
  filters.schoolId === '' ? allTeachers.value : allTeachers.value.filter((t) => t.schoolId === filters.schoolId)
)

function onSchoolChange(): void {
  filters.classId = ''
  filters.teacherId = ''
}

async function load(): Promise<void> {
  try {
    data.value = await apiGet<ScheduleData>('/schedule', {
      schoolId: filters.schoolId === '' ? undefined : filters.schoolId,
      classId: filters.classId === '' ? undefined : filters.classId,
      teacherId: filters.teacherId === '' ? undefined : filters.teacherId,
      date: filters.date || undefined,
      status: filters.status || undefined,
    })
  } catch {
    // 后端启动期间静默，轮询补齐
  }
}

watch(filters, () => void load(), { deep: true })
watch(() => store.refreshKey, () => void load())
onMounted(() => void load())

// ============ 调课弹窗 ============
const dialogVisible = ref(false)
const currentLessonId = ref<number | null>(null)
const detail = ref<ScheduleDetail | null>(null)
const form = reactive<AdjustBody>({
  teacherId: 0,
  classroomId: 0,
  lessonDate: '',
  startTime: '',
  endTime: '',
  reason: '',
})
const preview = ref<PreviewData | null>(null)
const previewLoading = ref(false)
const submitting = ref(false)
const successData = ref<AdjustData | null>(null)
const submitError = ref<{ message: string; conflicts: string[] } | null>(null)

const teacherOptions = computed<MetaTeacher[]>(() => store.meta?.teachers ?? [])
const classroomOptions = computed<MetaClassroom[]>(() => store.meta?.classrooms ?? [])

const dialogTitle = computed(() =>
  detail.value ? `调整课程 · ${detail.value.lesson.code} ${detail.value.lesson.courseName}` : '调整课程'
)

function schoolName(id: number): string {
  return store.meta?.schools.find((s) => s.id === id)?.name ?? ''
}

// 教师下拉标注学校 / 科目 / 是否可代课 / 当前时段占用
function teacherLabel(t: MetaTeacher): string {
  const busy = detail.value?.candidates.teachers.find((c) => c.id === t.id)?.busy
  return `${t.name}（${schoolName(t.schoolId)} · ${t.subject} · ${t.substituteAvailable ? '可代课' : '不可代课'}${busy ? ' · 当前时段已占用' : ''}）`
}

function classroomLabel(r: MetaClassroom): string {
  const busy = detail.value?.candidates.classrooms.find((c) => c.id === r.id)?.busy
  return `${r.name}（${schoolName(r.schoolId)} · ${r.capacity} 人${busy ? ' · 当前时段已占用' : ''}）`
}

function body(): AdjustBody {
  return {
    teacherId: Number(form.teacherId),
    classroomId: Number(form.classroomId),
    lessonDate: form.lessonDate,
    startTime: form.startTime,
    endTime: form.endTime,
    reason: form.reason,
  }
}

async function runPreview(): Promise<void> {
  if (!currentLessonId.value || !dialogVisible.value || successData.value) return
  previewLoading.value = true
  submitError.value = null
  try {
    preview.value = await apiPost<PreviewData>(`/schedule/${currentLessonId.value}/preview`, body())
  } catch (e) {
    ElMessage.error(errorText(e))
  } finally {
    previewLoading.value = false
  }
}

const debouncedPreview = debounce(() => void runPreview(), 300)
watch(
  form,
  () => {
    if (dialogVisible.value && !successData.value) debouncedPreview()
  },
  { deep: true }
)

async function openAdjust(row: LessonView): Promise<void> {
  currentLessonId.value = row.id
  dialogVisible.value = true
  successData.value = null
  submitError.value = null
  preview.value = null
  detail.value = null
  try {
    const d = await apiGet<ScheduleDetail>(`/schedule/${row.id}`)
    detail.value = d
    form.teacherId = d.before.teacherId
    form.classroomId = d.before.classroomId
    form.lessonDate = d.before.lessonDate
    form.startTime = d.before.startTime
    form.endTime = d.before.endTime
    form.reason = ''
    await runPreview()
  } catch (e) {
    ElMessage.error(errorText(e))
  }
}

// 演示用推荐方案：陈思琪(TCH-005) + 多功能报告厅(ROM-06) + 第七节 15:30-16:15
function fillRecommended(): void {
  form.teacherId = 5
  form.classroomId = 6
  form.startTime = '15:30'
  form.endTime = '16:15'
  form.reason = '教师参加教研，调至第七节并更换教室'
  ElMessage.success('已填入推荐调课方案，正在实时校验')
}

async function submit(): Promise<void> {
  if (!preview.value?.valid) {
    ElMessage.warning('请先解决校验冲突再提交')
    return
  }
  submitting.value = true
  submitError.value = null
  try {
    const r = await apiPost<AdjustData>(`/schedule/${currentLessonId.value}/adjust`, body())
    successData.value = r
    ElMessage.success('调课成功，变更记录已写入，所有视角已同步')
    await load()
    await triggerRefresh()
  } catch (e) {
    if (e instanceof ApiError) {
      submitError.value = { message: e.message, conflicts: e.conflicts }
    } else {
      submitError.value = { message: errorText(e), conflicts: [] }
    }
    // 提交失败时同步把影响面板置为未通过，避免与「校验通过」绿条矛盾并禁用重复提交
    if (preview.value) preview.value.valid = false
    ElMessage.error('调课未通过校验，请查看下方具体原因')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-header">
        <h2 class="card-title">课表管理</h2>
        <span class="card-sub">共 {{ stats.total }} 节 · 正在上课 {{ stats.ongoing }} · 即将开始 {{ stats.upcoming }} · 已结束 {{ stats.finished }}</span>
      </div>
      <div class="toolbar">
        <el-select
          v-model="filters.schoolId"
          clearable
          placeholder="全部学校"
          style="width: 180px"
          @change="onSchoolChange"
        >
          <el-option v-for="s in schools" :key="s.id" :label="s.name" :value="s.id" />
        </el-select>
        <el-select v-model="filters.classId" clearable placeholder="全部班级" style="width: 170px">
          <el-option v-for="c in classes" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
        <el-select v-model="filters.teacherId" clearable placeholder="全部教师" style="width: 170px" filterable>
          <el-option v-for="t in teachers" :key="t.id" :label="`${t.name}（${t.subject}）`" :value="t.id" />
        </el-select>
        <input v-model="filters.date" type="date" class="time-input" style="min-width: 150px" aria-label="日期" />
        <el-select v-model="filters.status" clearable placeholder="全部状态" style="width: 140px">
          <el-option label="正在上课" value="正在上课" />
          <el-option label="即将开始" value="即将开始" />
          <el-option label="已结束" value="已结束" />
        </el-select>
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <h2 class="card-title">授课安排（{{ stats.total }} 节）</h2>
        <span class="card-sub">点击「调整」可实时预览影响、校验冲突并提交</span>
      </div>

      <div v-if="!isMobile">
        <el-table :data="items" size="small" style="width: 100%" stripe>
          <el-table-column prop="code" label="安排编号" width="100" />
          <el-table-column prop="lessonDate" label="日期" width="110" />
          <el-table-column prop="periodName" label="节次" width="90" />
          <el-table-column label="时间" width="130">
            <template #default="{ row }">{{ row.startTime }}–{{ row.endTime }}</template>
          </el-table-column>
          <el-table-column prop="className" label="班级" min-width="120" />
          <el-table-column prop="courseName" label="课程" min-width="90" />
          <el-table-column prop="teacherName" label="教师" min-width="90" />
          <el-table-column prop="classroomName" label="教室" min-width="150" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" effect="light">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="studentCount" label="学生数" width="80" />
          <el-table-column label="操作" width="90" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" size="small" link @click="openAdjust(row)">调整</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!items.length" class="empty">当前筛选条件下没有课程安排</div>
      </div>

      <div v-else class="mobile-list">
        <div v-for="l in items" :key="l.id" class="mobile-item">
          <div class="mobile-item-title">
            <span>{{ l.periodName }} · {{ l.courseName }} · {{ l.className }}</span>
            <el-tag :type="statusTagType(l.status)" size="small" effect="light">{{ l.status }}</el-tag>
          </div>
          <div class="mobile-item-row">{{ l.lessonDate }} {{ l.startTime }}–{{ l.endTime }}</div>
          <div class="mobile-item-row">教师：{{ l.teacherName }} ｜ 教室：{{ l.classroomName }}</div>
          <div class="mobile-item-row">学生 {{ l.studentCount }} 人 ｜ {{ l.code }}</div>
          <el-button type="primary" size="small" style="margin-top: 8px" @click="openAdjust(l)">调整</el-button>
        </div>
        <div v-if="!items.length" class="empty">当前筛选条件下没有课程安排</div>
      </div>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      :width="isMobile ? '95%' : '780px'"
      top="4vh"
      :close-on-click-modal="false"
    >
      <!-- 编辑态 -->
      <template v-if="!successData">
        <div v-if="detail" class="card" style="margin-bottom: 12px; box-shadow: none; background: #f7faff">
          <div class="bold">
            当前安排：{{ detail.lesson.className }} · {{ detail.lesson.courseName }} · {{ detail.lesson.teacherName }}
          </div>
          <div class="muted small">
            {{ detail.before.lessonDate }} {{ detail.before.startTime }}–{{ detail.before.endTime }} ·
            {{ detail.before.classroomName }} · {{ detail.lesson.studentCount }} 人
          </div>
        </div>

        <div style="margin-bottom: 14px">
          <el-button type="primary" plain size="small" @click="fillRecommended">一键填入推荐调课方案</el-button>
          <span class="muted small" style="margin-left: 8px">陈思琪 + 多功能报告厅 + 15:30-16:15（演示用）</span>
        </div>

        <el-form label-width="96px" label-position="left">
          <el-form-item label="授课教师">
            <el-select v-model="form.teacherId" filterable style="width: 100%">
              <el-option v-for="t in teacherOptions" :key="t.id" :label="teacherLabel(t)" :value="t.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="日期">
            <input v-model="form.lessonDate" type="date" class="time-input" style="width: 100%; min-width: 0" aria-label="调整日期" />
          </el-form-item>
          <el-form-item label="开始时间">
            <input v-model="form.startTime" type="time" class="time-input" style="width: 100%; min-width: 0" aria-label="开始时间" />
          </el-form-item>
          <el-form-item label="结束时间">
            <input v-model="form.endTime" type="time" class="time-input" style="width: 100%; min-width: 0" aria-label="结束时间" />
          </el-form-item>
          <el-form-item label="教室">
            <el-select v-model="form.classroomId" filterable style="width: 100%">
              <el-option v-for="r in classroomOptions" :key="r.id" :label="classroomLabel(r)" :value="r.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="调整原因" required>
            <el-input
              v-model="form.reason"
              type="textarea"
              :rows="2"
              placeholder="必填，例如：教师参加教研，调至第七节并更换教室"
            />
          </el-form-item>
        </el-form>

        <div class="section-gap">
          <div class="card-header">
            <h2 class="card-title">实时影响预览</h2>
            <span v-if="previewLoading" class="muted small">校验中…</span>
          </div>

          <div v-if="submitError" class="conflict-list">
            <div class="impact-section-title" style="color: var(--danger)">提交失败：{{ submitError.message }}</div>
            <div v-for="(m, i) in submitError.conflicts" :key="i" class="conflict-item">· {{ m }}</div>
          </div>

          <ImpactPanel
            v-if="preview"
            :diff="preview.diff || []"
            :impact="preview.impact || null"
            :conflicts="preview.conflicts || []"
            :valid="preview.valid"
          />
          <div v-else class="empty">修改表单后自动校验并显示影响</div>
        </div>
      </template>

      <!-- 成功态 -->
      <template v-else>
        <div class="valid-banner" style="font-size: 15px">调课成功！变更记录已写入，所有视角数据已同步。</div>
        <div class="card" style="margin-bottom: 14px; box-shadow: none; background: #f7faff">
          <div class="bold">{{ successData.change.changeType }} · {{ successData.change.className }} {{ successData.change.courseName }}</div>
          <div class="muted small">原因：{{ successData.change.reason }}</div>
          <div class="muted small">
            变更编号 #{{ successData.change.id }} · {{ fmtDateTime(successData.change.occurredAt) }} · 操作人 {{ successData.change.operator }}
          </div>
        </div>
        <ImpactPanel
          :diff="successData.diff || []"
          :impact="successData.impact || null"
          :conflicts="[]"
          :valid="true"
          show-links
          title="本次调整影响（可直接跳转到相关视角）"
        />
      </template>

      <template #footer>
        <el-button @click="dialogVisible = false">{{ successData ? '完成' : '取消' }}</el-button>
        <el-button
          v-if="!successData"
          type="primary"
          :disabled="!preview?.valid"
          :loading="submitting"
          @click="submit"
        >
          确认调整
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
