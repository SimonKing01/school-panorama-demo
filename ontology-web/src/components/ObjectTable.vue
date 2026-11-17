<script setup lang="ts">
import { fmt, type FieldDef, type ObjectRow } from '../types'

/**
 * 通用对象列表：列由模型定义的 listFields 用 v-for 生成，
 * items[].attributes 是字典，按字段名取值；没有任何业务类型专用列。
 *
 * loading 只叠加「正在加载…」提示，不清空已有行：
 * 请求期间把表格清成空白，用户看到的是页面闪一下又复原，同样会被当成「按钮没生效」（缺陷 2）。
 */
defineProps<{ fields: FieldDef[]; rows: ObjectRow[]; loading?: boolean }>()
const emit = defineEmits<{ (e: 'open', globalId: string): void }>()

function value(row: ObjectRow, name: string): string {
  return fmt(row.attributes?.[name])
}
</script>

<template>
  <div>
    <div v-if="loading" class="loading">正在加载…</div>
    <table class="desktop-only">
      <thead>
        <tr>
          <th>对象</th>
          <th v-for="field in fields" :key="field.name">{{ field.displayName }}</th>
          <th>来源</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="row in rows" :key="row.globalId" @click="emit('open', row.globalId)">
          <td>
            <b>{{ row.title }}</b>
            <div class="hint">{{ row.type }}</div>
          </td>
          <td v-for="field in fields" :key="field.name">
            <span class="cell-text">{{ value(row, field.name) }}</span>
          </td>
          <td class="hint">{{ row.sourceSummary?.sourceTable || '—' }} / {{ row.sourceSummary?.sourcePk || '—' }}</td>
        </tr>
      </tbody>
    </table>

    <!-- 390px 手机宽度：卡片列表替代表格，避免横向溢出 -->
    <div class="mobile-only">
      <div
        v-for="row in rows"
        :key="row.globalId"
        class="card"
        style="margin-bottom:8px; cursor:pointer"
        @click="emit('open', row.globalId)"
      >
        <b>{{ row.title }}</b> <span class="badge info">{{ row.type }}</span>
        <div class="kv" style="margin-top:8px">
          <template v-for="field in fields" :key="field.name">
            <div class="k">{{ field.displayName }}</div>
            <div class="v">{{ value(row, field.name) }}</div>
          </template>
          <div class="k">来源</div>
          <div class="v">{{ row.sourceSummary?.sourceTable || '—' }} / {{ row.sourceSummary?.sourcePk || '—' }}</div>
        </div>
      </div>
    </div>

    <!-- 加载中不显示空态文案，否则会先闪一行「没有对象数据」再出数据 -->
    <div v-if="rows.length === 0 && !loading" class="empty">没有对象数据</div>
  </div>
</template>
