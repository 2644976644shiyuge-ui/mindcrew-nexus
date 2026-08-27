<template>
  <div ref="pageRef" class="crew-page" :class="{ 'is-running': !!activeTask }">
    <main v-if="!activeTask" class="crew-home">
      <section class="command-hero" aria-labelledby="crew-title">
        <div class="hero-copy">
          <div class="hero-kicker">AI RESEARCH DESK</div>
          <h1 id="crew-title">把复杂问题，交给四位 Agent 协作完成</h1>
          <p>从任务拆解到事实评审，每一步都有记录，每一条结论都有依据。</p>

          <form class="research-composer" @submit.prevent="handleStart">
            <label for="crew-query">你想研究什么？</label>
            <textarea
              id="crew-query"
              v-model="query"
              :placeholder="`例如：分析 ${brandStore.systemName} 在美国教育市场的竞争位置，并给出进入策略`"
              rows="4"
              :disabled="loading"
            ></textarea>
            <div class="composer-footer">
              <button type="button" class="attachment-btn" :disabled="loading" aria-label="添加附件">
                <el-icon :size="17"><Paperclip /></el-icon>
                <span>添加资料</span>
              </button>
              <span class="composer-hint">建议写清目标市场、时间范围与交付要求</span>
              <button type="submit" class="start-btn" :disabled="!query.trim() || loading">
                <span>{{ loading ? '正在创建' : '启动调研' }}</span>
                <el-icon :size="16"><Promotion /></el-icon>
              </button>
            </div>
          </form>

          <div class="prompt-presets" aria-label="问题示例">
            <button type="button" @click="query = '分析 ZYCOO 进入美国教育市场的主要竞争者、渠道与机会'">美国教育市场</button>
            <button type="button" @click="query = '对比 ZYCOO Nexus 与传统 RAG 的核心差异，并给出企业选型建议'">产品对比研究</button>
            <button type="button" @click="query = '总结本季度行业变化，并识别最值得跟进的三类客户机会'">行业机会扫描</button>
          </div>
        </div>

        <aside class="orchestration-panel" aria-label="四位 Agent 协作方式">
          <div class="orchestration-head">
            <div>
              <span>协作链路</span>
              <strong>四位专家，一份结论</strong>
            </div>
            <span class="ready-badge">READY</span>
          </div>
          <ol class="agent-chain">
            <li v-for="(agent, index) in agentLanes" :key="agent.role">
              <span class="chain-index">0{{ index + 1 }}</span>
              <span class="chain-icon"><component :is="agent.icon" /></span>
              <div>
                <strong>{{ agent.label }}</strong>
                <span>{{ agent.duty }}</span>
              </div>
              <el-icon class="chain-arrow" :size="15"><Right /></el-icon>
            </li>
          </ol>
          <div class="orchestration-note">
            <el-icon :size="17"><CircleCheckFilled /></el-icon>
            <span>Critic 不通过时，Writer 会基于反馈自动重写</span>
          </div>
        </aside>
      </section>

      <section ref="historySection" class="research-history" aria-labelledby="history-title">
        <header class="section-head">
          <div>
            <h2 id="history-title">最近调研</h2>
            <p>继续查看报告、通信图谱与完整推理过程。</p>
          </div>
          <button type="button" class="history-link" @click="goHistory">
            <el-icon :size="16"><Clock /></el-icon>
            <span>刷新记录</span>
          </button>
        </header>

        <div v-if="historyLoading" class="history-skeleton" aria-label="正在加载调研记录">
          <span v-for="i in 3" :key="i"></span>
        </div>

        <div v-else-if="historyError" class="history-state history-state--error">
          <strong>调研记录暂时无法加载</strong>
          <span>{{ historyError }}</span>
          <button type="button" @click="loadHistory">重新加载</button>
        </div>

        <div v-else-if="!historyList.length" class="history-state">
          <strong>这里还没有调研任务</strong>
          <span>从上方输入一个问题，第一份研究报告会出现在这里。</span>
        </div>

        <ol v-else class="history-list">
          <li v-for="t in historyList.slice(0, 6)" :key="t.id" class="history-item">
            <button type="button" class="history-main" @click="viewTask(t.id)">
              <span class="history-status" :class="t.status.toLowerCase()">{{ statusLabel(t.status) }}</span>
              <span class="history-query">{{ t.query }}</span>
              <span class="history-time">{{ formatTime(t.createTime) }}</span>
            </button>
            <div class="history-actions" v-if="t.status === 'COMPLETED' || t.status === 'FAILED'">
              <button type="button" @click="shareTask(t.id)" aria-label="复制分享链接"><el-icon><Share /></el-icon></button>
              <button type="button" @click="openGraph(t.id)" aria-label="查看通信图谱"><el-icon><Grid /></el-icon></button>
              <button type="button" @click="openReplay(t.id)" aria-label="查看推理回放"><el-icon><VideoPlay /></el-icon></button>
            </div>
          </li>
        </ol>
      </section>
    </main>

    <main v-else class="mission-workspace">
      <header class="mission-header">
        <button type="button" class="back-btn" @click="resetTask">
          <el-icon :size="15"><Back /></el-icon>
          <span>新建调研</span>
        </button>
        <div class="mission-title">
          <span class="mission-label">当前任务</span>
          <h1>{{ activeTask.query }}</h1>
        </div>
        <div class="mission-meta">
          <span class="meta-chip" :class="statusClass">{{ statusLabel(activeTask.status) }}</span>
          <span>{{ elapsedSec ? `${elapsedSec}s` : '刚刚启动' }}</span>
        </div>
      </header>

      <div class="mission-progress" role="progressbar" :aria-valuenow="Math.round(progress * 100)" aria-valuemin="0" aria-valuemax="100">
        <span :style="{ transform: `scaleX(${Math.max(progress, 0.012)})` }"></span>
      </div>

      <div v-if="activeTask.status === 'FAILED'" class="mission-alert" role="alert">
        <strong>本次调研未能完成</strong>
        <span>{{ activeTask.errorMsg || '请新建任务后重试。' }}</span>
      </div>

      <section class="mission-grid">
        <aside class="mission-rail" aria-label="Agent 执行状态">
          <header>
            <div>
              <span>协作进度</span>
              <strong>{{ completedAgentCount }} / 4</strong>
            </div>
            <span>{{ Math.round(progress * 100) }}%</span>
          </header>

          <ol class="mission-agents">
            <li v-for="agent in agentLanes" :key="agent.role" :class="agentClass(agent.role)">
              <span class="mission-agent-icon"><component :is="agent.icon" /></span>
              <div>
                <strong>{{ agent.label }}</strong>
                <span>{{ agentStatusDetail(agent.role) }}</span>
              </div>
              <span class="mission-agent-state">{{ agentStatusText(agent.role) }}</span>
            </li>
          </ol>

          <div class="mission-summary">
            <div><span>子主题</span><strong>{{ plan.length }}</strong></div>
            <div><span>研究发现</span><strong>{{ findings.length }}</strong></div>
            <div><span>引用</span><strong>{{ totalCitations }}</strong></div>
          </div>

          <section class="event-stream" aria-labelledby="event-title">
            <div class="event-head">
              <h2 id="event-title">实时事件</h2>
              <span>{{ timeline.length }}</span>
            </div>
            <ol v-if="timeline.length" class="event-list">
              <li v-for="(ev, i) in timeline" :key="i">
                <span class="event-node"></span>
                <div>
                  <span class="event-role">{{ roleDisplay(ev.role) }}</span>
                  <strong>{{ ev.title }}</strong>
                  <p v-if="ev.detail">{{ ev.detail }}</p>
                </div>
              </li>
            </ol>
            <div v-else class="event-empty">正在等待第一个执行事件</div>
          </section>
        </aside>

        <article class="report-panel">
          <header class="report-head">
            <div>
              <span>DELIVERABLE</span>
              <h2>研究报告</h2>
            </div>
            <div class="report-actions" v-if="report">
              <button type="button" @click="copyReport"><el-icon :size="15"><CopyDocument /></el-icon><span>复制</span></button>
              <button type="button" @click="downloadReport"><el-icon :size="15"><Download /></el-icon><span>下载</span></button>
            </div>
          </header>

          <div v-if="report" class="report-body md-body" v-html="renderedReport"></div>
          <div v-else class="report-waiting">
            <div class="waiting-sheet" aria-hidden="true">
              <span></span><span></span><span></span><span></span>
            </div>
            <div>
              <strong>{{ writerStarted ? 'Writer 正在整理报告' : '报告将在研究完成后生成' }}</strong>
              <span>{{ writerStarted ? `已生成 ${reportLength} 个字符` : '你可以在左侧实时查看四位 Agent 的执行过程。' }}</span>
            </div>
          </div>

          <footer v-if="review" class="review-strip">
            <div>
              <span>Critic 评分</span>
              <strong>{{ (review.score * 100).toFixed(0) }}</strong>
            </div>
            <div><span>事实</span><strong>{{ (review.factuality * 100).toFixed(0) }}</strong></div>
            <div><span>完整</span><strong>{{ (review.completeness * 100).toFixed(0) }}</strong></div>
            <div><span>引用</span><strong>{{ (review.citationCoverage * 100).toFixed(0) }}</strong></div>
          </footer>
        </article>
      </section>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Right, Back, CopyDocument, Download, DataAnalysis, Search, EditPen, Aim, VideoPlay, Share, Grid, CircleCheckFilled, Paperclip, Promotion, Clock } from '@element-plus/icons-vue'
