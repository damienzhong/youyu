import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import './styles/app.css'
import { useSessionStore } from './stores/session'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// 刷新/秒开后：若本地已有令牌，恢复当前用户摘要（GET /me），失败不阻断启动。
void useSessionStore().bootstrap()

app.mount('#app')
