<template>
  <div class="wf-page">
    <!-- ===== 左：工作流列表 ===== -->
    <aside class="wf-list">
      <div class="wf-list-head">
        <span class="wf-list-title">系统编排</span>
        <el-button size="small" type="primary" :icon="Plus" @click="newDef">新建</el-button>
      </div>
      <div class="wf-list-body">
        <div
          v-for="d in defs"
          :key="d.id"
          class="wf-item"
          :class="{ active: current && current.id === d.id }"
          @click="selectDef(d)"
        >
          <div class="wf-item-name">{{ d.name || '(未命名)' }}</div>
          <div class="wf-item-sub">{{ d.description || '-' }}</div>
        </div>
        <div v-if="!defs.length" class="wf-empty">还没有工作流，点「新建」开始</div>
      </div>
    </aside>

    <!-- ===== 右：编辑器 ===== -->
    <section v-if="current" class="wf-editor">
      <div class="wf-editor-head">
        <el-input v-model="current.name" placeholder="工作流名称" style="max-width: 280px" />
        <el-input v-model="current.description" placeholder="描述（可选）" style="max-width: 320px" />
        <div class="wf-head-actions">
          <el-button :icon="VideoPlay" type="success" :disabled="!current.id" @click="openRun">运行</el-button>
          <el-button :icon="Clock" :disabled="!current.id" @click="openHistory">历史</el-button>
          <el-button :icon="Check" type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button v-if="current.id" :icon="Delete" type="danger" plain @click="del" />
        </div>
      </div>

      <div class="wf-editor-body">
        <!-- 中：拖拽画布 -->
        <div class="wf-canvas-wrap">
          <div class="wf-palette">
            <span class="wf-palette-label">拖动节点排版 · 从节点底部圆点拉到另一节点顶部建连线 · 点节点/连线编辑</span>
            <el-button
              v-for="t in NODE_TYPES"
              :key="t.value"
              size="small"
              :icon="Plus"
              @click="addNode(t.value)"
            >{{ t.label }}</el-button>
          </div>
          <VueFlow
            :id="VF_ID"
            class="wf-canvas"
            :min-zoom="0.3"
            :max-zoom="2"
            fit-view-on-init
            @connect="onConnect"
            @node-click="onNodeClick"
            @edge-click="onEdgeClick"
            @pane-click="onPaneClick"
          >
            <Background />
            <Controls />
          </VueFlow>
        </div>

        <!-- 右：检查器（选中节点 / 连线时的配置） -->
        <div class="wf-inspector">
          <!-- 节点配置 -->
          <template v-if="selNode">
            <div class="wf-insp-head">
              <span class="wf-node-type" :style="{ background: typeColor(selNodeType) }">{{ typeLabel(selNodeType) }}</span>
              <span class="wf-insp-id">{{ selNode.id }}</span>
              <el-button size="small" text type="danger" :icon="Delete" @click="deleteSelectedNode" />
            </div>
            <div class="wf-cfg">
              <template v-if="selNodeType === 'retrieval'">
                <label>检索词</label>
                <el-input v-model="selNode.data.config.query" size="small" placeholder="{{question}}" />
                <div class="wf-cfg-row">
                  <div><label>topK</label><el-input v-model="selNode.data.config.topK" size="small" /></div>
                  <div><label>输出变量</label><el-input v-model="selNode.data.config.outputKey" size="small" placeholder="context" /></div>
                </div>
                <label>知识库 id（逗号分隔，空=全库）</label>
                <el-input v-model="selNode.data.config._kbIds" size="small" placeholder="如 1,2" />
              </template>

              <template v-else-if="selNodeType === 'llm'">
                <label>提示词（可用 {{ varSyntax }}）</label>
                <el-input v-model="selNode.data.config.prompt" type="textarea" :rows="4" size="small"
                  placeholder="根据资料回答。资料：{{context}} 问题：{{question}}" />
                <label>输出变量</label>
                <el-input v-model="selNode.data.config.outputKey" size="small" placeholder="answer" />
              </template>

              <template v-else-if="selNodeType === 'condition'">
                <label>判断表达式（渲染出的值与出边 when 匹配）</label>
                <el-input v-model="selNode.data.config.expression" size="small" placeholder="{{category}}" />
              </template>

              <template v-else-if="selNodeType === 'output'">
                <label>输出模板</label>
                <el-input v-model="selNode.data.config.template" type="textarea" :rows="3" size="small" placeholder="{{answer}}" />
              </template>

              <div v-else class="wf-cfg-hint">开始节点：运行输入变量从这里进入</div>
            </div>
          </template>

          <!-- 连线配置 -->
          <template v-else-if="selEdge">
            <div class="wf-insp-head">
              <span class="wf-edge-badge">连线</span>
              <span class="wf-insp-id">{{ selEdge.source }} → {{ selEdge.target }}</span>
              <el-button size="small" text type="danger" :icon="Delete" @click="deleteSelectedEdge" />
            </div>
            <div class="wf-cfg">
              <label>when（条件分支用 · 与上游 condition 渲染值匹配，空=默认分支）</label>
              <el-input :model-value="selEdgeWhen" size="small" placeholder="如 投诉"
                @update:model-value="setSelEdgeWhen" />
            </div>
          </template>

          <div v-else class="wf-insp-empty">
            <el-empty :image-size="60" description="点画布上的节点或连线编辑；从上方工具栏加节点" />
          </div>
        </div>
      </div>
    </section>

    <section v-else class="wf-blank">
      <el-empty description="选择左侧工作流，或点「新建」" />
    </section>

    <!-- ===== 运行抽屉 ===== -->
    <el-drawer v-model="runVisible" title="运行工作流" size="520px" @close="closeRun">
      <div class="wf-run">
        <div class="wf-run-vars">
          <div class="wf-section-head"><span>输入变量</span>
            <el-button size="small" :icon="Plus" @click="runVars.push({ key: '', value: '' })">加变量</el-button>
          </div>
          <div v-for="(v, i) in runVars" :key="i" class="wf-var-row">
            <el-input v-model="v.key" size="small" placeholder="变量名" style="width: 130px" />
            <el-input v-model="v.value" size="small" placeholder="值" />
            <el-button size="small" text :icon="Delete" @click="runVars.splice(i, 1)" />
          </div>
        </div>

        <el-button type="success" :icon="VideoPlay" :loading="running" style="width: 100%; margin: 12px 0"
          @click="startRun">开始运行</el-button>

        <div v-if="runLog.length" class="wf-run-log">
          <div v-for="(l, i) in runLog" :key="i" class="wf-log-row" :class="l.status">
            <span class="wf-log-dot"></span>
            <span class="wf-log-node">{{ l.nodeId }}</span>
            <span class="wf-log-text">{{ l.text }}</span>
          </div>
        </div>

        <div v-if="runResult !== null" class="wf-run-result">
          <div class="wf-result-title">运行结果</div>
          <pre class="wf-result-body">{{ runResult }}</pre>
        </div>
        <div v-if="runError" class="wf-run-error">运行失败：{{ runError }}</div>
      </div>
    </el-drawer>

    <!-- ===== 历史抽屉 ===== -->
    <el-drawer v-model="historyVisible" title="运行历史" size="560px">
      <div v-loading="historyLoading" class="wf-history">
        <!-- 列表 -->
        <div v-if="!detail" class="wf-hist-list">
          <div
            v-for="r in history"
            :key="r.id"
            class="wf-hist-item"
            @click="openDetail(r)"
          >
            <span class="wf-hist-status" :class="r.status.toLowerCase()">{{ statusLabel(r.status) }}</span>
            <span class="wf-hist-id">#{{ r.id }}</span>
            <span class="wf-hist-time">{{ fmtTime(r.startTime || r.createTime) }}</span>
            <span class="wf-hist-elapsed">{{ r.elapsedMs != null ? r.elapsedMs + 'ms' : '-' }}</span>
          </div>
          <div v-if="!history.length" class="wf-empty">还没有运行记录，去「运行」跑一次</div>
        </div>

        <!-- 详情：节点轨迹回放 -->
        <div v-else class="wf-hist-detail">
          <div class="wf-hist-detail-head">
            <el-button size="small" text :icon="ArrowLeft" @click="detail = null">返回列表</el-button>
            <span class="wf-hist-status" :class="(detail.run?.status || '').toLowerCase()">
              {{ statusLabel(detail.run?.status) }}
            </span>
            <span class="wf-hist-time">{{ fmtTime(detail.run?.startTime) }} · {{ detail.run?.elapsedMs ?? '-' }}ms</span>
          </div>

          <div class="wf-run-log">
            <div v-for="nr in detail.nodeRuns" :key="nr.id" class="wf-log-row" :class="logClass(nr.status)">
              <span class="wf-log-dot"></span>
              <span class="wf-log-node">{{ nr.nodeId }}</span>
              <span class="wf-log-type">{{ typeLabel(nr.nodeType) }}</span>
              <span class="wf-log-text">
                {{ nr.status === 'FAILED' ? (nr.errorMsg || '失败') : (nr.elapsedMs != null ? nr.elapsedMs + 'ms' : '') }}
              </span>
            </div>
            <div v-if="!detail.nodeRuns?.length" class="wf-empty">无节点记录</div>
          </div>

          <div v-if="detailResult !== null" class="wf-run-result">
            <div class="wf-result-title">运行结果</div>
            <pre class="wf-result-body">{{ detailResult }}</pre>
          </div>
          <div v-if="detail.run?.errorMsg" class="wf-run-error">运行失败：{{ detail.run.errorMsg }}</div>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, Check, VideoPlay, Clock, ArrowLeft } from '@element-plus/icons-vue'
