<script setup lang="ts">
import type { ObjectDetail } from '../types'
import DynamicResultCard from './DynamicResultCard.vue'
import ChangeItemCard from './ChangeItemCard.vue'

/** 通用对象详情：属性数组 + 动态结果 + 正/反向关联 + 最近变化 + 来源追溯 + 返回原业务入口。 */
defineProps<{ detail: ObjectDetail; typeName: string }>()
const emit = defineEmits<{
  (e: 'open', globalId: string): void
  (e: 'relations'): void
  (e: 'changes'): void
}>()

function originLabel(origin: string): string {
  const map: Record<string, string> = {
    SOURCE: '来源直接存在',
    DERIVED: '映射或计算得到',
  }
  return map[origin] ?? origin
}
</script>

<template>
  <div>
    <div class="card">
      <div class="page-head">
        <div>
          <h2>{{ detail.title }}</h2>
          <div class="sub">{{ typeName }} · {{ detail.globalId }}</div>
        </div>
        <div style="display:flex; flex-wrap:wrap; gap:6px">
          <button @click="emit('relations')">关系探索</button>
          <button @click="emit('changes')">变化记录</button>
          <!-- A5：业务操作只跳转原业务页面/接口，平台不提供改投影的输入。 -->
          <a v-if="detail.backToBusinessUrl" :href="detail.backToBusinessUrl" target="_blank" rel="noopener">
            <button class="primary" type="button">返回原业务对象</button>
          </a>
          <button v-else type="button" disabled>请到原业务系统操作</button>
        </div>
      </div>
      <div class="hint">映射版本 {{ detail.mappingVersion || '—' }} · 全部为演示数据 / 演示时间</div>
    </div>

    <div class="card">
      <h3>当前属性（三类信息可辨识）</h3>
      <div class="kv">
        <template v-for="field in detail.attributes ?? []" :key="field.name">
          <div class="k">{{ field.displayName }}</div>
          <div class="v">
            {{ field.value === null || field.value === undefined ? '—' : (typeof field.value === 'object' ? JSON.stringify(field.value) : String(field.value)) }}
            <span :class="'badge ' + (field.origin === 'SOURCE' ? 'info' : 'warn')" style="margin-left:6px">
              {{ originLabel(field.origin) }}
            </span>
            <span v-if="field.provider || field.resolver" class="hint">
              （{{ field.provider || field.resolver }}）
            </span>
          </div>
        </template>
      </div>
    </div>

    <div class="card">
      <h3>动态结果（依赖演示时间，无写入也变化）</h3>
      <DynamicResultCard v-for="(result, index) in detail.dynamicResults ?? []" :key="index" :result="result" />
      <div v-if="!(detail.dynamicResults ?? []).length" class="empty">该类型未定义动态计算</div>
    </div>

    <div class="grid cols-2">
      <div class="card">
        <h3>正向关联（{{ (detail.relations?.forward ?? []).length }}）</h3>
        <ul class="list-plain">
          <li v-for="rel in detail.relations?.forward ?? []" :key="rel.relationId">
            <b>{{ rel.displayName }}</b> →
            <a href="#" @click.prevent="emit('open', rel.to.globalId)">{{ rel.to.title }}</a>
            <span v-if="!rel.active" class="badge muted" style="margin-left:6px">已结束</span>
            <div class="hint">成因：{{ rel.reason }}</div>
          </li>
        </ul>
        <div v-if="!(detail.relations?.forward ?? []).length" class="empty">暂无正向关联</div>
      </div>
      <div class="card">
        <h3>反向关联（{{ (detail.relations?.reverse ?? []).length }}）</h3>
        <ul class="list-plain">
          <li v-for="rel in detail.relations?.reverse ?? []" :key="rel.relationId">
            <b>{{ rel.displayName }}</b> ←
            <a href="#" @click.prevent="emit('open', rel.from.globalId)">{{ rel.from.title }}</a>
            <span v-if="!rel.active" class="badge muted" style="margin-left:6px">已结束</span>
            <div class="hint">成因：{{ rel.reason }}</div>
          </li>
        </ul>
        <div v-if="!(detail.relations?.reverse ?? []).length" class="empty">暂无反向关联</div>
      </div>
    </div>

    <div class="card">
      <h3>来源追溯</h3>
      <div class="kv">
        <div class="k">来源系统</div><div class="v">{{ detail.source?.sourceId || '—' }}</div>
        <div class="k">来源表 / 主键</div><div class="v">{{ detail.source?.sourceTable || '—' }} / {{ detail.source?.sourcePk || '—' }}</div>
        <div class="k">来源编号</div><div class="v">{{ detail.source?.sourceCode || '—' }}</div>
        <div class="k">记录指纹</div><div class="v">{{ detail.source?.sourceHash || '—' }}</div>
        <div class="k">映射版本</div><div class="v">{{ detail.source?.mappingVersion || detail.mappingVersion || '—' }}</div>
      </div>
    </div>

    <div class="card">
      <h3>最近变化</h3>
      <ChangeItemCard v-for="item in detail.recentChanges ?? []" :key="item.changeId" :item="item" />
      <div v-if="!(detail.recentChanges ?? []).length" class="empty">暂无变化记录</div>
    </div>
  </div>
</template>
