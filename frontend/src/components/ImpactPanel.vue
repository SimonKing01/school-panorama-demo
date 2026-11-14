<script setup lang="ts">
import { computed } from 'vue'
import type { Conflict, DiffItem, Impact } from '../types'

const props = withDefaults(
  defineProps<{
    diff: DiffItem[]
    impact: Impact | null
    conflicts: Conflict[]
    valid: boolean
    showLinks?: boolean
    hideValidBanner?: boolean
    title?: string
  }>(),
  { showLinks: false, hideValidBanner: false, title: '影响面板' }
)

const hasConflict = computed(() => props.conflicts.length > 0)

// 链接直接按影响对象 id 跳转到对应详情或筛选列表
function classId(): number | null {
  return props.impact?.classIds?.[0] ?? null
}
function teacherOldId(): number | null {
  return props.impact?.teacherIds?.[0] ?? null
}
function teacherNewId(): number | null {
  const ids = props.impact?.teacherIds ?? []
  return ids.length > 1 ? ids[ids.length - 1] : null
}
function classroomId(): number | null {
  return props.impact?.classroomIds?.[0] ?? null
}
</script>

<template>
  <div class="impact-panel">
    <div class="impact-section-title">{{ title }}</div>

    <div v-if="hasConflict" class="conflict-list">
      <div class="impact-section-title" style="color: var(--danger)">校验未通过（{{ conflicts.length }} 项）</div>
      <div v-for="(c, i) in conflicts" :key="i" class="conflict-item">· {{ c.message }}</div>
    </div>
    <div v-else-if="valid && !hideValidBanner" class="valid-banner">校验通过，可执行本次调整</div>

    <div class="impact-section-title">修改前后差异</div>
    <div v-if="diff.length">
      <div v-for="d in diff" :key="d.field" class="diff-row">
        <span class="diff-label">{{ d.label }}</span>
        <span class="diff-before">{{ d.before || '—' }}</span>
        <span class="diff-arrow">→</span>
        <span class="diff-after">{{ d.after || '—' }}</span>
      </div>
    </div>
    <div v-else class="muted small">未产生字段变化</div>

    <template v-if="impact">
      <div class="impact-section-title" style="margin-top: 14px">受影响范围</div>
      <div class="impact-kv">
        <div><span class="k">受影响学生</span><span class="v">{{ impact.studentCount }} 人</span></div>
        <div><span class="k">受影响班级</span><span class="v">{{ impact.classNames?.join('、') || '—' }}</span></div>
        <div><span class="k">受影响教师</span><span class="v">{{ impact.teacherNames?.join('、') || '—' }}</span></div>
        <div><span class="k">受影响教室</span><span class="v">{{ impact.classroomNames?.join('、') || '—' }}</span></div>
      </div>
    </template>

    <div v-if="showLinks && impact" class="link-row">
      <router-link v-if="classId()" class="link-pill" :to="`/objects?type=student&classId=${classId()}`">查看受影响学生</router-link>
      <router-link v-if="classId()" class="link-pill" :to="`/object/class/${classId()}`">班级详情</router-link>
      <router-link v-if="teacherOldId()" class="link-pill" :to="`/object/teacher/${teacherOldId()}`">原教师详情</router-link>
      <router-link v-if="teacherNewId()" class="link-pill" :to="`/object/teacher/${teacherNewId()}`">新教师详情</router-link>
      <router-link v-if="classroomId()" class="link-pill" :to="`/object/classroom/${classroomId()}`">教室详情</router-link>
      <router-link class="link-pill" to="/schedule">查看课表</router-link>
      <router-link class="link-pill" to="/changes">变更记录</router-link>
    </div>
  </div>
</template>
