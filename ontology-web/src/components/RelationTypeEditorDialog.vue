<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { RelationGenerationRule, RelationTypeEditDef } from '../types'

/**
 * 关系类型编辑表单（07 增量设计 §9.1）：5 组字段，按 generation.kind 动态显示必填项。
 * 只负责「收集并回传」，校验与生效全部在后端（保存通过 == 启动通过）。
 */
const props = defineProps<{
  open: boolean
  relation: RelationTypeEditDef | null
  objectTypes: { id: string; displayName: string }[]
  mappingRefs: string[]
  saving: boolean
  errors: string[]
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'submit', value: RelationTypeEditDef): void
}>()

const DIRECTIONS = ['OUT', 'IN']
const CARDINALITIES = ['N:1', '1:1', '1:N', 'N:M']
const KINDS = ['FOREIGN_KEY', 'JOIN_RULE']

// 选项「显示中文、提交原值」：v-model 与 :value 仍绑定原值，绝不改提交给后端的内容。
const DIRECTION_LABELS: Record<string, string> = { OUT: '正向（从起点看）', IN: '反向（从终点看）' }
const CARDINALITY_LABELS: Record<string, string> = {
  'N:1': '多对一（多个起点指向一个终点）',
  '1:1': '一对一',
  '1:N': '一对多',
  'N:M': '多对多',
}
const KIND_LABELS: Record<string, string> = {
  FOREIGN_KEY: '来源表外键（按外键字段自动关联）',
  JOIN_RULE: '按规则推算（按联表条件算出来）',
}

function emptyGeneration(): RelationGenerationRule {
  return {
    kind: 'FOREIGN_KEY',
    sourceTable: '',
    fkColumn: '',
    targetTable: '',
    rule: '',
    distinct: false,
    sourceColumn: '',
    joinTable: '',
    joinFilterColumn: '',
    joinSelectColumn: '',
    validFromColumns: [],
  }
}

function emptyForm(): RelationTypeEditDef {
  return {
    id: '',
    displayName: '',
    fromType: '',
    toType: '',
    forwardName: '',
    reverseName: '',
    direction: 'OUT',
    cardinality: 'N:1',
    businessEffectiveTime: false,
    generation: emptyGeneration(),
    sourceMappingRef: '',
  }
}

const form = reactive<RelationTypeEditDef>(emptyForm())
const isEdit = computed(() => Boolean(props.relation))
const isJoinRule = computed(() => form.generation.kind === 'JOIN_RULE')

const validFromText = computed({
  get: () => (form.generation.validFromColumns ?? []).join(', '),
  set: (value: string) => {
    form.generation.validFromColumns = value
      .split(',')
      .map(item => item.trim())
      .filter(item => item.length > 0)
  },
})

function load(): void {
  const source = props.relation
  Object.assign(form, emptyForm())
  if (source) {
    Object.assign(form, {
      ...source,
      generation: { ...emptyGeneration(), ...(source.generation ?? {}) },
    })
  }
}

watch(() => [props.open, props.relation], () => {
  if (props.open) load()
}, { immediate: true })

function submit(): void {
  if (props.saving) return
  emit('submit', {
    ...form,
    id: form.id.trim(),
    generation: {
      ...form.generation,
      validFromColumns: (form.generation.validFromColumns ?? []).filter(item => item !== ''),
    },
  })
}
</script>

