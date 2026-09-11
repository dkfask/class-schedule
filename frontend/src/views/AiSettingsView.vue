<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { aiSettings, updateAiSettings, type AiSettings } from '../api/ai'

const settings = ref<AiSettings | null>(null)
const form = ref({ baseUrl: '', protocol: 'OPENAI' as 'OPENAI' | 'ANTHROPIC', model: '', apiKey: '', clearApiKey: false })
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const message = ref('')

function applySettings(value: AiSettings) {
  settings.value = value
  form.value.baseUrl = value.baseUrl
  form.value.protocol = value.protocol === 'ANTHROPIC' ? 'ANTHROPIC' : 'OPENAI'
  form.value.model = value.model
  form.value.apiKey = ''
  form.value.clearApiKey = false
}

function formatDate(value?: string | null) {
  if (!value) return '尚未通过管理员保存'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    applySettings(await aiSettings())
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'AI 配置加载失败'
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  message.value = ''
  try {
    applySettings(await updateAiSettings({
      baseUrl: form.value.baseUrl,
      protocol: form.value.protocol,
      model: form.value.model,
      apiKey: form.value.apiKey,
      clearApiKey: form.value.clearApiKey,
    }))
    message.value = 'AI 配置已保存并立即生效'
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : 'AI 配置保存失败'
  } finally {
    saving.value = false
  }
}

onMounted(() => void load())
</script>

<template>
  <header class="topbar">
    <div>
      <p class="eyebrow">ADMIN / AI CONFIGURATION</p>
      <h1>AI 模型设置</h1>
      <p class="topbar-subtitle">配置系统级 AI 助手的服务连接</p>
    </div>
    <div class="top-actions">
      <span class="sync-state"><i></i>{{ loading ? '正在读取配置' : '管理员设置' }}</span>
      <div class="avatar">管</div>
    </div>
  </header>

  <main class="ai-settings-page">
    <div v-if="error" class="inline-message error-message" role="alert">{{ error }}</div>
    <div v-if="message" class="inline-message success-message" role="status">{{ message }}</div>

    <section class="settings-layout" aria-label="AI 模型配置">
      <div class="settings-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">MODEL PROVIDER</span>
            <h2>服务连接</h2>
          </div>
          <span class="panel-caption">{{ settings?.source === 'ADMIN' ? '数据库配置' : '环境变量配置' }}</span>
        </div>

        <form class="settings-form" @submit.prevent="save">
          <label>
            <span>接口协议</span>
            <select v-model="form.protocol">
              <option value="OPENAI">OpenAI 兼容</option>
              <option value="ANTHROPIC">Anthropic 兼容</option>
            </select>
            <small>MiniMax Anthropic 兼容服务请选择 Anthropic。</small>
          </label>
          <label>
            <span>兼容接口地址</span>
            <input v-model="form.baseUrl" type="url" maxlength="512" :placeholder="form.protocol === 'ANTHROPIC' ? 'https://api.minimax.cn/anthropic' : 'https://api.example.com/v1'" autocomplete="url" />
            <small>留空可停用对话服务；地址需使用 HTTP(S)。</small>
          </label>
          <label>
            <span>模型名称</span>
            <input v-model="form.model" maxlength="128" placeholder="例如：deepseek-chat" autocomplete="off" />
          </label>
          <label>
            <span>API Key</span>
            <input v-model="form.apiKey" type="password" maxlength="4096" placeholder="留空保持当前密钥" autocomplete="new-password" @input="form.clearApiKey = false" />
            <small>密钥不会回显；输入新值会覆盖当前密钥。</small>
          </label>
          <label class="clear-key-option">
            <input v-model="form.clearApiKey" type="checkbox" :disabled="!settings?.apiKeyConfigured || Boolean(form.apiKey.trim())" />
            <span>清除当前已保存的 API Key</span>
          </label>
          <div class="settings-actions">
            <small>{{ saving ? '正在保存...' : '保存后立即用于新的 AI 请求' }}</small>
            <button class="primary-button" type="submit" :disabled="loading || saving">保存并应用</button>
          </div>
        </form>
      </div>

      <aside class="settings-status-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">RUNTIME STATUS</span>
            <h2>当前状态</h2>
          </div>
          <span class="settings-status-mark" :class="{ ready: settings?.chatEnabled }"></span>
        </div>
        <dl class="settings-facts">
          <div><dt>服务状态</dt><dd :class="{ ready: settings?.chatEnabled }">{{ settings?.chatEnabled ? '已连接' : '未就绪' }}</dd></div>
          <div><dt>接口协议</dt><dd>{{ settings?.protocol === 'ANTHROPIC' ? 'Anthropic 兼容' : 'OpenAI 兼容' }}</dd></div>
          <div><dt>当前模型</dt><dd>{{ settings?.model || '未配置' }}</dd></div>
          <div><dt>API Key</dt><dd>{{ settings?.apiKeyConfigured ? '已安全保存' : '未配置' }}</dd></div>
          <div><dt>最近更新</dt><dd>{{ formatDate(settings?.updatedAt) }}</dd></div>
        </dl>
        <p class="settings-note">排课诊断功能继续使用本地规则引擎，不依赖外部 AI 服务。</p>
      </aside>
    </section>
  </main>
