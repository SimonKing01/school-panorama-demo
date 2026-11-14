<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { apiGet } from '../api'
import { store } from '../store'
import { debounce, typeLabel } from '../utils'
import type { ObjectRow, SearchData } from '../types'
import LessonSummary from '../components/LessonSummary.vue'
import { useMobile } from '../composables/useMobile'

const route = useRoute()
const router = useRouter()
const isMobile = useMobile()

const keyword = ref(typeof route.query.q === 'string' ? route.query.q : '')
const type = ref<'all' | 'student' | 'teacher'>('all')
const schoolId = ref<number | ''>('')
const classId = ref<number | ''>('')
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
    const d = await apiGet<SearchData>('/search', {
      q: keyword.value.trim(),
      type: type.value,
      schoolId: schoolId.value === '' ? undefined : schoolId.value,
      classId: classId.value === '' ? undefined : classId.value,
    })
    items.value = d.items
    total.value = d.total
  } catch {
    // 后端启动期间静默，轮询会自动补齐
  } finally {
    loading.value = false
  }
}

const debouncedLoad = debounce(() => void load(), 300)

watch([keyword, type, schoolId, classId], () => debouncedLoad())
watch(() => store.refreshKey, () => void load())
onMounted(() => void load())
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="search-box">
        <el-input
          v-model="keyword"
          size="large"
          clearable
          placeholder="输入学生或教师姓名 / 编号，如 陈一诺、STU-0001、张明远"
          @keyup.enter="load"
        />
        <div class="muted small" style="text-align: center; margin-top: 8px">
          无需逐层进菜单，输入即搜；结果直接显示归属、当前课程与下一节课（含教师 / 时间 / 地点）
        </div>
      </div>

      <div class="toolbar" style="justify-content: center">
        <el-radio-group v-model="type">
          <el-radio-button value="all">全部</el-radio-button>
          <el-radio-button value="student">学生</el-radio-button>
          <el-radio-button value="teacher">教师</el-radio-button>
        </el-radio-group>
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
      </div>
    </div>

    <div class="card">
      <div class="card-header">
        <h2 class="card-title">查询结果</h2>
        <span class="card-sub">共 {{ total }} 条 · 点击行进入对象详情</span>
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
          <el-table-column label="归属" min-width="180">
            <template #default="{ row }">
              <div>{{ row.schoolName }}</div>
              <div class="muted small">{{ row.className || '—' }} {{ row.extra }}</div>
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
          <div class="mobile-item-row">{{ row.schoolName }} {{ row.className }} {{ row.extra }}</div>
          <div style="margin-top: 8px">
            <div class="muted small bold">当前课程</div>
            <LessonSummary :lesson="row.currentLesson" empty-text="当前无课" />
          </div>
          <div style="margin-top: 8px">
            <div class="muted small bold">下一节课</div>
            <LessonSummary :lesson="row.nextLesson" empty-text="今日课程已全部结束" />
          </div>
        </div>
        <div v-if="!items.length && !loading" class="empty">没有匹配的对象，换个姓名或编号试试</div>
      </div>

      <div v-if="!isMobile && !items.length && !loading" class="empty">没有匹配的对象，换个姓名或编号试试</div>
    </div>
  </div>
</template>

<style scoped>
.clickable-row {
  cursor: pointer;
}
</style>