import { marked } from 'marked'
import { crewApi, type AgentTask, type PlanItem, type Finding, type ReviewResult } from '@/api/crew'
import { useUserStore } from '@/stores/user'
import { useBrandStore } from '@/stores/brand'

const router = useRouter()
const openReplay = (id: number) => router.push(`/crew/replay/${id}`)
const openGraph  = (id: number) => router.push(`/crew/graph/${id}`)
const historySection = ref<HTMLElement | null>(null)
const goHistory = async () => {
  await loadHistory()
  historySection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
// 分享任务：复制回放页链接到剪贴板
const shareTask = async (id: number) => {
  const url = `${window.location.origin}/crew/replay/${id}`
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success(`分享链接已复制：${url}`)
  } catch {
    ElMessage.warning('复制失败，请手动复制链接：' + url)
  }
}

const userStore = useUserStore()
const brandStore = useBrandStore()

const query = ref('')
const pageRef = ref<HTMLElement | null>(null)
const loading = ref(false)
const activeTask = ref<AgentTask | null>(null)

const plan       = ref<PlanItem[]>([])
const findings   = ref<Finding[]>([])
const report     = ref('')
const review     = ref<ReviewResult | null>(null)
const writerStarted = ref(false)
const progress   = ref(0)

const agentStatus = ref<Record<string, 'idle' | 'working' | 'done' | 'failed'>>({
  PLANNER: 'idle', RESEARCHER: 'idle', WRITER: 'idle', CRITIC: 'idle'
})

interface TLEvent { role?: string; title: string; detail?: string; tone?: string }
const timeline = ref<TLEvent[]>([])

const historyList = ref<AgentTask[]>([])
const historyLoading = ref(true)
const historyError = ref('')
const elapsedSec = ref(0)
let elapsedTimer: number | undefined
let eventSource: EventSource | null = null

const agentLanes = [
  { role: 'PLANNER',    label: '任务规划师', duty: '分解为子主题',          icon: Aim,          tone: '#EEF1FF',  accent: '#3D5AFE' },
  { role: 'RESEARCHER', label: '调研员',     duty: '并行多路检索',          icon: Search,       tone: '#E0F2FE',  accent: '#0EA5E9' },
  { role: 'WRITER',     label: '撰写员',     duty: '合成结构化报告',        icon: EditPen,      tone: '#F3F1FF',  accent: '#0a0a0a' },
  { role: 'CRITIC',     label: '评审员',     duty: '评分+反馈+决定重写',    icon: DataAnalysis, tone: '#DCFCE7',  accent: '#10B981' },
]

const reportLength = computed(() => report.value ? report.value.length : 0)
const completedAgentCount = computed(() => Object.values(agentStatus.value).filter(s => s === 'done').length)
const totalCitations = computed(() => findings.value.reduce((sum, item) => sum + (item.sources?.length || 0), 0))

const renderedReport = computed(() => report.value ? (marked.parse(report.value) as string) : '')

const statusClass = computed(() => 'status-' + (activeTask.value?.status || '').toLowerCase())

const statusLabel = (s: string) => {
  const map: Record<string, string> = {
    PENDING: '待启动', PLANNING: '规划中', RESEARCHING: '调研中',
    WRITING: '撰写中', REVIEWING: '评审中', REVISING: '重写中',
    COMPLETED: '已完成', FAILED: '失败'
  }
  return map[s] || s
}

const agentStatusText = (role: string) => {
  const s = agentStatus.value[role]
  return s === 'working' ? '进行中' : s === 'done' ? '已完成' : s === 'failed' ? '失败' : '等待中'
}

const agentClass = (role: string) => {
  return 'state-' + agentStatus.value[role]
}

const agentStatusDetail = (role: string) => {
  if (role === 'PLANNER') return plan.value.length ? `已拆分 ${plan.value.length} 个子主题` : '拆解目标与研究路径'
  if (role === 'RESEARCHER') return findings.value.length ? `已完成 ${findings.value.length} 项发现` : '检索知识库与可信来源'
  if (role === 'WRITER') return reportLength.value ? `已生成 ${reportLength.value} 个字符` : '整合发现并撰写报告'
  if (role === 'CRITIC') return review.value ? `综合评分 ${(review.value.score * 100).toFixed(0)}` : '核验事实、完整性与引用'
  return ''
}

const roleDisplay = (role?: string) => {
  const map: Record<string, string> = {
    PLANNER: 'Planner', RESEARCHER: 'Researcher', WRITER: 'Writer', CRITIC: 'Critic', SYS: 'System', error: 'System'
  }
  return map[role || 'SYS'] || role || 'System'
}

const formatTime = (s: string) => {
  if (!s) return ''
  const d = new Date(s.replace(' ', 'T'))
  return d.toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' })
}

// ─── 启动 ─────────────────────────────────
const handleStart = async () => {
  if (!query.value.trim() || loading.value) return
  loading.value = true
  try {
    const res: any = await crewApi.createTask(query.value.trim())
    const taskId = res.data?.taskId ?? res.taskId
    if (!taskId) throw new Error('taskId missing in response')

    activeTask.value = {
      id: taskId, userId: 0, query: query.value, status: 'PENDING',
      revisionCount: 0, totalSteps: 0, totalTokens: 0, elapsedMs: 0,
      createTime: new Date().toISOString()
    }

    startElapsed()
    subscribeStream(taskId)
  } catch (e: any) {
    ElMessage.error('启动失败：' + (e?.message || e))
    loading.value = false
  }
}