import { VueFlow, useVueFlow, type Connection, type Node, type Edge, type Styles, type NodeMouseEvent, type EdgeMouseEvent } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import {
  workflowApi,
  type WorkflowDefinition,
  type WorkflowRun, type WorkflowNodeRun,
} from '@/api/workflow'

const NODE_TYPES = [
  { value: 'start', label: '开始' },
  { value: 'retrieval', label: '检索' },
  { value: 'llm', label: 'LLM' },
  { value: 'condition', label: '条件分支' },
  { value: 'output', label: '输出' },
]
const TYPE_COLORS: Record<string, string> = {
  start: '#22C55E', retrieval: '#0EA5E9', llm: '#0a0a0a', condition: '#F59E0B', output: '#3D5AFE',
}
const varSyntax = '{{变量}}'
const typeLabel = (t: string) => NODE_TYPES.find(n => n.value === t)?.label || t
const typeColor = (t: string) => TYPE_COLORS[t] || '#94A3B8'

const defs = ref<WorkflowDefinition[]>([])
const current = ref<WorkflowDefinition | null>(null)
const saving = ref(false)

// ─── Vue Flow 画布（编辑期的唯一真源：拓扑 + 坐标 + 每节点 data.config） ───
const VF_ID = 'wf-editor'
const {
  addNodes, addEdges, removeNodes, removeEdges, setNodes, setEdges,
  findNode, findEdge, toObject, fitView,
} = useVueFlow(VF_ID)

