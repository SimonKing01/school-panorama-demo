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
      <h3>{{ isEdit ? '编辑关系类型：' + form.id : '新增关系类型' }}</h3>

      <p v-if="errors.length" class="error-banner">
        <b>校验未通过（{{ errors.length }} 条）：</b>
        <span v-for="(item, index) in errors" :key="index" style="display:block">· {{ item }}</span>
      </p>

      <h4>1. 基本信息</h4>
      <div class="field">
        <label>id（唯一标识，字母开头，仅字母/数字/下划线）</label>
        <input v-model="form.id" type="text" :disabled="isEdit" placeholder="如 session_mentored_by" />
      </div>
      <div class="field">
        <label>displayName（管理页展示名）</label>
        <input v-model="form.displayName" type="text" placeholder="如 授课安排-辅导教师" />
      </div>

      <h4>2. 起止对象类型</h4>
      <div class="field">
        <label>起点 fromType</label>
        <select v-model="form.fromType">
          <option value="">请选择</option>
          <option v-for="type in objectTypes" :key="type.id" :value="type.id">
            {{ type.displayName }}（{{ type.id }}）
          </option>
        </select>
      </div>
      <div class="field">
        <label>终点 toType</label>
        <select v-model="form.toType">
          <option value="">请选择</option>
          <option v-for="type in objectTypes" :key="type.id" :value="type.id">
            {{ type.displayName }}（{{ type.id }}）
          </option>
        </select>
      </div>

      <h4>3. 展示语义</h4>
      <div class="field">
        <label>正向展示名 forwardName（从起点看）</label>
        <input v-model="form.forwardName" type="text" placeholder="如 辅导教师" />
      </div>
      <div class="field">
        <label>反向展示名 reverseName（从终点看）</label>
        <input v-model="form.reverseName" type="text" placeholder="如 辅导安排" />
      </div>
      <div class="field">
        <label>方向 direction</label>
        <select v-model="form.direction">
          <option v-for="item in DIRECTIONS" :key="item" :value="item">{{ item }}</option>
        </select>
      </div>
      <div class="field">
        <label>基数 cardinality</label>
        <select v-model="form.cardinality">
          <option v-for="item in CARDINALITIES" :key="item" :value="item">{{ item }}</option>
        </select>
      </div>
      <div class="field inline">
        <label>
          <input v-model="form.businessEffectiveTime" type="checkbox" style="width:auto" />
          业务有效时间（按来源列推导 validFrom）
        </label>
      </div>

      <h4>4. 生成规则</h4>
      <div class="field">
        <label>generation.kind</label>
        <select v-model="form.generation.kind">
          <option v-for="item in KINDS" :key="item" :value="item">{{ item }}</option>
        </select>
      </div>
      <template v-if="!isJoinRule">
        <div class="field">
          <label>sourceTable（外键所在表）</label>
          <input v-model="form.generation.sourceTable" type="text" placeholder="如 lesson_sessions" />
        </div>
        <div class="field">
          <label>fkColumn（外键列）</label>
          <input v-model="form.generation.fkColumn" type="text" placeholder="如 teacher_id" />
        </div>
        <div class="field">
          <label>targetTable（目标表）</label>
          <input v-model="form.generation.targetTable" type="text" placeholder="如 teachers" />
        </div>
      </template>
      <template v-else>
        <div class="field">
          <label>sourceColumn（来源列）</label>
          <input v-model="form.generation.sourceColumn" type="text" placeholder="如 class_id" />
        </div>
        <div class="field">
          <label>joinTable（联表）</label>
          <input v-model="form.generation.joinTable" type="text" placeholder="如 lesson_sessions" />
        </div>
        <div class="field">
          <label>joinFilterColumn（联表过滤列）</label>
          <input v-model="form.generation.joinFilterColumn" type="text" placeholder="如 class_id" />
        </div>
        <div class="field">
          <label>joinSelectColumn（联表取值列）</label>
          <input v-model="form.generation.joinSelectColumn" type="text" placeholder="如 teacher_id" />
        </div>
        <div class="field">
          <label>targetTable（目标表）</label>
          <input v-model="form.generation.targetTable" type="text" placeholder="如 teachers" />
        </div>
        <div class="field">
          <label>rule（规则说明，给人看）</label>
          <input v-model="form.generation.rule" type="text" placeholder="如 student.class_id == lessonSession.class_id" />
        </div>
        <div class="field inline">
          <label>
            <input v-model="form.generation.distinct" type="checkbox" style="width:auto" />
            distinct（结果去重）
          </label>
        </div>
      </template>
      <div class="field">
        <label>validFromColumns（业务有效时间来源列，逗号分隔）</label>
        <input v-model="validFromText" type="text" placeholder="如 lesson_date, start_time" />
      </div>

      <h4>5. 来源映射</h4>
      <div class="field">
        <label>sourceMappingRef</label>
        <select v-model="form.sourceMappingRef">
          <option value="">（不指定）</option>
          <option v-for="ref in mappingRefs" :key="ref" :value="ref">{{ ref }}</option>
        </select>
      </div>

      <div class="toolbar" style="margin-top:14px; margin-bottom:0">
        <span class="spacer"></span>
        <button @click="emit('close')" :disabled="saving">取消</button>
        <button class="primary" @click="submit" :disabled="saving">
          {{ saving ? '保存中…' : '保存为草稿' }}
        </button>
      </div>
      <div class="hint" style="margin-top:8px">
        保存只写草稿并做与启动同一套校验；点「应用到运行时」后由本体服务重启加载新定义。
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