// ─── 订阅 SSE ─────────────────────────────
const subscribeStream = (taskId: number) => {
  if (eventSource) eventSource.close()
  const source = crewApi.streamTask(taskId, userStore.token)
  eventSource = source

  const evs = [
    'task.start','task.done','task.failed',
    'agent.start','agent.done','agent.failed',
    'planner.plan',
    'researcher.start','researcher.finding',
    'writer.token','writer.done',
    'critic.review','revision.start'
  ]

  evs.forEach(name => source.addEventListener(name, (e: MessageEvent) => handleEvent(name, e.data)))

  source.onerror = () => {
    log('error', '连接中断')
    source.close()
    eventSource = null
  }
}

const handleEvent = (name: string, raw: any) => {
  let payload: any = {}
  try { payload = JSON.parse(raw) } catch { /* ignore */ }
  const data = payload.data || {}

  if (payload.progress != null) progress.value = payload.progress

  switch (name) {
    case 'task.start':
      activeTask.value!.status = 'PLANNING'
      log('PLANNER', '任务启动', payload.data?.query); break

    case 'planner.plan':
      plan.value = data.plan || []
      agentStatus.value.PLANNER = 'done'
      log('PLANNER', '任务分解完成', `${plan.value.length} 个子主题`)
      break

    case 'agent.start':
      if (payload.role) {
        agentStatus.value[payload.role] = 'working'
        const statusByRole: Record<string, string> = {
          PLANNER: 'PLANNING', RESEARCHER: 'RESEARCHING', WRITER: 'WRITING', CRITIC: 'REVIEWING'
        }
        const nextStatus = statusByRole[payload.role]
        if (activeTask.value && nextStatus) activeTask.value.status = nextStatus
      }
      break

    case 'agent.done':
      if (payload.role) agentStatus.value[payload.role] = 'done'
      break

    case 'researcher.start':
      if (activeTask.value) activeTask.value.status = 'RESEARCHING'
      log('RESEARCHER', `开始调研：${data.title}`)
      break

    case 'researcher.finding':
      const f: Finding = data.finding
      if (f) {
        findings.value = [...findings.value, f].sort((a, b) => a.planIndex - b.planIndex)
        log('RESEARCHER', `完成：${f.title}`, `${f.sources?.length || 0} 处引用`)
      }
      break

    case 'writer.token':
      if (activeTask.value) activeTask.value.status = 'WRITING'
      writerStarted.value = true
      if (data.delta) report.value += data.delta
      break

    case 'writer.done':
      report.value = data.report || report.value
      log('WRITER', '报告撰写完成', `${(data.report || '').length} 字符`)
      break

    case 'critic.review':
      if (activeTask.value) activeTask.value.status = 'REVIEWING'
      review.value = data.review
      log('CRITIC', `评审完成（${review.value?.passed ? '通过' : '未通过'}）`,
          `综合分 ${((review.value?.score || 0) * 100).toFixed(0)}/100`)
      break

    case 'revision.start':
      if (activeTask.value) activeTask.value.status = 'REVISING'
      report.value = ''
      log('CRITIC', '触发重写', data.reason)
      break

    case 'task.done':
      activeTask.value!.status = 'COMPLETED'
      stopElapsed()
      loading.value = false
      log('SYS', '任务完成', `用时 ${(data.elapsedMs / 1000).toFixed(1)}s`)
      ElMessage.success('调研完成')
      eventSource?.close()
      loadHistory()
      break

    case 'task.failed':
      activeTask.value!.status = 'FAILED'
      stopElapsed()
      loading.value = false
      log('SYS', '任务失败', data.error)
      ElMessage.error('任务失败：' + data.error)
      eventSource?.close()
      break
  }
}

const log = (role: string, title: string, detail?: string) => {
  timeline.value.push({ role, title, detail })
}

// ─── 计时 ─────────────────────────────────
const startElapsed = () => {
  elapsedSec.value = 0
  elapsedTimer = window.setInterval(() => { elapsedSec.value++ }, 1000)
}
const stopElapsed = () => {
  if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = undefined }
}

// ─── 重置 / 历史 ──────────────────────────
const resetTask = () => {
  activeTask.value = null
  plan.value = []
  findings.value = []
  report.value = ''
  review.value = null
  writerStarted.value = false
  progress.value = 0
  timeline.value = []
  agentStatus.value = { PLANNER: 'idle', RESEARCHER: 'idle', WRITER: 'idle', CRITIC: 'idle' }
  eventSource?.close()
  eventSource = null
  stopElapsed()
  loading.value = false
}

const viewTask = async (id: number) => {
  try {
    const res: any = await crewApi.getTask(id)
    const data = res.data || res
    activeTask.value = data.task
    if (data.task.planJson) {
      try { plan.value = JSON.parse(data.task.planJson) } catch { /* ignore */ }
    }
    report.value = data.task.finalReport || ''
    progress.value = data.task.status === 'COMPLETED' ? 1 : 0
    agentStatus.value = {
      PLANNER: 'done', RESEARCHER: 'done', WRITER: 'done', CRITIC: 'done'
    }
    // 时间线从 steps 重建
    data.steps?.forEach((s: any) => {
      log(s.agentRole, s.stepName, s.status === 'DONE' ? `${s.elapsedMs}ms` : s.errorMsg)
    })
  } catch (e: any) {
    ElMessage.error('加载失败')
  }
}

const loadHistory = async () => {
  historyLoading.value = true
  historyError.value = ''
  try {
    const res: any = await crewApi.listTasks({ current: 1, size: 8 })
    historyList.value = (res.data?.records || res.records || []).slice(0, 8)
  } catch (e: any) {
    historyError.value = e?.message || '网络连接异常'
  } finally {
    historyLoading.value = false
  }
}

// ─── 报告操作 ─────────────────────────────
const copyReport = async () => {
  try {
    await navigator.clipboard.writeText(report.value)
    ElMessage.success('已复制')
  } catch { ElMessage.error('复制失败') }
}

