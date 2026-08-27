<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { http } from '../api/http'
import { resolveScore } from '../utils/score'
import { useTermStore } from '../stores/term'

interface Version { id: number; status: string; revision?: number; score?: string; hardScore?: number | null; mediumScore?: number | null; softScore?: number | null; createdAt?: string }
const term = useTermStore()
const versions = ref<Version[]>([])
const selected = ref<number | null>(null)
const loading = ref(false)
const message = ref('')

async function load() {
  loading.value = true
  try {
    const data = await http<{ items?: Version[] }>(`/api/schedule-versions?termCode=${encodeURIComponent(term.selectedTermCode.value)}&status=PUBLISHED&page=0&size=50`)
    versions.value = data.items ?? []
    selected.value = versions.value[0]?.id ?? null
  } catch (error) {
    message.value = error instanceof Error ? error.message : '已发布课表加载失败'
  } finally { loading.value = false }
}

function download(format: 'xlsx' | 'pdf') {
  if (!selected.value) return
  window.open(`/api/schedule-versions/${selected.value}/exports/${format}?view=CLASS`, '_blank')
}

function print() {
  if (!selected.value) return
  window.open(`/api/schedule-versions/${selected.value}/print?view=CLASS`, '_blank')
}

function scoreParts(version: Version) {
  return resolveScore(version.score, { hard: version.hardScore, medium: version.mediumScore, soft: version.softScore })
}

watch(() => term.selectedTermCode.value, () => void load())
onMounted(() => void load())
</script>

<template>
  <header class="topbar"><div><p class="eyebrow">PUBLISHED / READ ONLY</p><h1>已发布课表</h1></div><div class="top-actions"><span class="sync-state">● 只读查看</span><div class="avatar">教</div></div></header>
  <section class="data-page panel published-page">
    <div class="data-toolbar"><div><span class="eyebrow">PUBLISHED SCHEDULES</span><h2>已发布版本</h2></div><div class="published-actions"><el-button plain :disabled="!selected" @click="download('xlsx')">下载 Excel</el-button><el-button plain :disabled="!selected" @click="download('pdf')">下载 PDF</el-button><el-button plain :disabled="!selected" @click="print">打印</el-button><el-button plain :loading="loading" @click="load">刷新</el-button></div></div>
    <div v-if="message" class="inline-message error-message">{{ message }}</div>
    <div v-if="versions.length" class="published-list"><button v-for="version in versions" :key="version.id" class="version-row" :class="{ selected: selected === version.id }" @click="selected = version.id"><strong>版本 v{{ version.id }} · revision {{ version.revision ?? 0 }}</strong><span>{{ version.status }} · {{ version.score ?? '未评分' }} · H{{ scoreParts(version).hard ?? '—' }} / M{{ scoreParts(version).medium ?? '—' }} / S{{ scoreParts(version).soft ?? '—' }}</span><small>{{ version.createdAt ?? '' }}</small></button></div>
    <el-empty v-else-if="!loading" description="暂无已发布课表" />
  </section>
</template>
