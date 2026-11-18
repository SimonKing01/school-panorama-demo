<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import type { ObjectTypeEditDef } from '../types'

/**
 * 对象类型在线编辑表单（C 档）：只开放六个展示层字段。
 *
 * <p>id / identity / sourceMappingRef / attributes / derivedProperties / dynamicResolvers
 * 全部只读带回，后端会逐项比对；界面明确写出「为什么不能改」，避免用户误以为漏了输入框。
 * 校验与生效都在后端：保存只写草稿，点「应用到运行时」才重启生效。</p>
 */
const props = defineProps<{
  open: boolean
  objectType: ObjectTypeEditDef | null
  saving: boolean
  errors: string[]
}>()

const emit = defineEmits<{
  (event: 'close'): void
  (event: 'submit', value: ObjectTypeEditDef): void
}>()

function emptyForm(): ObjectTypeEditDef {
  return {
    id: '',
    displayName: '',
    description: '',
    titleTemplate: '',
    listFields: [],
    searchableFields: [],
    filterableFields: [],
    identity: { sourceId: '', sourceTable: '', sourcePk: '', globalIdRule: '' },
    attributes: [],
    derivedProperties: [],
    dynamicResolvers: [],
    sourceMappingRef: '',
  }
}

const form = reactive<ObjectTypeEditDef>(emptyForm())

/** 三个字段列表的候选项：attributes ∪ derivedProperties，标签带中文 displayName。 */
const fieldOptions = computed<{ name: string; displayName: string; origin: string }[]>(() => {
  const rows: { name: string; displayName: string; origin: string }[] = []
  const seen = new Set<string>()
  for (const attr of form.attributes ?? []) {
    if (attr?.name && !seen.has(attr.name)) {
      seen.add(attr.name)
      rows.push({ name: attr.name, displayName: attr.displayName || attr.name, origin: 'SOURCE' })
    }
  }
  for (const derived of form.derivedProperties ?? []) {
    if (derived?.name && !seen.has(derived.name)) {
      seen.add(derived.name)
      rows.push({ name: derived.name, displayName: derived.displayName || derived.name, origin: 'DERIVED' })
    }
  }
  // 存量筛选项（如 schoolId / classId）是来源列而不是展示属性：保留勾选，防止保存后丢失。
  for (const name of form.filterableFields ?? []) {
    if (name && !seen.has(name)) {
      seen.add(name)
      rows.push({ name, displayName: `${name}（原有字段）`, origin: 'LEGACY' })
    }
  }
  return rows
})

function load(): void {
  const source = props.objectType
  Object.assign(form, emptyForm())
  if (source) {
    // 深拷贝：编辑过程中的勾选不能污染管理页当前展示的定义。
    const clone = JSON.parse(JSON.stringify(source)) as ObjectTypeEditDef
    Object.assign(form, clone)
  }
}

watch(() => [props.open, props.objectType], () => {
  if (props.open) load()
}, { immediate: true })

function submit(): void {
  if (props.saving) return
  emit('submit', {
    ...JSON.parse(JSON.stringify(form)) as ObjectTypeEditDef,
    displayName: form.displayName.trim(),
    titleTemplate: form.titleTemplate.trim(),
  })
}
</script>

