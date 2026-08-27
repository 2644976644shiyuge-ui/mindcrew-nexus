import request from '@/utils/request'

export interface AiConfig {
  id: number
  configKey: string
  configValue: string
  configured?: boolean
  valueType: 'string' | 'integer' | 'float'
  groupName: string
  label: string
  description: string
  defaultValue: string
  minValue?: string
  maxValue?: string
}

export interface PptConnectionTestResult {
  success: boolean
  provider: string
  providerName: string
  latencyMs: number
  message: string
}

export const aiConfigApi = {
  // 查询全部配置（按 groupName 分组）
  listAll: (): Promise<Record<string, AiConfig[]>> =>
    request.get('/admin/ai-config/list'),

  // 批量更新配置
  batchUpdate: (params: Record<string, string>): Promise<void> =>
    request.put('/admin/ai-config/batch', params),

  // 重置指定分组为默认值
  resetGroup: (groupName: string): Promise<void> =>
    request.post(`/admin/ai-config/reset/${groupName}`),

  // 重置全部为默认值
  resetAll: (): Promise<void> =>
    request.post('/admin/ai-config/reset-all'),

  // 获取可选模型列表
  getModels: (): Promise<string[]> =>
    request.get('/admin/ai-config/models'),

  // 测试当前（含未保存表单值）PPT 服务商连接
  testPptConnection: (params: Record<string, string>): Promise<PptConnectionTestResult> =>
    request.post('/admin/ai-config/ppt/test', params)
}