const selNodeId = ref<string | null>(null)
const selEdgeId = ref<string | null>(null)
const selNode = computed(() => (selNodeId.value ? findNode(selNodeId.value) ?? null : null))
const selEdge = computed(() => (selEdgeId.value ? findEdge(selEdgeId.value) ?? null : null))
const selNodeType = computed<string>(() => String(selNode.value?.data?.wfType ?? ''))
const selEdgeWhen = computed<string>(() => String(selEdge.value?.data?.when ?? ''))

function genEdgeId(): string {
  return `e_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`
}
function nodeLabel(wfType: string, id: string): string {
  return `${typeLabel(wfType)} · ${id}`
}
function nodeStyle(wfType: string): Styles {
  return {
    background: '#fff',
    border: `2px solid ${typeColor(wfType)}`,
    borderRadius: '10px',
    padding: '8px 12px',
    fontSize: '12px',
    color: '#1e293b',
    minWidth: '120px',
  }
}

async function loadList() {
  try {
    const res: any = await workflowApi.list()
    defs.value = res?.data ?? res ?? []
  } catch (e: any) { ElMessage.error('加载失败：' + (e?.message || '')) }
}

function defaultConfig(type: string): Record<string, any> {
  switch (type) {
    case 'retrieval': return { query: '{{question}}', topK: 6, outputKey: 'context', _kbIds: '' }
    case 'llm': return { prompt: '', outputKey: 'answer' }
    case 'condition': return { expression: '' }
    case 'output': return { template: '{{answer}}' }
    default: return {}
  }
}

