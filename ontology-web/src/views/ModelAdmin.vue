<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { api, qs, ApiError } from '../api'
import { registerRefresher, store } from '../store'
import SourceStatusBadge from '../components/SourceStatusBadge.vue'
import RelationTypeEditorDialog from '../components/RelationTypeEditorDialog.vue'
import ObjectTypeEditorDialog from '../components/ObjectTypeEditorDialog.vue'
import DefinitionVersionPanel from '../components/DefinitionVersionPanel.vue'
import { useDefinitionApply } from '../composables/useDefinitionApply'
import {
  fmt,
  type DeleteImpactData,
  type ModelMappingsData,
  type ModelRelationsData,
  type ModelTypesData,
  type ModelVersionsData,
  type ObjectTypeEditDef,
  type OutboxData,
  type SaveObjectResult,
  type RelationTypeEditDef,
  type SaveRelationResult,
  type SourceStatus,
  type SyncTaskData,
  type ValidationErrorData,
} from '../types'

/** 与后端一致的默认操作人（原业务 change_records.operator 口径）。 */
const OPERATOR = '演示管理员'

/**
 * 下面的映射只用于「显示中文」，绝不参与提交。
 * 下拉框 :value / v-model 绑定的仍是后端原值（如 N:1、FOREIGN_KEY、OUT）。
 */
const TASK_TYPE_LABELS: Record<string, string> = {
  FULL_IMPORT: '全量导入',
  REBUILD: '重新生成页面数据',
  RECONCILE: '一致性检查',
}
const TASK_STATUS_LABELS: Record<string, string> = {
  SUCCESS: '成功',
  FAILED: '失败',
  RUNNING: '进行中',
  INTERRUPTED: '已中断（上次没做完）',
}
const OUTBOX_STATUS_LABELS: Record<string, string> = {
  DEAD: '彻底失败（需人工处理）',
  FAILED: '同步失败',
  PENDING: '等待同步',
  PROCESSING: '正在处理',
  DELIVERED: '已同步',
}
const CHANGE_KIND_LABELS: Record<string, string> = {
  ADJUST: '调整课程',
  BATCH_ADJUST: '批量调整课程',
  DELETE: '删除',
  RECONCILE_FIX: '一致性检查修复',
}
const DYNAMIC_LABELS: Record<string, string> = {
  currentLesson: '当前课程',
  nextLesson: '下一节课',
  lessonStatusResolver: '课程状态（按时间点）',
}
const CARDINALITY_LABELS: Record<string, string> = {
  'N:1': '多对一',
  '1:1': '一对一',
  '1:N': '一对多',
  'N:M': '多对多',
}
const GENERATION_LABELS: Record<string, string> = {
  FOREIGN_KEY: '来源表外键',
  JOIN_RULE: '按规则推算',
}
const SOURCE_TABLE_LABELS: Record<string, string> = {
  schools: '学校表',
  classes: '班级表',
  students: '学生表',
  teachers: '教师表',
  courses: '课程表',
  lesson_sessions: '授课安排表',
  classrooms: '教室表',
}
const DEF_SOURCE_LABELS: Record<string, string> = {
  OVERLAY: '在线修改',
  OVERLAY_FALLBACK: '已回退到最初设置',
  BASELINE: '最初设置',
}
const PROVIDER_LABELS: Record<string, string> = {
  schoolCountProvider: '学校人数等统计',
  classStudentCountProvider: '班级学生人数',
  classLookupProvider: '所在班级',
  teacherClassProvider: '任教班级',
  classroomUsageProvider: '教室使用情况',
}
const RESOLVER_LABELS: Record<string, string> = {
  currentLesson: '当前课程',
  nextLesson: '下一节课',
  lessonStatusResolver: '课程状态（按时间点）',
  SchoolLessonResolver: '学校课表',
}

function labelOf(value: unknown, map: Record<string, string>, fallback = '—'): string {
  const key = String(value ?? '')
  return key ? (map[key] ?? key) : fallback
}

