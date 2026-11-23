// 登录状态：启动时向后端确认一次，同时拿到 CSRF 令牌 Cookie
import { reactive } from 'vue'
import { request } from './api'

export const auth = reactive({ loaded: false, authenticated: false, username: '', roles: [] })

export async function loadSession() {
  const s = await request('GET', '/api/admin/session')
  auth.loaded = true
  auth.authenticated = s.authenticated
  auth.username = s.username || ''
  auth.roles = s.roles || []
  return s
}

export async function login(username, password) {
  if (!auth.loaded) await loadSession()
  const s = await request('POST', '/api/admin/session', { username, password })
  auth.authenticated = s.authenticated
  auth.username = s.username || ''
  auth.roles = s.roles || []
}

export async function logout() {
  await request('DELETE', '/api/admin/session')
  auth.authenticated = false
  auth.username = ''
  await loadSession() // 换一个新的 CSRF 令牌，方便再次登录
}

export const can = role => auth.roles.includes('ADMIN') || auth.roles.includes(role)
