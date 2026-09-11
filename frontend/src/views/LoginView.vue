<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http, jsonRequest } from '../api/http'
import { useAuthStore } from '../stores/auth'

const mode = ref<'login' | 'register'>('login')
const username = ref('')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const verificationCode = ref('')
const displayName = ref('')
const error = ref('')
const notice = ref('')
const registerLoading = ref(false)
const codeLoading = ref(false)
const codeCountdown = ref(0)
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const registrationEnabled = import.meta.env.VITE_AUTH_REGISTRATION_ENABLED === 'true'
let countdownTimer: ReturnType<typeof setInterval> | undefined

const canSendCode = computed(() => {
  return !codeLoading.value && codeCountdown.value === 0 && isValidEmail(email.value)
})

function isValidEmail(value: string) {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.trim())
}

function clearCountdown() {
  if (countdownTimer) clearInterval(countdownTimer)
  countdownTimer = undefined
  codeCountdown.value = 0
}

function startCountdown() {
  clearCountdown()
  codeCountdown.value = 60
  countdownTimer = setInterval(() => {
    codeCountdown.value -= 1
    if (codeCountdown.value <= 0) clearCountdown()
  }, 1000)
}

async function sendVerificationCode() {
  error.value = ''
  notice.value = ''
  if (!isValidEmail(email.value)) {
    error.value = '请输入有效的邮箱地址'
    return
  }
  codeLoading.value = true
  try {
    await http('/api/auth/registration-code', jsonRequest('POST', { email: email.value.trim() }))
    notice.value = '如果该邮箱可注册，验证码已发送，请查收邮件'
    startCountdown()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '验证码发送失败'
  } finally {
    codeLoading.value = false
  }
}

function loginRedirect() {
  const redirect = route.query.redirect
  if (typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//')) return redirect
  return auth.isPlanner ? '/overview' : '/published'
}

async function submit() {
  error.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    await router.push(loginRedirect())
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '登录失败'
  }
}

async function submitRegister() {
  error.value = ''
  notice.value = ''
  const normalizedEmail = email.value.trim()
  if (!isValidEmail(normalizedEmail)) {
    error.value = '请输入有效的邮箱地址'
    return
  }
  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }
  if (!/^\d{6}$/.test(verificationCode.value.trim())) {
    error.value = '请输入 6 位邮箱验证码'
    return
  }
  registerLoading.value = true
  try {
    await http('/api/auth/register', {
      ...jsonRequest('POST', {
        email: normalizedEmail,
        password: password.value,
        displayName: displayName.value.trim(),
        verificationCode: verificationCode.value.trim(),
      }),
    })
    notice.value = '注册成功，请使用邮箱登录'
    mode.value = 'login'
    username.value = normalizedEmail
    password.value = ''
    confirmPassword.value = ''
    verificationCode.value = ''
    clearCountdown()
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '注册失败'
  } finally {
    registerLoading.value = false
  }
}

function switchMode(target: 'login' | 'register') {
  mode.value = target
  error.value = ''
  notice.value = ''
  if (target === 'login') clearCountdown()
}

onBeforeUnmount(clearCountdown)
</script>

