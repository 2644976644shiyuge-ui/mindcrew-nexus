import request from '@/utils/request'

export interface WorkflowDefinition {
  id?: number
  name: string
  description?: string
  graphJson: string
  enabled?: number
  ownerUserId?: number
  updateTime?: string
}

export interface WfNode {
  id: string
  type: string
  config: Record<string, any>
}
export interface WfEdge {
  from: string
  to: string
  when?: string
}

export interface WorkflowRun {
  id: number
  workflowId: number
  userId?: number
  status: string
  errorMsg?: string
  elapsedMs?: number
  startTime?: string
  endTime?: string
  createTime?: string
}

export interface WorkflowNodeRun {
  id: number
  runId: number
  nodeId: string
  nodeType: string
  seq: number
  status: string
  input?: string
  output?: string
  errorMsg?: string
  elapsedMs?: number
}

export const workflowApi = {
  list: (): Promise<any> => request.get('/v2/workflow/definitions'),
  get: (id: number): Promise<any> => request.get(`/v2/workflow/definitions/${id}`),
  save: (def: Partial<WorkflowDefinition>): Promise<any> =>
    request.post('/v2/workflow/definitions', def),
  remove: (id: number): Promise<any> => request.delete(`/v2/workflow/definitions/${id}`),
  createRun: (workflowId: number, variables: Record<string, any>): Promise<any> =>
    request.post('/v2/workflow/runs', { workflowId, variables }),
  listRuns: (workflowId: number): Promise<any> =>
    request.get('/v2/workflow/runs', { params: { workflowId } }),
  getRun: (id: number): Promise<any> => request.get(`/v2/workflow/runs/${id}`),
}