function uniqueId(type: string): string {
  let i = 1
  while (findNode(`${type}${i}`)) i++
  return `${type}${i}`
}

/** 把 graphJson 的图灌进画布 */
function loadGraphToFlow(parsed: { nodes?: any[]; edges?: any[] }) {
  const rawNodes = Array.isArray(parsed.nodes) ? parsed.nodes : []
  const rawEdges = Array.isArray(parsed.edges) ? parsed.edges : []
  const vfNodes: Node[] = rawNodes.map((n: any, i: number) => {
    const config: Record<string, any> = { ...(n.config || {}) }
    if (n.type === 'retrieval' && Array.isArray(config.kbIds)) {
      config._kbIds = (config.kbIds as any[]).join(',')
    }
    const pos = n.position && typeof n.position.x === 'number'
      ? { x: Number(n.position.x), y: Number(n.position.y) }
      : { x: 120 + (i % 3) * 200, y: 60 + Math.floor(i / 3) * 120 }
    return {
      id: String(n.id),
      type: 'default',
      position: pos,
      label: nodeLabel(n.type, n.id),
      data: { wfType: n.type, config },
      style: nodeStyle(n.type),
    }
  })
  const vfEdges: Edge[] = rawEdges
    .filter((e: any) => e.from && e.to)
    .map((e: any) => ({
      id: genEdgeId(),
      source: String(e.from),
      target: String(e.to),
      label: e.when || '',
      data: { when: e.when || '' },
    }))
  setNodes(vfNodes)
  setEdges(vfEdges)
  selNodeId.value = null
  selEdgeId.value = null
  nextTick(() => { try { fitView() } catch { /* 画布未就绪，忽略 */ } })
}

function newDef() {
  current.value = { name: '新工作流', description: '', graphJson: '' }
  nextTick(() => loadGraphToFlow({ nodes: [{ id: 'start', type: 'start', config: {} }], edges: [] }))
}

async function selectDef(d: WorkflowDefinition) {
  try {
    const res: any = await workflowApi.get(d.id as number)
    const def: WorkflowDefinition = res?.data ?? res
    current.value = { ...def }
    const parsed = def.graphJson ? JSON.parse(def.graphJson) : { nodes: [], edges: [] }
    nextTick(() => loadGraphToFlow(parsed))
  } catch (e: any) { ElMessage.error('打开失败：' + (e?.message || '')) }
}

// ─── 画布交互 ───
function addNode(wfType: string) {
  const id = uniqueId(wfType)
  const n = toObject().nodes.length
  addNodes([{
    id,
    type: 'default',
    position: { x: 120 + (n % 3) * 200, y: 60 + Math.floor(n / 3) * 120 },
    label: nodeLabel(wfType, id),
    data: { wfType, config: defaultConfig(wfType) },
    style: nodeStyle(wfType),
  }])
  selNodeId.value = id
  selEdgeId.value = null
}
function onConnect(c: Connection) {
  addEdges([{ id: genEdgeId(), source: c.source, target: c.target, label: '', data: { when: '' } }])
}
function onNodeClick(e: NodeMouseEvent) { selNodeId.value = e.node.id; selEdgeId.value = null }
function onEdgeClick(e: EdgeMouseEvent) { selEdgeId.value = e.edge.id; selNodeId.value = null }
function onPaneClick() { selNodeId.value = null; selEdgeId.value = null }
function deleteSelectedNode() {
  if (!selNodeId.value) return
  removeNodes([selNodeId.value], true)   // 同时删掉相连的边
  selNodeId.value = null
}
function deleteSelectedEdge() {
  if (!selEdgeId.value) return
  removeEdges([selEdgeId.value])
  selEdgeId.value = null
}
function setSelEdgeWhen(val: string) {
  const id = selEdgeId.value
  if (!id) return
  setEdges(edges => edges.map(e => (e.id === id
    ? { ...e, label: val, data: { ...(e.data || {}), when: val } }
    : e)))
}

