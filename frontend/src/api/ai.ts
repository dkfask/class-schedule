import { http, jsonRequest } from './http'

export interface AiStatus {
  diagnosticsEnabled: boolean
  chatEnabled: boolean
  model: string
}

export interface AiSettings {
  baseUrl: string
  protocol: 'OPENAI' | 'ANTHROPIC' | string
  model: string
  apiKeyConfigured: boolean
  chatEnabled: boolean
  source: 'ADMIN' | 'ENVIRONMENT' | string
  updatedAt?: string | null
}

export interface AiSettingsRequest {
  baseUrl: string
  protocol: 'OPENAI' | 'ANTHROPIC'
  model: string
  apiKey: string
  clearApiKey: boolean
}

export interface AiFinding {
  severity: string
  title: string
  detail: string
}

export interface AiDiagnosis {
  versionId: number
  status: string
  score?: string | null
  metrics: {
    occurrences: number
    unplaced: number
    roomless: number
    teachers: number
    studentGroups: number
    roomUtilization: Record<string, number>
  }
  findings: AiFinding[]
  suggestions: string[]
}

export async function aiStatus(): Promise<AiStatus> {
  return http<AiStatus>('/api/ai-assist/status')
}

export async function aiSettings(): Promise<AiSettings> {
  return http<AiSettings>('/api/ai-assist/settings')
}

export async function updateAiSettings(request: AiSettingsRequest): Promise<AiSettings> {
  return http<AiSettings>('/api/ai-assist/settings', jsonRequest('PUT', request))
}

export async function aiDiagnostics(versionId: number): Promise<AiDiagnosis> {
  return http<AiDiagnosis>(`/api/ai-assist/diagnostics/${versionId}`)
}

export async function aiChat(messages: { content: string }[]): Promise<{ reply: string }> {
  return http<{ reply: string }>(
    '/api/ai-assist/chat',
    { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ messages }) },
  )
}
