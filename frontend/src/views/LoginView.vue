<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const username = ref('')
const password = ref('')
const error = ref('')
const auth = useAuthStore()
const router = useRouter()

async function submit() {
  error.value = ''
  try {
    await auth.login(username.value.trim(), password.value)
    await router.push('/workspace')
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '登录失败'
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-panel">
      <div class="brand"><span class="brand-mark">排</span><div><strong>排课工作台</strong><small>独立校务工具</small></div></div>
      <p class="eyebrow">ACCOUNT / SIGN IN</p>
      <h1>登录排课系统</h1>
      <p class="login-caption">使用排课员或只读账号继续。</p>
      <form @submit.prevent="submit">
        <label>用户名<input v-model="username" autocomplete="username" required /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" required /></label>
        <div v-if="error" class="inline-message error-message">{{ error }}</div>
        <button type="submit" :disabled="auth.loading">{{ auth.loading ? '登录中…' : '登录' }}</button>
      </form>
    </section>
  </main>
</template>