/** 画布 → graphJson（保存用，含坐标；retrieval 的 _kbIds 转回 number[]） */
function buildGraphJson(): string {
  const obj = toObject()
  const nodes = obj.nodes.map((n: any) => {
    const wfType: string = n.data?.wfType
    const config: Record<string, any> = { ...(n.data?.config || {}) }
    if (wfType === 'retrieval') {
      const raw = String(config._kbIds || '').trim()
      delete config._kbIds
      if (raw) config.kbIds = raw.split(',').map((s: string) => Number(s.trim())).filter((x: number) => !Number.isNaN(x))
      if (config.topK != null) config.topK = Number(config.topK) || 6
    }
    return {
      id: n.id,
      type: wfType,
      config,
      position: { x: Math.round(n.position.x), y: Math.round(n.position.y) },
    }
  })
  const edges = obj.edges
    .filter((e: any) => e.source && e.target)
    .map((e: any) => ({ from: e.source, to: e.target, when: String(e.data?.when ?? '') }))
  return JSON.stringify({ nodes, edges })
}

async function save() {
  if (!current.value) return
  if (!current.value.name?.trim()) { ElMessage.warning('请填写名称'); return }
  if (!toObject().nodes.length) { ElMessage.warning('至少要有一个节点'); return }
  saving.value = true
  try {
    const payload: Partial<WorkflowDefinition> = {
      id: current.value.id,
      name: current.value.name,
      description: current.value.description,
      graphJson: buildGraphJson(),
    }
    const res: any = await workflowApi.save(payload)
    const saved: WorkflowDefinition = res?.data ?? res
    current.value = { ...current.value, id: saved.id }
    ElMessage.success('已保存')
    await loadList()
  } catch (e: any) { ElMessage.error('保存失败：' + (e?.message || '')) }
  finally { saving.value = false }
}

async function del() {
  if (!current.value?.id) return
  await ElMessageBox.confirm('确定删除该工作流？', '提示', { type: 'warning' })
  try {
    await workflowApi.remove(current.value.id)
    ElMessage.success('已删除')
    current.value = null
    await loadList()
  } catch (e: any) {
    if (e === 'cancel') return
    ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

// ─── 运行 ───
const runVisible = ref(false)
const running = ref(false)
const runVars = ref<{ key: string; value: string }[]>([{ key: 'question', value: '' }])
const runLog = ref<{ nodeId: string; status: string; text: string }[]>([])
const runResult = ref<string | null>(null)
const runError = ref('')
let runSse: EventSource | null = null

function openRun() {
  runVisible.value = true
  runLog.value = []
  runResult.value = null
  runError.value = ''
}
function closeRun() { if (runSse) { runSse.close(); runSse = null } running.value = false }

async function startRun() {
  if (!current.value?.id) { ElMessage.warning('请先保存工作流'); return }
  runLog.value = []
  runResult.value = null
  runError.value = ''
  const vars: Record<string, any> = {}
  for (const v of runVars.value) if (v.key.trim()) vars[v.key.trim()] = v.value
  try {
    running.value = true
    const res: any = await workflowApi.createRun(current.value.id, vars)
    const runId = res?.data?.runId ?? res?.runId
    if (!runId) throw new Error('未拿到 runId')
    const token = localStorage.getItem('token') || ''
    runSse = new EventSource(`/api/v2/workflow/runs/${runId}/stream?token=${encodeURIComponent(token)}`)
    runSse.addEventListener('node.start', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      runLog.value.push({ nodeId: d.nodeId, status: 'running', text: '执行中…' })
    })
    runSse.addEventListener('node.done', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      const row = [...runLog.value].reverse().find(r => r.nodeId === d.nodeId && r.status === 'running')
      if (row) { row.status = 'done'; row.text = `完成 · ${d.elapsedMs}ms` }
    })
    runSse.addEventListener('node.skipped', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      runLog.value.push({ nodeId: d.nodeId, status: 'skipped', text: '跳过（未命中分支）' })
    })
    runSse.addEventListener('node.error', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      runLog.value.push({ nodeId: d.nodeId, status: 'error', text: d.message })
    })
    runSse.addEventListener('run.done', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      runResult.value = String(d.result ?? '')
      running.value = false
      runSse?.close(); runSse = null
    })
    runSse.addEventListener('run.error', (e) => {
      const d = JSON.parse((e as MessageEvent).data)
      runError.value = d.message || '未知错误'
      running.value = false
      runSse?.close(); runSse = null
    })
    runSse.onerror = () => { running.value = false; runSse?.close(); runSse = null }
  } catch (e: any) {
    running.value = false
    ElMessage.error('运行失败：' + (e?.message || ''))
  }
}