/** 版本哈希只给短号，完整串用 title 悬停查看。 */
function shortVersion(value: unknown): string {
  const text = String(value ?? '')
  if (!text) return '—'
  const parts = text.split(':')
  const tail = parts.length > 1 ? parts[parts.length - 1] : text
  return tail.slice(0, 12)
}

function objectTypeLabel(id: string): string {
  return (modelTypes.value?.types ?? []).find(item => item.id === id)?.displayName || id
}

/** 列表里优先取模型里的字段中文名，取不到才回退技术字段名。 */
function fieldLabel(typeId: string, name: string): string {
  const def = (modelTypes.value?.types ?? []).find(item => item.id === typeId)
  return (def?.detailFields ?? []).find(field => field.name === name)?.displayName || name
}

function fieldLabels(typeId: string, names: unknown): string {
  const list = Array.isArray(names) ? names.map(name => String(name)) : []
  return list.length ? list.map(name => fieldLabel(typeId, name)).join('、') : '—'
}

function dynamicLabels(keys: unknown): string {
  const list = Array.isArray(keys) ? keys.map(key => String(key)) : []
  return list.length ? list.map(key => labelOf(key, DYNAMIC_LABELS, key)).join('、') : '—'
}

function sourceTableLabel(table: unknown): string {
  return labelOf(table, SOURCE_TABLE_LABELS, '来源表')
}

function sourcePkLabel(pk: unknown): string {
  return String(pk ?? '') === 'id' ? '编号' : String(pk ?? '—')
}

function mappingRefLabel(ref: string): string {
  const typeId = ref.split('#')[1] || ''
  return typeId ? `${objectTypeLabel(typeId)}（来源配置）` : ref
}

function relationName(id: string): string {
  const row = (relations.value?.relations ?? []).find(item => item.id === id)
  return row?.displayName || id
}

const modelTypes = ref<ModelTypesData | null>(null)
const relations = ref<ModelRelationsData | null>(null)
const mappings = ref<ModelMappingsData | null>(null)
const versions = ref<ModelVersionsData | null>(null)
const status = ref<SourceStatus | null>(null)
const tasks = ref<SyncTaskData | null>(null)
const outbox = ref<OutboxData | null>(null)
const message = ref('')
const error = ref('')
const taskIdInput = ref('')
const eventIdInput = ref('')

/** 关系类型编辑状态。 */
const editorOpen = ref(false)
const editing = ref<RelationTypeEditDef | null>(null)
const formErrors = ref<string[]>([])
const saving = ref(false)
/** 删除影响确认（先 409 拿清单，再 force 重发）。 */
const deleteImpact = ref<{ relationId: string; impact: DeleteImpactData } | null>(null)
const resetting = ref(false)
const resetConfirmOpen = ref(false)

/** 对象类型编辑状态（C 档）：与关系编辑共用「应用到运行时」入口。 */
const objectEditorOpen = ref(false)
const editingObject = ref<ObjectTypeEditDef | null>(null)
const objectFormErrors = ref<string[]>([])
const objectSaving = ref(false)
const objectResetting = ref(false)
const objectResetConfirmOpen = ref(false)

const apply = useDefinitionApply(async () => {
  await reload()
})
const applyPhase = apply.phase
const applyMessage = apply.message

let unregister: (() => void) | undefined

async function loadAll(): Promise<void> {
  const [typesData, relationsData, mappingsData, versionsData, statusData, tasksData, outboxData] =
    await Promise.all([
      api.get<ModelTypesData>('/model/types'),
      api.get<ModelRelationsData>('/model/relations'),
      api.get<ModelMappingsData>('/model/mappings'),
      api.get<ModelVersionsData>('/model/versions'),
      api.get<SourceStatus>('/sources/status'),
      api.get<SyncTaskData>('/sync/tasks' + qs({ page: 1, size: 10 })),
      api.get<OutboxData>('/admin/outbox' + qs({ page: 1, size: 20 })),
    ])
  modelTypes.value = typesData
  // 应用草稿后 App 不会重新挂载：这里同步全局模型，避免类型导航仍显示旧展示名。
  store.types = typesData.types
  store.mappingVersion = typesData.mappingVersion
  relations.value = relationsData
  mappings.value = mappingsData
  versions.value = versionsData
  status.value = statusData
  tasks.value = tasksData
  outbox.value = outboxData
}

