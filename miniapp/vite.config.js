import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// uni-app 统一构建入口：小程序/H5 由 -p 参数区分，配置本身共用。
// H5 dev server 代理 /api 到本地后端，避免浏览器跨域（仅开发期生效，不影响小程序构建）。
// 端口用环境变量 YOUYU_API_PORT 覆盖，默认 8090（与 deploy/dev-remote-db.conf 的 PORT 一致）。
// 同机若还跑着 lodestar(8080) 或旧的 youyu 进程，务必确认这里指向的是你真正在调试的那一个——
// 打到一个残留的旧进程上会得到「接口存在但报 500」这种最难判断的现象。
const apiPort = process.env.YOUYU_API_PORT || '8090'

// PWA 静态资源（manifest.webmanifest / sw.js / icons）放在 miniapp/public/，
// 由 vite 原样拷贝到 H5 产物根目录（线上即 /app/ 下），不参与打包与指纹重写。
// 小程序端不需要这些文件，且小程序有主包体积限制，故编译到 mp-* 平台时关掉 publicDir。
// 取值刻意「默认开启、仅小程序关闭」：万一 UNI_PLATFORM 未注入，也不会让 H5 端悄悄丢掉 PWA 文件。
const isMiniProgram = (process.env.UNI_PLATFORM || '').startsWith('mp-')

export default defineConfig({
  publicDir: isMiniProgram ? false : 'public',
  plugins: [uni()],
  server: {
    proxy: {
      '/api': {
        target: `http://127.0.0.1:${apiPort}`,
        changeOrigin: true
      }
    }
  }
})
