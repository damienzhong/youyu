import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

// uni-app 统一构建入口：小程序/H5 由 -p 参数区分，配置本身共用。
export default defineConfig({
  plugins: [uni()]
})
