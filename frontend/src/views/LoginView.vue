<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const displayName = ref('')
const error = ref('')
const notice = ref('')
const auth = useAuthStore()
const router = useRouter()

async function submit() {
  error.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    await router.push(auth.isPlanner ? '/workspace' : '/published')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '登录失败'
  }
}

async function submitRegister() {
  error.value = ''
  notice.value = ''
  try {
    await http('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: username.value.trim(),
        password: password.value,
        displayName: displayName.value.trim(),
      }),
    })
    notice.value = '注册成功，请使用新账号登录'
    mode.value = 'login'
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '注册失败'
  }
}

function switchMode(target: 'login' | 'register') {
  mode.value = target
  error.value = ''
  notice.value = ''
}
</script>

<template>
  <main class="login-canvas">
    <section class="glass-card login-panel">
      <!-- 品牌区 -->
      <div class="brand-row">
        <div class="brand-logo">排</div>
        <div>
          <h2>排课工作台</h2>
          <small>智程校务智能排课系统</small>
        </div>
      </div>

      <!-- 头部标题与引导 -->
      <div class="panel-header">
        <p class="eyebrow">ACCOUNT / {{ mode === 'login' ? 'SIGN IN' : 'SIGN UP' }}</p>
        <h1>{{ mode === 'login' ? '登录排课系统' : '注册新账号' }}</h1>
        <p class="login-caption">
          {{ mode === 'login' ? '使用排课员或只读账号继续。' : '注册后可直接进入排课工作台进行排课、求解与课表调整。' }}
        </p>
      </div>

      <!-- 表单主体 -->
      <form class="login-form" @submit.prevent="mode === 'login' ? submit() : submitRegister()">
        <label class="form-field">
          <span>用户名</span>
          <input
            v-model="username"
            class="styled-input"
            autocomplete="username"
            placeholder="请输入用户名"
            required
          />
        </label>

        <label v-if="mode === 'register'" class="form-field">
          <span>显示名称</span>
          <input
            v-model="displayName"
            class="styled-input"
            autocomplete="name"
            placeholder="可选，默认同用户名"
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
        <small v-if="mode === 'register'" class="field-hint">密码长度 8-128 位</small>

        <!-- 提示与错误信息 -->
        <div v-if="error" class="inline-message error-message">{{ error }}</div>
        <div v-if="notice" class="inline-message success-message">{{ notice }}</div>

        <!-- 提交按钮 -->
        <button
          type="submit"
          class="submit-button"
          :disabled="auth.loading"
        >
          {{ mode === 'login' ? (auth.loading ? '登录中…' : '登录') : (auth.loading ? '注册中…' : '注册') }}
        </button>
      </form>

      <!-- 模式切换与页脚 -->
      <div class="panel-footer">
        <span>{{ mode === 'login' ? '还没有账号？' : '已有账号？' }}</span>
        <a
          v-if="mode === 'login'"
          href="#"
          class="switch-link"
          @click.prevent="switchMode('register')"
        >
          没有账号？注册
        </a>
        <a
          v-else
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
  background: radial-gradient(circle at top left, #edf5f0 0%, #f8fafa 100%);
}
.glass-card {
  width: 100%;
  max-width: 430px;
  background: #ffffff;
  border: 1px solid rgba(23, 59, 54, 0.12);
  border-radius: 16px;
  padding: 36px 32px;
  box-shadow: 0 20px 40px -15px rgba(23, 59, 54, 0.08), 0 0 0 1px rgba(255, 255, 255, 0.8) inset;
}
.brand-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}
.brand-logo {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: #173b36;
  color: #ffffff;
  font-weight: 700;
  font-size: 18px;
  display: grid;
  place-items: center;
  box-shadow: 0 4px 10px rgba(23, 59, 54, 0.2);
}
.brand-row h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: #173b36;
}
.brand-row small {
  display: block;
  font-size: 11px;
  color: #728c82;
  margin-top: 2px;
}

.panel-header {
  margin-bottom: 22px;
}
.eyebrow {
  margin: 0 0 4px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: #2c694e;
  text-transform: uppercase;
}
.panel-header h1 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 700;
  color: #191c1d;
}
.login-caption {
  margin: 0;
  font-size: 12px;
  color: #6a7b74;
  line-height: 1.5;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.form-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-field span {
  font-size: 12px;
  font-weight: 600;
  color: #344740;
}
.styled-input {
  width: 100%;
  padding: 10px 12px;
  border-radius: 8px;
  border: 1px solid #dce4e0;
  background: #fbfdfc;
  color: #191c1d;
  font-size: 13.5px;
  outline: none;
  transition: all 0.2s ease;
}
.styled-input:focus {
  background: #ffffff;
  border-color: #173b36;
  box-shadow: 0 0 0 3px rgba(23, 59, 54, 0.1);
}
.field-hint {
  font-size: 11px;
  color: #83978f;
  margin-top: -8px;
}

.submit-button {
  margin-top: 6px;
  width: 100%;
  padding: 11px;
  border-radius: 8px;
  border: 0;
  background: #173b36;
  color: #ffffff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 6px 16px -2px rgba(23, 59, 54, 0.25);
  transition: all 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}
.submit-button:hover:not(:disabled) {
  background: #12302c;
  transform: translateY(-1px);
  box-shadow: 0 8px 20px -2px rgba(23, 59, 54, 0.35);
}
.submit-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.panel-footer {
  margin-top: 24px;
  padding-top: 18px;
  border-top: 1px solid #edf2ef;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
  color: #7b948a;
}
.switch-link {
  color: #1b5a45;
  font-weight: 600;
  text-decoration: none;
  transition: color 0.2s;
}
.switch-link:hover {
  text-decoration: underline;
}

.inline-message {
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
}
.error-message {
  background: #fef2f2;
  border: 1px solid #fecaca;
  color: #991b1b;
}
.success-message {
  background: #f0fdf4;
  border: 1px solid #bbf7d0;
  color: #166534;
}
</style>
