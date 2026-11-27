// 调用后端 /api 接口：自动带上登录 Cookie 和 CSRF 令牌，出错时抛出带说明的 ApiError
import router from './router'
import { auth } from './auth'

export class ApiError extends Error {
  constructor(status, message, fields) {
    super(message)
    this.status = status
    this.fields = fields || {}
  }
}

/** 给用户看的错误说明：字段校验错误时带上具体是哪一项。 */
export function errorText(e) {
  const first = e && e.fields ? Object.values(e.fields)[0] : null
  return first ? `${e.message}：${first}` : (e?.message || '操作失败')
}

function csrfToken() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
  return m ? decodeURIComponent(m[1]) : ''
}

export async function request(method, url, body, { form = false, headers: extraHeaders = {} } = {}) {
  const headers = { Accept: 'application/json', ...extraHeaders }
  if (method !== 'GET') headers['X-XSRF-TOKEN'] = csrfToken()
  let payload
  if (form) {
    payload = body // FormData：浏览器自己设置 Content-Type
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  let res
  try {
    res = await fetch(url, { method, headers, body: payload, credentials: 'same-origin' })
  } catch (e) {
    throw new ApiError(0, '网络错误，请检查后端是否在运行')
  }
  if (res.status === 204) return null
  const data = await res.json().catch(() => ({}))
  if (!res.ok) {
    if (res.status === 401 && !url.startsWith('/api/admin/session')) {
      auth.authenticated = false
      auth.loaded = false
      auth.roles = []
      router.push({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } })
    }
    throw new ApiError(res.status, data.message || `请求失败（${res.status}）`, data.fields)
  }
  return data
}

function query(params) {
  const q = new URLSearchParams()
  Object.entries(params || {}).forEach(([k, v]) => {
    if (v !== undefined && v !== null && v !== '') q.append(k, v)
  })
  const s = q.toString()
  return s ? `?${s}` : ''
}

export const api = {
  get: (url, params) => request('GET', url + query(params)),
  post: (url, body, params) => request('POST', url + query(params), body),
  put: (url, body) => request('PUT', url, body),
  del: (url) => request('DELETE', url),
  upload: (url, formData, params) => request('POST', url + query(params), formData, { form: true }),
  query
}
