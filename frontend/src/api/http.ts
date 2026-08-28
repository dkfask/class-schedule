export interface HttpError extends Error {
  status?: number
  code?: string
  body?: unknown
}

interface CsrfToken {
  headerName: string
  token: string
}

let csrfToken: CsrfToken | null = null

function requiresCsrf(method?: string) {
  return !['GET', 'HEAD', 'OPTIONS'].includes((method ?? 'GET').toUpperCase())
}

async function loadCsrfToken(): Promise<CsrfToken> {
  const response = await fetch('/api/auth/csrf', {
    credentials: 'include',
    headers: { Accept: 'application/json' },
  })
  const body = await response.json().catch(() => ({})) as Partial<CsrfToken> & { message?: string }
  if (!response.ok || !body.headerName || !body.token) {
    throw new Error(body.message ?? '无法获取 CSRF 令牌')
  }
  csrfToken = { headerName: body.headerName, token: body.token }
  return csrfToken
}

export function clearCsrfToken() {
  csrfToken = null
}

export async function http<T>(url: string, init: RequestInit = {}): Promise<T> {
  const method = init.method ?? 'GET'
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/json')
  if (requiresCsrf(method)) {
    const token = csrfToken ?? await loadCsrfToken()
    headers.set(token.headerName, token.token)
  }
  const response = await fetch(url, {
    ...init,
    credentials: 'include',
    headers,
  })
  const body = await response.json().catch(() => ({}))
  if (response.status === 401) {
    window.dispatchEvent(new CustomEvent('auth:expired'))
  }
  if (!response.ok) {
    const error = new Error(body.message ?? body.errorMessage ?? `请求失败 (${response.status})`) as HttpError
    error.status = response.status
    error.code = body.code
    error.body = body
    throw error
  }
  return body as T
}

export async function downloadBlob(url: string, init: RequestInit = {}): Promise<Blob> {
  const method = init.method ?? 'GET'
  const headers = new Headers(init.headers)
  headers.set('Accept', 'application/octet-stream')
  if (requiresCsrf(method)) {
    const token = csrfToken ?? await loadCsrfToken()
    headers.set(token.headerName, token.token)
  }
  const response = await fetch(url, {
    ...init,
    credentials: 'include',
    headers,
  })
  if (response.status === 401) {
    window.dispatchEvent(new CustomEvent('auth:expired'))
  }
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { message?: string; errorMessage?: string; code?: string }
    const error = new Error(body.message ?? body.errorMessage ?? `请求失败 (${response.status})`) as HttpError
    error.status = response.status
    error.code = body.code
    error.body = body
    throw error
  }
  return response.blob()
}

export function jsonRequest(method: string, body?: unknown): RequestInit {
  return { method, headers: { 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) }
}
