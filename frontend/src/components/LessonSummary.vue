<script setup lang="ts">
import type { LessonView } from '../types'
import { statusTagType } from '../utils'

withDefaults(
  defineProps<{
    lesson: LessonView | null
    emptyText?: string
    showStatus?: boolean
  }>(),
  { emptyText: '无', showStatus: true }
)
</script>

<template>
  <div v-if="lesson" class="lesson-summary">
    <div class="ls-title">
      {{ lesson.courseName }}
      <el-tag v-if="showStatus" :type="statusTagType(lesson.status)" size="small" effect="light">
        {{ lesson.status }}
      </el-tag>
    </div>
    <div class="ls-line">{{ lesson.className }} · {{ lesson.teacherName }} 老师</div>
    <div class="ls-line">{{ lesson.lessonDate }} {{ lesson.startTime }}–{{ lesson.endTime }} · {{ lesson.periodName }}</div>
    <div class="ls-line">地点：{{ lesson.classroomName }}</div>
  </div>
  <div v-else class="lesson-none">{{ emptyText }}</div>
</template>