<template>
  <main class="login-canvas">
    <section class="glass-card login-panel">
      <div class="brand-row">
        <img class="brand-logo-img" src="/logo.png" alt="排课工作台 Logo" />
        <div>
          <h2>排课工作台</h2>
          <small>智程校务智能排课系统</small>
        </div>
      </div>

      <div class="panel-header">
        <p class="eyebrow">ACCOUNT / {{ mode === 'login' ? 'SIGN IN' : 'SIGN UP' }}</p>
        <h1>{{ mode === 'login' ? '登录排课系统' : '注册新账号' }}</h1>
        <p class="login-caption">
          {{ mode === 'login' ? '使用邮箱或用户名继续。' : '验证邮箱后即可进入排课工作台进行排课、求解与课表调整。' }}
        </p>
      </div>

      <form class="login-form" @submit.prevent="mode === 'login' ? submit() : submitRegister()">
        <label v-if="mode === 'login'" class="form-field">
          <span>邮箱或用户名</span>
          <input
            v-model="username"
            class="styled-input"
            autocomplete="username"
            placeholder="请输入邮箱或用户名"
            required
          />
        </label>

        <label v-if="mode === 'register'" class="form-field">
          <span>邮箱</span>
          <input
            v-model="email"
            type="email"
            class="styled-input"
            autocomplete="email"
            placeholder="name@example.com"
            required
          />
        </label>

        <label v-if="mode === 'register'" class="form-field">
          <span>邮箱验证码</span>
          <div class="code-field">
            <input
              v-model="verificationCode"
              class="styled-input"
              inputmode="numeric"
              maxlength="6"
              autocomplete="one-time-code"
              placeholder="输入 6 位验证码"
              required
            />
            <button type="button" class="code-button" :disabled="!canSendCode" @click="sendVerificationCode">
              {{ codeLoading ? '发送中…' : (codeCountdown > 0 ? `${codeCountdown}s 后重发` : '发送验证码') }}
            </button>
          </div>
        </label>

        <label v-if="mode === 'register'" class="form-field">
          <span>显示名称</span>
          <input
            v-model="displayName"
            class="styled-input"
            autocomplete="name"
            placeholder="可选，默认使用邮箱"
          />
        </label>

        <label class="form-field">
          <span>密码</span>
          <input
            v-model="password"
            type="password"
            class="styled-input"
            :autocomplete="mode === 'login' ? 'current-password' : 'new-password'"
            placeholder="请输入密码"
            required
          />
        </label>
        <label v-if="mode === 'register'" class="form-field">
          <span>确认密码</span>
          <input
            v-model="confirmPassword"
            type="password"
            class="styled-input"
            autocomplete="new-password"
            placeholder="请再次输入密码"
            required
          />
        </label>
        <small v-if="mode === 'register'" class="field-hint">密码长度 8-128 位，验证码 10 分钟内有效</small>

        <div v-if="error" class="inline-message error-message">{{ error }}</div>
        <div v-if="notice" class="inline-message success-message">{{ notice }}</div>

        <button
          type="submit"
          class="submit-button"
          :disabled="auth.loading || registerLoading || codeLoading"
        >
          {{ mode === 'login' ? (auth.loading ? '登录中…' : '登录') : (registerLoading ? '注册中…' : '注册') }}
        </button>
      </form>

      <div v-if="registrationEnabled" class="panel-footer">
        <span>{{ mode === 'login' ? '还没有账号？' : '已有账号？' }}</span>
        <a
          v-if="mode === 'login' && registrationEnabled"
          href="#"
          class="switch-link"
          @click.prevent="switchMode('register')"
        >
          没有账号？注册
        </a>
        <a
          v-else-if="mode === 'register' && registrationEnabled"
          href="#"
          class="switch-link"
          @click.prevent="switchMode('login')"
        >
          已有账号？返回登录
        </a>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-canvas {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: #F5F6F8;
}
.glass-card {
  width: 100%;
  max-width: 430px;
  background: #ffffff;
  border: 1px solid #D9DEE3;
  border-radius: 6px;
  padding: 32px;
  box-shadow: 0 8px 24px rgba(32, 42, 53, .06);
}
.brand-row { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.brand-logo-img { width: 38px; height: 38px; border-radius: 6px; object-fit: cover; box-shadow: none; }
.brand-row h2 { margin: 0; font-size: 16px; font-weight: 700; color: #202A35; }
.brand-row small { display: block; font-size: 11px; color: #8795A5; margin-top: 2px; }
.panel-header { margin-bottom: 22px; }
.eyebrow { margin: 0 0 4px; font-size: 10px; font-weight: 700; letter-spacing: 0.1em; color: #B85C45; text-transform: uppercase; }
.panel-header h1 { margin: 0 0 6px; font-size: 22px; font-weight: 700; color: #191c1d; }
.login-caption { margin: 0; font-size: 12px; color: #566474; line-height: 1.5; }
.login-form { display: flex; flex-direction: column; gap: 16px; }
.form-field { display: flex; flex-direction: column; gap: 6px; }
.form-field span { font-size: 12px; font-weight: 600; color: #182029; }
.styled-input { width: 100%; padding: 10px 12px; border-radius: 4px; border: 1px solid #D9DEE3; background: #F9FAFB; color: #182029; font-size: 13.5px; outline: none; transition: all 0.2s ease; }
.styled-input:focus { background: #ffffff; border-color: #B85C45; box-shadow: 0 0 0 3px rgba(184, 92, 69, .12); }
.code-field { display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; }
.code-button { min-width: 112px; padding: 0 10px; border: 1px solid #EED8AF; border-radius: 4px; background: #FBF4E7; color: #984936; font-size: 11px; cursor: pointer; }
.code-button:disabled { opacity: 0.55; cursor: not-allowed; }
.field-hint { font-size: 11px; color: #8795A5; margin-top: -8px; }
.submit-button { margin-top: 6px; width: 100%; padding: 11px; border-radius: 4px; border: 0; background: #B85C45; color: #ffffff; font-size: 14px; font-weight: 600; cursor: pointer; box-shadow: 0 4px 10px rgba(184, 92, 69, .16); transition: background .2s ease; }
.submit-button:hover:not(:disabled) { background: #984936; }
.submit-button:disabled { opacity: 0.6; cursor: not-allowed; }
.panel-footer { margin-top: 24px; padding-top: 18px; border-top: 1px solid #EEF1F3; display: flex; justify-content: space-between; align-items: center; font-size: 12px; color: #566474; }
.switch-link { color: #984936; font-weight: 600; text-decoration: none; transition: color 0.2s; }
.switch-link:hover { text-decoration: underline; }
.inline-message { padding: 8px 12px; border-radius: 6px; font-size: 12px; line-height: 1.5; }
.error-message { background: #fef2f2; border: 1px solid #fecaca; color: #991b1b; }
.success-message { background: #f0fdf4; border: 1px solid #bbf7d0; color: #166534; }
</style>
