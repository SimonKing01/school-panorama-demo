import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './style.css'

const app = createApp(App)
// 与现有业务前端同款依赖；本体平台组件以模型驱动的原生渲染为主，
// 不引入任何图库 / 搜索集群 / 业务类型专用组件。
app.use(ElementPlus)
app.use(router)
app.mount('#app')
