import { defineConfig } from 'vitest/config'

// 前端最小测试基建：只跑不依赖 uni API 的纯逻辑（src/utils/*.test.js）。
// 刻意不复用 vite.config.js —— 那份配置带 @dcloudio/vite-plugin-uni 插件，
// 会把 uni-app 的编译期改写与平台条件编译带进测试进程；本文件存在即让 vitest
// 优先取它（vitest.config.* 优先于 vite.config.*），从而拿到干净的 node 环境。
// 不引入 jsdom/happy-dom：页面渲染与 uni.* 交互由手工验收清单覆盖，不在此自动化。
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/utils/**/*.test.js'],
    // utils/invite.js 及其测试由后续任务补齐，此前跑 test 不应判失败
    passWithNoTests: true
  }
})
