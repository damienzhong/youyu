<script setup lang="ts">
/**
 * 注册页：表单校验 + 注册流转。
 *
 * - 前端先做长度校验（账号去空白 1–64、口令 8–64，对齐需求 1.1/1.3），逐字段内联提示。
 * - 注册成功：若后端直接返回令牌则自动登录进入首页，否则引导到登录页。
 * - 后端错误码（如 USERNAME_TAKEN / PASSWORD_WEAK / FIELD_REQUIRED）经 toAuthFeedback
 *   映射为友好中文并归位到对应字段。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { register, toAuthFeedback, validateCredentials, type AuthField } from '@/lib/auth'

const router = useRouter()
const session = useSessionStore()

const username = ref('')
const password = ref('')
const submitting = ref(false)

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
    const result = await register({ username: username.value.trim(), password: password.value })
    if (result.token) {
      // 后端支持注册即登录：写入会话直接进入首页。
      session.signIn(result.token, result.user)
      router.replace('/')
    } else {
      // 否则引导到登录页，并带上刚注册的账号提示。
      router.replace({ path: '/login', query: { registered: '1' } })
    }
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
      <h1 class="title">注册有余</h1>

      <form novalidate @submit.prevent="onSubmit">
        <label class="field">
          <span>账号（1–64 字符）</span>
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
          <span>口令（8–64 字符）</span>
          <input
            v-model="password"
            type="password"
            autocomplete="new-password"
            :class="{ invalid: errors.password }"
            :aria-invalid="!!errors.password"
            @input="errors.password = ''"
          />
          <small v-if="errors.password" class="field-err">{{ errors.password }}</small>
        </label>

        <p v-if="errors.form" class="err" role="alert">{{ errors.form }}</p>

        <button class="btn btn-block" type="submit" :disabled="submitting">
          {{ submitting ? '注册中…' : '注册' }}
        </button>
      </form>

      <p class="switch text-muted">
        已有账号？<RouterLink to="/login">去登录</RouterLink>
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
  margin: 0 0 20px;
  color: var(--color-primary);
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
