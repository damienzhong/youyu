<script setup lang="ts">
/**
 * 应用外壳：响应式导航。
 * - 窄屏（<768px）：底部标签栏，主区域上下滚动。
 * - 宽屏（≥768px）：左侧导航 + 右侧内容，最大宽度约束居中。
 * 全断点无横向滚动、无重叠（需求 11.1）。
 */
import { RouterView, RouterLink } from 'vue-router'

const navItems = [
  { to: '/', label: '首页', exact: true },
  { to: '/quick', label: '记一笔' },
  { to: '/transactions', label: '流水' },
  { to: '/reports', label: '报表' },
  { to: '/accounts', label: '账户' },
]
</script>

<template>
  <div class="shell">
    <aside class="shell-nav">
      <div class="brand">有余</div>
      <nav>
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-link"
          :class="{ active: $route.path === item.to }"
        >
          {{ item.label }}
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

/* 底部标签栏（移动优先默认态）。 */
.shell-nav {
  order: 2;
  position: sticky;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-around;
  gap: 4px;
  padding: 6px 8px calc(6px + var(--safe-bottom));
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
}

.shell-nav .brand {
  display: none;
}

.shell-main {
  order: 1;
  flex: 1;
  padding-block: 16px 24px;
}

.nav-link {
  flex: 1;
  text-align: center;
  padding: 8px 4px;
  border-radius: 10px;
  font-size: 13px;
  color: var(--color-muted);
}

.nav-link.active {
  color: var(--color-primary);
  font-weight: 600;
}

/* 宽屏：左侧固定导航。 */
@media (min-width: 768px) {
  .shell {
    flex-direction: row;
  }

  .shell-nav {
    order: 1;
    position: sticky;
    top: 0;
    align-self: flex-start;
    height: 100vh;
    width: 220px;
    flex-direction: column;
    justify-content: flex-start;
    align-items: stretch;
    gap: 4px;
    padding: 24px 16px;
    border-top: none;
    border-right: 1px solid var(--color-border);
  }

  .shell-nav .brand {
    display: block;
    font-size: 20px;
    font-weight: 700;
    color: var(--color-primary);
    padding: 8px 12px 20px;
  }

  .shell-main {
    order: 2;
    padding-block: 32px;
  }

  .nav-link {
    flex: none;
    text-align: left;
    padding: 10px 12px;
    font-size: 15px;
  }
}
</style>
