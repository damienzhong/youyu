import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// uni-app 统一构建入口：小程序/H5 由 -p 参数区分，配置本身共用。
// H5 dev server 代理 /api 到本地后端，避免浏览器跨域（仅开发期生效，不影响小程序构建）。
export default defineConfig({
  plugins: [uni()],
  server: {
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true
      }
    }
  }
})
