<script setup lang="ts">
/**
 * 登录页：表单校验 + 令牌/用户存储 + 登录后跳转。
 *
 * - 前端先做长度校验（账号 1–64、密码 8–64），逐字段内联提示（需求 1.1/1.5）。
 * - 成功后把 JWT 令牌与用户摘要写入会话（内存 + localStorage），
 *   并跳转到守卫记录的 redirect 或首页。
 * - 后端错误码经 toAuthFeedback 映射为友好中文并归位到对应字段。
 *
 * 视觉：上半部品牌绿渐变区（logo + slogan），下半部白色表单卡片上翻承载输入，
 * 移动优先、适配 PWA 安全区。
 */
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { login, toAuthFeedback, validateCredentials, type AuthField } from '@/lib/auth'

const router = useRouter()
const route = useRoute()
const session = useSessionStore()

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const submitting = ref(false)

/** 注册成功跳转而来时的提示。 */
const registeredNotice = route.query.registered === '1' ? '注册成功，请登录' : ''

/** 按字段归类的错误：username / password 为内联字段错误，form 为整体错误。 */
const errors = reactive<Record<AuthField, string>>({ username: '', password: '', form: '' })

function clearErrors() {
  errors.username = ''
  errors.password = ''
  errors.form = ''
}

async function onSubmit() {
  clearErrors()

  const fieldErrors = validateCredentials(username.value, password.value)
  if (fieldErrors.username || fieldErrors.password) {
    errors.username = fieldErrors.username ?? ''
    errors.password = fieldErrors.password ?? ''
    return
  }

  submitting.value = true
  try {
    const result = await login({ username: username.value.trim(), password: password.value })
    session.signIn(result.token, result.user)
    const redirect = (route.query.redirect as string) || '/'
    router.replace(redirect)
  } catch (e) {
    const feedback = toAuthFeedback(e)
    errors[feedback.field] = feedback.message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="auth-page">
    <!-- 品牌区 -->
    <header class="hero">
      <div class="logo">¥</div>
      <div class="brand-name">有余</div>
      <p class="brand-slogan">记得清清楚楚，过得富富余余</p>
    </header>

    <!-- 表单卡片 -->
    <section class="sheet">
      <h1 class="title">欢迎回来</h1>
      <p class="sub">登录继续记账，让每一笔都心里有数</p>

      <p v-if="registeredNotice" class="notice">{{ registeredNotice }}</p>

      <form novalidate @submit.prevent="onSubmit">
        <div class="field">
          <label for="login-acct">账号</label>
          <div class="input" :class="{ invalid: errors.username }">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
            <input
              id="login-acct"
              v-model="username"
              type="text"
              placeholder="请输入账号"
              autocomplete="username"
              :aria-invalid="!!errors.username"
              @input="errors.username = ''"
            />
          </div>
          <small v-if="errors.username" class="field-err">{{ errors.username }}</small>
        </div>

        <div class="field">
          <label for="login-pwd">密码</label>
          <div class="input" :class="{ invalid: errors.password }">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
            <input
              id="login-pwd"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="请输入密码"
              autocomplete="current-password"
              :aria-invalid="!!errors.password"
              @input="errors.password = ''"
            />
            <button
              class="eye"
              type="button"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <svg v-if="showPassword" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.9 4.24A9.1 9.1 0 0 1 12 4c6.5 0 10 7 10 7a13.2 13.2 0 0 1-2.16 3.19M6.7 6.7A13.3 13.3 0 0 0 2 12s3.5 7 10 7a9 9 0 0 0 4.3-1.1"/><path d="M3 3l18 18"/></svg>
            </button>
          </div>
          <small v-if="errors.password" class="field-err">{{ errors.password }}</small>
        </div>

        <p v-if="errors.form" class="err" role="alert">{{ errors.form }}</p>

        <button class="btn-primary" type="submit" :disabled="submitting">
          <span v-if="submitting" class="spinner" aria-hidden="true"></span>
          <span>{{ submitting ? '登录中…' : '登录' }}</span>
        </button>
      </form>

      <p class="switch">
        还没有账号？<RouterLink to="/register">立即注册</RouterLink>
      </p>
    </section>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #fff;
}

/* 品牌区：对角双色绿渐变 + 柔光斑，顶部适配安全区 */
.hero {
  position: relative;
  flex: 0 0 auto;
  padding: calc(72px + env(safe-area-inset-top)) 32px 72px;
  color: #fff;
  overflow: hidden;
  background: linear-gradient(150deg, #35d07f 0%, #16a34a 46%, #0b6b34 100%);
}
.hero::before,
.hero::after {
  content: '';
  position: absolute;
  border-radius: 50%;
}
.hero::before { width: 240px; height: 240px; top: -90px; right: -70px; background: rgba(255, 255, 255, 0.14); }
.hero::after { width: 160px; height: 160px; bottom: -30px; left: -40px; background: rgba(255, 255, 255, 0.09); }

.logo {
  position: relative;
  z-index: 1;
  width: 62px;
  height: 62px;
  border-radius: 19px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid rgba(255, 255, 255, 0.3);
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.14);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34px;
  font-weight: 800;
  color: #fff;
}
.brand-name {
  position: relative;
  z-index: 1;
  margin-top: 20px;
  font-size: 30px;
  font-weight: 800;
  letter-spacing: 0.06em;
}
.brand-slogan {
  position: relative;
  z-index: 1;
  margin-top: 9px;
  font-size: 15px;
  color: rgba(255, 255, 255, 0.9);
}

/* 表单卡片：上翻覆盖渐变边界 */
.sheet {
  flex: 1 1 auto;
  margin-top: -28px;
  background: #fff;
  border-radius: 28px 28px 0 0;
  padding: 32px 26px calc(28px + env(safe-area-inset-bottom));
  position: relative;
  z-index: 2;
  box-shadow: 0 -3px 26px rgba(15, 23, 42, 0.06);
  animation: rise 0.45s cubic-bezier(0.16, 1, 0.3, 1) both;
}
@keyframes rise {
  from { opacity: 0; transform: translateY(14px); }
  to { opacity: 1; transform: none; }
}

.title { margin: 0; font-size: 21px; font-weight: 700; }
.sub { margin: 6px 0 22px; font-size: 13px; color: var(--color-muted); }

.notice {
  margin: 0 0 16px;
  padding: 9px 12px;
  border-radius: var(--radius);
  background: #ecfdf5;
  color: var(--color-primary-dark);
  font-size: 14px;
}

.field { margin-bottom: 15px; }
.field > label {
  display: block;
  margin-bottom: 7px;
  font-size: 13px;
  font-weight: 600;
  color: #475569;
}
.input {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 52px;
  padding: 0 14px;
  background: #f5f7f9;
  border: 1.5px solid transparent;
  border-radius: 14px;
  transition: border-color 0.18s, background 0.18s, box-shadow 0.18s;
}
.input:focus-within {
  background: #fff;
  border-color: var(--color-primary);
  box-shadow: 0 0 0 4px rgba(22, 163, 74, 0.13);
}
.input.invalid {
  border-color: var(--color-danger);
}
.input > svg {
  flex: 0 0 auto;
  width: 20px;
  height: 20px;
  color: #9aa4b0;
  transition: color 0.18s;
}
.input:focus-within > svg { color: var(--color-primary); }
.input input {
  flex: 1 1 auto;
  min-width: 0;
  height: 100%;
  border: none;
  outline: none;
  background: transparent;
  font: inherit;
  font-size: 16px;
  color: var(--color-text);
}
.input input::placeholder { color: #a9b2bd; }
.eye {
  flex: 0 0 auto;
  border: none;
  background: none;
  cursor: pointer;
  color: #9aa4b0;
  padding: 4px;
  display: flex;
  align-items: center;
}
.eye svg { width: 20px; height: 20px; }
.eye:hover { color: var(--color-muted); }

.field-err {
  display: block;
  margin-top: 6px;
  color: var(--color-danger);
  font-size: 13px;
}
.err {
  margin: 0 0 12px;
  color: var(--color-danger);
  font-size: 14px;
}

.btn-primary {
  width: 100%;
  height: 54px;
  margin-top: 4px;
  border: none;
  border-radius: 15px;
  cursor: pointer;
  background: linear-gradient(135deg, #1eb257, #128a3f);
  color: #fff;
  font-size: 16px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  box-shadow: 0 10px 24px rgba(22, 163, 74, 0.32);
  transition: transform 0.08s, filter 0.18s, box-shadow 0.18s;
}
.btn-primary:hover { filter: brightness(1.04); }
.btn-primary:active { transform: scale(0.985); }
.btn-primary:disabled { opacity: 0.7; cursor: default; }
.spinner {
  width: 18px;
  height: 18px;
  border: 2.5px solid rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.switch {
  margin-top: 22px;
  text-align: center;
  font-size: 14px;
  color: var(--color-muted);
}
.switch a { color: var(--color-primary); font-weight: 600; }
</style>
