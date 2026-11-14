<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { apiGet } from '../api'
import { store } from '../store'
import { fmtDateTime, statusTagType } from '../utils'
import type { LessonView, OverviewData } from '../types'

const router = useRouter()
const data = ref<OverviewData | null>(null)

function go(path: string): void {
  void router.push(path)
}

async function load(): Promise<void> {
  try {
    data.value = await apiGet<OverviewData>('/overview')
  } catch {
    // 轮询会在下一轮自动补齐
  }
}

onMounted(() => void load())
watch(() => store.refreshKey, () => void load())

interface StatCard {
  key: string
  label: string
  value: number
  to: string
  hint?: string
}

const statCards = computed<StatCard[]>(() => {
  const c = data.value?.counts
  return [
    { key: 'schools', label: '学校', value: c?.schools ?? 0, to: '/objects?type=school' },
    { key: 'teachers', label: '教师', value: c?.teachers ?? 0, to: '/objects?type=teacher' },
    { key: 'classes', label: '班级', value: c?.classes ?? 0, to: '/objects?type=class' },
    { key: 'students', label: '学生', value: c?.students ?? 0, to: '/objects?type=student' },
    { key: 'courses', label: '课程', value: c?.courses ?? 0, to: '/objects?type=course' },
    { key: 'classrooms', label: '教室', value: c?.classrooms ?? 0, to: '/objects?type=classroom' },
    { key: 'sessionsToday', label: '当日课程', value: c?.sessionsToday ?? 0, to: '/schedule', hint: '点击进入课表' },
    { key: 'currentLessons', label: '正在上课', value: c?.currentLessons ?? 0, to: '/schedule', hint: '按演示时间计算' },
    { key: 'changes', label: '最近变更', value: c?.changes ?? 0, to: '/changes' },
  ]
})

const currentLessons = computed<LessonView[]>(() => data.value?.currentLessons ?? [])
const recentChanges = computed(() => data.value?.recentChanges ?? [])

// 今日课程按学校分组，便于逐校查看
const groupedLessons = computed<Array<[string, LessonView[]]>>(() => {
  const map = new Map<string, LessonView[]>()
  for (const l of data.value?.todayLessons ?? []) {
    const arr = map.get(l.schoolName) ?? []
    arr.push(l)
    map.set(l.schoolName, arr)
  }
  return Array.from(map.entries())
})

const activeSchools = ref<string[]>([])

watch(groupedLessons, (list) => {
  if (activeSchools.value.length === 0 && list.length) {
    activeSchools.value = [list[0][0]]
  }
})
</script>

<template>
  <div class="page">
    <div class="card" style="padding: 12px 16px">
      <span class="muted">当前演示时间</span>
      <strong style="margin-left: 8px">{{ fmtDateTime(store.demoTime) }}</strong>
      <span class="muted" style="margin-left: 18px">
        当日共 {{ data?.counts.sessionsToday ?? 0 }} 节课，{{ data?.counts.currentLessons ?? 0 }} 节正在上课
      </span>
    </div>

    <div class="stat-grid section-gap">
      <div
        v-for="s in statCards"
        :key="s.key"
        class="stat-card"
        role="button"
        tabindex="0"
        @click="go(s.to)"
        @keyup.enter="go(s.to)"
      >
        <div class="stat-value">{{ s.value }}</div>
        <div class="stat-label">{{ s.label }}</div>
        <div v-if="s.hint" class="stat-hint">{{ s.hint }}</div>
      </div>
    </div>

    <div class="grid-2 section-gap">
      <div class="card" style="margin-bottom: 0">
        <div class="card-header">
          <h2 class="card-title">当前正在上课</h2>
          <span class="card-sub">按统一演示时间计算</span>
        </div>
        <div v-if="currentLessons.length" class="mobile-list">
          <div
            v-for="l in currentLessons"
            :key="l.id"
            class="mobile-item"
            @click="go(`/object/class/${l.classId}`)"
          >
            <div class="mobile-item-title">
              <span>{{ l.courseName }} · {{ l.className }}</span>
              <el-tag :type="statusTagType(l.status)" size="small" effect="light">{{ l.status }}</el-tag>
            </div>
            <div class="mobile-item-row">教师：{{ l.teacherName }} ｜ 地点：{{ l.classroomName }}</div>
            <div class="mobile-item-row">
              {{ l.lessonDate }} {{ l.startTime }}–{{ l.endTime }} · {{ l.periodName }} ｜ {{ l.studentCount }} 人
            </div>
          </div>
        </div>
        <div v-else class="empty">
          <span class="empty-strong">当前演示时间下无正在上课的课程</span>
          可能是上课前、课间，或今日课程已全部结束（演示时间 {{ fmtDateTime(store.demoTime) }}）
        </div>
      </div>

      <div class="card" style="margin-bottom: 0">
        <div class="card-header">
          <h2 class="card-title">最近变更记录</h2>
          <span class="link-primary" @click="go('/changes')">查看全部</span>
        </div>
        <div v-if="recentChanges.length" class="mobile-list">
          <div
            v-for="c in recentChanges"
            :key="c.id"
            class="mobile-item"
            @click="go(`/changes?id=${c.id}`)"
          >
            <div class="mobile-item-title">
              <span>{{ c.changeType }} · {{ c.className }} {{ c.courseName }}</span>
              <span class="muted small">{{ fmtDateTime(c.occurredAt) }}</span>
            </div>
            <div class="mobile-item-row">原因：{{ c.reason }}</div>
            <div class="mobile-item-row">受影响 {{ c.affectedStudentCount }} 名学生 · 操作人 {{ c.operator }}</div>
          </div>
        </div>
        <div v-else class="empty">暂无变更记录</div>
      </div>
    </div>

    <div class="card section-gap">
      <div class="card-header">
        <h2 class="card-title">今日课程概况（按学校分组）</h2>
        <span class="link-primary" @click="go('/schedule')">进入课表管理</span>
      </div>

      <el-collapse v-if="groupedLessons.length" v-model="activeSchools">
        <el-collapse-item
          v-for="[name, list] in groupedLessons"
          :key="name"
          :name="name"
          :title="`${name}（${list.length} 节）`"
        >
          <el-table :data="list" size="small" style="width: 100%" stripe>
            <el-table-column prop="periodName" label="节次" width="90" />
            <el-table-column label="时间" width="130">
              <template #default="{ row }">{{ row.startTime }}–{{ row.endTime }}</template>
            </el-table-column>
            <el-table-column prop="className" label="班级" min-width="120" />
            <el-table-column prop="courseName" label="课程" min-width="90" />
            <el-table-column prop="teacherName" label="教师" min-width="90" />
            <el-table-column prop="classroomName" label="教室" min-width="140" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusTagType(row.status)" size="small" effect="light">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-collapse-item>
      </el-collapse>
      <div v-else class="empty">
        <span class="empty-strong">当前演示时间下无课程安排</span>
        今日课程可能已全部结束，或尚未到上课时间
      </div>
    </div>
  </div>
</template>
