/** 统一 fetch 封装：按 03 契约 {code,data,message} 解包，错误给中文提示。 */
const BASE = '/ontology-api'

export interface ApiEnvelope<T> {
  code: string
  data: T
  message: string
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
    try {
      const body = (await response.json()) as ApiEnvelope<unknown>
      if (body.message) message = body.message
    } catch {
      // 非 JSON 错误体，保留 HTTP 状态提示。
    }
    throw new Error(message)
  }
  let body: ApiEnvelope<T>
  try {
    body = (await response.json()) as ApiEnvelope<T>
  } catch {
    throw new Error('本体服务返回的内容无法解析')
  }
  if (body.code !== 'OK') {
    throw new Error(body.message || `本体服务返回错误码：${body.code}`)
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
}
