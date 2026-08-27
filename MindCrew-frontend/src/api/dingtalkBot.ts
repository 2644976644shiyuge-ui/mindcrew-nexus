import request from '@/utils/request'

export interface DingtalkBot {
  id: number
  name: string
  appKey?: string
  token: string
  collectionId?: number | null
  signatureVerify?: number
  enabled?: number
  description?: string
  hasSecret?: boolean
  createTime?: string
}

export const dingtalkBotApi = {
  list: (): Promise<any> => request.get('/v2/dingtalk-bot/list'),

  create: (data: {
    name: string
    appKey?: string
    collectionId?: number | null
    appSecret?: string
    signatureVerify?: number
    description?: string
  }): Promise<any> => request.post('/v2/dingtalk-bot', data),

  update: (id: number, data: {
    name?: string
    appKey?: string
    collectionId?: number | null
    appSecret?: string          // 留空=不改
    signatureVerify?: number
    description?: string
  }): Promise<any> => request.put(`/v2/dingtalk-bot/${id}`, data),

  setEnabled: (id: number, enabled: boolean): Promise<any> =>
    request.put(`/v2/dingtalk-bot/${id}/enabled`, null, { params: { enabled } }),

  delete: (id: number): Promise<any> => request.delete(`/v2/dingtalk-bot/${id}`),

  logs: (params: { current?: number; size?: number; botId?: number | null; keyword?: string }): Promise<any> =>
    request.get('/v2/dingtalk-bot/logs', { params }),
}

export interface DingtalkChatLog {
  id: number
  botId?: number
  botName?: string
  conversationTitle?: string
  conversationType?: string
  senderNick?: string
  senderId?: string
  question?: string
  answer?: string
  answerMs?: number
  createTime?: string
}
