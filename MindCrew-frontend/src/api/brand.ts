import request from '@/utils/request'

export interface BrandSettings {
  systemName: string
  logoUrl: string | null
}

export const brandApi = {
  getPublic: (): Promise<BrandSettings> =>
    request.get('/system/brand'),

  getSettings: (): Promise<BrandSettings> =>
    request.get('/admin/brand-settings'),

  updateSettings: (params: Partial<BrandSettings>): Promise<BrandSettings> =>
    request.put('/admin/brand-settings', params),

  uploadLogo: (file: File): Promise<string> => {
    const form = new FormData()
    form.append('file', file)
    return request.post('/admin/brand-logo', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}
