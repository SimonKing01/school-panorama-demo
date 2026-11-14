<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { apiGet, errorText } from '../api'
import { store } from '../store'
import { fmtDateTime, objectRoute, statusTagType, typeLabel } from '../utils'
import type { DetailData, LessonView } from '../types'
import LessonSummary from '../components/LessonSummary.vue'
import { useMobile } from '../composables/useMobile'

const route = useRoute()
const router = useRouter()
const isMobile = useMobile()

const objType = computed(() => String(route.params.type))
const objId = computed(() => Number(route.params.id))
const data = ref<DetailData | null>(null)

function go(path: string): void {
  void router.push(path)
}
function goBack(): void {
  if (window.history.length > 1) router.back()
  else void router.push('/objects')
}

async function load(): Promise<void> {
  try {
    data.value = await apiGet<DetailData>(`/objects/${objType.value}/${objId.value}`)
  } catch (e) {
    ElMessage.error(errorText(e))
    data.value = null
  }
}

watch([objType, objId, () => store.refreshKey], () => void load())
onMounted(() => void load())

const schedule = computed<LessonView[]>(() => data.value?.schedule ?? [])
const recentChanges = computed(() => data.value?.recentChanges ?? [])
</script>

<template>
  <div class="page">
    <div class="card">
      <div class="card-header">
        <div>
          <h2 class="card-title" style="font-size: 20px">{{ data?.name || '对象详情' }}</h2>
          <div class="muted small" style="margin-top: 4px">
            {{ typeLabel(data?.type || objType) }} · 编号 {{ data?.code || '—' }}
            <span v-if="data?.studentCount"> · 总人数 {{ data.studentCount }} 人</span>
          </div>
        </div>
        <el-button @click="goBack">返回</el-button>
      </div>

      <div class="muted small">
        当前演示时间 {{ fmtDateTime(store.demoTime) }} · 当前课程 / 下一节课按统一演示时间计算
      </div>
    </div>

    <div class="card">
      <div class="card-header"><h2 class="card-title">基本信息</h2></div>
      <el-descriptions :column="isMobile ? 1 : 3" border size="small">
        <el-descriptions-item label="名称">{{ data?.name || '—' }}</el-descriptions-item>
        <el-descriptions-item label="编号">{{ data?.code || '—' }}</el-descriptions-item>
        <el-descriptions-item v-if="data?.school" label="所属学校">
          <router-link class="link" :to="objectRoute('school', data?.school?.id ?? 0)">{{ data?.school?.name }}</router-link>
        </el-descriptions-item>
        <el-descriptions-item v-for="a in data?.attributes || []" :key="a.label" :label="a.label">
          {{ a.value }}
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="card">
      <div class="card-header">
        <h2 class="card-title">关联对象</h2>
        <span class="card-sub">点击标签跳转到对应对象详情</span>
      </div>
      <div v-if="data?.relations?.length">
        <span
          v-for="r in data.relations"
          :key="`${r.type}-${r.id}`"
          class="link-pill relation-tag"
          @click="go(objectRoute(r.type, r.id))"
        >
          {{ r.role }}：{{ r.name }}
        </span>
      </div>
      <div v-else class="empty">暂无关联对象</div>
    </div>

    <div class="grid-2">
      <div class="card" style="margin-bottom: 0">
        <div class="card-header"><h2 class="card-title">当前课程</h2></div>
        <div class="lesson-card primary">
          <div class="lesson-card-title">按演示时间 {{ fmtDateTime(store.demoTime) }} 判定</div>
          <LessonSummary :lesson="data?.currentLesson || null" empty-text="当前无课 / 今日课程已全部结束" />
        </div>
      </div>
      <div class="card" style="margin-bottom: 0">
        <div class="card-header"><h2 class="card-title">下一节课</h2></div>
        <div class="lesson-card">
          <div class="lesson-card-title">演示时间之后最近一次安排</div>
          <LessonSummary :lesson="data?.nextLesson || null" empty-text="今日课程已全部结束" />
        </div>
      </div>
    </div>

    <div class="card section-gap">
      <div class="card-header">
        <h2 class="card-title">完整课表</h2>
        <span class="link-primary" @click="go('/schedule')">进入课表管理</span>
      </div>

      <div v-if="!isMobile && schedule.length">
        <el-table :data="schedule" size="small" style="width: 100%" stripe>
          <el-table-column prop="lessonDate" label="日期" width="110" />
          <el-table-column prop="periodName" label="节次" width="90" />
          <el-table-column label="时间" width="130">
            <template #default="{ row }">{{ row.startTime }}–{{ row.endTime }}</template>
          </el-table-column>
          <el-table-column label="班级" min-width="120">
            <template #default="{ row }">
              <router-link class="link" :to="objectRoute('class', row.classId)">{{ row.className }}</router-link>
            </template>
          </el-table-column>
          <el-table-column label="课程" min-width="90">
            <template #default="{ row }">
              <router-link class="link" :to="objectRoute('course', row.courseId)">{{ row.courseName }}</router-link>
            </template>
          </el-table-column>
          <el-table-column label="教师" min-width="90">
            <template #default="{ row }">
              <router-link class="link" :to="objectRoute('teacher', row.teacherId)">{{ row.teacherName }}</router-link>
            </template>
          </el-table-column>
          <el-table-column label="教室" min-width="140">
            <template #default="{ row }">
              <router-link class="link" :to="objectRoute('classroom', row.classroomId)">{{ row.classroomName }}</router-link>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small" effect="light">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="studentCount" label="人数" width="70" />
        </el-table>
      </div>

      <div v-else-if="isMobile && schedule.length" class="mobile-list">
        <div v-for="l in schedule" :key="l.id" class="mobile-item">
          <div class="mobile-item-title">
            <span>{{ l.periodName }} · {{ l.courseName }}</span>
            <el-tag :type="statusTagType(l.status)" size="small" effect="light">{{ l.status }}</el-tag>
          </div>
          <div class="mobile-item-row">{{ l.lessonDate }} {{ l.startTime }}–{{ l.endTime }}</div>
          <div class="mobile-item-row">
            班级 <router-link class="link" :to="objectRoute('class', l.classId)">{{ l.className }}</router-link> ·
            课程 <router-link class="link" :to="objectRoute('course', l.courseId)">{{ l.courseName }}</router-link>
          </div>
          <div class="mobile-item-row">
            教师 <router-link class="link" :to="objectRoute('teacher', l.teacherId)">{{ l.teacherName }}</router-link> ·
            教室 <router-link class="link" :to="objectRoute('classroom', l.classroomId)">{{ l.classroomName }}</router-link>
          </div>
        </div>
      </div>

      <div v-else class="empty">
        <span class="empty-strong">当前演示时间下无课表安排</span>
        该对象今日暂无课程，或课程已全部结束
      </div>
    </div>

    <div class="card">
      <div class="card-header"><h2 class="card-title">最近变更</h2></div>
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
          <div class="mobile-item-row">受影响 {{ c.affectedStudentCount }} 名学生</div>
        </div>
      </div>
      <div v-else class="empty">暂无与该对象相关的变更记录</div>
    </div>
  </div>
</template>
