import request from '@/utils/request'

export interface RankItem {
  question: string
  count: number
  lastAt?: string
  sampleMsgId?: number
}
export interface RankGroup {
  kbId?: number | null
  kbName?: string
  userId?: number | null
  userName?: string
  count: number
  top: RankItem[]
}

export const qaRankingApi = {
  /** 合并接口：一次扫描返回 {system, byKb, byUser} · 比分别拉快 ~3 倍 */
  all: (range = '7d'): Promise<any> =>
    request.get('/v2/qa-ranking/all', { params: { range } }),
  system: (range = '7d'): Promise<any> =>
    request.get('/v2/qa-ranking/system', { params: { range } }),
  byKb: (range = '7d'): Promise<any> =>
    request.get('/v2/qa-ranking/by-kb', { params: { range } }),
  byUser: (range = '7d'): Promise<any> =>
    request.get('/v2/qa-ranking/by-user', { params: { range } }),
  /** 取某条提问对应的答案（收录/纠正用） */
  answer: (userMsgId: number): Promise<any> =>
    request.get(`/v2/qa-ranking/answer/${userMsgId}`),
  /** 点赞（标记回答有用） */
  like: (msgId: number): Promise<any> =>
    request.post(`/v2/qa-ranking/like/${msgId}`),
}
