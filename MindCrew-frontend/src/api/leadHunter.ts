import request from '@/utils/request'

/** 11 步工作流单步状态 */
export interface HuntStep {
  index: number
  key: string
  title: string
  status: 'pending' | 'running' | 'done' | 'skipped' | 'failed'
  detail: string
  time: string
}

export interface HuntStats {
  discovered: number
  verifiedCompanies: number
  contacts: number
  emailVerified: number
  duplicates: number
  rejected: number
  finalLeads: number
}

export interface HuntSession {
  id: number
  userId: number
  countries: string
  customerTypes: string
  products: string
  targetCount: number
  status: 'queued' | 'running' | 'done' | 'failed' | 'cancelled'
  currentStep: number
  progress: number
  icpSummary?: string
  stepLogs?: string
  statsJson?: string
  errorMsg?: string
  createTime?: string
}

export interface HuntStatus {
  session: HuntSession
  steps: HuntStep[]
  stats: HuntStats
  queuePosition?: number
}

export interface LeadRow {
  companyId: number
  contactId?: number
  country: string
  region: string
  company: string
  person: string
  title: string
  email: string
  emailStatus: string
  phone: string
  website: string
  customerType: string
  industry: string
  majorBusiness: string
  majorBusinessCn: string
  city: string
  state: string
  address: string
  zip: string
  icpScore: number
  contactScore?: number
  companySize: string
  competitor: string
  source: string
  contactSource: string
  verificationStatus: string
  remarks: string
  searchDate: string
}

export interface StartParams {
  countries: string[]
  customerTypes: string[]
  products: string[]
  targetCount: number
}

/** 国家维度线索统计（地图模块） */
export interface CountryStat {
  country: string
  count: number
}

export const leadHunterApi = {
  start: (params: StartParams): Promise<{ sessionId: number }> =>
    request.post('/lead-hunter/start', params),

  status: (sessionId: number): Promise<HuntStatus> =>
    request.get(`/lead-hunter/status/${sessionId}`),

  leads: (sessionId: number, params: {
    keyword?: string
    emailStatus?: string
    minScore?: number
    onlyWithContact?: boolean
    page?: number
    size?: number
  }): Promise<{ total: number; records: LeadRow[] }> =>
    request.get(`/lead-hunter/leads/${sessionId}`, { params }),

  sessions: (): Promise<HuntSession[]> =>
    request.get('/lead-hunter/sessions'),

  mapStats: (sessionId?: number): Promise<Array<{ country: string; cnt: number }>> =>
    request.get('/lead-hunter/map-stats', { params: sessionId ? { sessionId } : {} }),

  cancel: (sessionId: number): Promise<void> =>
    request.post(`/lead-hunter/cancel/${sessionId}`),

  exportUrl: (sessionId: number, format: 'xlsx' | 'csv'): string =>
    `/api/lead-hunter/export/${sessionId}?format=${format}`
}

/** 带鉴权下载导出文件 */
export async function downloadExport(sessionId: number, format: 'xlsx' | 'csv') {
  const token = localStorage.getItem('token')
  const resp = await fetch(leadHunterApi.exportUrl(sessionId, format), {
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  })
  if (!resp.ok) throw new Error(`导出失败：HTTP ${resp.status}`)
  const blob = await resp.blob()
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `leads-${sessionId}.${format}`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
