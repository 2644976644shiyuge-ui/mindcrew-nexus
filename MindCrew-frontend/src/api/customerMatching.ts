import request from '@/utils/request'

export interface AnalysisResult {
  companyName: string
  found: boolean
  company: any
  contactCount: number
  businessCapability: string[]
  productMatches: Array<{ product: string; score: number; reasons: string[]; models: string[] }>
  applications: Array<{ name: string; stars: number; reason: string; solution: string }>
  competitorIntel: { brand?: string; relation?: string; opportunity?: number; strategy?: string }
  salesStrategy: { first_contact_angle?: string; email_subjects?: string[] }
  whyExplanation: string
  fitScore: number
  grade: string
  scoreBreakdown: Record<string, number>
}

export const customerMatchingApi = {
  // LLM 单客户分析最长可达 120s，单独把 axios 超时放宽到 180s，避免被前端 30s 默认超时打断
  analyze: (company: string): Promise<AnalysisResult> =>
    request.post('/customer-matching/analyze', { company }, { timeout: 180000 })
}