async function reload(): Promise<void> {
  try {
    await loadAll()
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '配置没加载出来，请刷新页面重试'
  }
}

/** 有草稿时列表显示草稿内容（含刚新增还没生效的关系），否则显示生效定义。 */
const relationRows = computed<RelationTypeEditDef[]>(() => {
  const draft = versions.value?.draft
  if (draft?.exists && (draft.relations?.length ?? 0) > 0) {
    return draft.relations as RelationTypeEditDef[]
  }
  return relations.value?.relations ?? []
})

/** 对象类型列表：草稿存在时展示草稿定义，否则展示当前生效定义（后端 draft.objectTypes 已统一）。 */
const objectRows = computed<ObjectTypeEditDef[]>(() => versions.value?.draft?.objectTypes ?? [])
const objectDraftExists = computed(() => Boolean(versions.value?.draft?.objectTypesFromDraft))

const showingDraft = computed(() => Boolean(versions.value?.draft?.exists))
const baseMappingVersion = computed(
  () => versions.value?.activeMappingVersion || relations.value?.mappingVersion || '',
)
const objectTypeOptions = computed(() =>
  (modelTypes.value?.types ?? []).map(item => ({ id: item.id, displayName: item.displayName })),
)
const mappingRefs = computed(() =>
  (mappings.value?.objectMappings ?? [])
    .map(item => String((item as Record<string, unknown>).objectType ?? ''))
    .filter(objectType => objectType.length > 0)
    .map(objectType => `school-source.yaml#${objectType}`),
)

function openCreate(): void {
  editing.value = null
  formErrors.value = []
  editorOpen.value = true
}

function openEdit(row: RelationTypeEditDef): void {
  editing.value = row
  formErrors.value = []
  editorOpen.value = true
}

function closeEditor(): void {
  editorOpen.value = false
}

function openEditObject(row: ObjectTypeEditDef): void {
  editingObject.value = row
  objectFormErrors.value = []
  objectEditorOpen.value = true
}

async function submitObject(value: ObjectTypeEditDef): Promise<void> {
  objectSaving.value = true
  objectFormErrors.value = []
  try {
    const result = await api.put<SaveObjectResult>(
      `/model/object-types/${encodeURIComponent(value.id)}`,
      { baseMappingVersion: baseMappingVersion.value, operator: OPERATOR, objectType: value },
    )
    objectEditorOpen.value = false
    message.value = result.impact?.hint
      ? `${result.message}（${result.impact.hint}）`
      : result.message
    error.value = ''
    await reload()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'VALIDATION_FAILED') {
      const data = e.data as ValidationErrorData | null
      objectFormErrors.value = data?.errors?.length ? data.errors : [e.message]
    } else {
      error.value = e instanceof Error ? e.message : '没保存成功，请检查填写内容后重试'
    }
  } finally {
    objectSaving.value = false
  }
}

/** 只恢复对象类型基线：后端不会触碰关系 overlay，确认文案明确写出这一点。 */
async function doObjectReset(): Promise<void> {
  objectResetting.value = true
  try {
    const result = await api.post<SaveObjectResult>('/model/object-types/reset', {
      baseMappingVersion: baseMappingVersion.value,
      confirm: true,
      operator: OPERATOR,
    })
    objectResetConfirmOpen.value = false
    message.value = result.message
    error.value = ''
    await reload()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '撤销没成功，请稍后重试'
  } finally {
    objectResetting.value = false
  }
}

