/**
 * 路由与全局守卫。
 *
 * 公开路由：/login、/register
 * 受保护路由（需登录）：/（首页）、/quick（记一笔）、/accounts、/transactions、
 *                       /categories、/reports、/export
 *
 * 守卫规则：
 *  - 未登录访问受保护路由 → 跳 /login
 *  - 已登录访问 /login、/register → 跳首页
 */
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useSessionStore } from '@/stores/session'

const routes: RouteRecordRaw[] = [
  { path: '/welcome', component: () => import('@/views/LandingView.vue'), meta: { public: true } },
  { path: '/login', component: () => import('@/views/auth/LoginView.vue'), meta: { public: true } },
  { path: '/register', component: () => import('@/views/auth/RegisterView.vue'), meta: { public: true } },
  {
    path: '/legal/:doc(agreement|privacy)',
    component: () => import('@/views/LegalView.vue'),
    meta: { public: true },
  },
  {
    path: '/',
    component: () => import('@/components/layout/AppShell.vue'),
    children: [
      { path: '', component: () => import('@/views/HomeView.vue') },
      // 记一笔：PC 保留侧边栏（与首页一致）；移动端隐藏底部 tab、占满全屏（flush）。
      { path: 'quick', component: () => import('@/views/QuickEntryView.vue'), meta: { flush: true } },
      { path: 'accounts', component: () => import('@/views/AccountsView.vue') },
      { path: 'categories', component: () => import('@/views/CategoriesView.vue') },
      { path: 'reports', component: () => import('@/views/ReportsView.vue') },
      { path: 'budget', component: () => import('@/views/BudgetView.vue') },
      { path: 'export', component: () => import('@/views/ExportView.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const session = useSessionStore()

  // 已登录访问落地/登录/注册页 → 回首页。
  if ((to.path === '/welcome' || to.path === '/login' || to.path === '/register') && session.isLoggedIn) {
    return '/'
  }

  if (to.meta.public) return true

  // 受保护路由未登录 → 先看落地页（访客一进来先被产品吸引，再引导注册/登录）。
  if (!session.isLoggedIn) {
    return '/welcome'
  }

  return true
})

export default router
