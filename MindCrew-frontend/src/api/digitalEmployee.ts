import request from '@/utils/request'

// AI PPT 需要等待外部服务完成排版和文件下载，不能沿用普通接口的 30 秒超时。
// 后端最长允许 15 分钟，额外预留内置渲染和文件传输时间。
const PPT_EXPORT_TIMEOUT_MS = 20 * 60 * 1000

export interface DigitalEmployeeCard {
  id: number
  name: string
  avatar?: string
  summary?: string
  status: string
  primaryScenario?: string
  primaryScenarioLabel?: string
  runtimeLabel?: string
  sessionCount?: number
  tokenDisplay?: string
  activeDisplay?: string
}

export interface DigitalEmployeeDetail {
  id: number
  name: string
  avatar?: string
  summary?: string
  systemPrompt?: string
  modelProvider?: string
  modelName?: string
  webSearch?: boolean
  memoryEnabled?: boolean
  scenarioConfig?: string
  primaryScenario?: string
  status?: string
  visibility?: string
  kbOnlyReply?: boolean
  sortOrder?: number
  collectionIds?: number[]
  aclEntries?: { principalType: string; principalId: number; permission?: string }[]
}

export interface ScenarioFieldDef {
  key: string
  label: string
  type: string
  placeholder?: string
  defaultValue?: string
}

export interface ScenarioTemplate {
  id: string
  name: string
  description: string
  configFields?: ScenarioFieldDef[]
}

export interface DeliverableDraft {
  draftType: 'ppt' | 'contract' | string
  title: string
  scenario?: string
  qualityScore?: number
  readiness?: 'READY' | 'NEEDS_REVIEW' | string
  presentation?: {
    generationMode?: 'auto' | 'guided' | 'corporate' | string
    visualStyle?: string
    audience?: string
    purpose?: string
    editable?: boolean
    includeSpeakerNotes?: boolean
    preferVisuals?: boolean
  }
  warnings?: string[]
  qualityChecks?: Array<{
    label: string
    status: 'PASS' | 'WARN' | 'BLOCK' | string
    message?: string
  }>
  slides?: Array<{
    title: string
    bullets: string[]
    speakerNotes?: string
    layout?: string
  }>
  sections?: Array<{
    title: string
    clauses: string[]
  }>
  risks?: Array<{
    level?: string
    position?: string
    description?: string
    suggestion?: string
  }>
}

export interface PptProviderStatus {
  enabled: boolean
  provider: string
  providerName: string
  configured: boolean
  mode: string
  templateId: string
  fallbackEnabled: boolean
}

function normalizePptDraftForExport(draft: DeliverableDraft): DeliverableDraft {
  if (draft.draftType !== 'ppt' || !draft.slides?.length) return draft
  const slides: NonNullable<DeliverableDraft['slides']> = []
  for (const source of draft.slides) {
    const bullets = (source.bullets || [])
      .map(bullet => bullet?.trim())
      .filter((bullet): bullet is string => Boolean(bullet))
    const pageCount = Math.max(1, Math.ceil(bullets.length / 6))
    for (let pageIndex = 0; pageIndex < pageCount; pageIndex++) {
      slides.push({
        ...source,
        title: pageIndex === 0
          ? source.title
          : `${source.title || '未命名页'}（续${pageIndex}）`,
        layout: pageIndex === 0 ? source.layout : 'content',
        bullets: bullets.slice(pageIndex * 6, (pageIndex + 1) * 6),
      })
    }
  }
  return {
    ...draft,
    slides,
  }
}

