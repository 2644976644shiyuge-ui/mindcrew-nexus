import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { userApi, type LoginParams, type UserInfo } from '@/api/user'

export const useUserStore = defineStore('user', () => {
  const token = ref<string>(localStorage.getItem('token') || '')
  const userInfo = ref<UserInfo | null>(null)

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => userInfo.value?.role === 'admin')
  // #3 · 有效可用功能点
  const permissions = computed<string[]>(() => userInfo.value?.permissions ?? [])
  // 是否可用某功能：admin 全开；否则看有效权限集
  const hasFeature = (key: string) => isAdmin.value || permissions.value.includes(key)

  async function login(params: LoginParams) {
    const result = await userApi.login(params)
    token.value = result.token
    localStorage.setItem('token', result.token)
    await fetchUserInfo()
    return result
  }

  async function fetchUserInfo() {
    const info = await userApi.getUserInfo()
    userInfo.value = info
  }

  function logout() {
    // 通知服务端吊销会话（best-effort，失败不阻塞本地清理）
    if (token.value) {
      userApi.logout().catch(() => {})
    }
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
  }

  return { token, userInfo, isLoggedIn, isAdmin, permissions, hasFeature, login, fetchUserInfo, logout }
})
