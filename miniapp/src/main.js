import { createSSRApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'

// uni-app 要求导出 createApp 工厂，由各端运行时调用。
export function createApp() {
  const app = createSSRApp(App)
  app.use(createPinia())
  return { app }
}