export const digitalEmployeeApi = {
  listMine: (q?: string): Promise<DigitalEmployeeCard[]> =>
    request.get('/digital-employees/mine', { params: q ? { q } : {} }),

  detail: (id: number): Promise<DigitalEmployeeDetail> =>
    request.get(`/digital-employees/${id}`),

  pptProviderStatus: (id: number): Promise<PptProviderStatus> =>
    request.get(`/digital-employees/${id}/ppt-provider/status`),

  sessions: (id: number): Promise<any[]> =>
    request.get(`/digital-employees/${id}/sessions`),

  newSession: (id: number, title?: string): Promise<any> =>
    request.post(`/digital-employees/${id}/sessions`, title ? { title } : {}),

  sessionHistory: (
    id: number,
    conversationId: number,
    params: { current?: number; size?: number } = {},
    options: { silentError?: boolean } = {},
  ): Promise<any> =>
    request.get(`/digital-employees/${id}/sessions/${conversationId}/history`, {
      params,
      ...(options.silentError ? { silentError: true } : {}),
    } as any),

  adminList: (q?: string): Promise<any[]> =>
    request.get('/admin/digital-employees', { params: q ? { q } : {} }),

  adminDetail: (id: number): Promise<DigitalEmployeeDetail> =>
    request.get(`/admin/digital-employees/${id}`),

  scenarioTemplates: (): Promise<ScenarioTemplate[]> =>
    request.get('/admin/digital-employees/scenario-templates'),

  create: (body: Partial<DigitalEmployeeDetail>): Promise<any> =>
    request.post('/admin/digital-employees', body),

  update: (id: number, body: Partial<DigitalEmployeeDetail>): Promise<void> =>
    request.put(`/admin/digital-employees/${id}`, body),

  publish: (id: number): Promise<void> =>
    request.post(`/admin/digital-employees/${id}/publish`),

  unpublish: (id: number): Promise<void> =>
    request.post(`/admin/digital-employees/${id}/unpublish`),

  optimizePrompt: (id: number): Promise<string> =>
    request.post(`/admin/digital-employees/${id}/optimize-prompt`),

  remove: (id: number): Promise<void> =>
    request.delete(`/admin/digital-employees/${id}`),

  exportMarkdownUrl: (employeeId: number, conversationId: number) => {
    const token = localStorage.getItem('token') || ''
    return `/api/digital-employees/${employeeId}/export?conversationId=${conversationId}&token=${encodeURIComponent(token)}`
  },

  exportDeliverableUrl: (
    employeeId: number,
    conversationId: number,
    format: 'docx' | 'pptx' | 'auto',
    messageId?: number,
  ) => {
    const params = new URLSearchParams({ conversationId: String(conversationId) })
    if (messageId) params.append('messageId', String(messageId))
    if (format === 'auto') {
      return `/api/digital-employees/${employeeId}/export/deliverable?${params}`
    }
    return `/api/digital-employees/${employeeId}/export/${format}?${params}`
  },

  buildDeliverableDraft: (
    employeeId: number,
    conversationId: number,
    messageId?: number,
  ): Promise<DeliverableDraft> =>
    request.post(`/digital-employees/${employeeId}/deliverable/draft`, {
      conversationId,
      messageId,
    }),

  exportDraft: (
    employeeId: number,
    conversationId: number,
    format: 'docx' | 'pptx',
    draft: DeliverableDraft,
  ): Promise<Blob> =>
    request.post(`/digital-employees/${employeeId}/deliverable/export`, {
      conversationId,
      format,
      draft: normalizePptDraftForExport(draft),
    }, {
      responseType: 'blob',
      timeout: format === 'pptx' ? PPT_EXPORT_TIMEOUT_MS : 2 * 60 * 1000,
    }) as any,

  streamUrl: (
    employeeId: number,
    message: string,
    conversationId?: number,
    webSearch?: boolean,
    attachments?: string,
    allowClarify?: boolean,
  ) => {
    const params = new URLSearchParams({ message })
    if (conversationId) params.append('conversationId', String(conversationId))
    if (webSearch !== undefined) params.append('webSearch', String(webSearch))
    if (attachments) params.append('attachments', attachments)
    if (allowClarify !== undefined) params.append('allowClarify', String(allowClarify))
    const token = localStorage.getItem('token') || ''
    params.append('token', token)
    return `/api/digital-employees/${employeeId}/chat/stream?${params}`
  },
}
