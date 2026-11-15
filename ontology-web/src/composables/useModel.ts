import { computed } from 'vue'
import { api } from '../api'
import { store } from '../store'
import type { FieldDef, ModelTypesData, TypeDef } from '../types'

/**
 * 模型定义唯一来源：GET /model/types。
 * 类型列表、列表列、详情字段、可搜索/可筛选字段全部由此驱动，
 * 前端不为任何业务类型写专用分支或专用页面（B5/B6）。
 */
export function useModel() {
  async function load(): Promise<void> {
    const data = await api.get<ModelTypesData>('/model/types')
    store.types = data.types
    store.mappingVersion = data.mappingVersion
  }

  const types = computed<TypeDef[]>(() => store.types)

  function typeOf(id: string): TypeDef | undefined {
    return store.types.find((item) => item.id === id)
  }

  function listFields(id: string): FieldDef[] {
    return typeOf(id)?.listFields ?? []
  }

  function displayName(id: string): string {
    return typeOf(id)?.displayName ?? id
  }

  return { load, types, typeOf, listFields, displayName }
}
