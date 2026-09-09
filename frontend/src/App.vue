<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { RouterLink, RouterView, useRouter } from 'vue-router'
import AiChatPanel from './components/AiChatPanel.vue'
import { useAuthStore } from './stores/auth'
import { useTermStore } from './stores/term'

const auth = useAuthStore()
const term = useTermStore()
const router = useRouter()

function selectTerm(event: Event) {
  term.selectTerm((event.target as HTMLSelectElement).value)
}

onMounted(() => {
  window.addEventListener('auth:expired', () => {
    auth.user = null
    void router.push('/login')
  })
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
      <div class="brand">
        <img class="brand-logo-img" src="/logo.png" alt="智程排课" />
        <div>
          <strong>排课工作台</strong>
          <small>智程校务智能排课</small>
        </div>
      </div>
      <div class="term-card"><span>当前学期</span><select :value="term.selectedTermCode.value" :disabled="term.loading.value || !term.hasValidTerm.value" @change="selectTerm"><option v-if="term.loading.value" value="">正在加载学期…</option><option v-else-if="!term.terms.value.length" value="">暂无可用学期</option><option v-for="item in term.terms.value" :key="item.code" :value="item.code">{{ item.name }} · {{ item.code }}</option></select><em v-if="term.error.value" class="error-text">{{ term.error.value }}</em><em v-else>{{ auth.user?.displayName }} · {{ auth.isPlanner ? '排课员' : auth.isReviewer ? '审核员' : auth.isBusinessOwner ? '业务负责人' : '只读' }}</em></div>
      <nav class="sidebar-nav">
        <div class="nav-section-title">排课流水线</div>
        <RouterLink v-if="auth.canReview" to="/overview" class="nav-item" active-class="active">
          <span class="nav-icon">⌂</span>
          <span class="nav-title">学期总览</span>
          <span class="nav-badge">总览</span>
        </RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/workspace" class="nav-item" active-class="active">
          <span class="nav-icon">▦</span>
          <span class="nav-title">排课工作台</span>
        </RouterLink>

        <div v-if="auth.isPlanner" class="nav-section-title">第一阶段 · 准备</div>
        <RouterLink v-if="auth.isPlanner" to="/master-data" class="nav-item" active-class="active">
          <span class="nav-icon">◫</span>
          <span class="nav-title">基础数据</span>
        </RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/import" class="nav-item" active-class="active">
          <span class="nav-icon">⇧</span>
          <span class="nav-title">数据导入</span>
        </RouterLink>

        <div v-if="auth.isPlanner" class="nav-section-title">第二阶段 · 规则</div>
        <RouterLink v-if="auth.isPlanner" to="/teaching-plan" class="nav-item" active-class="active">
          <span class="nav-icon">◌</span>
          <span class="nav-title">教学计划</span>
        </RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/rule-facts" class="nav-item" active-class="active">
          <span class="nav-icon">◫</span>
          <span class="nav-title">规则中心</span>
        </RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/rule-templates" class="nav-item" active-class="active">
          <span class="nav-icon">▣</span>
          <span class="nav-title">规则模板</span>
        </RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/retrospective" class="nav-item" active-class="active">
          <span class="nav-icon">◒</span>
          <span class="nav-title">学期复盘</span>
        </RouterLink>
        <RouterLink v-if="auth.isPlanner" to="/term-reuse" class="nav-item" active-class="active">
          <span class="nav-icon">↻</span>
          <span class="nav-title">学期复用</span>
        </RouterLink>

        <div class="nav-section-title">第三阶段 · 交付</div>
        <RouterLink v-if="auth.canReview" to="/versions" class="nav-item" active-class="active">
          <span class="nav-icon">◷</span>
          <span class="nav-title">版本与发布</span>
        </RouterLink>
        <RouterLink to="/published" class="nav-item" active-class="active">
          <span class="nav-icon">▤</span>
          <span class="nav-title">已发布课表</span>
        </RouterLink>
        <RouterLink v-if="auth.canReadAudit" to="/audit" class="nav-item" active-class="active">
          <span class="nav-icon">◷</span>
          <span class="nav-title">操作与审计</span>
        </RouterLink>
        <RouterLink v-if="auth.canReadAudit" to="/problems" class="nav-item" active-class="active">
          <span class="nav-icon">!</span>
          <span class="nav-title">问题与反馈</span>
        </RouterLink>
        <RouterLink to="/notifications" class="nav-item" active-class="active">
          <span class="nav-icon">◔</span>
          <span class="nav-title">通知与待办</span>
        </RouterLink>
      </nav>
      <div class="sidebar-foot"><span class="status-dot"></span>本地环境已连接<button class="logout-button" @click="logout">退出登录</button></div>
    </aside>
    <main class="workspace"><RouterView /></main>
    <AiChatPanel v-if="auth.isAuthenticated" />
  </div>
  <RouterView v-else />
</template>