<template>
  <div v-if="open" class="modal-mask" @click.self="emit('close')">
    <div class="modal">
      <h3>编辑对象类别：{{ form.displayName || form.id }}</h3>

      <p v-if="errors.length" class="error-banner">
        <b>有几处需要修改（共 {{ errors.length }} 条）：</b>
        <span v-for="(item, index) in errors" :key="index" style="display:block">· {{ item }}</span>
      </p>

      <h4>1. 固定信息（这里不能改）</h4>
      <div class="readonly-grid">
        <span>类别编号</span><b>{{ form.id || '—' }}</b>
        <span>来源表 / 主键</span><b>{{ form.identity?.sourceTable || '—' }} / {{ form.identity?.sourcePk || '—' }}</b>
        <span>来源系统</span><b>{{ form.identity?.sourceId || '—' }}</b>
        <span>编号生成规则</span><b>{{ form.identity?.globalIdRule || '—' }}</b>
        <span>对应哪份来源配置</span><b>{{ form.sourceMappingRef || '—' }}</b>
        <span>原始字段</span>
        <b>
          <span v-for="attr in form.attributes ?? []" :key="attr.name" class="chip">
            {{ attr.displayName }}（{{ attr.name }}）
          </span>
          <span v-if="!(form.attributes ?? []).length">—</span>
        </b>
        <span>计算字段</span>
        <b>
          <span v-for="item in form.derivedProperties ?? []" :key="item.name" class="chip">
            {{ item.displayName }}（{{ item.name }}）
          </span>
          <span v-if="!(form.derivedProperties ?? []).length">—</span>
        </b>
        <span>按时间变化的信息</span><b>{{ (form.dynamicResolvers ?? []).join('、') || '—' }}</b>
      </div>
      <p class="hint">
        这些信息决定对象身份和字段来源，改了会让历史记录对不上、页面出现空列。
        确实需要调整时，请联系管理员修改来源配置并重新导入数据；这个页面改不了。
      </p>

      <h4>2. 可以修改的显示设置</h4>
      <div class="field">
        <label>显示名称（按钮和菜单上给用户看的名字）</label>
        <input v-model="form.displayName" type="text" placeholder="如 学生" />
      </div>
      <div class="field">
        <label>类别说明（一句话说清这类对象是什么）</label>
        <textarea v-model="form.description" rows="2" placeholder="如 学校里的学生"></textarea>
      </div>
      <div class="field">
        <label>标题格式（用 {字段名} 表示要显示的字段，如 {code} · {name}）</label>
        <input v-model="form.titleTemplate" type="text" placeholder="如 {code} · {name}" />
      </div>

      <div class="field">
        <label>列表显示列（勾选哪些字段、按什么顺序显示）</label>
        <div class="checkbox-grid">
          <label v-for="field in fieldOptions" :key="field.name" class="check">
            <input v-model="form.listFields" type="checkbox" :value="field.name" />
            {{ field.displayName }}<span class="mono"> {{ field.name }}</span>
          </label>
          <span v-if="!fieldOptions.length" class="hint">（这类对象暂时没有可勾选的字段）</span>
        </div>
      </div>

      <div class="field">
        <label>可搜索字段（输入关键字时会搜这些字段）</label>
        <div class="checkbox-grid">
          <label v-for="field in fieldOptions" :key="field.name" class="check">
            <input v-model="form.searchableFields" type="checkbox" :value="field.name" />
            {{ field.displayName }}<span class="mono"> {{ field.name }}</span>
          </label>
        </div>
      </div>

      <div class="field">
        <label>可筛选字段（可以按哪些字段筛选；新增的必须是上面已有的字段）</label>
        <div class="checkbox-grid">
          <label v-for="field in fieldOptions" :key="field.name" class="check">
            <input v-model="form.filterableFields" type="checkbox" :value="field.name" />
            {{ field.displayName }}<span class="mono"> {{ field.name }}</span>
          </label>
        </div>
      </div>

      <div class="toolbar" style="margin-top:14px; margin-bottom:0">
        <span class="spacer"></span>
        <button @click="emit('close')" :disabled="saving">取消</button>
        <button class="primary" @click="submit" :disabled="saving">
          {{ saving ? '正在暂存…' : '暂存修改' }}
        </button>
      </div>
      <div class="hint" style="margin-top:8px">
        保存只是暂存，不会马上生效；点「让改动生效」后服务重启约 10 秒才会用上新设置。
        如果改了标题格式，还要点「重新生成页面数据」，已存对象的标题才会跟着更新。
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

.field input[type='text'],
.field select,
.field textarea {
  width: 100%;
}

.readonly-grid {
  display: grid;
  grid-template-columns: 130px 1fr;
  gap: 4px 8px;
  padding: 8px 10px;
  background: var(--soft, #f8fafc);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 12px;
}

.readonly-grid > span {
  color: var(--muted);
}

.chip {
  display: inline-block;
  margin: 0 6px 2px 0;
  padding: 1px 5px;
  border: 1px solid var(--line);
  border-radius: 4px;
  font-size: 11px;
}

.checkbox-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(190px, 1fr));
  gap: 4px 10px;
  max-height: 132px;
  overflow-y: auto;
  border: 1px solid var(--line);
  border-radius: 6px;
  padding: 6px 8px;
}

.check {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  cursor: pointer;
}

.check input {
  width: auto;
}

.mono {
  color: var(--muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
