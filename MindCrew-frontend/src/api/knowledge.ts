import request from '@/utils/request'
import axios from 'axios'

export interface KnowledgeBase {
  id: number
  name: string
  description: string
  category: string
  fileUrl: string
  fileType: string
  fileSize: number
  chunkCount: number
  status: 'uploading' | 'processing' | 'rebuild_queued' | 'rebuilding' |
          'ready' | 'failed' | 'rebuild_failed'
  errorMsg: string
  createTime: string
  /** 任务 7 · 可见性 */
  visibility?: 'public' | 'scoped' | 'private'
}

export interface KnowledgeListParams {
  current?: number
  size?: number
  category?: string
  status?: string
}

export const knowledgeApi = {
  // 上传文档（带进度回调） · 任务 15 加 collectionId 可选
  upload: (
    file: File,
    category: string,
    description: string,
    onProgress?: (percent: number) => void,
    collectionId?: number | null,
    signal?: AbortSignal,
  ): Promise<number> => {
    const formData = new FormData()
    formData.append('file', file)
    formData.append('category', category)
    if (description) formData.append('description', description)
    if (collectionId != null) formData.append('collectionId', String(collectionId))

    return axios.post('/api/knowledge/upload', formData, {
      headers: {
        'Content-Type': 'multipart/form-data',
        Authorization: `Bearer ${localStorage.getItem('token')}`
      },
      // 大文件 + OSS multipart 上传：500MB 按 10MB/s 约 50s，10 分钟超时安全
      timeout: 10 * 60 * 1000,
      maxContentLength: Infinity,
      maxBodyLength: Infinity,
      signal,   // 暂停时 abort 在传请求
      onUploadProgress: (e) => {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      }
    }).then(res => {
      const { code, message, data } = res.data
      if (code === 200) return data
      throw new Error(message)
    })
  },

  list: (params: KnowledgeListParams): Promise<any> =>
    request.get('/knowledge/list', { params }),

  /** 文档全量统计（顶部卡片）· total/ready/processing/failed/totalChunks */
  stats: (): Promise<any> =>
    request.get('/knowledge/stats'),

  getById: (id: number): Promise<KnowledgeBase> =>
    request.get(`/knowledge/${id}`),

  /** 取原文件下载直链（OSS 预签名 / 本地代理）· 仅 admin */
  downloadUrl: (id: number): Promise<string> =>
    request.get(`/knowledge/${id}/download-url`),

  delete: (id: number): Promise<void> =>
    request.delete(`/knowledge/${id}`),

  reprocess: (id: number): Promise<void> =>
    request.post(`/knowledge/${id}/reprocess`),

  getCategories: (): Promise<string[]> =>
    request.get('/knowledge/categories'),

  /** 任务 7 · 切换可见性 (public / scoped / private) */
  updateVisibility: (id: number, visibility: string): Promise<void> =>
    request.put(`/knowledge/${id}/visibility`, null, { params: { visibility } })
}
