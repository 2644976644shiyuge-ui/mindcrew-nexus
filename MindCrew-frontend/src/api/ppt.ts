import request from '@/utils/request'

export type PptTaskStatus = 'queued' | 'generating' | 'completed' | 'failed' | 'canceled'

export interface PptTask {
  id: number
  employeeId?: number
  conversationId?: number
  parentTaskId?: number
  versionNo: number
  operationType: 'create' | 'revise'
  prompt: string
  attachments?: string
  title: string
  pageCount: number
  language: string
  visualStyle: string
  audience?: string
  purpose?: string
  status: PptTaskStatus
  progress: number
  stage?: string
  provider?: string
  providerName?: string
  fallbackUsed: boolean
  fileName?: string
  fileSize?: number
  errorMessage?: string
  warnings?: string
  previewFileSize?: number
  createTime: string
  updateTime: string
  startedAt?: string
  completedAt?: string
  downloadable: boolean
  previewable: boolean
  cancellable: boolean
}

export interface CreatePptTaskRequest {
  prompt: string
  title?: string
  pageCount?: number
  language?: string
  visualStyle?: string
  audience?: string
  purpose?: string
  employeeId?: number
  conversationId?: number
  baseTaskId?: number
  attachments?: Array<{ objectName: string; name: string }>
}

export const pptApi = {
  create: (body: CreatePptTaskRequest): Promise<PptTask> =>
    request.post('/ppt/tasks', body),

  list: (
    limit = 30,
    scope?: { employeeId?: number; conversationId?: number },
  ): Promise<PptTask[]> =>
    request.get('/ppt/tasks', { params: { limit, ...scope } }),

  detail: (id: number): Promise<PptTask> =>
    request.get(`/ppt/tasks/${id}`),

  retry: (id: number): Promise<PptTask> =>
    request.post(`/ppt/tasks/${id}/retry`),

  cancel: (id: number): Promise<PptTask> =>
    request.post(`/ppt/tasks/${id}/cancel`),

  download: (id: number): Promise<any> =>
    request.get(`/ppt/tasks/${id}/download`, {
      responseType: 'blob',
      timeout: 2 * 60 * 1000,
    }),

  preview: (id: number): Promise<any> =>
    request.get(`/ppt/tasks/${id}/preview`, {
      responseType: 'blob',
      timeout: 2 * 60 * 1000,
    }),
}
