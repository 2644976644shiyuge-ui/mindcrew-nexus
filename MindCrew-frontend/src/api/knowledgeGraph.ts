import request from '@/utils/request'

/** 图谱节点（已聚合，name 即唯一 id） */
export interface GraphNode {
  name: string
  type: string
  /** 类型在 categories 中的下标 · ECharts 着色用 */
  category: number
  /** 权重（提及次数）· 控制节点大小 */
  value: number
  description?: string
}

/** 图谱关系边 */
export interface GraphEdge {
  source: string
  target: string
  relation: string
  value: number
  description?: string
}

/** 聚合后的图谱数据 · 直接喂给 ECharts graph series */
export interface GraphData {
  nodes: GraphNode[]
  edges: GraphEdge[]
  /** 去重后的实体类型列表（图例 + 着色） */
  categories: string[]
}

export const knowledgeGraphApi = {
  /** 按知识库(集合)聚合查询图谱 */
  getByCollection: (collectionId: number): Promise<GraphData> =>
    request.get(`/knowledge-graph/collection/${collectionId}`),

  /** 按单文档查询图谱 */
  getByKb: (kbId: number): Promise<GraphData> =>
    request.get(`/knowledge-graph/kb/${kbId}`),

  /**
   * 手动触发：为某文档重新抽取建图，返回该文档子图。
   * 同步跑 LLM 抽取，单篇可能数十秒，单独放宽超时到 5 分钟（默认 30s 必超）。
   */
  build: (kbId: number): Promise<GraphData> =>
    request.post(`/knowledge-graph/kb/${kbId}/build`, null, { timeout: 300000 }),
}