const downloadReport = () => {
  const blob = new Blob([report.value], { type: 'text/markdown; charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `crew-report-${activeTask.value?.id || Date.now()}.md`
  a.click()
  URL.revokeObjectURL(url)
}

onMounted(() => {
  pageRef.value?.scrollTo({ top: 0 })
  loadHistory()
})
onBeforeUnmount(() => {
  eventSource?.close()
  stopElapsed()
})
</script>

<style scoped media="not all">
@media not all {
.crew-page {
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 32px 36px 64px;
  background: transparent;
  scrollbar-gutter: stable;
}

/* ───── Hero（设计稿：微软纯净风）───── */
.crew-hero {
  max-width: 960px;
  margin: 32px auto 0;
  position: relative;
}
/* 右上浮按钮：调研历史 */
.history-fab {
  position: absolute;
  top: -10px;
  right: 0;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 38px;
  padding: 0 18px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 999px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-2);
  cursor: pointer;
  box-shadow: 0 2px 8px rgba(15, 23, 42, .04);
  transition: var(--transition);
}
.history-fab:hover { border-color: var(--brand-soft-2); color: var(--ink-1); transform: translateY(-1px); }

/* 主标题 */
.hero-title {
  font-family: 'Manrope', 'Noto Sans SC', 'PingFang SC', sans-serif;
  font-size: 44px;
  font-weight: 800;
  line-height: 1.22;
  letter-spacing: -0.025em;
  color: var(--ink-1);
  margin-bottom: 18px;
}
.hero-title__dark  { color: var(--ink-1); }
.hero-title__brand { color: #2F54EB; }
.hero-desc {
  font-size: 14.5px;
  line-height: 1.85;
  color: var(--ink-2);
  margin-bottom: 26px;
  white-space: pre-wrap;
}

/* 输入卡片（v6 气泡样式 · 与首页统一 · 紧凑高度） */
.hero-form {
  position: relative;
  background: #FFFFFF;
  border: 0.5px solid #E4E4E7;
  border-radius: 18px;
  padding: 10px 14px 8px;
  box-shadow: 0 2px 12px rgba(15, 23, 42, .03);
  transition: border-color 180ms, box-shadow 180ms;
}
.hero-form:focus-within {
  border-color: rgba(0, 113, 227, .5);
  box-shadow: 0 0 0 4px rgba(0, 113, 227, .10);
}
.hero-input {
  width: 100%;
  border: none;
  outline: none;
  background: transparent;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.5;
  color: #1D1D1F;
  resize: none;
}
.hero-input::placeholder { color: #AEAEB2; }

.hero-form-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 6px;
  padding-top: 6px;
  border-top: 0.5px solid #F0F0F2;
}
.form-icon-btn {
  width: 28px; height: 28px;
  display: inline-flex; align-items: center; justify-content: center;
  background: transparent;
  border: 0;
  border-radius: 8px;
  color: #AEAEB2;
  cursor: pointer;
  transition: background 180ms, color 180ms;
}
.form-icon-btn:hover:not(:disabled) { background: #F5F5F7; color: #1D1D1F; }
.form-icon-btn:disabled { opacity: .5; cursor: not-allowed; }

/* 发送按钮：首页同款黑色圆形（32px） */
.form-send-btn {
  width: 32px; height: 32px;
  display: inline-flex; align-items: center; justify-content: center;
  background: #1D1D1F;
  color: #fff;
  border: 0;
  border-radius: 50%;
  cursor: pointer;
  transition: background 180ms, transform 180ms;
}
.form-send-btn:hover:not(:disabled) { background: #3a3a3c; transform: scale(1.05); }
.form-send-btn:disabled { background: #D2D2D7; cursor: not-allowed; }

/* 3 个能力标签 */
.hero-tags {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin: 24px 0 36px;
}
.hero-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 7px 14px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 999px;
  font-size: 13px;
  color: var(--ink-2);
  font-weight: 500;
}
.tag-dot { width: 8px; height: 8px; border-radius: 50%; }
.tag-dot--violet { background: #8B5CF6; }
.tag-dot--green  { background: #10B981; }
.tag-dot--orange { background: #F59E0B; }

/* ───── 四大 Agent 协作卡 ───── */
.agents { margin: 8px 0 32px; }
.agents-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-3);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  margin-bottom: 14px;
}
.agents-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
}
.agent-card {
  position: relative;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 16px;
  padding: 18px 18px 16px;
  box-shadow: 0 2px 10px rgba(15, 23, 42, .04);
  transition: transform .25s var(--ease), box-shadow .25s ease;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  min-height: 200px;
}
.agent-card:hover { transform: translateY(-3px); box-shadow: 0 10px 30px rgba(0, 113, 227, .10); border-color: rgba(0,113,227,.35); }
.agent-arrow {
  position: absolute;
  right: -16px;
  top: 50%;
  transform: translateY(-50%);
  z-index: 2;
  color: var(--ink-4);
  font-size: 18px;
  background: transparent;
  width: 18px;
  height: 18px;
  display: inline-flex; align-items: center; justify-content: center;
  line-height: 1;
}
.agent-card:last-child .agent-arrow { display: none; }

.agent-icon {
  width: 38px; height: 38px;
  border-radius: 11px;
  display: inline-flex; align-items: center; justify-content: center;
  color: #fff;
  margin-bottom: 14px;
}
.agent-card--planner    .agent-icon { background: linear-gradient(135deg, #8B5CF6, #A78BFA); box-shadow: 0 4px 12px rgba(139, 92, 246, .3); }
.agent-card--researcher .agent-icon { background: linear-gradient(135deg, #10B981, #34D399); box-shadow: 0 4px 12px rgba(16, 185, 129, .3); }
.agent-card--writer     .agent-icon { background: linear-gradient(135deg, #3B82F6, #60A5FA); box-shadow: 0 4px 12px rgba(59, 130, 246, .3); }
.agent-card--critic     .agent-icon { background: linear-gradient(135deg, #F59E0B, #FBBF24); box-shadow: 0 4px 12px rgba(245, 158, 11, .3); }

.agent-name { font-size: 15px; font-weight: 700; color: var(--ink-1); margin-bottom: 2px; }
.agent-role { font-size: 12px; color: var(--ink-3); margin-bottom: 10px; }
.agent-desc {
  font-size: 12.5px;
  line-height: 1.55;
  color: var(--ink-2);
  flex: 1;
  margin: 0 0 12px;
}
.agent-status {
  font-size: 11px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 999px;
  align-self: flex-start;
}
.agent-status--planning  { background: #F3E8FF; color: #6B21A8; }
.agent-status--searching { background: #D1FAE5; color: #047857; }
.agent-status--writing   { background: #DBEAFE; color: #1E40AF; }
.agent-status--reviewing { background: #FEF3C7; color: #B45309; }

/* ───── 历史任务 ───── */
.history { margin-top: 36px; }
.history-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-3);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  margin-bottom: 12px;
}
.history-list { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.history-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition: var(--transition);
}
.history-item:hover { border-color: var(--brand-soft-2); background: var(--brand-soft); }
.hist-status {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  padding: 4px 8px;
  border-radius: 4px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  flex-shrink: 0;
}
.hist-status.completed { background: #D1FAE5; color: #047857; }
.hist-status.failed    { background: var(--danger-soft);  color: var(--danger-ink); }
.hist-status.running   { background: #DBEAFE; color: #1E40AF; }
.hist-query { flex: 1; font-size: 13.5px; color: var(--ink-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hist-time { font-size: 11px; color: var(--ink-4); font-family: 'JetBrains Mono', monospace; flex-shrink: 0; }
.hist-btn {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 10px;
  border-radius: 8px;
  background: transparent;
  border: 1px solid var(--line);
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  flex-shrink: 0;
  transition: var(--transition);
}
.hist-btn:hover { background: var(--bg-subtle); color: var(--ink-1); border-color: var(--brand-soft-2); }

/* ───── 底部 ───── */
.crew-footer {
  margin-top: 36px;
  text-align: center;
  font-size: 12px;
  color: var(--ink-4);
  letter-spacing: 0.02em;
}

/* ───── 历史（保留 legacy 类名兼容 crew-stage 渲染）───── */
.history-head { margin-bottom: 10px; }
.tip-pill {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 4px 10px;
  background: var(--bg-subtle);
  border-radius: 999px;
  font-size: 12px;
  color: var(--ink-2);
}
.dot { width: 6px; height: 6px; border-radius: 50%; }
.dot.blue   { background: #3D5AFE; }
.dot.green  { background: #10B981; }
.dot.violet { background: #0a0a0a; }

/* ───── 工作区 ───── */
.crew-stage {
  max-width: 1280px;
  margin: 0 auto;
}
.stage-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 16px;
}
.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--ink-2);
  font-size: 12.5px;
  cursor: pointer;
  transition: var(--transition);
}
.back-btn:hover { border-color: var(--brand); color: var(--brand); }
.stage-query {
  flex: 1;
  font-size: 16px;
  font-weight: 600;
  color: var(--ink-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.stage-meta { display: flex; align-items: center; gap: 10px; }
.meta-chip {
  display: inline-block;
  padding: 4px 10px;
  background: var(--brand-soft);
  color: var(--brand-ink);
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 600;
}
.meta-chip.status-completed { background: var(--success-soft); color: var(--success-ink); }
.meta-chip.status-failed    { background: var(--danger-soft);  color: var(--danger-ink); }
.meta-elapsed {
  font-family: 'JetBrains Mono', monospace;
  font-size: 12.5px;
  color: var(--ink-3);
  font-weight: 600;
}

/* 进度条 */
.progress-bar {
  height: 3px;
  background: var(--line-soft);
  border-radius: 2px;
  overflow: hidden;
  margin-bottom: 22px;
}
.progress-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--brand-hover), #0a0a0a);
  border-radius: 2px;
  transition: width 400ms var(--ease);
}

/* Agent 卡片 */
.agent-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 22px;
}
.agent-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px;
  position: relative;
  transition: var(--transition);
}
.agent-card.state-working {
  border-color: var(--brand-soft-2);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-sm);
}
.agent-card.state-done { border-color: rgba(16, 185, 129, 0.4); }

.agent-card::before {
  content: '';
  position: absolute;
  top: -1px; left: -1px; right: -1px; height: 2px;
  border-radius: var(--radius) var(--radius) 0 0;
  background: var(--line);
}
.agent-card.state-working::before {
  background: linear-gradient(90deg, var(--brand), #0a0a0a);
  background-size: 200% 100%;
  animation: shimmer-brand 1.5s linear infinite;
}
.agent-card.state-done::before { background: var(--success); }

.agent-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.agent-ic {
  width: 36px; height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex-shrink: 0;
}
.agent-text { flex: 1; min-width: 0; }
.agent-label { font-family: 'Manrope', sans-serif; font-size: 14px; font-weight: 700; color: var(--ink-1); }
.agent-sub { font-size: 11px; color: var(--ink-3); margin-top: 1px; }
.agent-status {
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.05em;
  padding: 3px 8px;
  border-radius: 4px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  flex-shrink: 0;
}
.state-working .agent-status { background: var(--brand-soft); color: var(--brand-ink); }
.state-done    .agent-status { background: var(--success-soft); color: var(--success-ink); }
.state-failed  .agent-status { background: var(--danger-soft); color: var(--danger-ink); }

.agent-body { min-height: 80px; }
.lane-content { display: flex; flex-direction: column; gap: 6px; }
.lane-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  color: var(--ink-2);
}
.lane-idx {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  font-weight: 700;
  color: var(--ink-4);
  width: 22px;
  flex-shrink: 0;
}
.lane-idx.done { color: var(--success); }
.lane-text { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lane-cite { font-size: 11px; color: var(--ink-4); font-family: 'JetBrains Mono', monospace; }
.lane-empty { color: var(--ink-4); font-size: 12.5px; padding: 14px 0; text-align: center; }

.lane-stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 8px;
}
.stat-big {
  font-family: 'Manrope', sans-serif;
  font-size: 28px;
  font-weight: 800;
  color: var(--ink-1);
  letter-spacing: -0.02em;
  line-height: 1;
}
.stat-big.good { color: var(--success-ink); }
.stat-big.warn { color: var(--warning-ink); }
.stat-unit { font-size: 12px; color: var(--ink-3); }

.critic-dims {
  display: flex;
  gap: 10px;
  margin-top: 6px;
  font-size: 11px;
}
.critic-dims .dim {
  flex: 1;
  background: var(--bg-subtle);
  padding: 6px 8px;
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.critic-dims .dim span { color: var(--ink-3); }
.critic-dims .dim strong { font-family: 'JetBrains Mono', monospace; color: var(--ink-1); font-size: 13px; }

/* ───── 主体 ───── */
.stage-body {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 18px;
  min-height: 480px;
}

.timeline {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px;
  height: fit-content;
  max-height: 600px;
  overflow-y: auto;
}
.timeline-head {
  font-family: 'Manrope', sans-serif;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--ink-3);
  text-transform: uppercase;
  margin-bottom: 14px;
}
.timeline-list { list-style: none; padding: 0; display: flex; flex-direction: column; gap: 12px; position: relative; }
.timeline-list::before {
  content: '';
  position: absolute;
  left: 5px; top: 6px; bottom: 6px;
  width: 1px;
  background: var(--line);
}
.tl-item { display: flex; gap: 12px; position: relative; }
.tl-dot {
  width: 11px; height: 11px;
  border-radius: 50%;
  background: var(--bg-surface);
  border: 2px solid var(--line-strong);
  flex-shrink: 0;
  margin-top: 3px;
  z-index: 1;
}
.tl-item.error .tl-dot { border-color: var(--danger); background: var(--danger-soft); }
.tl-body { flex: 1; min-width: 0; }
.tl-row { display: flex; align-items: center; gap: 8px; }
.tl-role {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  letter-spacing: 0.04em;
}
.tl-role.planner    { background: var(--brand-soft); color: var(--brand-ink); }
.tl-role.researcher { background: var(--info-soft);  color: var(--info-ink); }
.tl-role.writer     { background: #F3F1FF; color: #6B21A8; }
.tl-role.critic     { background: var(--success-soft); color: var(--success-ink); }
.tl-title { font-size: 12.5px; color: var(--ink-1); font-weight: 500; }
.tl-detail { font-size: 11.5px; color: var(--ink-3); margin-top: 3px; line-height: 1.5; }

.report {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.report-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  border-bottom: 1px solid var(--line-soft);
}
.report-title {
  font-family: 'Manrope', sans-serif;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.01em;
}
.report-actions { display: flex; gap: 8px; }
.rb-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 10px;
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 12px;
  color: var(--ink-2);
  cursor: pointer;
  transition: var(--transition);
}
.rb-btn:hover { background: var(--brand-soft); color: var(--brand-ink); border-color: var(--brand-soft-2); }

.report-body {
  padding: 24px 28px;
  overflow-y: auto;
  flex: 1;
}
.report-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px;
  color: var(--ink-4);
}
.empty-ic {
  width: 60px; height: 60px;
  border-radius: 16px;
  background: var(--bg-subtle);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 12px;
  color: var(--ink-4);
}
.empty-msg { font-size: 13px; }

/* 响应式 */
@media (max-width: 1100px) {
  .agent-row { grid-template-columns: repeat(2, 1fr); }
  .stage-body { grid-template-columns: 1fr; }
}
}
</style>

<style scoped>
.crew-page {
  --crew-ink: #222329;
  --crew-ink-soft: #666872;
  --crew-canvas: #f4f1ea;
  --crew-paper: #fffdfa;
  --crew-charcoal: #24252b;
  --crew-charcoal-soft: #303138;
  --crew-line: rgba(34, 35, 41, 0.12);
  --crew-accent: #e8bd3f;
  --crew-accent-strong: #bd8d08;
  --crew-danger: #bd3d3d;
  height: 100%;
  min-height: 0;
  overflow: auto;
  padding: clamp(24px, 3vw, 48px);
  background:
    radial-gradient(circle at 96% 2%, rgba(232, 189, 63, 0.14), transparent 23rem),
    transparent;
  color: var(--crew-ink);
  scrollbar-gutter: stable;
}

button,
textarea { font: inherit; }

button:focus-visible,
textarea:focus-visible {
  outline: 3px solid rgba(189, 141, 8, 0.28);
  outline-offset: 3px;
}

.crew-home,
.mission-workspace {
  width: min(100%, 1320px);
  margin: 0 auto;
}

.command-hero {
  display: grid;
  grid-template-columns: minmax(0, 1.18fr) minmax(330px, 0.82fr);
  gap: clamp(26px, 4vw, 56px);
  align-items: stretch;
  min-height: min(660px, calc(100dvh - 170px));
}

.hero-copy {
  display: flex;
  flex-direction: column;
  justify-content: center;
  min-width: 0;
  padding: 18px 0 24px;
}

.hero-kicker {
  width: fit-content;
  margin-bottom: 22px;
  color: #7b5c06;
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.16em;
}

.hero-copy h1 {
  max-width: 760px;
  margin: 0;
  color: var(--crew-ink);
  font-size: clamp(38px, 4.2vw, 62px);
  font-weight: 780;
  line-height: 1.08;
  letter-spacing: -0.045em;
}

.hero-copy > p {
  max-width: 38rem;
  margin: 22px 0 30px;
  color: var(--crew-ink-soft);
  font-size: 16px;
  line-height: 1.75;
}

.research-composer {
  overflow: hidden;
  background: var(--crew-paper);
  border: 1px solid var(--crew-line);
  border-radius: 18px;
  box-shadow: 0 22px 70px rgba(61, 52, 30, 0.1);
  transition: border-color 180ms ease, box-shadow 180ms ease, transform 180ms ease;
}

.research-composer:focus-within {
  border-color: rgba(189, 141, 8, 0.55);
  box-shadow: 0 24px 70px rgba(61, 52, 30, 0.12), 0 0 0 4px rgba(232, 189, 63, 0.12);
  transform: translateY(-2px);
}

.research-composer label {
  display: block;
  padding: 20px 22px 0;
  color: var(--crew-ink);
  font-size: 13px;
  font-weight: 750;
}

.research-composer textarea {
  display: block;
  width: 100%;
  min-height: 116px;
  padding: 12px 22px 18px;
  resize: vertical;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--crew-ink);
  font-size: 16px;
  line-height: 1.65;
}

.research-composer textarea::placeholder { color: #8b8c94; }

.composer-footer {
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 14px;
  align-items: center;
  padding: 12px 14px 12px 18px;
  border-top: 1px solid rgba(34, 35, 41, 0.08);
}

.attachment-btn,
.start-btn,
.history-link,
.back-btn,
.report-actions button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 0;
  cursor: pointer;
  transition: transform 180ms ease, background 180ms ease, color 180ms ease, border-color 180ms ease;
}

.attachment-btn {
  padding: 9px 10px;
  background: transparent;
  color: #555760;
  border-radius: 10px;
  font-size: 13px;
}

.attachment-btn:hover:not(:disabled) { background: #f0ede7; color: var(--crew-ink); }

.composer-hint {
  overflow: hidden;
  color: #8b8c94;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.start-btn {
  min-width: 118px;
  min-height: 42px;
  padding: 0 17px;
  background: var(--crew-charcoal);
  color: #fffdf8;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 750;
}

.start-btn:hover:not(:disabled) { background: #35363d; transform: translateY(-1px); }
.start-btn:active:not(:disabled) { transform: scale(0.98); }
.start-btn:disabled { cursor: not-allowed; opacity: 0.38; }

.prompt-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 15px;
}

.prompt-presets button {
  padding: 8px 12px;
  border: 1px solid rgba(34, 35, 41, 0.1);
  border-radius: 999px;
  background: rgba(255, 253, 250, 0.58);
  color: #676973;
  font-size: 12px;
  cursor: pointer;
  transition: background 180ms ease, color 180ms ease, border-color 180ms ease, transform 180ms ease;
}

.prompt-presets button:hover {
  border-color: rgba(189, 141, 8, 0.36);
  background: rgba(232, 189, 63, 0.12);
  color: #6f5205;
  transform: translateY(-1px);
}

.orchestration-panel {
  display: flex;
  flex-direction: column;
  align-self: center;
  min-height: 520px;
  padding: clamp(24px, 3vw, 36px);
  overflow: hidden;
  border-radius: 22px;
  background:
    radial-gradient(circle at 110% -10%, rgba(232, 189, 63, 0.28), transparent 18rem),
    var(--crew-charcoal);
  color: #f8f5ee;
  box-shadow: 0 28px 80px rgba(37, 35, 29, 0.22);
}

.orchestration-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding-bottom: 24px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.orchestration-head > div { display: grid; gap: 6px; }
.orchestration-head span { color: #b8b6b0; font-size: 12px; }
.orchestration-head strong { font-size: 18px; font-weight: 720; }
.orchestration-head .ready-badge {
  padding: 6px 9px;
  border: 1px solid rgba(232, 189, 63, 0.38);
  border-radius: 999px;
  color: #f1cf6d;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.1em;
}

.agent-chain {
  display: grid;
  margin: 18px 0 0;
  padding: 0;
  list-style: none;
}

.agent-chain li {
  display: grid;
  grid-template-columns: 28px 42px minmax(0, 1fr) 18px;
  gap: 13px;
  align-items: center;
  min-height: 78px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.09);
}

.chain-index { color: #807f7b; font-size: 10px; font-weight: 700; letter-spacing: 0.08em; }
.chain-icon {
  display: grid;
  width: 40px;
  height: 40px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.12);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.06);
  color: var(--crew-accent);
}
.chain-icon :deep(svg) { width: 18px; height: 18px; }
.agent-chain li > div { display: grid; gap: 4px; min-width: 0; }
.agent-chain strong { font-size: 14px; font-weight: 720; }
.agent-chain li > div span { overflow: hidden; color: #aaa9a5; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.chain-arrow { color: #73736f; transition: transform 180ms ease, color 180ms ease; }
.agent-chain li:hover .chain-arrow { color: var(--crew-accent); transform: translateX(3px); }

.orchestration-note {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-top: auto;
  padding-top: 20px;
  color: #b9b8b3;
  font-size: 12px;
  line-height: 1.55;
}
.orchestration-note .el-icon { flex: 0 0 auto; color: var(--crew-accent); }

.research-history { padding: 72px 0 20px; scroll-margin-top: 20px; }
.section-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  margin-bottom: 20px;
}
.section-head h2 { margin: 0 0 7px; font-size: 28px; letter-spacing: -0.025em; }
.section-head p { margin: 0; color: var(--crew-ink-soft); font-size: 14px; }
.history-link {
  padding: 10px 13px;
  border: 1px solid var(--crew-line);
  border-radius: 11px;
  background: rgba(255, 253, 250, 0.75);
  color: #555760;
  font-size: 13px;
}
.history-link:hover { border-color: rgba(189, 141, 8, 0.38); color: #715506; transform: translateY(-1px); }

.history-list {
  margin: 0;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--crew-line);
  border-radius: 18px;
  background: rgba(255, 253, 250, 0.78);
  list-style: none;
  box-shadow: 0 18px 50px rgba(62, 55, 38, 0.06);
}
.history-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  min-height: 72px;
  padding: 0 14px 0 0;
  border-bottom: 1px solid rgba(34, 35, 41, 0.08);
}
.history-item:last-child { border-bottom: 0; }
.history-main {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr) 112px;
  gap: 18px;
  align-items: center;
  min-width: 0;
  height: 100%;
  padding: 0 8px 0 22px;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.history-main:hover .history-query { color: #8a6706; }
.history-status {
  width: fit-content;
  padding: 5px 8px;
  border-radius: 999px;
  background: #efede8;
  color: #66676d;
  font-size: 11px;
  font-weight: 700;
}
.history-status.completed { background: rgba(232, 189, 63, 0.18); color: #6f5205; }
.history-status.failed { background: rgba(189, 61, 61, 0.1); color: #9f2e2e; }
.history-status.planning,
.history-status.researching,
.history-status.writing,
.history-status.reviewing,
.history-status.revising { background: #292a30; color: #f8f5ee; }
.history-query { overflow: hidden; color: #303138; font-size: 14px; font-weight: 630; text-overflow: ellipsis; white-space: nowrap; transition: color 180ms ease; }
.history-time { color: #86878e; font-size: 12px; text-align: right; }
.history-actions { display: flex; gap: 5px; }
.history-actions button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  color: #71727a;
  cursor: pointer;
  transition: background 180ms ease, color 180ms ease, border-color 180ms ease, transform 180ms ease;
}
.history-actions button:hover { border-color: rgba(34, 35, 41, 0.1); background: #f0ede7; color: var(--crew-ink); transform: translateY(-1px); }

.history-skeleton { display: grid; gap: 1px; overflow: hidden; border: 1px solid var(--crew-line); border-radius: 18px; }
.history-skeleton span { display: block; height: 72px; background: linear-gradient(90deg, #f0ede7 25%, #faf8f3 50%, #f0ede7 75%); background-size: 200% 100%; }
.history-state {
  display: grid;
  min-height: 180px;
  place-content: center;
  justify-items: center;
  gap: 8px;
  padding: 28px;
  border: 1px solid var(--crew-line);
  border-radius: 18px;
  background: rgba(255, 253, 250, 0.75);
  color: var(--crew-ink-soft);
  text-align: center;
}
.history-state strong { color: var(--crew-ink); font-size: 17px; }
.history-state button { margin-top: 6px; padding: 9px 13px; border: 0; border-radius: 10px; background: var(--crew-charcoal); color: #fff; cursor: pointer; }

.mission-workspace { padding-bottom: 32px; }
.mission-header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 22px;
  align-items: start;
  padding: 6px 0 24px;
}
.back-btn {
  min-height: 40px;
  padding: 0 13px;
  border: 1px solid var(--crew-line);
  border-radius: 11px;
  background: rgba(255, 253, 250, 0.72);
  color: #4f5058;
  font-size: 13px;
}
.back-btn:hover { background: var(--crew-paper); color: var(--crew-ink); transform: translateY(-1px); }
.mission-title { min-width: 0; }
.mission-label { display: block; margin-bottom: 7px; color: #8c6a0b; font-size: 11px; font-weight: 800; letter-spacing: 0.12em; }
.mission-title h1 { max-width: 920px; margin: 0; color: var(--crew-ink); font-size: clamp(24px, 2.4vw, 36px); font-weight: 760; line-height: 1.3; letter-spacing: -0.025em; }
.mission-meta { display: flex; gap: 10px; align-items: center; padding-top: 1px; color: #777982; font-size: 12px; }
.meta-chip { padding: 7px 10px; border-radius: 999px; background: var(--crew-charcoal); color: #f7f4ed; font-weight: 700; }
.meta-chip.status-completed { background: rgba(232, 189, 63, 0.24); color: #6f5205; }
.meta-chip.status-failed { background: rgba(189, 61, 61, 0.12); color: #a32f2f; }

.mission-progress { height: 3px; overflow: hidden; border-radius: 999px; background: rgba(34, 35, 41, 0.08); }
.mission-progress span { display: block; width: 100%; height: 100%; transform-origin: left center; border-radius: inherit; background: var(--crew-accent-strong); transition: transform 480ms cubic-bezier(.16, 1, .3, 1); }
.mission-alert { display: flex; gap: 12px; margin-top: 18px; padding: 14px 16px; border: 1px solid rgba(189, 61, 61, 0.18); border-radius: 12px; background: rgba(189, 61, 61, 0.07); color: #8d2b2b; font-size: 13px; }

.mission-grid {
  display: grid;
  grid-template-columns: minmax(290px, 340px) minmax(0, 1fr);
  gap: 22px;
  align-items: start;
  margin-top: 22px;
}

.mission-rail {
  overflow: hidden;
  border-radius: 18px;
  background: var(--crew-charcoal);
  color: #f8f5ee;
  box-shadow: 0 22px 64px rgba(35, 34, 30, 0.16);
}
.mission-rail > header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 22px 22px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}
.mission-rail > header > div { display: grid; gap: 5px; }
.mission-rail > header span { color: #aaa9a5; font-size: 11px; }
.mission-rail > header strong { font-size: 22px; }
.mission-rail > header > span { color: #e8bd3f; font-weight: 800; }

.mission-agents { margin: 0; padding: 8px 0; list-style: none; }
.mission-agents li {
  position: relative;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  min-height: 70px;
  padding: 10px 18px;
  transition: background 220ms ease;
}
.mission-agents li + li::before { content: ''; position: absolute; top: 0; left: 68px; right: 18px; height: 1px; background: rgba(255, 255, 255, 0.08); }
.mission-agents li.state-working { background: rgba(232, 189, 63, 0.08); }
.mission-agent-icon { display: grid; width: 36px; height: 36px; place-items: center; border: 1px solid rgba(255, 255, 255, 0.12); border-radius: 11px; color: #8d8c88; transition: color 180ms ease, border-color 180ms ease, background 180ms ease; }
.mission-agent-icon :deep(svg) { width: 17px; height: 17px; }
.state-working .mission-agent-icon { border-color: rgba(232, 189, 63, 0.42); background: rgba(232, 189, 63, 0.12); color: var(--crew-accent); }
.state-done .mission-agent-icon { background: rgba(232, 189, 63, 0.9); border-color: transparent; color: #29261d; }
.state-failed .mission-agent-icon { color: #ef9999; border-color: rgba(239, 153, 153, 0.35); }
.mission-agents li > div { display: grid; gap: 4px; min-width: 0; }
.mission-agents strong { font-size: 13px; }
.mission-agents li > div span { overflow: hidden; color: #989792; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.mission-agent-state { color: #7f7f7c; font-size: 10px; font-weight: 700; }
.state-working .mission-agent-state { color: #f0ce6e; }
.state-done .mission-agent-state { color: #d5d2ca; }
.state-failed .mission-agent-state { color: #ef9999; }

.mission-summary { display: grid; grid-template-columns: repeat(3, 1fr); margin: 0 18px; padding: 16px 0; border-top: 1px solid rgba(255, 255, 255, 0.1); border-bottom: 1px solid rgba(255, 255, 255, 0.1); }
.mission-summary div { display: grid; gap: 5px; text-align: center; }
.mission-summary div + div { border-left: 1px solid rgba(255, 255, 255, 0.09); }
.mission-summary span { color: #8f8e8a; font-size: 10px; }
.mission-summary strong { color: #f6f3ec; font-size: 18px; }

.event-stream { padding: 20px 18px 22px; }
.event-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.event-head h2 { margin: 0; font-size: 13px; }
.event-head span { color: #8f8e8a; font-size: 11px; }
.event-list { display: grid; gap: 0; max-height: 320px; margin: 0; padding: 0 0 0 6px; overflow: auto; list-style: none; }
.event-list li { position: relative; display: grid; grid-template-columns: 12px minmax(0, 1fr); gap: 10px; min-height: 58px; padding-bottom: 14px; }
.event-list li:not(:last-child)::before { content: ''; position: absolute; top: 12px; bottom: -2px; left: 4px; width: 1px; background: rgba(255, 255, 255, 0.1); }
.event-node { position: relative; z-index: 1; width: 9px; height: 9px; margin-top: 3px; border: 2px solid var(--crew-accent); border-radius: 50%; background: var(--crew-charcoal); }
.event-list li > div { min-width: 0; }
.event-role { display: block; margin-bottom: 3px; color: #e8bd3f; font-size: 9px; font-weight: 800; letter-spacing: 0.06em; text-transform: uppercase; }
.event-list strong { display: block; overflow: hidden; color: #e6e3dc; font-size: 11px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.event-list p { margin: 4px 0 0; color: #8f8e8a; font-size: 10px; line-height: 1.5; }
.event-empty { padding: 24px 0; color: #85847f; font-size: 11px; text-align: center; }

.report-panel {
  display: flex;
  min-width: 0;
  min-height: 720px;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--crew-line);
  border-radius: 18px;
  background: var(--crew-paper);
  box-shadow: 0 22px 64px rgba(62, 55, 38, 0.08);
}
.report-head {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  min-height: 82px;
  padding: 0 24px;
  border-bottom: 1px solid rgba(34, 35, 41, 0.09);
}
.report-head > div:first-child { display: grid; gap: 4px; }
.report-head > div:first-child span { color: #8b6b13; font-size: 9px; font-weight: 800; letter-spacing: 0.14em; }
.report-head h2 { margin: 0; font-size: 21px; letter-spacing: -0.02em; }
.report-actions { display: flex; gap: 7px; }
.report-actions button { min-height: 36px; padding: 0 11px; border: 1px solid var(--crew-line); border-radius: 10px; background: transparent; color: #5d5f67; font-size: 12px; }
.report-actions button:hover { background: #f0ede7; color: var(--crew-ink); transform: translateY(-1px); }
.report-body { flex: 1; padding: 28px clamp(24px, 4vw, 54px); overflow: visible; color: #393a41; font-size: 14px; line-height: 1.8; }
.report-body :deep(h1) { margin: 0 0 24px; color: var(--crew-ink); font-size: 30px; line-height: 1.25; }
.report-body :deep(h2) { margin: 34px 0 14px; color: var(--crew-ink); font-size: 21px; }
.report-body :deep(h3) { margin: 26px 0 10px; color: #3e3f46; font-size: 16px; }
.report-body :deep(p) { margin: 0 0 14px; }
.report-body :deep(a) { color: #7a5a04; text-decoration-color: rgba(122, 90, 4, 0.35); text-underline-offset: 3px; }
.report-body :deep(blockquote) { margin: 22px 0; padding: 14px 18px; border-left: 3px solid var(--crew-accent); background: #f7f3e8; color: #56575d; }
.report-body :deep(table) { width: 100%; margin: 22px 0; border-collapse: collapse; font-size: 12px; }
.report-body :deep(th),
.report-body :deep(td) { padding: 11px 12px; border-bottom: 1px solid var(--crew-line); text-align: left; vertical-align: top; }
.report-body :deep(th) { background: #f1eee7; color: var(--crew-ink); font-weight: 750; }

.report-waiting { display: grid; flex: 1; place-content: center; justify-items: center; gap: 26px; padding: 40px; color: var(--crew-ink-soft); text-align: center; }
.waiting-sheet { display: grid; width: 116px; gap: 11px; padding: 24px 20px; border: 1px solid var(--crew-line); border-radius: 15px; background: #faf7f1; box-shadow: 12px 12px 0 rgba(232, 189, 63, 0.14); }
.waiting-sheet span { display: block; height: 5px; border-radius: 999px; background: #dedad1; }
.waiting-sheet span:nth-child(2) { width: 78%; }
.waiting-sheet span:nth-child(3) { width: 88%; }
.waiting-sheet span:nth-child(4) { width: 58%; }
.report-waiting > div:last-child { display: grid; gap: 7px; }
.report-waiting strong { color: var(--crew-ink); font-size: 17px; }
.report-waiting span { font-size: 13px; }
.review-strip { display: grid; grid-template-columns: 1.5fr repeat(3, 1fr); flex: 0 0 auto; padding: 16px 24px; border-top: 1px solid var(--crew-line); background: #f4f0e7; }
.review-strip div { display: flex; gap: 9px; align-items: baseline; justify-content: center; border-left: 1px solid var(--crew-line); }
.review-strip div:first-child { justify-content: flex-start; border-left: 0; }
.review-strip span { color: #777880; font-size: 11px; }
.review-strip strong { color: var(--crew-ink); font-size: 17px; }

@media (prefers-reduced-motion: no-preference) {
  .hero-copy { animation: crew-enter 620ms cubic-bezier(.16, 1, .3, 1) both; }
  .orchestration-panel { animation: crew-enter 720ms 90ms cubic-bezier(.16, 1, .3, 1) both; }
  .history-skeleton span { animation: crew-shimmer 1.5s ease-in-out infinite; }
  .state-working .mission-agent-icon { animation: crew-pulse 1.8s ease-in-out infinite; }
}

@keyframes crew-enter {
  from { opacity: 0; transform: translateY(18px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes crew-shimmer { to { background-position: -200% 0; } }
@keyframes crew-pulse {
  0%, 100% { box-shadow: 0 0 0 0 rgba(232, 189, 63, 0); }
  50% { box-shadow: 0 0 0 6px rgba(232, 189, 63, 0.08); }
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { scroll-behavior: auto !important; animation: none !important; transition-duration: 0.01ms !important; }
}

@media (max-width: 1080px) {
  .command-hero { grid-template-columns: 1fr; min-height: auto; }
  .hero-copy { padding-top: 0; }
  .orchestration-panel { min-height: auto; }
  .agent-chain { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
  .agent-chain li { grid-template-columns: 24px 40px minmax(0, 1fr); }
  .chain-arrow { display: none; }
  .mission-grid { grid-template-columns: 280px minmax(0, 1fr); }
}

@media (max-width: 820px) {
  .crew-page { padding: 20px 16px 36px; }
  .hero-copy h1 { font-size: clamp(36px, 10vw, 48px); }
  .composer-footer { grid-template-columns: 1fr auto; }
  .composer-hint { display: none; }
  .section-head { align-items: flex-start; }
  .history-main { grid-template-columns: 72px minmax(0, 1fr); gap: 12px; padding-left: 14px; }
  .history-time { display: none; }
  .mission-header { grid-template-columns: auto 1fr; }
  .mission-meta { grid-column: 2; }
  .mission-grid { grid-template-columns: 1fr; }
  .mission-rail { position: static; }
  .report-panel { min-height: 600px; }
}

@media (max-width: 560px) {
  .command-hero { gap: 18px; }
  .hero-copy > p { margin: 16px 0 22px; }
  .research-composer label { padding: 16px 16px 0; }
  .research-composer textarea { padding: 10px 16px 16px; }
  .composer-footer { padding: 10px; }
  .attachment-btn span { display: none; }
  .start-btn { min-width: 106px; }
  .agent-chain { grid-template-columns: 1fr; }
  .orchestration-panel { padding: 22px 18px; border-radius: 18px; }
  .section-head { display: grid; }
  .history-item { grid-template-columns: minmax(0, 1fr) auto; padding-right: 8px; }
  .history-main { grid-template-columns: 1fr; gap: 6px; padding: 12px; }
  .history-status { order: 2; }
  .history-query { white-space: normal; }
  .history-actions { flex-direction: column; }
  .mission-header { grid-template-columns: 1fr; }
  .mission-title,
  .mission-meta { grid-column: 1; }
  .mission-title h1 { font-size: 25px; }
  .mission-meta { justify-content: space-between; }
  .report-head { padding: 0 16px; }
  .report-actions button span { display: none; }
  .report-body { padding: 22px 18px; }
  .review-strip { grid-template-columns: repeat(2, 1fr); gap: 10px 0; }
  .review-strip div:first-child { justify-content: center; }
  .review-strip div:nth-child(3) { border-left: 0; }
}
</style>
