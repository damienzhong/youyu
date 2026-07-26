<script setup lang="ts">
/**
 * 注册页：表单校验 + 注册流转。
 *
 * - 前端实时校验：账号去空白 1–64、密码 8–64、两次密码一致（对齐需求 1.1/1.3），
 *   并要求勾选协议后才能提交（前端 UX 门控，后端只接收 username + password）。
 * - 注册成功：若后端返回令牌则自动登录进入首页，否则引导到登录页。
 * - 后端错误码（USERNAME_TAKEN / PASSWORD_WEAK / FIELD_REQUIRED 等）经 toAuthFeedback
 *   映射为友好中文并归位到对应字段。
 *
 * 视觉与登录页成套：品牌绿渐变区 + 表单卡片；注册特有确认密码、密码强度条、协议勾选。
 */
import { reactive, ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import { useSessionStore } from '@/stores/session'
import { register, toAuthFeedback, validateCredentials, type AuthField } from '@/lib/auth'

const router = useRouter()
const session = useSessionStore()

const username = ref('')
const password = ref('')
const password2 = ref('')
const agreed = ref(false)
const showPassword = ref(false)
const showPassword2 = ref(false)
const submitting = ref(false)

const errors = reactive<Record<AuthField, string>>({ username: '', password: '', form: '' })

// —— 前端实时校验 ——
const usernameValid = computed(() => {
  const u = username.value.trim()
  return u.length >= 1 && u.length <= 64
})
const passwordValid = computed(() => password.value.length >= 8 && password.value.length <= 64)
const matchValid = computed(() => password2.value.length > 0 && password.value === password2.value)
const canSubmit = computed(
  () => usernameValid.value && passwordValid.value && matchValid.value && agreed.value && !submitting.value,
)

/** 密码强度 0–3：长度达标 +1；字母且数字 +1；含符号且更长 +1。 */
const strengthScore = computed(() => {
  const v = password.value
  if (!v) return 0
  let s = 0
  if (v.length >= 8) s++
  if (/[a-zA-Z]/.test(v) && /\d/.test(v)) s++
  if (/[^a-zA-Z0-9]/.test(v) && v.length >= 10) s++
  return Math.min(s, 3)
})
const strengthLabel = computed(() =>
  !password.value ? '' : strengthScore.value <= 1 ? '强度：弱' : strengthScore.value === 2 ? '强度：中' : '强度：强',
)

function clearErrors() {
  errors.username = ''
  errors.password = ''
  errors.form = ''
}

async function onSubmit() {
  clearErrors()
  if (!canSubmit.value) return

  // 防御性再校验（对齐后端约束）。
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
      session.signIn(result.token, result.user)
      router.replace('/')
    } else {
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
  <div class="auth-page">
    <!-- 品牌区 -->
    <header class="hero">
      <div class="logo">¥</div>
      <div class="brand-name">有余</div>
      <p class="brand-slogan">记得清清楚楚，过得富富余余</p>
    </header>

    <!-- 表单卡片 -->
    <section class="sheet">
      <h1 class="title">创建账号</h1>
      <p class="sub">几秒注册，开始记好每一笔</p>

      <form novalidate @submit.prevent="onSubmit">
        <!-- 账号 -->
        <div class="field">
          <label for="reg-acct">
            <span class="lbl">账号</span><span class="hint">1–64 个字符</span>
          </label>
          <div class="input" :class="{ valid: usernameValid, invalid: !!errors.username }">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
            <input
              id="reg-acct"
              v-model="username"
              type="text"
              placeholder="设置你的账号"
              autocomplete="username"
              @input="errors.username = ''"
            />
            <span v-if="usernameValid" class="tick"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg></span>
          </div>
          <small v-if="errors.username" class="msg err">{{ errors.username }}</small>
        </div>

        <!-- 密码 -->
        <div class="field">
          <label for="reg-pwd">
            <span class="lbl">密码</span><span class="hint">8–64 个字符</span>
          </label>
          <div class="input" :class="{ invalid: !!errors.password }">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
            <input
              id="reg-pwd"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              placeholder="设置密码"
              autocomplete="new-password"
              @input="errors.password = ''"
            />
            <button class="eye" type="button" :aria-label="showPassword ? '隐藏密码' : '显示密码'" @click="showPassword = !showPassword">
              <svg v-if="showPassword" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.9 4.24A9.1 9.1 0 0 1 12 4c6.5 0 10 7 10 7a13.2 13.2 0 0 1-2.16 3.19M6.7 6.7A13.3 13.3 0 0 0 2 12s3.5 7 10 7a9 9 0 0 0 4.3-1.1"/><path d="M3 3l18 18"/></svg>
            </button>
          </div>
          <div class="strength" :class="`s${strengthScore}`"><i></i><i></i><i></i></div>
          <small class="msg muted">{{ strengthLabel }}</small>
        </div>

        <!-- 确认密码 -->
        <div class="field">
          <label for="reg-pwd2"><span class="lbl">确认密码</span></label>
          <div class="input" :class="{ valid: matchValid, invalid: password2.length > 0 && !matchValid }">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
            <input
              id="reg-pwd2"
              v-model="password2"
              :type="showPassword2 ? 'text' : 'password'"
              placeholder="再次输入密码"
              autocomplete="new-password"
            />
            <button class="eye" type="button" :aria-label="showPassword2 ? '隐藏密码' : '显示密码'" @click="showPassword2 = !showPassword2">
              <svg v-if="showPassword2" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/></svg>
              <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9.9 4.24A9.1 9.1 0 0 1 12 4c6.5 0 10 7 10 7a13.2 13.2 0 0 1-2.16 3.19M6.7 6.7A13.3 13.3 0 0 0 2 12s3.5 7 10 7a9 9 0 0 0 4.3-1.1"/><path d="M3 3l18 18"/></svg>
            </button>
          </div>
          <small v-if="password2.length > 0" class="msg" :class="matchValid ? 'ok' : 'err'">
            {{ matchValid ? '两次密码一致' : '两次输入的密码不一致' }}
          </small>
        </div>

        <!-- 协议 -->
        <label class="agree">
          <input v-model="agreed" type="checkbox" />
          <span>
            我已阅读并同意
            <RouterLink to="/legal/agreement" @click.stop>《用户协议》</RouterLink>
            与
            <RouterLink to="/legal/privacy" @click.stop>《隐私政策》</RouterLink>
          </span>
        </label>

        <p v-if="errors.form" class="msg err form-err" role="alert">{{ errors.form }}</p>

        <button class="btn-primary" type="submit" :disabled="!canSubmit">
          <span v-if="submitting" class="spinner" aria-hidden="true"></span>
          <span>{{ submitting ? '注册中…' : '注册' }}</span>
        </button>
      </form>

      <p class="switch">
        已有账号？<RouterLink to="/login">去登录</RouterLink>
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

/* 品牌区（注册页压缩高度，给多字段腾空间） */
.hero {
  position: relative;
  flex: 0 0 auto;
  padding: calc(52px + env(safe-area-inset-top)) 32px 50px;
  color: #fff;
  overflow: hidden;
  background: linear-gradient(150deg, #35d07f 0%, #16a34a 46%, #0b6b34 100%);
}
.hero::before,
.hero::after { content: ''; position: absolute; border-radius: 50%; }
.hero::before { width: 230px; height: 230px; top: -90px; right: -70px; background: rgba(255, 255, 255, 0.14); }
.hero::after { width: 150px; height: 150px; bottom: -30px; left: -40px; background: rgba(255, 255, 255, 0.09); }

.logo {
  position: relative; z-index: 1;
  width: 56px; height: 56px; border-radius: 17px;
  background: rgba(255, 255, 255, 0.18); border: 1px solid rgba(255, 255, 255, 0.3);
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.14);
  display: flex; align-items: center; justify-content: center;
  font-size: 30px; font-weight: 800; color: #fff;
}
.brand-name { position: relative; z-index: 1; margin-top: 16px; font-size: 26px; font-weight: 800; letter-spacing: 0.06em; }
.brand-slogan { position: relative; z-index: 1; margin-top: 7px; font-size: 14px; color: rgba(255, 255, 255, 0.9); }

/* 表单卡片 */
.sheet {
  flex: 1 1 auto;
  margin-top: -26px;
  background: #fff;
  border-radius: 28px 28px 0 0;
  padding: 28px 26px calc(22px + env(safe-area-inset-bottom));
  position: relative; z-index: 2;
  box-shadow: 0 -3px 26px rgba(15, 23, 42, 0.06);
  animation: rise 0.45s cubic-bezier(0.16, 1, 0.3, 1) both;
}
@keyframes rise { from { opacity: 0; transform: translateY(14px); } to { opacity: 1; transform: none; } }

.title { margin: 0; font-size: 21px; font-weight: 700; }
.sub { margin: 6px 0 20px; font-size: 13px; color: var(--color-muted); }

.field { margin-bottom: 14px; }
.field > label { display: flex; justify-content: space-between; align-items: baseline; margin-bottom: 7px; }
.field > label .lbl { font-size: 13px; font-weight: 600; color: #475569; }
.field > label .hint { font-size: 11px; color: #a3adb8; }

.input {
  display: flex; align-items: center; gap: 10px;
  height: 50px; padding: 0 14px;
  background: #f5f7f9; border: 1.5px solid transparent; border-radius: 14px;
  transition: border-color 0.18s, background 0.18s, box-shadow 0.18s;
}
.input:focus-within { background: #fff; border-color: var(--color-primary); box-shadow: 0 0 0 4px rgba(22, 163, 74, 0.13); }
.input.valid { border-color: #86d6a3; }
.input.invalid { border-color: var(--color-danger); }
.input > svg { flex: 0 0 auto; width: 20px; height: 20px; color: #9aa4b0; transition: color 0.18s; }
.input:focus-within > svg { color: var(--color-primary); }
.input input { flex: 1 1 auto; min-width: 0; height: 100%; border: none; outline: none; background: transparent; font: inherit; font-size: 16px; color: var(--color-text); }
.input input::placeholder { color: #a9b2bd; }
.tick { flex: 0 0 auto; color: var(--color-primary); display: flex; }
.tick svg { width: 18px; height: 18px; }
.eye { flex: 0 0 auto; border: none; background: none; cursor: pointer; color: #9aa4b0; padding: 4px; display: flex; }
.eye svg { width: 20px; height: 20px; }
.eye:hover { color: var(--color-muted); }

.msg { display: block; margin-top: 6px; font-size: 12px; min-height: 15px; }
.msg.err { color: var(--color-danger); }
.msg.ok { color: var(--color-primary); }
.msg.muted { color: var(--color-muted); }
.form-err { margin-bottom: 10px; font-size: 14px; }

/* 密码强度条 */
.strength { display: flex; gap: 5px; margin-top: 8px; }
.strength i { flex: 1; height: 4px; border-radius: 2px; background: #e6e9ee; transition: background 0.2s; }
.strength.s1 i:nth-child(1) { background: #ef4444; }
.strength.s2 i:nth-child(-n + 2) { background: #f59e0b; }
.strength.s3 i:nth-child(-n + 3) { background: #16a34a; }

/* 协议 */
.agree { display: flex; align-items: flex-start; gap: 8px; margin: 4px 0 18px; font-size: 12px; color: var(--color-muted); line-height: 1.5; cursor: pointer; }
.agree input { margin-top: 2px; width: 16px; height: 16px; accent-color: var(--color-primary); flex: 0 0 auto; }
.agree a { color: var(--color-primary); }

.btn-primary {
  width: 100%; height: 52px; border: none; border-radius: 15px; cursor: pointer;
  background: linear-gradient(135deg, #1eb257, #128a3f); color: #fff; font-size: 16px; font-weight: 700;
  display: flex; align-items: center; justify-content: center; gap: 9px;
  box-shadow: 0 10px 24px rgba(22, 163, 74, 0.3);
  transition: transform 0.08s, filter 0.18s, opacity 0.18s;
}
.btn-primary:hover:not(:disabled) { filter: brightness(1.04); }
.btn-primary:active:not(:disabled) { transform: scale(0.985); }
.btn-primary:disabled { opacity: 0.45; cursor: not-allowed; box-shadow: none; }
.spinner { width: 18px; height: 18px; border: 2.5px solid rgba(255, 255, 255, 0.4); border-top-color: #fff; border-radius: 50%; animation: spin 0.7s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.switch { margin-top: 18px; text-align: center; font-size: 14px; color: var(--color-muted); }
.switch a { color: var(--color-primary); font-weight: 600; }
</style>
