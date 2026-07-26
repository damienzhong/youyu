<script setup lang="ts">
/**
 * 登录页：表单校验 + 令牌/用户存储 + 登录后跳转。
 *
 * - 前端先做长度校验（账号 1–64、口令 8–64），逐字段内联提示（需求 1.1/1.5）。
 * - 成功后把 JWT 令牌与用户摘要写入会话（内存 + localStorage），
 *   并跳转到守卫记录的 redirect 或首页。
 * - 后端错误码经 toAuthFeedback 映射为友好中文并归位到对应字段。
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
  <div class="auth container">
    <div class="card auth-card">
      <h1 class="title">有余</h1>
      <p class="text-muted slogan">记好每一笔，日子有余</p>

      <p v-if="registeredNotice" class="notice">{{ registeredNotice }}</p>

      <form novalidate @submit.prevent="onSubmit">
        <label class="field">
          <span>账号</span>
          <input
            v-model="username"
            type="text"
            autocomplete="username"
            :class="{ invalid: errors.username }"
            :aria-invalid="!!errors.username"
            @input="errors.username = ''"
          />
          <small v-if="errors.username" class="field-err">{{ errors.username }}</small>
        </label>

        <label class="field">
          <span>口令</span>
          <input
            v-model="password"
            type="password"
            autocomplete="current-password"
            :class="{ invalid: errors.password }"
            :aria-invalid="!!errors.password"
            @input="errors.password = ''"
          />
          <small v-if="errors.password" class="field-err">{{ errors.password }}</small>
        </label>

        <p v-if="errors.form" class="err" role="alert">{{ errors.form }}</p>

        <button class="btn btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '登录中…' : '登录' }}
        </button>
      </form>

      <p class="switch text-muted">
        还没有账号？<RouterLink to="/register">去注册</RouterLink>
      </p>
    </div>
  </div>
</template>

<style scoped>
.auth {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding-block: 24px;
}
.auth-card {
  width: 100%;
  max-width: 380px;
}
.title {
  margin: 0;
  color: var(--color-primary);
}
.slogan {
  margin: 4px 0 20px;
}
.notice {
  margin: 0 0 16px;
  padding: 8px 12px;
  border-radius: var(--radius);
  background: #ecfdf5;
  color: var(--color-primary-dark);
  font-size: 14px;
}
.field {
  display: block;
  margin-bottom: 14px;
}
.field span {
  display: block;
  margin-bottom: 6px;
  font-size: 14px;
}
.field input {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font: inherit;
}
.field input.invalid {
  border-color: var(--color-danger);
}
.field-err {
  display: block;
  margin-top: 6px;
  color: var(--color-danger);
  font-size: 13px;
}
.err {
  color: var(--color-danger);
  font-size: 14px;
  margin: 0 0 12px;
}
.switch {
  margin-top: 16px;
  font-size: 14px;
  text-align: center;
}
.switch a {
  color: var(--color-primary);
}
</style>
