<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { api, qs } from '../api'
import { useModel } from '../composables/useModel'
import type { SearchData } from '../types'

const router = useRouter()
const { displayName } = useModel()
const q = ref('')
const results = ref<SearchData | null>(null)
const error = ref('')
const searching = ref(false)

/** 全局直达搜索：按模型定义的 searchableFields 跨所有已接入类型命中（US-01 / Q4）。 */
async function search(): Promise<void> {
  const keyword = q.value.trim()
  if (!keyword) {
    results.value = null
    return
  }
  searching.value = true
  try {
    results.value = await api.get<SearchData>('/search' + qs({ q: keyword }))
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '没查出来，请换个词或稍后再试'
  } finally {
    searching.value = false
  }
}

function open(globalId: string): void {
  results.value = null
  void router.push({ name: 'detail', query: { globalId } })
}
</script>

<template>
  <div class="global-search" style="position:relative; min-width:260px">
    <form style="display:flex; gap:6px" @submit.prevent="search">
      <input v-model="q" type="text" placeholder="输入姓名或编号，直接找到对象" style="width:260px; max-width:100%" />
      <button class="primary" type="submit" :disabled="searching" title="输入姓名或编号后点这里，会直接列出对应的对象">{{ searching ? '查找中…' : '查找' }}</button>
    </form>
    <div v-if="error" class="error-banner" style="margin-top:6px">{{ error }}</div>
    <div v-if="results" class="card" style="position:absolute; z-index:20; top:42px; left:0; width:min(420px, 92vw); max-height:320px; overflow:auto; margin:0">
      <div style="display:flex; justify-content:space-between; margin-bottom:8px">
        <b>找到 {{ results.total }} 个对象</b>
        <button type="button" @click="results = null">关闭</button>
      </div>
      <div v-if="results.items.length === 0" class="empty">没有找到匹配的对象，换个词再试</div>
      <ul class="list-plain">
        <li v-for="item in results.items" :key="item.globalId" @click="open(item.globalId)" style="cursor:pointer" title="点这一行看这个对象的详细信息">
          <b>{{ item.title }}</b>
          <span class="badge info" style="margin-left:6px">{{ displayName(item.type) }}</span>
          <div style="color:var(--muted); font-size:12px">
            在「{{ item.matchedField }}」里找到 · 数据来自 {{ item.sourceSummary.sourceTable || '—' }} / {{ item.sourceSummary.sourcePk || '—' }}
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>