</template>

<style scoped>
.ai-settings-page { display: grid; gap: 16px; margin: 24px 32px 0; }
.settings-layout { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 16px; align-items: start; }
.settings-panel, .settings-status-panel { min-width: 0; overflow: hidden; background: #fff; border: 1px solid #D9DEE3; border-radius: 6px; box-shadow: 0 1px 3px rgba(32, 42, 53, .05); }
.settings-form { display: grid; gap: 18px; padding: 20px; }
.settings-form label:not(.clear-key-option) { display: grid; gap: 7px; }
.settings-form label > span { color: #182029; font-size: 11px; font-weight: 600; }
.settings-form input:not([type="checkbox"]) { width: 100%; min-height: 36px; padding: 8px 10px; color: #182029; background: #F9FAFB; border: 1px solid #D9DEE3; border-radius: 4px; outline: none; font-size: 12px; }
.settings-form select { width: 100%; min-height: 36px; padding: 8px 10px; color: #182029; background: #F9FAFB; border: 1px solid #D9DEE3; border-radius: 4px; outline: none; font-size: 12px; }
.settings-form input:not([type="checkbox"]):focus { border-color: #B85C45; box-shadow: 0 0 0 3px rgba(184, 92, 69, .12); }
.settings-form select:focus { border-color: #B85C45; box-shadow: 0 0 0 3px rgba(184, 92, 69, .12); }
.settings-form label small { color: #8795A5; font-size: 10px; line-height: 1.4; }
.clear-key-option { display: flex; align-items: center; gap: 8px; color: #566474; font-size: 11px; }
.clear-key-option input { accent-color: #B85C45; }
.clear-key-option input:disabled { opacity: .5; }
.settings-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-top: 16px; border-top: 1px solid #EEF1F3; }
.settings-actions small { color: #8795A5; font-size: 10px; }
.settings-actions .primary-button { border: 1px solid #B85C45; background: #B85C45; color: #fff; border-radius: 4px; padding: 9px 14px; cursor: pointer; font-size: 11px; }
.settings-actions .primary-button:hover:not(:disabled) { background: #984936; border-color: #984936; }
.settings-actions .primary-button:disabled { cursor: wait; opacity: .55; }
.settings-status-mark { width: 8px; height: 8px; margin: 5px 2px 0 0; border-radius: 50%; background: #D19A3B; }
.settings-status-mark.ready { background: #3F806F; }
.settings-facts { display: grid; gap: 0; margin: 0; padding: 5px 19px; }
.settings-facts div { display: flex; align-items: baseline; justify-content: space-between; gap: 14px; padding: 14px 0; border-bottom: 1px solid #EEF1F3; }
.settings-facts div:last-child { border-bottom: 0; }
.settings-facts dt { color: #8795A5; font-size: 10px; }
.settings-facts dd { max-width: 190px; margin: 0; color: #182029; font-family: 'JetBrains Mono', ui-monospace, monospace; font-size: 10px; text-align: right; overflow-wrap: anywhere; }
.settings-facts dd.ready { color: #3F806F; }
.settings-note { margin: 8px 19px 19px; padding-top: 14px; border-top: 1px solid #EEF1F3; color: #566474; font-size: 10px; line-height: 1.55; }
.inline-message { margin: 0; }
@media (max-width: 900px) { .ai-settings-page { margin-left: 20px; margin-right: 20px; } .settings-layout { grid-template-columns: 1fr; } }
@media (max-width: 640px) { .ai-settings-page { margin: 14px; } .settings-form { padding: 14px; } .settings-actions { align-items: flex-start; flex-direction: column; } .settings-actions .primary-button { width: 100%; } }
</style>
