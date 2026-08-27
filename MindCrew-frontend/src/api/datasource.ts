import request from '@/utils/request'

export interface DataSource {
  id?: number
  name: string
  dbType?: string
  host: string
  port?: number
  dbName: string
  username: string
  /** 明文密码 · 仅写入时用；更新留空表示沿用旧值 */
  password?: string
  /** 回传时为 '' 或 '******'，不含密文 */
  passwordEnc?: string
  jdbcParams?: string
  description?: string
  visibility?: 'public' | 'scoped' | 'private'
  status?: 'enabled' | 'disabled'
  /** 是否自动同步表结构(1开/0关) */
  autoSync?: number
  /** 同步周期(分钟)，0=仅手动 */
  syncIntervalMin?: number
  lastTestStatus?: string
  lastTestTime?: string
  lastTestError?: string
  userId?: number
}

export interface DsColumn {
  name: string
  type?: string
  comment?: string
  businessName?: string
  description?: string
}

export interface DsTableMeta {
  id?: number
  datasourceId?: number
  tableName: string
  businessName?: string
  description?: string
  /** 列语义 JSON 字符串（后端 columns_json） */
  columnsJson?: string
  enabled?: number
  sortOrder?: number
}

export interface IntrospectedTable {
  tableName: string
  tableComment?: string
  columns: DsColumn[]
}

export const dataSourceApi = {
  list:       (): Promise<any> => request.get('/v2/datasource'),
  get:        (id: number): Promise<any> => request.get(`/v2/datasource/${id}`),
  create:     (d: DataSource): Promise<any> => request.post('/v2/datasource', d),
  update:     (id: number, d: DataSource): Promise<any> => request.put(`/v2/datasource/${id}`, d),
  delete:     (id: number): Promise<any> => request.delete(`/v2/datasource/${id}`),
  /** 测试连接（带 id 用库里密码，或临时配置） */
  test:       (d: DataSource): Promise<any> => request.post('/v2/datasource/test', d),
  /** JDBC 反查表结构骨架 */
  introspect: (id: number): Promise<any> => request.post(`/v2/datasource/${id}/introspect`),
  /** 表语义元数据 */
  listTables: (id: number): Promise<any> => request.get(`/v2/datasource/${id}/tables`),
  saveTables: (id: number, tables: DsTableMeta[]): Promise<any> =>
              request.put(`/v2/datasource/${id}/tables`, tables),
  /** 当前用户可访问 + enabled 的数据源精简列表（问答里的数据源选择器用） */
  accessible: (): Promise<any> => request.get('/v2/datasource/accessible'),
  /** 手动「立即同步」表结构（合并新表 + 禁用失效表），返回 {added,disabled} */
  syncTables: (id: number): Promise<any> => request.post(`/v2/datasource/${id}/sync-tables`),
  /** 数据二次分析：type=interpret(解读)/attribution(归因)/forecast(预测) */
  analyze: (payload: {
    type: string; question?: string; datasourceName?: string; sql?: string
    columns?: string[]; rows?: any[][]
  }): Promise<any> => request.post('/v2/datasource/analyze', payload),
}

/** 数据源精简信息（问答选择器用） */
export interface DataSourceBrief {
  id: number
  name: string
  description?: string
}

// ── 数据源 ACL（用户 / 职位 / 部门 三选一） ──
export interface DsAclEntry {
  positionId?: number | null
  departmentId?: number | null
  userId?: number | null
  permission?: 'read' | 'admin'
}

export const dataSourceAclApi = {
  list:       (dsId: number): Promise<any> => request.get(`/v2/datasource-acl/${dsId}`),
  replace:    (dsId: number, entries: DsAclEntry[]): Promise<any> =>
              request.put(`/v2/datasource-acl/${dsId}/replace`, { entries }),
  check:      (dsId: number, perm = 'read'): Promise<any> =>
              request.get('/v2/datasource-acl/check', { params: { dsId, perm } }),
  accessible: (): Promise<any> => request.get('/v2/datasource-acl/accessible'),
}
