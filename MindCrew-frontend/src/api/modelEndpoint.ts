import request from '@/utils/request'

export interface ModelEndpoint {
  id?: number
  name: string
  modelType: string            // ocr | vision | video | asr | tts | reranker | voice_chat
  providerType?: string        // dashscope | openai_compatible | local
  baseUrl: string
  apiKey?: string              // 写入时用；前端展示用 apiKeyMasked
  apiKeyMasked?: string
  apiKeySet?: boolean
  modelName: string
  extraParams?: string         // JSON 扩展参数
  description?: string
  isActive?: number
  enabled?: number
  sortOrder?: number
  lastTestAt?: string
  lastTestOk?: number
  lastTestMsg?: string
}

export const MODEL_TYPE_LABELS: Record<string, string> = {
  ocr:         '文档 OCR',
  vision:      '图片理解',
  video:       '视频理解',
  asr:         '语音识别',
  tts:         '语音合成',
  reranker:    '重排序',
  voice_chat:  '语音对话',
}

export const MODEL_TYPE_ORDER = ['ocr', 'vision', 'video', 'asr', 'tts', 'reranker', 'voice_chat']

export const modelEndpointApi = {
  list:        (): Promise<any> => request.get('/v2/model-endpoint/list'),
  listByType:  (modelType: string): Promise<any> => request.get(`/v2/model-endpoint/by-type/${modelType}`),
  getActive:   (modelType: string): Promise<any> => request.get(`/v2/model-endpoint/active/${modelType}`),
  getById:     (id: number): Promise<any> => request.get(`/v2/model-endpoint/${id}`),
  create:      (data: ModelEndpoint): Promise<any> => request.post('/v2/model-endpoint', data),
  update:      (id: number, data: ModelEndpoint): Promise<any> => request.put(`/v2/model-endpoint/${id}`, data),
  setActive:   (id: number): Promise<any> => request.post(`/v2/model-endpoint/${id}/set-active`),
  delete:      (id: number): Promise<any> => request.delete(`/v2/model-endpoint/${id}`),
  testById:    (id: number, apiKey?: string): Promise<{ success: boolean; message: string }> =>
                  request.post(`/v2/model-endpoint/${id}/test`, { apiKey }),
  testRaw:     (data: ModelEndpoint): Promise<{ success: boolean; message: string }> =>
                  request.post('/v2/model-endpoint/test', data),
}
