import request from '@/utils/request'

export interface KnowledgeCollection {
  id: number
  name: string
  description?: string
  icon?: string
  color?: string
  categoryCode?: string
  visibility: 'public' | 'scoped' | 'private'
  ownerUserId?: number | null
  docCount: number
  totalChunks: number
  isSystem: number
  /** 绑定的 Soul 人格 id（null/0=用全局默认人格） */
  personaId?: number | null
  /** 绑定的技能包 id（null/0=不套用技能） */
  skillPackId?: number | null
  /** 教练模式·绑定的教练规则 id（null/0=默认出题逻辑） */
  coachRuleId?: number | null
  /** 教练模式·本知识库出题规则（旧版内联，向后兼容；新版用 coachRuleId） */
  coachRule?: string | null
  /** 教练模式·出题题型配比 JSON，如 {"single":4,"multiple":2,"judge":2,"short":2}（null/空=默认均衡） */
  quizConfig?: string | null
  /** 本知识库可用音色 voice_persona.id 列表（逗号分隔，多选）；null/空=不限制（全部音色） */
  voiceIds?: string | null
  createTime: string
  updateTime: string
}

export const collectionApi = {
  /** 列出当前用户可访问的全部知识库 */
  list: (): Promise<KnowledgeCollection[]> => request.get('/v2/collections'),

  /** 单个详情 */
  get: (id: number): Promise<KnowledgeCollection> => request.get(`/v2/collections/${id}`),

  /** 库内文档 */
  listDocs: (id: number): Promise<any[]> => request.get(`/v2/collections/${id}/docs`),

  /** 创建 */
  create: (data: Partial<KnowledgeCollection>): Promise<KnowledgeCollection> =>
    request.post('/v2/collections', data),

  /** 修改 */
  update: (id: number, data: Partial<KnowledgeCollection>): Promise<KnowledgeCollection> =>
    request.put(`/v2/collections/${id}`, data),

  /** 删除（文档不丢，变散文档） */
  delete: (id: number): Promise<void> => request.delete(`/v2/collections/${id}`),

  /** 把文档批量移入此库 */
  assign: (id: number, docIds: number[]): Promise<void> =>
    request.post(`/v2/collections/${id}/assign`, { docIds }),

  /** 从此库移出文档 */
  remove: (id: number, docIds: number[]): Promise<void> =>
    request.post(`/v2/collections/${id}/remove`, { docIds }),
}
