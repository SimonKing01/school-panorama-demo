// fetch 统一封装：同源访问 /api，错误信息统一转成可展示文本。
// 后端错误体：{ code, message, conflicts:[{type,message}], demoTime }，HTTP 400/404/500。

export class ApiError extends Error {
  conflicts: string[]

  constructor(message: string, conflicts: string[] = []) {
    super(message)
    this.name = 'ApiError'
    this.conflicts = conflicts
  }

  get fullMessage(): string {
    return this.conflicts.length
      ? `${this.message}\n${this.conflicts.map((c) => '· ' + c).join('\n')}`
      : this.message
  }
}

export type QueryParams = Record<string, string | number | boolean | undefined | null>

function buildQuery(params?: QueryParams): string {
  if (!params) return ''
  const q = new URLSearchParams()
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined || v === null || v === '') return
    q.append(k, String(v))
  })
  const s = q.toString()
  return s ? '?' + s : ''
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  // 无 body 的 POST（sync-today / reset）不带 Content-Type，避免后端把空体当 JSON 解析失败。
  const headers: Record<string, string> = {}
  if (init?.body !== undefined && init.body !== null) {
    headers['Content-Type'] = 'application/json'
  }

  let res: Response
  try {
    res = await fetch('/api' + path, {
      ...init,
      headers: { ...headers, ...(init?.headers as Record<string, string> | undefined) },
    })
  } catch {
    throw new ApiError('无法连接后端服务，请稍后重试')
  }

  let data: Record<string, unknown> = {}
  try {
    data = (await res.json()) as Record<string, unknown>
  } catch {
    // 非 JSON 响应（如代理异常），用空对象兜底
  }

  if (!res.ok) {
    const raw = data.conflicts
    const conflicts = Array.isArray(raw)
      ? raw
          .map((c) => {
            if (c && typeof c === 'object' && 'message' in c) return String((c as { message: unknown }).message)
            return String(c)
          })
          .filter(Boolean)
      : []
    const message = typeof data.message === 'string' ? data.message : `请求失败（HTTP ${res.status}）`
    throw new ApiError(message, conflicts)
  }
  return data as unknown as T
}

export function apiGet<T>(path: string, params?: QueryParams): Promise<T> {
  return request<T>(path + buildQuery(params), { method: 'GET' })
}

export function apiPost<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
}

// 把任意异常转成可展示的中文文本
export function errorText(err: unknown): string {
  if (err instanceof ApiError) return err.fullMessage
  if (err instanceof Error) return err.message
  return String(err)
}
