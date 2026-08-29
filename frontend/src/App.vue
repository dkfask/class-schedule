<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'
import { useTermStore } from './stores/term'

const auth = useAuthStore()
const term = useTermStore()
const router = useRouter()

function selectTerm(event: Event) {
  term.selectTerm((event.target as HTMLSelectElement).value)
}

onMounted(() => {
  window.addEventListener('auth:expired', () => { void router.push('/login') })
  if (auth.isAuthenticated) void term.loadTerms()
})

watch(() => auth.isAuthenticated, authenticated => {
  if (authenticated) void term.loadTerms(true)
})

async function logout() {
  await auth.logout()
  await router.push('/login')
}
</script>

<template>
  <div v-if="auth.isAuthenticated" class="app-shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">排</span><div><strong>排课工作台</strong><small>独立校务工具</small></div></div>
      <div class="term-card"><span>当前学期</span><select :value="term.selectedTermCode.value" :disabled="term.loading.value || !term.hasValidTerm.value" @change="selectTerm"><option v-if="term.loading.value" value="">正在加载学期…</option><option v-else-if="!term.terms.value.length" value="">暂无可用学期</option><option v-for="item in term.terms.value" :key="item.code" :value="item.code">{{ item.name }} · {{ item.code }}</option></select><em v-if="term.error.value" class="error-text">{{ term.error.value }}</em><em v-else>{{ auth.user?.displayName }} · {{ auth.isPlanner ? '排课员' : '只读' }}</em></div>
      <nav>
        <RouterLink to="/workspace" class="nav-item" active-class="active"><span>▦</span>排课工作台</RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/master-data" class="nav-item" active-class="active"><span>◫</span>基础数据</RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/teaching-plan" class="nav-item" active-class="active"><span>◌</span>教学计划</RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/rule-facts" class="nav-item" active-class="active"><span>◫</span>规则事实</RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/import" class="nav-item" active-class="active"><span>⇧</span>数据导入</RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/versions" class="nav-item" active-class="active"><span>◷</span>版本与发布</RouterLink>
        <RouterLink to="/published" class="nav-item" active-class="active"><span>▤</span>已发布课表</RouterLink>
      </nav>
      <div class="sidebar-foot"><span class="status-dot"></span>本地环境已连接<button class="logout-button" @click="logout">退出登录</button></div>
    </aside>
    <main class="workspace"><RouterView /></main>
  </div>
  <RouterView v-else />
</template>
