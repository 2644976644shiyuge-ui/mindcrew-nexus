import request from '@/utils/request'

export interface VoicePersona {
  id: number
  name: string
  voiceId: string
  provider: string
  model: string
  gender?: string
  language?: string
  description?: string
  tags?: string
  sampleRate?: number
  ownerUserId?: number
  status?: 'cloning' | 'ready' | 'failed'
  sampleObjectName?: string
  errorMessage?: string
  isDefault: number
  enabled: number
  /** 默认情绪：neutral/happy/serious/sad/gentle 等（空=不指定） */
  emotion?: string
  createTime?: string
}

/** 可选播报情绪（值与后端约定，渲染效果依赖 DashScope 能力 + tts.emotion-enabled 开关） */
export const TTS_EMOTIONS: { value: string; label: string }[] = [
  { value: 'neutral', label: '中性' },
  { value: 'happy',   label: '开心' },
  { value: 'serious', label: '严肃' },
  { value: 'gentle',  label: '温柔' },
  { value: 'sad',     label: '抱歉/低沉' },
]

export const voiceApi = {
  voices: (): Promise<any> => request.get('/v2/tts/voices'),

  /** 我的自定义音色列表 */
  myVoices: (): Promise<any> => request.get('/v2/tts/voices/mine'),

  /** 个人中心音色管理列表 · 原始（预置）音色 + 自己的自定义音色（含未启用） */
  manageVoices: (): Promise<any> => request.get('/v2/tts/voices/manage'),

  /** 切换音色「是否在电话列表显示」（enabled）· 预置音色仅管理员可改 */
  setVoiceEnabled: (id: number, enabled: boolean): Promise<any> =>
    request.patch(`/v2/tts/voices/${id}/enabled`, { enabled }),

  /** 把个人音色转为系统预设（全员可见）· 仅管理员 */
  promoteVoice: (id: number): Promise<any> =>
    request.post(`/v2/tts/voices/${id}/promote`),

  /** 一次性合成 · 返回 audio/wav blob URL · 失败时把错误 blob 转回 JSON 抛 */
  synth: async (text: string, voiceId?: number, emotion?: string): Promise<string> => {
    try {
      const res = await request.post(
        '/v2/tts/synth',
        { text, voiceId, emotion },
        { responseType: 'blob', timeout: 60_000 }
      )
      const blob: Blob = (res as any)?.data ?? (res as any)
      // 后端 200 但实际返回的是 JSON 错误（response interceptor 没拦到 blob 类型）
      if (blob && blob.type && blob.type.includes('json')) {
        const txt = await blob.text()
        const j = JSON.parse(txt)
        throw new Error(j?.message || j?.error || '后端返回错误')
      }
      if (!blob || blob.size < 100) {
        throw new Error('TTS 返回空内容（音色可能不存在或后端日志有错）')
      }
      return URL.createObjectURL(blob)
    } catch (e: any) {
      // axios error: response.data 可能是 blob，需要转回 text
      const respData = e?.response?.data
      if (respData instanceof Blob) {
        try {
          const txt = await respData.text()
          // 尝试解析 JSON
          try {
            const j = JSON.parse(txt)
            throw new Error(j?.message || j?.error || `HTTP ${e?.response?.status || ''}`)
          } catch {
            throw new Error(txt || `HTTP ${e?.response?.status || ''}`)
          }
        } catch (parseErr: any) {
          throw new Error(parseErr?.message || '试听失败')
        }
      }
      throw e
    }
  },

  /** 上传声音样本 → 复刻自定义音色（耗时 5-30s） */
  cloneVoice: (sample: File | Blob, name: string, gender: string, agreed: boolean): Promise<any> => {
    const fd = new FormData()
    const fileName = sample instanceof File ? sample.name : 'sample.webm'
    fd.append('sample', sample, fileName)
    fd.append('name', name)
    fd.append('gender', gender)
    fd.append('agreed', String(agreed))
    return request.post('/v2/tts/voices/clone', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120_000,
    })
  },

  /** 删除自己的自定义音色 */
  deleteMyVoice: (id: number): Promise<any> =>
    request.delete(`/v2/tts/voices/${id}`),
}
