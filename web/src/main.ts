import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './styles/app.css'

// 营销落地站：无需登录态与全局状态，仅挂载路由。
createApp(App).use(router).mount('#app')
