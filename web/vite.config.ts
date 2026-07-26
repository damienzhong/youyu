import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { VitePWA } from 'vite-plugin-pwa'

// 「有余」前端 Vite 配置：Vue 3 + PWA（Workbox）。
// - 开发期 /api 代理到后端（Spring Boot 默认 8080）。
// - PWA：display standalone，可添加到主屏、独立窗口启动（需求 11.2 / 11.3）。
export default defineConfig({
  plugins: [
    vue(),
    VitePWA({
      registerType: 'autoUpdate',
      // 开发环境也启用 SW，便于本地验证「添加到主屏 / 独立窗口」。
      devOptions: { enabled: false },
      workbox: {
        // 应用外壳缓存 + 导航回退，支持独立窗口秒开。
        globPatterns: ['**/*.{js,css,html,svg,png,ico,woff2}'],
        navigateFallback: '/index.html',
        cleanupOutdatedCaches: true,
      },
      includeAssets: ['favicon.svg'],
      manifest: {
        name: '有余 记账',
        short_name: '有余',
        description: '记好每一笔，日子有余',
        lang: 'zh-CN',
        theme_color: '#16a34a',
        background_color: '#ffffff',
        display: 'standalone',
        start_url: '/',
        scope: '/',
        icons: [
          { src: 'pwa-192x192.png', sizes: '192x192', type: 'image/png' },
          { src: 'pwa-512x512.png', sizes: '512x512', type: 'image/png' },
          {
            src: 'pwa-512x512.png',
            sizes: '512x512',
            type: 'image/png',
            purpose: 'maskable',
          },
        ],
      },
    }),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5275,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    chunkSizeWarningLimit: 600,
  },
})
