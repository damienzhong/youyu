import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// uni-app 统一构建入口：小程序/H5 由 -p 参数区分，配置本身共用。
// H5 dev server 代理 /api 到本地后端，避免浏览器跨域（仅开发期生效，不影响小程序构建）。
// 端口用环境变量 YOUYU_API_PORT 覆盖，默认 8090（与 deploy/dev-remote-db.conf 的 PORT 一致）。
// 同机若还跑着 lodestar(8080) 或旧的 youyu 进程，务必确认这里指向的是你真正在调试的那一个——
// 打到一个残留的旧进程上会得到「接口存在但报 500」这种最难判断的现象。
const apiPort = process.env.YOUYU_API_PORT || '8090'

export default defineConfig({
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
