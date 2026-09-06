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
    notice.value = '注册成功，请使用新账号登录（新账号为只读角色）'
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
  <main class="login-page">
    <section class="login-panel">
      <div class="brand"><span class="brand-mark">排</span><div><strong>排课工作台</strong><small>独立校务工具</small></div></div>
      <p class="eyebrow">ACCOUNT / {{ mode === 'login' ? 'SIGN IN' : 'SIGN UP' }}</p>
      <h1>{{ mode === 'login' ? '登录排课系统' : '注册新账号' }}</h1>
      <p class="login-caption">{{ mode === 'login' ? '使用排课员或只读账号继续。' : '注册后为只读角色，可查看已发布课表；排课员账号由管理员分配。' }}</p>
      <form @submit.prevent="mode === 'login' ? submit() : submitRegister()">
        <label>用户名<input v-model="username" autocomplete="username" required /></label>
        <label v-if="mode === 'register'">显示名称<input v-model="displayName" autocomplete="name" placeholder="可选，默认同用户名" /></label>
        <label>密码<input v-model="password" type="password" :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" required /></label>
        <small v-if="mode === 'register'" class="field-hint">密码长度 8-128 位</small>
        <div v-if="error" class="inline-message error-message">{{ error }}</div>
        <div v-if="notice" class="inline-message">{{ notice }}</div>
        <button type="submit" :disabled="auth.loading">{{ mode === 'login' ? (auth.loading ? '登录中…' : '登录') : (auth.loading ? '注册中…' : '注册') }}</button>
      </form>
      <p class="mode-switch">
        <a v-if="mode === 'login'" href="#" @click.prevent="switchMode('register')">没有账号？注册</a>
        <a v-else href="#" @click.prevent="switchMode('login')">已有账号？返回登录</a>
      </p>
    </section>
  </main>
</template>

<style scoped>
.mode-switch { margin-top: 14px; font-size: 13px; }
.mode-switch a { color: var(--el-color-primary, #4a7c59); }
.field-hint { display: block; margin-top: 4px; color: var(--el-text-color-secondary, #909399); font-size: 12px; }
.inline-message { margin: 10px 0; }
</style>
