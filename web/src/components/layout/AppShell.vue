<script setup lang="ts">
/**
 * 应用外壳：响应式导航（与首页/落地页统一的品牌视觉）。
 * - 窄屏（<768px）：底部标签栏，图标在上、文字在下，主区域上下滚动。
 * - 宽屏（≥768px）：左侧固定导航（品牌标 + 图标化菜单 + 选中态），右侧内容居中约束。
 * 全断点无横向滚动、无重叠（需求 11.1）。
 */
import { RouterView, RouterLink } from 'vue-router'

const navItems = [
  { to: '/', label: '首页', icon: '🏠' },
  { to: '/quick', label: '记一笔', icon: '✏️' },
  { to: '/transactions', label: '流水', icon: '📄' },
  { to: '/reports', label: '报表', icon: '📊' },
  { to: '/accounts', label: '账户', icon: '💳' },
]
</script>

<template>
  <div class="shell">
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

/* ===== 底部标签栏（移动优先默认态） ===== */
.shell-nav {
  order: 2;
  position: sticky;
  bottom: 0;
  z-index: 30;
  display: flex;
  align-items: stretch;
  gap: 2px;
  padding: 6px 6px calc(6px + var(--safe-bottom));
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
  box-shadow: 0 -4px 16px rgba(15, 23, 42, 0.05);
}

/* 移动端隐藏品牌（顶部内容页各自有标题）。 */
.brand {
  display: none;
}

.nav {
  display: flex;
  flex: 1;
  gap: 2px;
}

.nav-link {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 2px;
  padding: 6px 2px;
  border-radius: 12px;
  color: var(--color-muted);
  font-size: 11px;
}
.nav-icon {
  font-size: 20px;
  line-height: 1.1;
}
.nav-label {
  font-weight: 600;
}
.nav-link.active {
  color: var(--color-primary);
}
.nav-link.active .nav-icon {
  transform: translateY(-1px);
}

.shell-main {
  order: 1;
  flex: 1;
  padding-block: 16px 24px;
}

/* ===== 宽屏：左侧固定导航 ===== */
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
    width: 230px;
    flex-direction: column;
    align-items: stretch;
    gap: 4px;
    padding: 22px 16px;
    border-top: none;
    border-right: 1px solid var(--color-border);
    box-shadow: none;
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
    flex-direction: column;
    gap: 4px;
  }

  .nav-link {
    flex: none;
    flex-direction: row;
    justify-content: flex-start;
    gap: 10px;
    padding: 10px 12px;
    border-radius: 11px;
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
  .nav-link.active .nav-icon {
    transform: none;
  }

  .shell-main {
    order: 2;
    padding-block: 32px;
  }
}
</style>
