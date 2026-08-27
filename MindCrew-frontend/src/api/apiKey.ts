import request from '@/utils/request'

export interface ApiKey {
  id: number
  name: string
  keyPrefix: string
  allowedKbIds?: string                  // 旧字段 · JSON string · 文档 id 列表（兼容）
  allowedCollectionIds?: string          // ⭐ 任务 15 · JSON string · 知识库 id 列表
  scopeType?: 'kb_scoped' | 'collection_scoped' | 'user_scoped'
  monthlyQuota: number
  monthUsed: number
  rateLimitQps: number
  totalCalls: number
  lastUsedAt?: string
  expireAt?: string
  status: 'active' | 'revoked' | 'expired'
  description?: string
  createdBy?: number
  createTime?: string
}

export interface ApiCallLog {
  id: number
  keyId: number
  kbId?: number
  api: string
  question?: string
  statusCode: number
  inputTokens: number
  outputTokens: number
  costCny: number
  latencyMs: number
  ip?: string
  userAgent?: string
  errorMsg?: string
  calledAt: string
}

export interface IssueResult {
  id: number
  rawKey: string          // 完整 key · 仅此一次返回
  prefix: string
  warning: string
}

export const apiKeyApi = {
  /** 生成新 API Key · 返回的 rawKey 必须立即让用户复制保存 · 任务 15：推荐传 allowedCollectionIds */
  issue: (data: {
    name: string
    allowedCollectionIds?: number[]    // ⭐ 任务 15 主字段 · 知识库 id 列表
    allowedKbIds?: number[]            // 旧字段 · 文档 id（不推荐）
    monthlyQuota?: number
    rateLimitQps?: number
    expireAt?: string
    description?: string
  }): Promise<any> => request.post('/v2/api-key', data),

  page: (params: { current?: number; size?: number; kbId?: number; status?: string }): Promise<any> =>
    request.get('/v2/api-key/page', { params }),

  /** 旧 · 列出某文档绑定的所有 API Key */
  byKb: (kbId: number): Promise<any> => request.get(`/v2/api-key/by-kb/${kbId}`),

  /** ⭐ 任务 15 · 列出某【知识库】绑定的所有 API Key */
  byCollection: (collectionId: number): Promise<any> =>
    request.get(`/v2/api-key/by-collection/${collectionId}`),

  revoke: (id: number): Promise<any> => request.post(`/v2/api-key/${id}/revoke`),

  updateQuota: (id: number, data: { monthlyQuota?: number; rateLimitQps?: number }): Promise<any> =>
    request.put(`/v2/api-key/${id}/quota`, data),

  /** 旧 · 改文档授权 */
  updateAllowedKbs: (id: number, allowedKbIds: number[]): Promise<any> =>
    request.put(`/v2/api-key/${id}/kbs`, { allowedKbIds }),

  /** ⭐ 任务 15 · 改知识库授权 */
  updateAllowedCollections: (id: number, allowedCollectionIds: number[]): Promise<any> =>
    request.put(`/v2/api-key/${id}/collections`, { allowedCollectionIds }),

  delete: (id: number): Promise<any> => request.delete(`/v2/api-key/${id}`),

  logs: (params: { current?: number; size?: number; keyId?: number; kbId?: number }): Promise<any> =>
    request.get('/v2/api-key/logs', { params }),
}
