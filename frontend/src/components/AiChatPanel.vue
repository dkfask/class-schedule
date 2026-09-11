<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { aiChat, aiStatus } from '../api/ai'

interface ChatLine { role: 'user' | 'assistant'; content: string; time?: string }

const visible = ref(false)
const chatEnabled = ref<boolean | null>(null)
const model = ref('')
const sending = ref(false)
const draft = ref('')
const lines = ref<ChatLine[]>([
  { role: 'assistant', content: '您好！我是智能排课顾问。我可以为您深度分析排课评分、定位规则冲突、解析算法瓶颈，或提供课表微调建议。请问有什么可以帮您？', time: '刚刚' },
])
const listEl = ref<HTMLDivElement | null>(null)

const promptPills = [
  '为什么方案有硬冲突？',
  '如何消除跨天连堂与空档？',
  '已发布版本如何做微调？',
  '怎么提高教室利用率？'
]

function usePill(text: string) {
  draft.value = text
  void send()
}

watch(visible, open => {
  if (!open || chatEnabled.value !== null) return
  aiStatus()
    .then(status => {
      chatEnabled.value = status.chatEnabled
      model.value = status.model ?? ''
      if (status.chatEnabled && model.value) {
        lines.value.push({ role: 'assistant', content: `已接入大模型服务：${model.value}，支持全上下文多轮排课推演。`, time: '在线' })
      }
    })
    .catch(() => {
      chatEnabled.value = false
    })
})

