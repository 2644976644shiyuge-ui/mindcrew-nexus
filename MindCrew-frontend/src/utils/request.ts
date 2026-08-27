import axios, { type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

function showRequestError(message: string, config?: unknown) {
  if ((config as any)?.silentError) return
  ElMessage({
    message: message || '请求失败',
    type: 'error',
    grouping: true,
  })
}

function resolveRequestErrorMessage(error: any): string {
  const responseData = error.response?.data
  if (responseData && typeof responseData === 'object') {
    const serverMessage = responseData.message || responseData.error
    if (serverMessage) return String(serverMessage)
  }
  if (error.response?.status >= 500) {
    return '服务暂时不可用，请稍后重试'
  }
  if (typeof responseData === 'string' && responseData.trim()) {
    return responseData.trim()
  }
  if (error.code === 'ECONNABORTED') {
    return '请求超时，请稍后重试'
  }
  if (!error.response) {
    return '无法连接服务器，请确认服务已启动'
  }
  return error.message || '网络错误'
}

const request = axios.create({
  baseURL: '/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' }
})

// 请求拦截器 - 注入 Token
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers['Authorization'] = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器 - 统一错误处理
request.interceptors.response.use(
  async (response: AxiosResponse) => {
    // 二进制响应（blob/arraybuffer）不走 {code,message,data} 协议
    // 但如果后端返回了错误 JSON（content-type=application/json），仍要把它转回错误抛出
    const respType = (response.config as any)?.responseType
    if (respType === 'blob' || respType === 'arraybuffer') {
      const ct = String(response.headers?.['content-type'] || '').toLowerCase()
      // 后端 502 等错误也可能带 JSON body
      if (ct.includes('application/json')) {
        try {
          let text: string
          if (response.data instanceof Blob) {
            text = await response.data.text()
          } else if (response.data instanceof ArrayBuffer) {
            text = new TextDecoder().decode(response.data)
          } else {
            text = typeof response.data === 'string' ? response.data : JSON.stringify(response.data)
          }
          const obj = JSON.parse(text)
          if (obj && obj.code && obj.code !== 200) {
            showRequestError(obj.message || '请求失败', response.config)
            return Promise.reject(new Error(obj.message || '请求失败'))
          }
        } catch (e) {
          // 解析失败也不当成错误，继续返回原 response（让调用方自己处理）
        }
      }
      return response
    }

    // 正常 JSON 协议
    const { code, message, data } = response.data
    if (code === 200) {
      return data
    }
    // 401 未授权跳转登录
    if (code === 401) {
      localStorage.removeItem('token')
      router.push('/login')
      return Promise.reject(new Error(message))
    }
    showRequestError(message || '请求失败', response.config)
    return Promise.reject(new Error(message))
  },
  async (error) => {
    // HTTP 401：token 失效或会话因闲置超时被服务端吊销 → 清理本地登录态并跳登录页
    if (error.response?.status === 401) {
      if (localStorage.getItem('token')) {
        localStorage.removeItem('token')
        ElMessage.warning('登录已过期，请重新登录')
        router.push('/login')
      }
      return Promise.reject(error)
    }
    // blob/arraybuffer 错误响应：data 是 Blob 时需要转 text 拿 message
    const respType = (error.config as any)?.responseType
    if ((respType === 'blob' || respType === 'arraybuffer') && error.response?.data) {
      try {
        let text: string
        if (error.response.data instanceof Blob) {
          text = await error.response.data.text()
        } else if (error.response.data instanceof ArrayBuffer) {
          text = new TextDecoder().decode(error.response.data)
        } else {
          text = String(error.response.data)
        }
        const obj = JSON.parse(text)
        const msg = obj?.message || obj?.error || `HTTP ${error.response.status}`
        showRequestError(msg, error.config)
        return Promise.reject(new Error(msg))
      } catch {
        // 不是 JSON 就用默认逻辑
      }
    }
    const msg = resolveRequestErrorMessage(error)
    showRequestError(msg, error.config)
    return Promise.reject(error)
  }
)

export default request
