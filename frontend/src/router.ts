import { createRouter, createWebHistory } from 'vue-router'
import Overview from './views/Overview.vue'
import Search from './views/Search.vue'
import ObjectList from './views/ObjectList.vue'
import ObjectDetail from './views/ObjectDetail.vue'
import Schedule from './views/Schedule.vue'
import Changes from './views/Changes.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'overview', component: Overview, meta: { title: '全局总览' } },
    { path: '/search', name: 'search', component: Search, meta: { title: '对象查询' } },
    { path: '/objects', name: 'objects', component: ObjectList, meta: { title: '全局列表' } },
    { path: '/object/:type/:id', name: 'object-detail', component: ObjectDetail, meta: { title: '对象详情' } },
    { path: '/schedule', name: 'schedule', component: Schedule, meta: { title: '课表管理' } },
    { path: '/changes', name: 'changes', component: Changes, meta: { title: '变更记录' } },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})
