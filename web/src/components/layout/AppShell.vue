<script setup lang="ts">
/**
 * 应用外壳：响应式导航。
 * - 移动端（<768px）：无底部标签栏（首页作为中枢：快捷入口 + 悬浮＋ + 「全部」链接直达各模块）；
 *   非首页的子页顶部提供一个返回条（返回上一页 / 首页）。记一笔为全屏页（flush，自带关闭）。
 * - 宽屏（≥768px）：左侧固定品牌导航（与首页视觉一致，web 端保留侧栏更易用）。
 * 全断点无横向滚动、无重叠（需求 11.1）。
 */
import { computed } from 'vue'
import { RouterView, RouterLink, useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const navItems = [
  { to: '/', label: '首页', icon: '🏠' },
  { to: '/quick', label: '记一笔', icon: '✏️' },
  { to: '/reports', label: '报表', icon: '📊' },
  { to: '/accounts', label: '账户', icon: '💳' },
]

// 移动端返回条：仅在非首页、且非全屏页（记一笔自带关闭）时显示。
const showBackBar = computed(() => route.path !== '/' && !route.meta.flush)

function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}
</script>

<template>
  <div class="shell" :class="{ flush: $route.meta.flush }">
    <!-- 移动端子页返回条（PC 隐藏，用侧栏导航） -->
    <header v-if="showBackBar" class="mobile-back">
      <button type="button" class="back-btn" aria-label="返回" @click="goBack">←</button>
    </header>

    <!-- 侧边栏导航（仅 PC 显示） -->
    <aside class="shell-nav">
      <RouterLink to="/" class="brand">
        <span class="brand-mark">¥</span>
        <span class="brand-name">有余</span>
      </RouterLink>
      <nav class="nav">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          :class="{ active: $route.path === item.to }"
        >
          <span class="nav-icon">{{ item.icon }}</span>
          <span class="nav-label">{{ item.label }}</span>
        </RouterLink>
      </nav>
    </aside>

    <main class="shell-main">
      <div class="container">
        <RouterView />
      </div>
    </main>
  </div>
</template>

<style scoped>
.shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

/* ===== 移动端返回条 ===== */
.mobile-back {
  order: 0;
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: calc(6px + var(--safe-top)) 8px 6px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}
.back-btn {
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  font-size: 20px;
  color: var(--color-text);
}

/* ===== 侧边栏（移动端默认隐藏，PC 显示） ===== */
.shell-nav {
  display: none;
}
.brand {
  display: none;
}
.nav {
  display: none;
}

.shell-main {
  order: 1;
  flex: 1;
  min-width: 0;
  padding-block: 16px 24px;
}

/* flush 页面（记一笔）：移动端占满全屏（去内边距，自带关闭），无返回条 */
@media (max-width: 767px) {
  .shell.flush .shell-main {
    padding: 0;
  }
  .shell.flush .container {
    padding-inline: 0;
    max-width: none;
  }
}

/* ===== 宽屏：左侧固定导航 ===== */
@media (min-width: 768px) {
  .shell {
    flex-direction: row;
  }
  .mobile-back {
    display: none;
  }
  .shell-nav {
    order: 1;
    display: flex;
    flex-direction: column;
    position: sticky;
    top: 0;
    align-self: flex-start;
    height: 100vh;
    width: 230px;
    align-items: stretch;
    gap: 4px;
    padding: 22px 16px;
    border-right: 1px solid var(--color-border);
    background: var(--color-surface);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 10px 22px;
    font-size: 19px;
    font-weight: 800;
    color: var(--color-text);
  }
  .brand-mark {
    width: 32px;
    height: 32px;
    flex: 0 0 auto;
    border-radius: 9px;
    background: linear-gradient(135deg, #22c55e, #0f7a3a);
    color: #fff;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 18px;
    font-weight: 800;
  }
  .nav {
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .nav-link {
    display: flex;
    align-items: center;
    justify-content: flex-start;
    gap: 10px;
    padding: 10px 12px;
    border-radius: 11px;
    color: var(--color-muted);
    font-size: 15px;
    font-weight: 600;
  }
  .nav-icon {
    font-size: 18px;
  }
  .nav-link:hover:not(.active) {
    background: var(--color-bg);
  }
  .nav-link.active {
    background: #ecfdf3;
    color: var(--color-primary-dark);
  }
  .shell-main {
    order: 2;
    padding-block: 32px;
  }
}
</style>
