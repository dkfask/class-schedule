<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { http, jsonRequest } from '../api/http'
import { useTermStore } from '../stores/term'

interface Notification {
  id: number
  kind: string
  title: string
  message: string
  status: string
  aggregateType?: string
  aggregateId?: string
  termCode?: string
  mandatory: boolean
  createdAt?: string
}

const term = useTermStore()
const items = ref<Notification[]>([])
const filter = ref('')
const loading = ref(false)
const error = ref('')
const message = ref('')
const pendingCount = ref(0)
const statusLabels: Record<string, string> = { PENDING: '待处理', READ: '已读', DONE: '已完成' }

const visibleItems = computed(() => items.value.filter(item => !term.hasValidTerm.value || !item.termCode || item.termCode === term.selectedTermCode.value))

function formatDate(value?: string) {
  if (!value) return '时间未知'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    await term.loadTerms()
    const suffix = filter.value ? `?status=${encodeURIComponent(filter.value)}` : ''
    const result = await http<{ items?: Notification[]; pendingCount?: number }>(`/api/notifications${suffix}`)
    items.value = result.items ?? []
    pendingCount.value = result.pendingCount ?? 0
  } catch (reason) {
    items.value = []
    error.value = reason instanceof Error ? reason.message : '通知加载失败'
  } finally { loading.value = false }
}

async function update(item: Notification, status: string) {
  try {
    await http(`/api/notifications/${item.id}`, jsonRequest('PATCH', { status }))
    item.status = status
    pendingCount.value = Math.max(0, pendingCount.value - (status === 'READ' || status === 'DONE' ? 1 : 0))
    message.value = `通知已标记为${statusLabels[status] ?? status}`
  } catch (reason) { error.value = reason instanceof Error ? reason.message : '通知状态更新失败' }
}

watch(() => term.selectedTermCode.value, () => void load())
onMounted(() => void load())
</script>

<template>
  <header class="topbar">
    <div><p class="eyebrow">NOTIFICATIONS / FOLLOW-UP</p><h1>通知与待办</h1></div>
    <div class="top-actions"><span class="sync-state">● {{ pendingCount }} 条待处理</span><div class="avatar">铃</div></div>
  </header>
  <section class="notification-page panel canvas-card" data-testid="notifications-page">
    <div class="panel-heading"><div><span class="eyebrow">WORKFLOW INBOX</span><h2>与当前学期有关的进展</h2><small>通知关联原始版本、任务、导入批次或问题记录，处理状态与业务事实分开保存。</small></div><div class="notification-actions"><select v-model="filter" @change="load"><option value="">全部状态</option><option value="PENDING">待处理</option><option value="READ">已读</option><option value="DONE">已完成</option></select><button class="quiet-button" :disabled="loading" @click="load">刷新</button></div></div>
    <div v-if="message" class="inline-message success-message">{{ message }}</div>
    <div v-if="error" class="inline-message error-message">{{ error }}</div>
    <div v-if="loading" class="notification-empty">正在加载通知…</div>
    <div v-else-if="!visibleItems.length" class="notification-empty">当前没有需要展示的通知</div>
    <div v-else class="notification-list">
      <article v-for="item in visibleItems" :key="item.id" class="notification-row" :class="{ unread: item.status === 'PENDING', mandatory: item.mandatory }">
        <div class="notification-mark">{{ item.mandatory ? '!' : '·' }}</div>
        <div class="notification-body"><div class="notification-title"><strong>{{ item.title }}</strong><span class="notification-status">{{ statusLabels[item.status] ?? item.status }}</span></div><p>{{ item.message }}</p><small>{{ item.termCode || '未关联学期' }} · {{ formatDate(item.createdAt) }}<template v-if="item.aggregateId"> · {{ item.aggregateType }} #{{ item.aggregateId }}</template></small></div>
        <div v-if="item.status !== 'DONE'" class="notification-buttons"><button v-if="item.status === 'PENDING'" class="quiet-button" @click="update(item, 'READ')">标记已读</button><button class="primary-button" @click="update(item, 'DONE')">完成</button></div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.canvas-card { background: #fff; border: 1px solid rgba(23, 59, 54, .1); border-radius: 12px; box-shadow: 0 4px 16px -2px rgba(23, 59, 54, .03); overflow: hidden; }
.notification-page { padding: 20px; }
.notification-page h2 { margin: 5px 0 0; color: #173b36; font-size: 18px; }
.panel-heading small { display: block; margin-top: 6px; color: #6a7b75; font-size: 11px; }
.notification-actions { display: flex; align-items: center; gap: 8px; }
.notification-actions select, .primary-button, .quiet-button { border-radius: 6px; padding: 8px 11px; font: inherit; font-size: 12px; }
.notification-actions select { border: 1px solid #dce4e0; background: #fff; color: #50605c; }
.primary-button, .quiet-button { cursor: pointer; white-space: nowrap; }
.primary-button { border: 1px solid #173b36; background: #173b36; color: #fff; }
.quiet-button { border: 1px solid #dce4e0; background: #fff; color: #50605c; }
.primary-button:disabled, .quiet-button:disabled { cursor: not-allowed; opacity: .55; }
.notification-list { display: grid; gap: 8px; margin-top: 18px; }
.notification-row { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; gap: 10px; align-items: start; padding: 13px 14px; border: 1px solid #e4ece7; border-left: 3px solid #b7c9be; border-radius: 8px; background: #fff; }
.notification-row.unread { border-left-color: #2a7560; background: #f7fbf8; }
.notification-row.mandatory { border-left-color: #c77455; }
.notification-mark { color: #2a7560; font-size: 18px; line-height: 1; text-align: center; }
.mandatory .notification-mark { color: #b45138; font-weight: 700; }
.notification-title { display: flex; align-items: center; gap: 8px; color: #173b36; font-size: 13px; }
.notification-status { padding: 3px 7px; border-radius: 999px; color: #5f756a; background: #edf3ef; font-size: 10px; font-weight: 400; }
.notification-body p { margin: 6px 0; color: #50685d; font-size: 12px; line-height: 1.5; }
.notification-body small { color: #8a9a91; font-size: 10px; }
.notification-buttons { display: flex; gap: 6px; align-self: center; }
.notification-empty { padding: 36px 16px; margin-top: 18px; border: 1px dashed #cddbd5; border-radius: 8px; color: #687874; text-align: center; font-size: 12px; }
.inline-message { margin-top: 14px; padding: 10px 14px; border-radius: 8px; font-size: 12px; }
.success-message { border: 1px solid #bbdec6; background: #effaf2; color: #28623d; }
.error-message { border: 1px solid #fecaca; background: #fef2f2; color: #991b1b; }
@media (max-width: 680px) { .notification-page { padding: 14px; } .panel-heading, .notification-row { display: grid; } .notification-actions { justify-content: space-between; margin-top: 12px; } .notification-buttons { justify-content: end; } }
</style>
