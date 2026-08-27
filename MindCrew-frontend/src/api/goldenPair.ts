import request from '@/utils/request'

export interface GoldenPair {
  id: number
  question: string
  questionNorm?: string
  standardAnswer: string
  sourcesJson?: string
  milvusId?: string
  sourceFeedbackId?: number
  category?: string
  tags?: string
  enabled: number
  hitCount: number
  lastHitAt?: string
  createdBy?: number
  createTime?: string
}

export const goldenPairApi = {
  page: (params: { current?: number; size?: number; keyword?: string; enabled?: number }): Promise<any> =>
    request.get('/v2/golden-pair/page', { params }),

  get: (id: number): Promise<any> => request.get(`/v2/golden-pair/${id}`),

  create: (data: { question: string; answer: string; sourcesJson?: string }): Promise<any> =>
    request.post('/v2/golden-pair', data),

  /** 从反馈生成 */
  fromFeedback: (feedbackId: number, finalAnswer?: string): Promise<any> =>
    request.post(`/v2/golden-pair/from-feedback/${feedbackId}`, { finalAnswer }),

  /** 在历史对话里直接纠正某条 AI 回答 → 收录经验库 */
  fromConversation: (messageId: number, finalAnswer: string): Promise<any> =>
    request.post(`/v2/golden-pair/from-conversation/${messageId}`, { finalAnswer }),

  update: (id: number, data: {
    question?: string
    answer?: string
    enabled?: number
    category?: string
    tags?: string
  }): Promise<any> => request.put(`/v2/golden-pair/${id}`, data),

  delete: (id: number): Promise<any> => request.delete(`/v2/golden-pair/${id}`),

  stats: (): Promise<any> => request.get('/v2/golden-pair/stats'),

  /** 立即自动沉淀：高频+好答案的问答→生成候选(待批准)，返回新增数 */
  autoDistill: (): Promise<any> => request.post('/v2/golden-pair/auto-distill'),
}