// ─── 运行历史 ───
const historyVisible = ref(false)
const historyLoading = ref(false)
const history = ref<WorkflowRun[]>([])
const detail = ref<{ run: any; nodeRuns: WorkflowNodeRun[] } | null>(null)
const detailResult = ref<string | null>(null)

const STATUS_LABEL: Record<string, string> = {
  PENDING: '等待', RUNNING: '运行中', COMPLETED: '成功', FAILED: '失败',
}
const statusLabel = (s?: string) => STATUS_LABEL[String(s || '').toUpperCase()] || (s || '-')
const logClass = (s?: string) =>
  ({ COMPLETED: 'done', FAILED: 'error', SKIPPED: 'skipped', RUNNING: 'running' }[String(s || '').toUpperCase()] || '')
function fmtTime(t?: string | null): string {
  if (!t) return '-'
  const d = new Date(t)
  return Number.isNaN(d.getTime()) ? String(t) : d.toLocaleString()
}

async function openHistory() {
  if (!current.value?.id) return
  historyVisible.value = true
  detail.value = null
  detailResult.value = null
  await loadHistory()
}
async function loadHistory() {
  if (!current.value?.id) return
  historyLoading.value = true
  try {
    const res: any = await workflowApi.listRuns(current.value.id)
    history.value = res?.data ?? res ?? []
  } catch (e: any) { ElMessage.error('加载历史失败：' + (e?.message || '')) }
  finally { historyLoading.value = false }
}
async function openDetail(r: WorkflowRun) {
  historyLoading.value = true
  detailResult.value = null
  try {
    const res: any = await workflowApi.getRun(r.id)
    const data = res?.data ?? res
    detail.value = { run: data?.run ?? null, nodeRuns: data?.nodeRuns ?? [] }
    const rj = detail.value.run?.resultJson
    if (rj) {
      try {
        const ctx = JSON.parse(rj)
        detailResult.value = ctx?.result != null ? String(ctx.result) : null
      } catch { detailResult.value = null }
    }
  } catch (e: any) { ElMessage.error('加载详情失败：' + (e?.message || '')) }
  finally { historyLoading.value = false }
}

onMounted(loadList)
onBeforeUnmount(() => { if (runSse) runSse.close() })
</script>

<style scoped>
.wf-page { display: flex; height: 100%; overflow: hidden; }

