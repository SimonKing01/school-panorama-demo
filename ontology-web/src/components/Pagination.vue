<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{ page: number; size: number; total: number }>()
const emit = defineEmits<{ (e: 'change', page: number): void }>()

const pages = computed(() => Math.max(1, Math.ceil(props.total / Math.max(props.size, 1))))

function go(page: number): void {
  if (page >= 1 && page <= pages.value && page !== props.page) emit('change', page)
}
</script>

<template>
  <div style="display:flex; flex-wrap:wrap; gap:8px; align-items:center; margin-top:10px; font-size:13px">
    <span>共 {{ total }} 条 / 第 {{ page }} 页 / 共 {{ pages }} 页</span>
    <span class="spacer"></span>
    <button :disabled="page <= 1" @click="go(1)">首页</button>
    <button :disabled="page <= 1" @click="go(page - 1)">上一页</button>
    <button :disabled="page >= pages" @click="go(page + 1)">下一页</button>
    <button :disabled="page >= pages" @click="go(pages)">末页</button>
  </div>
</template>