async function submitRelation(value: RelationTypeEditDef): Promise<void> {
  saving.value = true
  formErrors.value = []
  try {
    const body = { baseMappingVersion: baseMappingVersion.value, operator: OPERATOR, relation: value }
    const result = editing.value
      ? await api.put<SaveRelationResult>(
        `/model/relations/${encodeURIComponent(value.id)}`, body)
      : await api.post<SaveRelationResult>('/model/relations', body)
    editorOpen.value = false
    message.value = result.message
    error.value = ''
    await reload()
  } catch (e) {
    if (e instanceof ApiError && e.code === 'VALIDATION_FAILED') {
      const data = e.data as ValidationErrorData | null
      formErrors.value = data?.errors?.length ? data.errors : [e.message]
    } else {
      error.value = e instanceof Error ? e.message : '没保存成功，请检查填写内容后重试'
    }
  } finally {
    saving.value = false
  }
}

async function deleteRelation(row: RelationTypeEditDef): Promise<void> {
  try {
    const result = await api.del<SaveRelationResult>(
      `/model/relations/${encodeURIComponent(row.id)}`,
      { baseMappingVersion: baseMappingVersion.value, operator: OPERATOR, force: false },
    )
    message.value = result.message
    error.value = ''
    await reload()
  } catch (e) {
    if (e instanceof ApiError && (e.code === 'DELETE_IMPACT_CONFIRM' || e.code === 'DELETE_BLOCKED_BY_MAPPING')) {
      deleteImpact.value = { relationId: row.id, impact: e.data as DeleteImpactData }
      if (e.code === 'DELETE_BLOCKED_BY_MAPPING') error.value = e.message
      return
    }
    error.value = e instanceof Error ? e.message : '没删除成功，请稍后重试'
  }
}

async function confirmForceDelete(): Promise<void> {
  const pending = deleteImpact.value
  if (!pending) return
  deleteImpact.value = null
  try {
    const result = await api.del<SaveRelationResult>(
      `/model/relations/${encodeURIComponent(pending.relationId)}`,
      { baseMappingVersion: baseMappingVersion.value, operator: OPERATOR, force: true },
    )
    message.value = result.message
    error.value = ''
    await reload()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '没删除成功，请稍后重试'
  }
}

async function doReset(): Promise<void> {
  resetting.value = true
  try {
    const result = await api.post<SaveRelationResult>('/model/relations/reset', {
      baseMappingVersion: baseMappingVersion.value,
      confirm: true,
      operator: OPERATOR,
    })
    resetConfirmOpen.value = false
    message.value = result.message
    error.value = ''
    await reload()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '撤销没成功，请稍后重试'
  } finally {
    resetting.value = false
  }
}

async function applyDefinition(): Promise<void> {
  error.value = ''
  try {
    await apply.apply(baseMappingVersion.value, OPERATOR)
    await reload()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '让改动生效没成功，请稍后重试'
  }
}