function formatTime() {
  const now = new Date()
  return `${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
}

async function send() {
  const content = draft.value.trim()
  if (!content || sending.value || chatEnabled.value === false) return
  lines.value.push({ role: 'user', content, time: formatTime() })
  draft.value = ''
  sending.value = true
  await nextTick()
  listEl.value?.scrollTo({ top: listEl.value.scrollHeight, behavior: 'smooth' })
  try {
    const history = lines.value
      .filter(line => line !== lines.value[lines.value.length - 1])
      .slice(-10)
      .map(line => ({ content: `${line.role === 'user' ? '用户' : '助手'}：${line.content}` }))
    const result = await aiChat([...history, { content }])
    lines.value.push({ role: 'assistant', content: result.reply, time: formatTime() })
  } catch (error) {
    lines.value.push({ role: 'assistant', content: error instanceof Error ? error.message : 'AI 服务调用失败', time: formatTime() })
  } finally {
    sending.value = false
    await nextTick()
    listEl.value?.scrollTo({ top: listEl.value.scrollHeight, behavior: 'smooth' })
  }
}
</script>

<template>
  <button class="ai-fab" title="打开 AI 排课助手" @click="visible = true">
    <div class="ai-fab-glow"></div>
    <span class="ai-fab-sparkle">✦</span>
    <span class="ai-fab-text">AI 助手</span>
  </button>

  <el-drawer
    v-model="visible"
    title="排课助手"
    custom-class="ai-chat-drawer"
    :with-header="false"
    size="460px"
    aria-label="排课助手"
  >
    <div class="drawer-container">
      <!-- 顶部 Header -->
      <header class="drawer-header">
        <div class="header-main">
          <div class="ai-avatar">
            <span class="ai-avatar-icon">✦</span>
          </div>
          <div>
            <div class="title-row">
              <h3>智能排课助手</h3>
              <span v-if="chatEnabled" class="status-badge online">
                <i class="dot"></i>已就绪
              </span>
              <span v-else class="status-badge offline">未连接</span>
            </div>
            <p class="model-caption">
              {{ model ? `模型引擎 · ${model}` : 'Timefold 协同排课认知智能' }}
            </p>
          </div>
        </div>
        <button class="close-btn" @click="visible = false" title="关闭">✕</button>
      </header>

      <!-- 消息列表 -->
      <main ref="listEl" class="ai-chat-list">
        <div class="watermark-bg">
          <span class="watermark-symbol">TIMEFOLD AI</span>
        </div>

        <div
          v-for="(line, index) in lines"
          :key="index"
          class="ai-chat-line"
          :class="line.role"
        >
          <div v-if="line.role === 'assistant'" class="msg-avatar">✦</div>
          <div class="msg-body">
            <div class="ai-chat-bubble">
              {{ line.content }}
            </div>
            <span v-if="line.time" class="msg-meta">{{ line.time }}</span>
          </div>
        </div>

        <!-- 思考中动效 -->
        <div v-if="sending" class="ai-chat-line assistant">
          <div class="msg-avatar">✦</div>
          <div class="msg-body">
            <div class="ai-chat-bubble thinking-bubble">
              <span class="thinking-text">排课模型推演中</span>
              <span class="pulse-dots">
                <i></i><i></i><i></i>
              </span>
            </div>
          </div>
        </div>
      </main>

      <!-- 快捷提问 Pill -->
      <div v-if="chatEnabled !== false && lines.length <= 3" class="quick-prompts">
        <button
          v-for="pill in promptPills"
          :key="pill"
          class="prompt-pill"
          @click="usePill(pill)"
        >
          {{ pill }}
        </button>
      </div>

      <!-- 底部输入区 -->
      <footer class="drawer-footer">
        <div v-if="chatEnabled === false" class="ai-chat-hint">
          <span class="hint-icon">⚙</span>
          <div>
            <strong>AI 代理服务未就绪</strong>
            <p>请联系系统管理员在“AI 模型设置”中配置服务。</p>
          </div>
        </div>
        <div v-else class="ai-chat-input">
          <div class="input-card">
            <textarea
              v-model="draft"
              class="styled-textarea"
              rows="2"
              placeholder="问点什么…例如：为什么周一第1节排不进？(Enter 发送)"
              @keydown.enter.exact.prevent="send"
            ></textarea>
            <div class="input-actions">
              <span class="input-tip">Shift + Enter 换行</span>
              <button
                class="send-btn"
                :disabled="!draft.trim() || sending"
                @click="send"
              >
                <span v-if="!sending">发送 ↗</span>
                <span v-else>推演中…</span>
              </button>
            </div>
          </div>
        </div>
      </footer>
    </div>
  </el-drawer>
</template>

<style scoped>
/* 悬浮 FAB 按钮 */
.ai-fab {
  position: fixed;
  right: 24px;
  bottom: 28px;
  z-index: 2000;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 18px 10px 14px;
  background: #202A35;
  color: #e5f6ed;
  border: 1px solid rgba(157, 219, 187, 0.35);
  border-radius: 6px;
  font-weight: 600;
  font-size: 13px;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(32, 42, 53, .2);
  transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}
.ai-fab:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(32, 42, 53, .26);
  border-color: rgba(157, 219, 187, 0.7);
}
.ai-fab-sparkle {
  color: #6ee7b7;
  font-size: 14px;
}
.ai-fab-text {
  letter-spacing: 0.02em;
}

/* 抽屉整体容器布局 */
.drawer-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #F9FAFB;
  color: #1a2a24;
  font-family: Inter, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
}

/* 顶部 Header */
.drawer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid rgba(223, 235, 229, 0.8);
  background: #ffffff;
}
.header-main {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ai-avatar {
  width: 38px;
  height: 38px;
  border-radius: 10px;
  background: #B85C45;
  color: #a7f3d0;
  display: grid;
  place-items: center;
  font-size: 16px;
  box-shadow: 0 4px 12px rgba(27, 90, 69, 0.2);
}
.title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.title-row h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
  color: #16382b;
}
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 7px;
  border-radius: 9999px;
  font-size: 11px;
  font-weight: 500;
}
.status-badge.online {
  background: #e6f7ef;
  color: #1e7048;
}
.status-badge.online .dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #10b981;
  box-shadow: 0 0 6px #10b981;
}
.status-badge.offline {
  background: #f1f5f3;
  color: #83978f;
}
.model-caption {
  margin: 2px 0 0;
  font-size: 11px;
  color: #7b948a;
}
.close-btn {
  background: transparent;
  border: 0;
  color: #8c9e97;
  width: 28px;
  height: 28px;
  border-radius: 6px;
  display: grid;
  place-items: center;
  cursor: pointer;
  font-size: 14px;
  transition: all 0.2s;
}
.close-btn:hover {
  background: #edf3f0;
  color: #1d3c31;
}

/* 消息列表 */
.ai-chat-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 24px;
  position: relative;
}
.watermark-bg {
  position: absolute;
  top: 40%;
  left: 50%;
  transform: translate(-50%, -50%);
  pointer-events: none;
  opacity: 0.06;
}
.watermark-symbol {
  font-size: 40px;
  font-weight: 900;
  letter-spacing: 0.15em;
  color: #184e3c;
}

.ai-chat-line {
  display: flex;
  gap: 10px;
  max-width: 100%;
}
.ai-chat-line.user {
  justify-content: flex-end;
}
.ai-chat-line.assistant {
  justify-content: flex-start;
}
.msg-avatar {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: #e1f0e8;
  color: #984936;
  font-size: 12px;
  display: grid;
  place-items: center;
  flex-shrink: 0;
  margin-top: 2px;
}
.msg-body {
  display: flex;
  flex-direction: column;
  max-width: 82%;
}
.ai-chat-line.user .msg-body {
  align-items: flex-end;
}
.ai-chat-bubble {
  padding: 11px 15px;
  font-size: 13.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-word;
}
.ai-chat-line.assistant .ai-chat-bubble {
  background: #ffffff;
  color: #21352e;
  border-radius: 4px 16px 16px 16px;
  box-shadow: 0 2px 12px rgba(22, 54, 43, 0.05), 0 0 0 1px rgba(223, 233, 228, 0.7);
}
.ai-chat-line.user .ai-chat-bubble {
  background: #B85C45;
  color: #ffffff;
  border-radius: 16px 4px 16px 16px;
  box-shadow: 0 4px 14px rgba(27, 90, 69, 0.2);
}
.msg-meta {
  font-size: 10px;
  color: #8795A5;
  margin-top: 4px;
  padding: 0 4px;
}

/* 思考中动画 */
.thinking-bubble {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #4a685c !important;
  font-size: 12.5px;
}
.pulse-dots {
  display: inline-flex;
  gap: 4px;
}
.pulse-dots i {
  width: 4px;
  height: 4px;
  background: #2b7a5a;
  border-radius: 50%;
  animation: dotPulse 1.4s infinite ease-in-out both;
}
.pulse-dots i:nth-child(1) { animation-delay: -0.32s; }
.pulse-dots i:nth-child(2) { animation-delay: -0.16s; }
@keyframes dotPulse {
  0%, 80%, 100% { transform: scale(0); opacity: 0.3; }
  40% { transform: scale(1); opacity: 1; }
}

/* 快捷提问胶囊 */
.quick-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 0 24px 14px;
}
.prompt-pill {
  border: 1px solid #dbe6e0;
  background: #ffffff;
  color: #315848;
  padding: 6px 12px;
  border-radius: 9999px;
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.2s;
  box-shadow: 0 1px 3px rgba(0,0,0,0.02);
}
.prompt-pill:hover {
  background: #f0f7f3;
  border-color: #92caa9;
  color: #144937;
  transform: translateY(-1px);
}

/* 底部输入框卡片 */
.drawer-footer {
  padding: 16px 20px 24px;
  background: #ffffff;
  border-top: 1px solid rgba(223, 235, 229, 0.8);
}
.input-card {
  border: 1px solid #d3e0d8;
  border-radius: 12px;
  padding: 10px 12px 8px;
  background: #fafcfb;
  box-shadow: 0 2px 8px rgba(25, 46, 38, 0.03);
  transition: all 0.2s ease;
}
.input-card:focus-within {
  background: #ffffff;
  border-color: #B85C45;
  box-shadow: 0 0 0 3px rgba(46, 138, 101, 0.12), 0 4px 12px rgba(25, 46, 38, 0.05);
}
.styled-textarea {
  width: 100%;
  border: 0;
  outline: none;
  background: transparent;
  resize: none;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.55;
  color: #172822;
}
.styled-textarea::placeholder {
  color: #94aaa0;
}
.input-actions {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 1px dashed #e8efe9;
}
.input-tip {
  font-size: 11px;
  color: #8795A5;
}
.send-btn {
  border: 0;
  background: #257053;
  color: #ffffff;
  padding: 6px 14px;
  border-radius: 7px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.2s;
}
.send-btn:hover:not(:disabled) {
  background: #1c5942;
  box-shadow: 0 2px 8px rgba(37, 112, 83, 0.3);
}
.send-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* 未配置状态卡片 */
.ai-chat-hint {
  display: flex;
  gap: 12px;
  padding: 14px;
  border-radius: 10px;
  background: #fff8f0;
  border: 1px solid #fed7aa;
  color: #9a5317;
  font-size: 12px;
  line-height: 1.5;
}
.hint-icon {
  font-size: 18px;
}
.ai-chat-hint strong {
  display: block;
  font-size: 13px;
  color: #823c0b;
  margin-bottom: 2px;
}
.ai-chat-hint p {
  margin: 0;
}
.ai-chat-hint code {
  background: rgba(254, 215, 170, 0.4);
  padding: 1px 4px;
  border-radius: 4px;
  font-family: monospace;
}
</style>
