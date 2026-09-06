<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { aiChat, aiStatus } from '../api/ai'

interface ChatLine { role: 'user' | 'assistant'; content: string }

const visible = ref(false)
const chatEnabled = ref<boolean | null>(null)
const model = ref('')
const sending = ref(false)
const draft = ref('')
const lines = ref<ChatLine[]>([
  { role: 'assistant', content: '你好！我是排课助手，可以解释评分、冲突原因、版本与发布流程，或给出调整建议。' },
])
const listEl = ref<HTMLDivElement | null>(null)

watch(visible, open => {
  if (!open || chatEnabled.value !== null) return
  aiStatus()
    .then(status => {
      chatEnabled.value = status.chatEnabled
      model.value = status.model ?? ''
      if (status.chatEnabled && model.value) {
        lines.value.push({ role: 'assistant', content: `当前模型：${model.value}。` })
      }
    })
    .catch(() => {
      chatEnabled.value = false
    })
})

async function send() {
  const content = draft.value.trim()
  if (!content || sending.value || chatEnabled.value === false) return
  lines.value.push({ role: 'user', content })
  draft.value = ''
  sending.value = true
  await nextTick()
  listEl.value?.scrollTo({ top: listEl.value.scrollHeight })
  try {
    const history = lines.value
      .filter(line => line !== lines.value[lines.value.length - 1])
      .slice(-10)
      .map(line => ({ content: `${line.role === 'user' ? '用户' : '助手'}：${line.content}` }))
    const result = await aiChat([...history, { content }])
    lines.value.push({ role: 'assistant', content: result.reply })
  } catch (error) {
    lines.value.push({ role: 'assistant', content: error instanceof Error ? error.message : 'AI 服务调用失败' })
  } finally {
    sending.value = false
    await nextTick()
    listEl.value?.scrollTo({ top: listEl.value.scrollHeight })
  }
}
</script>

<template>
  <el-button class="ai-fab" type="primary" circle @click="visible = true">AI</el-button>
  <el-drawer v-model="visible" title="排课助手" size="420px">
    <div ref="listEl" class="ai-chat-list">
      <div v-for="(line, index) in lines" :key="index" class="ai-chat-line" :class="line.role">
        <div class="ai-chat-bubble">{{ line.content }}</div>
      </div>
      <div v-if="sending" class="ai-chat-line assistant"><div class="ai-chat-bubble">思考中…</div></div>
    </div>
    <div v-if="chatEnabled === false" class="ai-chat-hint">
      未配置 AI 服务：请在部署环境设置 APP_AI_BASE_URL / APP_AI_API_KEY / APP_AI_MODEL 后重启后端。
    </div>
    <div v-else class="ai-chat-input">
      <el-input
        v-model="draft"
        type="textarea"
        :rows="2"
        placeholder="问点什么，例如：为什么不能发布？"
        @keydown.enter.exact.prevent="send"
      />
      <el-button type="primary" :loading="sending" :disabled="!draft.trim()" @click="send">发送</el-button>
    </div>
  </el-drawer>
</template>

<style scoped>
.ai-fab {
  position: fixed;
  right: 20px;
  bottom: 24px;
  z-index: 2000;
  font-weight: 600;
}
.ai-chat-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 4px;
  min-height: 0;
}
.ai-chat-line.user { display: flex; justify-content: flex-end; }
.ai-chat-line.assistant { display: flex; justify-content: flex-start; }
.ai-chat-bubble {
  max-width: 85%;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-chat-line.user .ai-chat-bubble { background: var(--el-color-primary-light-8); }
.ai-chat-line.assistant .ai-chat-bubble { background: var(--el-fill-color-light); }
.ai-chat-hint {
  margin-top: 10px;
  padding: 10px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.ai-chat-input {
  margin-top: 10px;
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
</style>