async function runAction(label: string, action: () => Promise<unknown>): Promise<void> {
  try {
    const result = await action()
    message.value = `${label}已提交，请稍后查看下面的执行记录。`
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
    error.value = '请填写要重新处理的记录编号（可从下面失败记录里复制）'
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
        <h2>配置中心</h2>
        <div class="sub">在这里改对象和关系的显示设置；改完先「暂存修改」，再点「让改动生效」。下面的数据同步操作是维护用的，一般不用点。</div>
      </div>
      <span class="demo-tag">演示数据 / 演示时间</span>
    </div>

    <p v-if="error" class="error-banner">{{ error }}</p>
    <div v-if="message" class="card" style="background:#ecfdf5; border-color:#a7f3d0">{{ message }}</div>

    <div class="card">
      <h3>数据源（业务系统）接入情况</h3>
      <SourceStatusBadge :status="status" />
      <div class="kv" style="margin-top:10px">
        <div class="k">已经读到业务系统的第几条记录</div><div class="v">{{ status?.snapshotRevision ?? '—' }}</div>
        <div class="k">业务系统现在到第几条记录</div><div class="v">{{ status?.currentSourceRevision ?? '—' }}</div>
        <div class="k">等待同步 / 同步失败 / 彻底失败（需人工处理）</div>
        <div class="v">{{ status?.pendingCount ?? 0 }} / {{ status?.errorCount ?? 0 }} / {{ status?.deadCount ?? 0 }}</div>
        <div class="k">当前数据批次</div><div class="v" :title="String(status?.mappingVersion ?? '')">{{ shortVersion(status?.mappingVersion) }}</div>
      </div>
      <div class="hint" style="margin-top:8px">
        常见情况：① 还没有业务数据；② 还没接入业务系统；③ 同步慢了，有新变化还没读进来；④ 同步失败，需要处理。
        「业务系统连不上」只是附带提示，不是第五种状态。这里的时间是平台处理时间，不是业务发生时间。
      </div>
    </div>

    <div class="card">
      <h3>数据同步操作（维护用，平时不用点）</h3>
      <div class="toolbar">
        <button class="primary" @click="fullImport" title="重新读取全部业务数据，会用最新数据覆盖当前页面数据">重新读取全部业务数据（全量导入）</button>
        <button @click="reconcile" title="检查页面数据是否和业务系统一致；发现不一致会记一条修复记录">检查页面数据和业务系统是否一致（对账）</button>
        <button class="danger" @click="rebuild" title="只重新生成页面数据，历史变化记录会保留">重新生成页面数据（历史变化记录会保留）</button>
        <span class="spacer"></span>
        <label>失败任务编号</label>
        <input v-model="taskIdInput" type="number" style="width:110px" placeholder="从下面失败任务里复制编号" />
        <button @click="retryImport">重试这次导入</button>
      </div>
      <div class="toolbar">
        <label>失败记录编号</label>
        <input v-model="eventIdInput" type="text" style="min-width:260px; max-width:100%" placeholder="从下面「失败 / 彻底失败」记录里复制编号" />
        <button @click="adminRetry">重新处理这条失败记录</button>
      </div>
      <div class="hint">这些是维护操作，普通查看不用点；要改业务内容（比如调课）请回原业务系统。</div>
    </div>

    <div class="card">
      <h3>数据同步记录</h3>
      <table class="desktop-only">
        <thead><tr><th>任务编号</th><th>做什么</th><th>结果</th><th>从第几条开始</th><th>到第几条结束</th><th>开始时间</th><th>完成时间</th></tr></thead>
        <tbody>
          <tr v-for="task in tasks?.items ?? []" :key="task.taskId">
            <td :title="'任务编号 ' + task.taskId">{{ task.taskId }}</td>
            <td>{{ labelOf(task.type, TASK_TYPE_LABELS) }}</td>
            <td><span :class="'badge ' + (task.status === 'SUCCESS' ? 'ok' : task.status === 'FAILED' ? 'danger' : 'info')">{{ labelOf(task.status, TASK_STATUS_LABELS) }}</span></td>
            <td>{{ task.snapshotRevision }}</td><td>{{ task.endRevision }}</td>
            <td>{{ task.startedAt || '—' }}</td><td>{{ task.finishedAt || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="mobile-only">
        <div v-for="task in tasks?.items ?? []" :key="task.taskId" class="card" style="margin-bottom:8px">
          <b>#{{ task.taskId }} {{ labelOf(task.type, TASK_TYPE_LABELS) }}</b>
          <span class="badge info">{{ labelOf(task.status, TASK_STATUS_LABELS) }}</span>
          <div class="hint">处理范围：第 {{ task.snapshotRevision }} 条到第 {{ task.endRevision }} 条 · {{ task.startedAt || '—' }}</div>
        </div>
      </div>
      <div v-if="!(tasks?.items ?? []).length" class="empty">还没有数据同步记录</div>
    </div>

    <div class="card">
      <h3>
        对象类别设置（共 {{ objectRows.length }} 类，数据批次 <span :title="String(modelTypes?.mappingVersion ?? '')">{{ shortVersion(modelTypes?.mappingVersion) }}</span>）
        <span v-if="objectDraftExists" class="badge warn" style="margin-left:6px">有未生效的修改</span>
        <button style="margin-left:8px" :disabled="objectResetting" @click="objectResetConfirmOpen = true" title="只撤销对象类别的在线修改，关系类别不受影响">
          撤销对象类别的在线修改（恢复对象基线）
        </button>
      </h3>
      <div class="grid cols-2">
        <div v-for="item in objectRows" :key="item.id" class="card" style="margin:0">
          <b data-testid="object-display-name">{{ item.displayName }}</b>
          <span class="badge info" :title="'类别编号：' + item.id">{{ item.id }}</span>
          <span v-if="objectDraftExists" class="badge warn" style="margin-left:6px">有改动未生效</span>
          <div class="hint">{{ item.description }}</div>
          <div class="kv" style="margin-top:8px">
            <div class="k">列表显示列</div><div class="v">{{ fieldLabels(item.id, item.listFields) }}</div>
            <div class="k">可搜索字段</div><div class="v">{{ fieldLabels(item.id, item.searchableFields) }}</div>
            <div class="k">可筛选字段</div><div class="v">{{ fieldLabels(item.id, item.filterableFields) }}</div>
            <div class="k">计算字段</div>
            <div class="v">{{ (item.derivedProperties ?? []).map(d => d.displayName || d.name).join('、') || '—' }}</div>
            <div class="k">按时间变化的信息</div><div class="v">{{ dynamicLabels(item.dynamicResolvers) }}</div>
            <div class="k">数据来自哪里</div>
            <div class="v">{{ sourceTableLabel(item.identity?.sourceTable) }}（主键：{{ sourcePkLabel(item.identity?.sourcePk) }}）</div>
          </div>
          <div class="toolbar" style="margin:8px 0 0">
            <span class="spacer"></span>
            <button :data-testid="'edit-object-' + item.id" @click="openEditObject(item)" title="修改这个类别的显示名称、说明、标题格式和字段勾选">编辑显示设置</button>
          </div>
        </div>
      </div>
      <div class="hint" style="margin-top:8px">
        可以改显示名称、说明、对象标题格式，以及列表 / 搜索 / 筛选勾选哪些字段。对象身份、原始字段和计算方式属于数据结构，
        这里不能改；需要调整请联系管理员改来源配置并重新导入。改完点「暂存修改」还不会生效，还要点「让改动生效」。
      </div>
    </div>

    <div class="card">
      <h3>
        关系类别设置（共 {{ relationRows.length }} 条）
        <span v-if="showingDraft" class="badge warn" style="margin-left:6px">有未生效的修改</span>
      </h3>

      <DefinitionVersionPanel
        :versions="versions"
        :busy="saving || resetting"
        :apply-phase="applyPhase"
        :apply-message="applyMessage"
        @apply="applyDefinition"
        @reset="resetConfirmOpen = true"
      />

      <div class="toolbar" style="margin-top:12px">
        <button class="primary" @click="openCreate">新增关系类型</button>
        <span class="spacer"></span>
        <span class="hint">新增或修改只是暂存；点「让改动生效」后服务会重启约 10 秒，然后才能看到新设置。</span>
      </div>

      <table class="desktop-only">
        <thead>
          <tr><th>编号</th><th>页面上显示的名称</th><th>从哪类对象 → 到哪类对象</th><th>对应关系</th><th>关系怎么来的</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="row in relationRows" :key="row.id">
            <td :title="'编号：' + row.id">{{ row.id }}</td>
            <td>{{ row.displayName || '—' }}</td>
            <td>{{ objectTypeLabel(row.fromType) }} → {{ objectTypeLabel(row.toType) }}</td>
            <td>{{ CARDINALITY_LABELS[row.cardinality] || row.cardinality || '—' }}</td>
            <td>{{ GENERATION_LABELS[row.generation?.kind ?? ''] || row.generation?.kind || '—' }}</td>
            <td>
              <button @click="openEdit(row)" title="修改这条关系的显示设置">编辑</button>
              <button class="danger" style="margin-left:6px" @click="deleteRelation(row)" title="删除前会先检查是否还有配置在用这条关系">删除</button>
            </td>
          </tr>
        </tbody>
      </table>
      <div class="mobile-only">
        <div v-for="row in relationRows" :key="row.id" class="card" style="margin:0 0 8px">
          <b>{{ row.displayName || row.id }}</b> <span class="badge info" :title="row.id">{{ row.id }}</span>
          <div class="hint">{{ objectTypeLabel(row.fromType) }} → {{ objectTypeLabel(row.toType) }} · {{ CARDINALITY_LABELS[row.cardinality] || row.cardinality }} · {{ GENERATION_LABELS[row.generation?.kind ?? ''] || row.generation?.kind }}</div>
          <div class="toolbar" style="margin:8px 0 0">
            <button @click="openEdit(row)">编辑</button>
            <button class="danger" @click="deleteRelation(row)">删除</button>
          </div>
        </div>
      </div>
      <div v-if="!relationRows.length" class="empty">还没有配置关系类别</div>
    </div>

    <div class="card">
      <h3>数据从哪来</h3>
      <div class="grid cols-2">
        <div>
          <b>数据源（业务系统）</b>
          <div v-for="(source, index) in mappings?.dataSources ?? []" :key="index" class="hint">
            {{ source.displayName || objectTypeLabel(String(source.id ?? '')) }}（{{ source.id }}）· {{ source.type }} · 状态：{{ source.status }}
          </div>
          <b style="display:block; margin-top:10px">数据批次记录</b>
          <div v-for="(version, index) in mappings?.versions ?? []" :key="index" class="hint">
            批次 {{ shortVersion(version.mappingVersion || version.version) }} · {{ version.status }} · {{ version.definitionSource ? labelOf(version.definitionSource, DEF_SOURCE_LABELS) : '' }} · {{ version.operator || '—' }}
          </div>
        </div>
        <div>
          <b>可用的计算方式</b>
          <div class="hint">数值计算：{{ (mappings?.providers ?? []).map(p => labelOf(p.key, PROVIDER_LABELS)).join('、') || '—' }}</div>
          <div class="hint">按时间点查询：{{ (mappings?.resolvers ?? []).map(r => labelOf(r.key, RESOLVER_LABELS)).join('、') || '—' }}</div>
          <b style="display:block; margin-top:10px">每个对象类别对应哪张业务表</b>
          <div v-for="(mapping, index) in mappings?.objectMappings ?? []" :key="index" class="hint">
            {{ objectTypeLabel(String(mapping.objectType ?? '')) }}：{{ sourceTableLabel(mapping.sourceTable) }}（主键：{{ sourcePkLabel(mapping.pk) }}）
          </div>
        </div>
      </div>
    </div>

    <div class="card">
      <h3>业务系统发来的变化记录（只读）</h3>
      <table class="desktop-only">
        <thead><tr><th>记录编号</th><th>变化类型</th><th>业务记录号</th><th>处理结果</th><th>重试次数</th><th>失败原因</th><th>业务发生时间</th></tr></thead>
        <tbody>
          <tr v-for="item in outbox?.items ?? []" :key="item.eventId">
            <td class="hint" :title="'记录编号：' + item.eventId">{{ item.eventId }}</td>
            <td>{{ labelOf(item.changeKind, CHANGE_KIND_LABELS) }}</td>
            <td>{{ item.revision }}</td>
            <td><span :class="'badge ' + (item.status === 'DEAD' ? 'danger' : item.status === 'DELIVERED' ? 'ok' : 'info')">{{ labelOf(item.status, OUTBOX_STATUS_LABELS) }}</span></td>
            <td>{{ item.attempts }}</td><td class="hint">{{ item.lastError || '—' }}</td><td class="hint">{{ item.businessTime || '—' }}</td>
          </tr>
        </tbody>
      </table>
      <div class="mobile-only">
        <div v-for="item in outbox?.items ?? []" :key="item.eventId" class="card" style="margin-bottom:8px">
          <b>{{ labelOf(item.changeKind, CHANGE_KIND_LABELS) }}</b>
          <span class="badge info">{{ labelOf(item.status, OUTBOX_STATUS_LABELS) }}</span>
          <div class="hint" :title="'记录编号：' + item.eventId">{{ item.eventId }}</div>
          <div class="hint">业务记录号 {{ item.revision }} · 重试 {{ item.attempts }} 次 · {{ item.lastError || '—' }}</div>
        </div>
      </div>
      <div v-if="!(outbox?.items ?? []).length" class="empty">还没有收到业务变化记录</div>
    </div>

    <RelationTypeEditorDialog
      :open="editorOpen"
      :relation="editing"
      :object-types="objectTypeOptions"
      :mapping-refs="mappingRefs"
      :saving="saving"
      :errors="formErrors"
      @close="closeEditor"
      @submit="submitRelation"
    />

    <ObjectTypeEditorDialog
      :open="objectEditorOpen"
      :object-type="editingObject"
      :saving="objectSaving"
      :errors="objectFormErrors"
      @close="objectEditorOpen = false"
      @submit="submitObject"
    />

    <div v-if="deleteImpact" class="modal-mask" @click.self="deleteImpact = null">
      <div class="modal">
        <h3>删除前先看看影响：{{ relationName(deleteImpact?.relationId ?? '') }}</h3>
        <p class="hint">{{ deleteImpact?.impact?.hint }}</p>
        <div v-if="(deleteImpact?.impact?.referencedByMappings?.length ?? 0) > 0">
          <b>下面这些配置还在用它：</b>
          <ul class="list-plain">
            <li v-for="(ref, index) in deleteImpact?.impact?.referencedByMappings ?? []" :key="index">
              {{ objectTypeLabel(String(ref.objectType ?? '')) }} · {{ ref.rule }}
            </li>
          </ul>
        </div>
        <div style="margin-top:8px">
          <b>会受影响的对象类别：</b>{{ (deleteImpact?.impact?.impactedObjectTypes ?? []).map(id => objectTypeLabel(String(id))).join('、') || '—' }}
        </div>
        <p v-if="(deleteImpact?.impact?.referencedByMappings?.length ?? 0) > 0"
           class="error-banner" style="margin-top:12px">
          这条关系还在被上面的配置使用。直接删除会让下次加载失败并退回最初设置，改动就不生效了。
          请先在来源配置里去掉对应规则再回来删除；点「仍然尝试删除」通常会看到明确拒绝原因。
        </p>
        <div class="toolbar" style="margin-top:12px; margin-bottom:0">
          <span class="spacer"></span>
          <button @click="deleteImpact = null">取消</button>
          <button class="danger" @click="confirmForceDelete">仍然尝试删除（通常会失败）</button>
        </div>
      </div>
    </div>

    <div v-if="resetConfirmOpen" class="modal-mask" @click.self="resetConfirmOpen = false">
      <div class="modal">
        <h3>撤销全部在线修改，恢复最初设置</h3>
        <p>会删除你保存的修改，让 {{ relationRows.length }} 条关系回到最初的设置；还要点「让改动生效」才会生效。</p>
        <p class="hint">旧设置会先自动备份，需要时请找管理员恢复。</p>
        <div class="toolbar" style="margin-top:12px; margin-bottom:0">
          <span class="spacer"></span>
          <button @click="resetConfirmOpen = false" :disabled="resetting">取消</button>
          <button class="danger" @click="doReset" :disabled="resetting">
            {{ resetting ? '处理中…' : '确认撤销（恢复最初设置）' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="objectResetConfirmOpen" class="modal-mask" @click.self="objectResetConfirmOpen = false">
      <div class="modal">
        <h3>撤销对象类别的在线修改</h3>
        <p>只撤销对象类别的修改，关系类别的修改和其它数据都不受影响；还要点「让改动生效」才会生效。</p>
        <p class="hint">旧设置会先自动备份，需要时请找管理员恢复。</p>
        <div class="toolbar" style="margin-top:12px; margin-bottom:0">
          <span class="spacer"></span>
          <button @click="objectResetConfirmOpen = false" :disabled="objectResetting">取消</button>
          <button class="danger" @click="doObjectReset" :disabled="objectResetting">
            {{ objectResetting ? '处理中…' : '确认恢复对象基线（撤销修改）' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