<template>
  <div v-if="open" class="modal-mask" @click.self="emit('close')">
    <div class="modal">
      <h3>{{ isEdit ? '编辑关系：' + (form.displayName || form.id) : '新增关系类别' }}</h3>

      <p v-if="errors.length" class="error-banner">
        <b>有几处需要修改（共 {{ errors.length }} 条）：</b>
        <span v-for="(item, index) in errors" :key="index" style="display:block">· {{ item }}</span>
      </p>

      <h4>1. 基本信息</h4>
      <div class="field">
        <label>编号（系统内部使用，新增时自定义，不能改）</label>
        <input v-model="form.id" type="text" :disabled="isEdit" placeholder="如 session_mentored_by（字母开头，只用字母、数字、下划线）" />
      </div>
      <div class="field">
        <label>显示名称（页面上给用户看的名字）</label>
        <input v-model="form.displayName" type="text" placeholder="如 授课安排-辅导教师" />
      </div>

      <h4>2. 起止对象类型</h4>
      <div class="field">
        <label>从哪类对象出发</label>
        <select v-model="form.fromType">
          <option value="">请选择</option>
          <option v-for="type in objectTypes" :key="type.id" :value="type.id">
            {{ type.displayName }}（{{ type.id }}）
          </option>
        </select>
      </div>
      <div class="field">
        <label>指向哪类对象</label>
        <select v-model="form.toType">
          <option value="">请选择</option>
          <option v-for="type in objectTypes" :key="type.id" :value="type.id">
            {{ type.displayName }}（{{ type.id }}）
          </option>
        </select>
      </div>

      <h4>3. 展示语义</h4>
      <div class="field">
        <label>正向名称（从起点对象看，这条关系叫什么）</label>
        <input v-model="form.forwardName" type="text" placeholder="如 辅导教师" />
      </div>
      <div class="field">
        <label>反向名称（从终点对象看，这条关系叫什么）</label>
        <input v-model="form.reverseName" type="text" placeholder="如 辅导安排" />
      </div>
      <div class="field">
        <label>从哪边看</label>
        <select v-model="form.direction">
          <option v-for="item in DIRECTIONS" :key="item" :value="item">{{ DIRECTION_LABELS[item] }}</option>
        </select>
      </div>
      <div class="field">
        <label>对应关系（一个对象能对应几个另一对象）</label>
        <select v-model="form.cardinality">
          <option v-for="item in CARDINALITIES" :key="item" :value="item">{{ CARDINALITY_LABELS[item] }}</option>
        </select>
      </div>
      <div class="field inline">
        <label>
          <input v-model="form.businessEffectiveTime" type="checkbox" style="width:auto" />
          这条关系是否有开始／结束日期（从来源数据自动取）
        </label>
      </div>

      <h4>4. 生成规则</h4>
      <div class="field">
        <label>这条关系是怎么来的</label>
        <select v-model="form.generation.kind">
          <option v-for="item in KINDS" :key="item" :value="item">{{ KIND_LABELS[item] }}</option>
        </select>
      </div>
      <template v-if="!isJoinRule">
        <div class="field">
          <label>外键所在的数据表</label>
          <input v-model="form.generation.sourceTable" type="text" placeholder="如 lesson_sessions" />
        </div>
        <div class="field">
          <label>外键字段（存着对方编号的那一列）</label>
          <input v-model="form.generation.fkColumn" type="text" placeholder="如 teacher_id" />
        </div>
        <div class="field">
          <label>指向的数据表</label>
          <input v-model="form.generation.targetTable" type="text" placeholder="如 teachers" />
        </div>
      </template>
      <template v-else>
        <div class="field">
          <label>用哪个字段去匹配</label>
          <input v-model="form.generation.sourceColumn" type="text" placeholder="如 class_id" />
        </div>
        <div class="field">
          <label>中间表（帮忙牵线的那张表）</label>
          <input v-model="form.generation.joinTable" type="text" placeholder="如 lesson_sessions" />
        </div>
        <div class="field">
          <label>中间表里匹配本对象的字段</label>
          <input v-model="form.generation.joinFilterColumn" type="text" placeholder="如 class_id" />
        </div>
        <div class="field">
          <label>中间表里指向对方的字段</label>
          <input v-model="form.generation.joinSelectColumn" type="text" placeholder="如 teacher_id" />
        </div>
        <div class="field">
          <label>指向的数据表</label>
          <input v-model="form.generation.targetTable" type="text" placeholder="如 teachers" />
        </div>
        <div class="field">
          <label>规则说明（给维护人员看的，可留空）</label>
          <input v-model="form.generation.rule" type="text" placeholder="如 student.class_id == lessonSession.class_id" />
        </div>
        <div class="field inline">
          <label>
            <input v-model="form.generation.distinct" type="checkbox" style="width:auto" />
            结果去重（同一对象只显示一次）
          </label>
        </div>
      </template>
      <div class="field">
        <label>开始／结束日期取自哪些字段（多个用逗号隔开）</label>
        <input v-model="validFromText" type="text" placeholder="如 lesson_date, start_time" />
      </div>

      <h4>5. 来源映射</h4>
      <div class="field">
        <label>对应哪份来源配置（一般不改）</label>
        <select v-model="form.sourceMappingRef">
          <option value="">（不指定）</option>
          <option v-for="ref in mappingRefs" :key="ref" :value="ref">{{ ref }}</option>
        </select>
      </div>

      <div class="toolbar" style="margin-top:14px; margin-bottom:0">
        <span class="spacer"></span>
        <button @click="emit('close')" :disabled="saving">取消</button>
        <button class="primary" @click="submit" :disabled="saving">
          {{ saving ? '正在暂存…' : '暂存修改' }}
        </button>
      </div>
      <div class="hint" style="margin-top:8px">
        保存只是暂存，不会马上生效；点「让改动生效」后服务会重启约 10 秒，然后用上新设置。
      </div>
    </div>
  </div>
</template>

<style scoped>
h4 {
  margin: 14px 0 6px;
  font-size: 13px;
  color: var(--muted);
  font-weight: 600;
  border-bottom: 1px dashed var(--line);
  padding-bottom: 4px;
}

.field {
  display: grid;
  grid-template-columns: 1fr;
  gap: 4px;
  margin-bottom: 8px;
}

.field > label {
  color: var(--muted);
  font-size: 12px;
}

.field.inline > label {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: inherit;
}

.field input[type='text'],
.field select {
  width: 100%;
}
</style>
