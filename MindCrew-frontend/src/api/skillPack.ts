import request from '@/utils/request'

export interface SkillPack {
  id?: number
  name: string
  icon?: string
  description?: string
  instruction?: string
  collectionIds?: string   // JSON 数组字符串，如 "[1,2]"
  enabled?: number
  sortOrder?: number
}

export const skillPackApi = {
  /** 销售端：可用技能包 */
  usable: (): Promise<any> => request.get('/v2/skill-pack/usable'),
  /** 管理员：全部 */
  list: (): Promise<any> => request.get('/v2/skill-pack/list'),
  create: (data: SkillPack): Promise<any> => request.post('/v2/skill-pack', data),
  update: (id: number, data: SkillPack): Promise<any> => request.put(`/v2/skill-pack/${id}`, data),
  setEnabled: (id: number, enabled: boolean): Promise<any> =>
    request.put(`/v2/skill-pack/${id}/enabled`, null, { params: { enabled } }),
  delete: (id: number): Promise<any> => request.delete(`/v2/skill-pack/${id}`),
}
