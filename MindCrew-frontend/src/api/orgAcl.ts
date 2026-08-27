import request from '@/utils/request'

export interface Department {
  id: number
  name: string
  parentId?: number | null
  description?: string
  /** #3 · 功能权限 JSON 字符串（null=继承基线） */
  permissions?: string | null
  sortOrder?: number
  enabled?: number
}

export interface DeptNode extends Department {
  children: DeptNode[]
}

export interface Position {
  id: number
  name: string
  code: string
  departmentId?: number | null
  description?: string
  /** #3 · 功能权限 JSON 字符串（null=继承部门/基线） */
  permissions?: string | null
  level?: number
  sortOrder?: number
  enabled?: number
}

/** #3 · 功能权限目录项 */
export interface FeatureDef {
  key: string
  label: string
  group: string
}

export interface KbAcl {
  id?: number
  kbId: number
  /** 职位 ID · 与 departmentId 二选一 */
  positionId?: number | null
  /** 部门 ID · 与 positionId 二选一 · 含子部门继承 */
  departmentId?: number | null
  permission: 'read' | 'write' | 'admin'
  grantedBy?: number
}

// 部门
export const departmentApi = {
  list:    (): Promise<any> => request.get('/v2/department/list'),
  tree:    (): Promise<any> => request.get('/v2/department/tree'),
  create:  (d: Partial<Department>): Promise<any> => request.post('/v2/department', d),
  update:  (id: number, d: Partial<Department>): Promise<any> => request.put(`/v2/department/${id}`, d),
  delete:  (id: number): Promise<any> => request.delete(`/v2/department/${id}`),
  /** #3 · 配置部门默认可用功能（permissions=null 清空=继承基线） */
  updatePermissions: (id: number, permissions: string[] | null): Promise<any> =>
            request.put(`/v2/department/${id}/permissions`, { permissions }),
}

// 职位
export const positionApi = {
  list:    (departmentId?: number): Promise<any> =>
            request.get('/v2/position/list', { params: { departmentId } }),
  create:  (p: Partial<Position>): Promise<any> => request.post('/v2/position', p),
  update:  (id: number, p: Partial<Position>): Promise<any> => request.put(`/v2/position/${id}`, p),
  delete:  (id: number): Promise<any> => request.delete(`/v2/position/${id}`),
  /** #3 · 功能权限目录 */
  features: (): Promise<any> => request.get('/v2/position/features'),
  /** #3 · 配置职位可用功能（permissions=null 清空=继承部门/基线） */
  updatePermissions: (id: number, permissions: string[] | null): Promise<any> =>
            request.put(`/v2/position/${id}/permissions`, { permissions }),
}

// KB ACL · subject 二选一：position 或 department
export interface AclEntry {
  positionId?: number | null
  departmentId?: number | null
  permission: 'read' | 'write' | 'admin'
}

export const kbAclApi = {
  list:    (kbId: number): Promise<any> => request.get(`/v2/kb-acl/${kbId}`),

  /** subject 用 positionId 或 departmentId 二选一 */
  grant:   (kbId: number, params: { positionId?: number; departmentId?: number; permission: string }): Promise<any> =>
            request.post(`/v2/kb-acl/${kbId}/grant`, params),

  revoke:  (kbId: number, params: { positionId?: number; departmentId?: number }): Promise<any> =>
            request.delete(`/v2/kb-acl/${kbId}/revoke`, { params }),

  replace: (kbId: number, entries: AclEntry[]): Promise<any> =>
            request.put(`/v2/kb-acl/${kbId}/replace`, { entries }),

  check:   (kbId: number, perm: string = 'read'): Promise<any> =>
            request.get('/v2/kb-acl/check', { params: { kbId, perm } }),

  accessibleKbs: (): Promise<any> => request.get('/v2/kb-acl/accessible-kbs'),

  // ⭐ 任务 15.2 · 知识库（集合）级 ACL
  listCollectionAcl: (collectionId: number): Promise<any> =>
    request.get(`/v2/kb-acl/collection/${collectionId}`),

  replaceCollectionAcl: (collectionId: number, entries: AclEntry[]): Promise<any> =>
    request.put(`/v2/kb-acl/collection/${collectionId}/replace`, { entries }),

  checkCollection: (collectionId: number, perm: string = 'read'): Promise<any> =>
    request.get('/v2/kb-acl/collection/check', { params: { collectionId, perm } }),

  accessibleCollections: (): Promise<any> =>
    request.get('/v2/kb-acl/accessible-collections'),
}
