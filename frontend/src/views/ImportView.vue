<script setup lang="ts">
import ImportPanel from '../components/ImportPanel.vue'
</script>

<template>
  <header class="topbar">
    <div>
      <p class="eyebrow">学期准备</p>
      <h1>数据导入</h1>
    </div>
    <div class="top-actions">
      <span class="sync-state">● 导入批次可追踪</span>
      <div class="avatar">教</div>
    </div>
  </header>
  <ImportPanel />
</template>
