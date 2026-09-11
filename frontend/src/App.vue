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
      <div class="sidebar-scroll">
        <div class="brand">
          <img class="brand-logo-img" src="/logo.png" alt="智程排课" />
          <div class="brand-copy">
            <strong>智程排课系统</strong>
            <small>校务智能排课</small>
          </div>
        </div>
        <div class="term-card">
          <span>当前学期</span>
          <select :value="term.selectedTermCode.value" :disabled="term.loading.value || !term.hasValidTerm.value" @change="selectTerm">
            <option v-if="term.loading.value" value="">正在加载学期...</option>
            <option v-else-if="!term.terms.value.length" value="">暂无可用学期</option>
            <option v-for="item in term.terms.value" :key="item.code" :value="item.code">{{ item.name }} · {{ item.code }}</option>
          </select>
          <em v-if="term.error.value" class="error-text">{{ term.error.value }}</em>
          <em v-else>{{ auth.user?.displayName }} · {{ auth.isSystemAdmin ? '系统管理员' : auth.isPlanner ? '排课员' : auth.isReviewer ? '审核员' : auth.isBusinessOwner ? '业务负责人' : '只读' }}</em>
        </div>
        <RouterLink v-if="auth.isPlanner" to="/workspace" class="sidebar-cta"><span class="nav-icon">+</span><span>新建排课方案</span></RouterLink>
        <nav class="sidebar-nav">
          <div class="nav-section-title">总览监控</div>
          <RouterLink v-if="auth.canReview" to="/overview" class="nav-item" active-class="active">
            <span class="nav-icon">□</span><span class="nav-title">学期总览</span><span class="nav-badge">总览</span>
          </RouterLink>

          <div v-if="auth.isPlanner" class="nav-section-title">阶段工作流</div>
          <RouterLink v-if="auth.isPlanner" to="/master-data" class="nav-item" active-class="active">
            <span class="nav-icon">01</span><span class="nav-title">基础准备</span>
          </RouterLink>
          <RouterLink v-if="auth.isPlanner" to="/teaching-plan" class="nav-item" active-class="active">
            <span class="nav-icon">02</span><span class="nav-title">教学计划</span>
          </RouterLink>
          <RouterLink v-if="auth.isPlanner" to="/rule-facts" class="nav-item" active-class="active">
            <span class="nav-icon">03</span><span class="nav-title">规则设定</span>
          </RouterLink>
          <RouterLink v-if="auth.isPlanner" to="/rule-templates" class="nav-item" active-class="active">
            <span class="nav-icon">04</span><span class="nav-title">规则模板</span>
          </RouterLink>
          <RouterLink v-if="auth.isPlanner" to="/workspace" class="nav-item" active-class="active">
            <span class="nav-icon">05</span><span class="nav-title">自动求解</span>
          </RouterLink>
          <RouterLink v-if="auth.canReview" to="/versions" class="nav-item" active-class="active">
            <span class="nav-icon">06</span><span class="nav-title">版本与发布</span>
          </RouterLink>

          <div class="nav-section-title">交付与治理</div>
          <RouterLink to="/published" class="nav-item" active-class="active">
            <span class="nav-icon">▤</span><span class="nav-title">已发布课表</span>
          </RouterLink>
          <RouterLink v-if="auth.canReadAudit" to="/audit" class="nav-item" active-class="active">
            <span class="nav-icon">◷</span><span class="nav-title">操作与审计</span>
          </RouterLink>
          <RouterLink v-if="auth.canReadAudit" to="/problems" class="nav-item" active-class="active">
            <span class="nav-icon">!</span><span class="nav-title">问题与反馈</span>
          </RouterLink>
          <RouterLink to="/notifications" class="nav-item" active-class="active">
            <span class="nav-icon">◔</span><span class="nav-title">通知与待办</span>
          </RouterLink>
          <RouterLink v-if="auth.isPlanner" to="/retrospective" class="nav-item" active-class="active">
            <span class="nav-icon">↻</span><span class="nav-title">学期复盘</span>
          </RouterLink>
          <RouterLink v-if="auth.isPlanner" to="/term-reuse" class="nav-item" active-class="active">
            <span class="nav-icon">↺</span><span class="nav-title">学期复用</span>
          </RouterLink>
          <div v-if="auth.canManageAi" class="nav-section-title">系统设置</div>
          <RouterLink v-if="auth.canManageAi" to="/settings/ai" class="nav-item" active-class="active">
            <span class="nav-icon">AI</span><span class="nav-title">AI 模型设置</span>
          </RouterLink>
        </nav>
      </div>
      <div class="sidebar-foot">
        <div class="engine-state"><span class="status-dot"></span><span>本地环境已连接</span><span class="engine-latency">12ms</span></div>
        <button class="logout-button" @click="logout">退出登录</button>
      </div>
    </aside>
    <main class="workspace"><RouterView /></main>
    <AiChatPanel v-if="auth.isAuthenticated" />
  </div>
  <RouterView v-else />
</template>
