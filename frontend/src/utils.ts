import type { LessonView } from './types'

export function fmtDateTime(t?: string | null): string {
  if (!t) return '—'
  return t.replace('T', ' ')
}

export function lessonRange(l: LessonView): string {
  return `${l.startTime}–${l.endTime}`
}

export type TagType = 'success' | 'primary' | 'info' | 'warning' | 'danger'

export function statusTagType(status: string): TagType {
  switch (status) {
    case '正在上课':
      return 'success'
    case '即将开始':
      return 'primary'
    case '已结束':
      return 'info'
    default:
      return 'info'
  }
}

export function objectRoute(type: string, id: number | string): string {
  return `/object/${type}/${id}`
}

const TYPE_LABELS: Record<string, string> = {
  student: '学生',
  teacher: '教师',
  class: '班级',
  school: '学校',
  course: '课程',
  classroom: '教室',
}

export function typeLabel(type: string): string {
  return TYPE_LABELS[type] || type
}

// 时间戳 "2026-11-14T10:15:00" -> datetime-local 值 "2026-11-14T10:15"
export function toDatetimeLocal(demoTime: string): string {
  return demoTime ? demoTime.slice(0, 16) : ''
}

// datetime-local 值补秒，作为 POST /api/demo/clock 的 demoTime
export function fromDatetimeLocal(v: string): string {
  if (!v) return ''
  return v.length === 16 ? v + ':00' : v
}

// 无参防抖，用于输入即搜与表单实时预览
export function debounce(fn: () => void, wait = 300): () => void {
  let timer: ReturnType<typeof setTimeout> | null = null
  return () => {
    if (timer) clearTimeout(timer)
    timer = setTimeout(fn, wait)
  }
}
