import { createRouter, createWebHashHistory } from 'vue-router'
import Overview from './views/Overview.vue'
import ObjectExplorer from './views/ObjectExplorer.vue'
import ObjectDetail from './views/ObjectDetail.vue'
import RelationExplorer from './views/RelationExplorer.vue'
import ChangeImpact from './views/ChangeImpact.vue'
import ModelAdmin from './views/ModelAdmin.vue'

/**
 * 5 部分页面 + 总览。全部路由用 query 传 globalId，
 * 避免 gid:{sourceId}:{type}:{pk} 中的冒号在路径分段里被不同框架解码不一致；
 * 同时天然保留筛选/分页的查询上下文（E2）。
 */
const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/', name: 'overview', component: Overview, meta: { title: '全景总览' } },
    { path: '/explorer', name: 'explorer', component: ObjectExplorer, meta: { title: '对象探索器' } },
    { path: '/detail', name: 'detail', component: ObjectDetail, meta: { title: '对象详情' } },
    { path: '/relations', name: 'relations', component: RelationExplorer, meta: { title: '关系探索' } },
    { path: '/changes', name: 'changes', component: ChangeImpact, meta: { title: '变化与影响' } },
    { path: '/admin', name: 'admin', component: ModelAdmin, meta: { title: '模型与接入管理' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

export default router
