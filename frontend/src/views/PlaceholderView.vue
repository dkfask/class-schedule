<script setup lang="ts">
import { useRoute } from 'vue-router'

const route = useRoute()
const labels: Record<string, { title: string; description: string }> = {
  '/teaching-plan': { title: '教学计划', description: '教学需求管理页面尚未接入，当前可通过后端教学需求 API 管理数据。' },
  '/import': { title: '数据导入', description: '独立导入页面尚未接入，请从排课工作台使用 Excel 预检和确认导入。' },
  '/versions': { title: '版本与发布', description: '独立版本列表页面尚未接入，当前可在排课工作台查看当前候选版本。' },
  '/published': { title: '已发布课表', description: '独立只读课表页面尚未接入，当前发布操作仍在排课工作台完成。' },
}
const current = labels[route.path] ?? { title: '功能占位', description: '该页面尚未接入。' }
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">FEATURE / PLACEHOLDER</p><h1>{{ current.title }}</h1></div><div class="top-actions"><span class="sync-state">● 本地环境已连接</span><div class="avatar">教</div></div></header>
  <section class="placeholder-page panel"><span class="placeholder-mark">◌</span><h2>{{ current.title }}尚未接入</h2><p>{{ current.description }}</p><RouterLink class="placeholder-link" to="/workspace">返回排课工作台</RouterLink></section>
</template>
