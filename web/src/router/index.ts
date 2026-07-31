/**
 * 营销落地站路由。
 *
 * 本站点只承载面向访客的营销落地页与法律文档，全部公开；
 * 记账应用本体由 uni-app H5 承担，部署在同源子路径 /app/（见 nginx 配置），
 * 落地页的「开始记账 / 登录」按钮以普通链接跳转到 /app/。
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  { path: '/', component: () => import('@/views/LandingView.vue') },
  {
    path: '/legal/:doc(agreement|privacy)',
    component: () => import('@/views/LegalView.vue'),
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
