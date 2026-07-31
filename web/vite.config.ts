import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 「有余」营销落地站 Vite 配置：Vue 3 纯静态站点。
// 记账应用本体是 uni-app H5，部署在同源 /app/；本站只服务落地页与法律文档。
// 不再使用 PWA/Service Worker：旧版本 web 曾在作用域 "/" 注册 SW，会拦截 /app/ 导航，
// 因此改由 public/sw.js（自注销脚本）让老客户端自愈，见该文件说明。
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5275,
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 600,
  },
})