.wf-list { width: 240px; flex-shrink: 0; border-right: 1px solid var(--line, #e6e9ef); display: flex; flex-direction: column; }
.wf-list-head { display: flex; align-items: center; justify-content: space-between; padding: 14px 14px; border-bottom: 1px solid var(--line, #e6e9ef); }
.wf-list-title { font-weight: 700; font-size: 15px; color: var(--ink-1, #1e293b); }
.wf-list-body { flex: 1; overflow-y: auto; padding: 8px; }
.wf-item { padding: 10px 12px; border-radius: 8px; cursor: pointer; margin-bottom: 4px; }
.wf-item:hover { background: var(--bg-hover, #f1f5f9); }
.wf-item.active { background: rgba(0,0,0,0.1); }
.wf-item-name { font-size: 13.5px; font-weight: 600; color: var(--ink-1, #1e293b); }
.wf-item-sub { font-size: 11.5px; color: var(--ink-4, #94a3b8); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-empty { padding: 20px; text-align: center; color: var(--ink-4, #94a3b8); font-size: 12.5px; }

.wf-editor { flex: 1; display: flex; flex-direction: column; min-width: 0; }
.wf-editor-head { display: flex; gap: 10px; align-items: center; padding: 14px 18px; border-bottom: 1px solid var(--line, #e6e9ef); flex-wrap: wrap; }
.wf-head-actions { margin-left: auto; display: flex; gap: 8px; }
.wf-editor-body { flex: 1; display: flex; min-height: 0; }

/* 画布 */
.wf-canvas-wrap { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.wf-palette { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-bottom: 1px solid var(--line, #e6e9ef); flex-wrap: wrap; }
.wf-palette-label { font-size: 11.5px; color: var(--ink-4, #94a3b8); margin-right: 6px; }
.wf-canvas { flex: 1; min-height: 320px; background: var(--bg-hover, #f8fafc); }

/* 检查器 */
.wf-inspector { width: 320px; flex-shrink: 0; border-left: 1px solid var(--line, #e6e9ef); padding: 16px; overflow-y: auto; }
.wf-insp-head { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; }
.wf-insp-id { font-weight: 600; font-size: 13px; color: var(--ink-2, #475569); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.wf-edge-badge { font-size: 11px; font-weight: 700; color: #fff; background: #64748b; padding: 2px 8px; border-radius: 999px; }
.wf-insp-empty { padding-top: 24px; }
.wf-node-type { font-size: 11px; font-weight: 700; color: #fff; padding: 2px 8px; border-radius: 999px; }
.wf-cfg { display: flex; flex-direction: column; gap: 5px; }
.wf-cfg > label { font-size: 11.5px; color: var(--ink-4, #94a3b8); }
.wf-cfg-row { display: flex; gap: 10px; }
.wf-cfg-row > div { flex: 1; }
.wf-cfg-hint { font-size: 12px; color: var(--ink-4, #94a3b8); }

.wf-blank { flex: 1; display: flex; align-items: center; justify-content: center; }

.wf-run { padding: 4px; }
.wf-section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; font-weight: 600; font-size: 13.5px; color: var(--ink-2, #475569); }
.wf-var-row { display: flex; gap: 6px; align-items: center; margin-bottom: 6px; }
.wf-run-log { margin-top: 8px; display: flex; flex-direction: column; gap: 4px; }
.wf-log-row { display: flex; align-items: center; gap: 8px; font-size: 12.5px; padding: 6px 10px; border-radius: 6px; background: var(--bg-hover, #f6f8fb); }
.wf-log-dot { width: 8px; height: 8px; border-radius: 50%; background: #94a3b8; flex-shrink: 0; }
.wf-log-row.running .wf-log-dot { background: #f59e0b; }
.wf-log-row.done .wf-log-dot { background: #22c55e; }
.wf-log-row.skipped .wf-log-dot { background: #cbd5e1; }
.wf-log-row.error .wf-log-dot { background: #ef4444; }
.wf-log-node { font-weight: 600; color: var(--ink-2, #475569); }
.wf-log-text { color: var(--ink-4, #94a3b8); }
.wf-log-type { font-size: 11px; color: var(--ink-4, #94a3b8); }
.wf-run-result { margin-top: 14px; }
.wf-result-title { font-size: 13px; font-weight: 600; margin-bottom: 6px; color: var(--ink-2, #475569); }
.wf-result-body { background: var(--bg-hover, #f6f8fb); border-radius: 8px; padding: 10px 12px; font-size: 13px; white-space: pre-wrap; word-break: break-word; }
.wf-run-error { margin-top: 12px; color: #ef4444; font-size: 13px; }

/* 历史 */
.wf-history { min-height: 60px; }
.wf-hist-list { display: flex; flex-direction: column; gap: 6px; }
.wf-hist-item { display: flex; align-items: center; gap: 10px; padding: 10px 12px; border-radius: 8px; background: var(--bg-hover, #f6f8fb); cursor: pointer; transition: background .15s; }
.wf-hist-item:hover { background: var(--bg-hover-2, #eef2f8); }
.wf-hist-id { font-weight: 600; color: var(--ink-2, #475569); }
.wf-hist-time { font-size: 12px; color: var(--ink-4, #94a3b8); flex: 1; }
.wf-hist-elapsed { font-size: 12px; color: var(--ink-3, #64748b); }
.wf-hist-status { font-size: 11px; font-weight: 600; padding: 2px 8px; border-radius: 10px; color: #fff; background: #94a3b8; flex-shrink: 0; }
.wf-hist-status.completed { background: #22c55e; }
.wf-hist-status.running { background: #f59e0b; }
.wf-hist-status.failed { background: #ef4444; }
.wf-hist-status.pending { background: #94a3b8; }
.wf-hist-detail-head { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line, #e6e9ef); }
.wf-hist-detail .wf-run-log { display: flex; flex-direction: column; gap: 6px; }
</style>
