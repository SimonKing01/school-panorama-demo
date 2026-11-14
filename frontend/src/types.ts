// 与 docs/API-CONTRACT.md 一一对应的类型定义，字段名逐字保持一致。

export interface Ref {
  id: number
  code: string
  name: string
}

export interface LessonView {
  id: number
  code: string
  schoolId: number
  schoolName: string
  classId: number
  className: string
  courseId: number
  courseName: string
  subject: string
  teacherId: number
  teacherName: string
  classroomId: number
  classroomName: string
  lessonDate: string
  startTime: string
  endTime: string
  periodName: string
  studentCount: number
  status: string // 正在上课 | 即将开始 | 已结束
}

export interface ObjectRow {
  type: string
  id: number
  code: string
  name: string
  schoolId: number
  schoolName: string
  classId: number | null
  className: string
  subject: string
  extra: string
  currentLesson: LessonView | null
  nextLesson: LessonView | null
}

export interface MetaSchool {
  id: number
  code: string
  name: string
}
export interface MetaClass {
  id: number
  code: string
  name: string
  schoolId: number
}
export interface MetaTeacher {
  id: number
  code: string
  name: string
  schoolId: number
  subject: string
  substituteAvailable: boolean
}
export interface MetaCourse {
  id: number
  code: string
  name: string
  subject: string
}
export interface MetaClassroom {
  id: number
  code: string
  name: string
  schoolId: number
  capacity: number
}

export interface MetaOptions {
  demoTime: string
  schools: MetaSchool[]
  classes: MetaClass[]
  teachers: MetaTeacher[]
  courses: MetaCourse[]
  classrooms: MetaClassroom[]
  subjects: string[]
}

export interface ClockPreset {
  label: string
  demoTime: string
}
export interface ClockData {
  demoTime: string
  running: boolean
  lessonDate: string
  lessonEndTime: string
  presets: ClockPreset[]
}

export interface RevisionData {
  revision: number
  demoTime: string
  running: boolean
}

export interface DiffItem {
  field: string
  label: string
  before: string
  after: string
}

export interface Impact {
  studentCount: number
  studentIds: number[]
  classIds: number[]
  classNames: string[]
  teacherIds: number[]
  teacherNames: string[]
  classroomIds: number[]
  classroomNames: string[]
}

export interface ChangeItem {
  id: number
  sessionId: number
  sessionCode: string
  schoolId: number
  changeType: string
  reason: string
  operator: string
  occurredAt: string
  before: Record<string, unknown>
  after: Record<string, unknown>
  diff: DiffItem[]
  impact: Impact
  affectedStudentCount: number
  className: string
  courseName: string
}

export interface Conflict {
  type: string
  message: string
}

export interface OverviewCounts {
  schools: number
  teachers: number
  classes: number
  students: number
  courses: number
  classrooms: number
  sessionsToday: number
  changes: number
  currentLessons: number
}

export interface PerSchool {
  schoolId: number
  schoolName: string
  classCount: number
  teacherCount: number
  studentCount: number
  sessionsToday: number
  currentLessonCount: number
}

export interface OverviewData {
  demoTime: string
  counts: OverviewCounts
  perSchool: PerSchool[]
  currentLessons: LessonView[]
  todayLessons: LessonView[]
  recentChanges: ChangeItem[]
}

export interface SearchData {
  demoTime: string
  total: number
  items: ObjectRow[]
}

export interface ObjectsData {
  demoTime: string
  type: string
  total: number
  items: ObjectRow[]
}

export interface Attribute {
  label: string
  value: string
}
export interface Relation {
  type: string
  id: number
  code: string
  name: string
  role: string
}
export interface DetailData {
  demoTime: string
  type: string
  id: number
  code: string
  name: string
  school: (Ref & { type: string }) | null
  attributes: Attribute[]
  relations: Relation[]
  currentLesson: LessonView | null
  nextLesson: LessonView | null
  schedule: LessonView[]
  recentChanges: ChangeItem[]
  studentCount: number
}

export interface ScheduleStats {
  total: number
  ongoing: number
  upcoming: number
  finished: number
}
export interface ScheduleData {
  demoTime: string
  total: number
  items: LessonView[]
  stats: ScheduleStats
}

export interface ScheduleBefore {
  teacherId: number
  teacherName: string
  classroomId: number
  classroomName: string
  lessonDate: string
  startTime: string
  endTime: string
}
export interface CandidateTeacher {
  id: number
  code: string
  name: string
  schoolName: string
  subject: string
  substituteAvailable: boolean
  busy: boolean
}
export interface CandidateClassroom {
  id: number
  code: string
  name: string
  schoolName: string
  capacity: number
  busy: boolean
}
export interface FreeSlot {
  label: string
  startTime: string
  endTime: string
}
export interface ScheduleDetail {
  demoTime: string
  lesson: LessonView
  before: ScheduleBefore
  candidates: {
    teachers: CandidateTeacher[]
    classrooms: CandidateClassroom[]
    freeSlots: FreeSlot[]
  }
}

export interface AdjustBody {
  teacherId: number
  classroomId: number
  lessonDate: string
  startTime: string
  endTime: string
  reason: string
}

export interface PreviewData {
  demoTime: string
  valid: boolean
  conflicts: Conflict[]
  before: Record<string, unknown>
  after: Record<string, unknown>
  diff: DiffItem[]
  impact: Impact
}

export interface AdjustData {
  demoTime: string
  lesson: LessonView
  change: ChangeItem
  impact: Impact
  diff: DiffItem[]
  revision: number
}

export interface ChangesData {
  demoTime: string
  total: number
  items: ChangeItem[]
}
