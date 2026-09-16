<script setup lang="ts">
import { RouterLink } from 'vue-router'

defineProps<{
  title: string
  description: string
  actionLabel?: string
  to?: string
}>()
</script>

<template>
  <div class="empty-state-card" data-testid="empty-state">
    <strong>{{ title }}</strong>
    <small>{{ description }}</small>
    <RouterLink v-if="to && actionLabel" class="empty-state-action" :to="to">{{ actionLabel }}</RouterLink>
  </div>
</template>

<style scoped>
.empty-state-card {
  display: grid;
  justify-items: center;
  gap: 8px;
  margin: 8px 18px 18px;
  padding: 28px 20px;
  border: 1px dashed #cddbd5;
  border-radius: 10px;
  background: #f8fbf9;
  text-align: center;
}
.empty-state-card strong {
  color: #2d5544;
  font-size: 13px;
}
.empty-state-card small {
  max-width: 28rem;
  color: #6d8075;
  font-size: 12px;
  line-height: 1.5;
}
.empty-state-action {
  margin-top: 4px;
  color: #2d6a56;
  font-size: 12px;
  font-weight: 650;
  text-decoration: none;
}
</style>
