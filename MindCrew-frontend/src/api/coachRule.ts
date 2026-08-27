import request from '@/utils/request'

export interface CoachRule {
  id?: number
  name: string
  description?: string
  /** 题型配比 JSON，如 {"single":4,"multiple":2,"judge":2,"short":2}；空=默认均衡 */
  quizConfig?: string
  /** 附加出题规则文本 */
  coachRule?: string
  enabled?: number
  sortOrder?: number
}

export const coachRuleApi = {
  /** 可用教练规则（知识库下拉选） */
  usable: (): Promise<any> => request.get('/v2/coach-rule/usable'),
  /** 管理员：全部 */
  list: (): Promise<any> => request.get('/v2/coach-rule/list'),
  create: (data: CoachRule): Promise<any> => request.post('/v2/coach-rule', data),
  update: (id: number, data: CoachRule): Promise<any> => request.put(`/v2/coach-rule/${id}`, data),
  setEnabled: (id: number, enabled: boolean): Promise<any> =>
    request.put(`/v2/coach-rule/${id}/enabled`, null, { params: { enabled } }),
  delete: (id: number): Promise<any> => request.delete(`/v2/coach-rule/${id}`),
}
