<script setup lang="ts">
import type { TypeDef } from '../types'

/** 类型导航完全来自 /model/types，用 v-for 渲染；没有任何业务类型专用分支。 */
defineProps<{ types: TypeDef[]; active: string }>()
const emit = defineEmits<{ (e: 'select', id: string): void }>()
</script>

<template>
  <div style="display:flex; flex-wrap:wrap; gap:6px; overflow-x:auto; -webkit-overflow-scrolling:touch">
    <button
      v-for="item in types"
      :key="item.id"
      :class="{ active: active === item.id }"
      :title="'查看' + item.displayName + '列表'"
      @click="emit('select', item.id)"
    >
      {{ item.displayName }}
    </button>
  </div>
</template>
