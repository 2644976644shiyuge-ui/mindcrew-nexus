import request from '@/utils/request'

export interface CountryOpportunity {
  country: string
  score: number
  grade: 'S' | 'A' | 'B' | 'C'
  companyCount: number
  customerTypes: Record<string, number>
  topIndustries: Array<{ name: string; count: number; stars: number }>
  competitors: Record<string, number>
  competitorTotal: number
  recommendedProducts: string[]
}

export const marketOpportunityApi = {
  overview: (): Promise<CountryOpportunity[]> =>
    request.get('/market-opportunity/overview'),

  detail: (country: string): Promise<CountryOpportunity> =>
    request.get(`/market-opportunity/detail/${encodeURIComponent(country)}`),

  actions: (country: string): Promise<string[]> =>
    request.get(`/market-opportunity/actions/${encodeURIComponent(country)}`)
}
