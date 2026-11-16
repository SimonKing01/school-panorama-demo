/** 统一 fetch 封装：按 03 契约 {code,data,message} 解包，错误给中文提示。 */
const BASE = '/ontology-api'

export interface ApiEnvelope<T> {
  code: string
  data: T
  message: string
}

/**
 * 带机器可读信息的接口错误。
 *
 * <p>校验失败（400 VALIDATION_FAILED）时 {@code data.errors[]} 是逐条中文错误，
 * 409 DELETE_IMPACT_CONFIRM 时 {@code data} 是删除影响清单。
 * 继承自 Error，因此既有 {@code e instanceof Error ? e.message} 的调用点不受影响。</p>
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly data: unknown

  constructor(status: number, code: string, message: string, data: unknown) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.data = data
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(BASE + path, {
      ...init,
      headers: {
        'Content-Type': 'application/json;charset=UTF-8',
        ...(init?.headers ?? {}),
      },
    })
  } catch {
    throw new Error('本体服务不可达，请稍后重试（已同步数据仍保留）')
  }
  if (!response.ok) {
    let message = `请求失败（HTTP ${response.status}）`
    let code = `HTTP_${response.status}`
    let data: unknown = null
    try {
      const body = (await response.json()) as ApiEnvelope<unknown>
      if (body.message) message = body.message
      if (body.code) code = body.code
      data = body.data ?? null
    } catch {
      // 非 JSON 错误体，保留 HTTP 状态提示。
    }
    throw new ApiError(response.status, code, message, data)
  }
  let body: ApiEnvelope<T>
  try {
    body = (await response.json()) as ApiEnvelope<T>
  } catch {
    throw new Error('本体服务返回的内容无法解析')
  }
  if (body.code !== 'OK') {
    throw new ApiError(response.status, body.code, body.message || `本体服务返回错误码：${body.code}`,
      body.data ?? null)
  }
  return body.data
}

/** 构造 query string；所有值统一编码，globalId 中的 ':' 会被编码为 %3A。 */
export function qs(params: Record<string, unknown>): string {
  const parts: string[] = []
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`)
  }
  return parts.length ? `?${parts.join('&')}` : ''
}

/** 路径参数统一编码，兼容裸冒号与全编码两种 globalId 写法。 */
export function gidPath(globalId: string): string {
  return encodeURIComponent(globalId)
}

export const api = {
  get<T>(path: string): Promise<T> {
    return request<T>(path)
  },
  post<T>(path: string, body: unknown = {}): Promise<T> {
    return request<T>(path, { method: 'POST', body: JSON.stringify(body) })
  },
  put<T>(path: string, body: unknown = {}): Promise<T> {
    return request<T>(path, { method: 'PUT', body: JSON.stringify(body) })
  },
  del<T>(path: string, body: unknown = {}): Promise<T> {
    return request<T>(path, { method: 'DELETE', body: JSON.stringify(body) })
  },
}
