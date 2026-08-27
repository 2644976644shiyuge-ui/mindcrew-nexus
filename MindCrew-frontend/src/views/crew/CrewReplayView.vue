<template>
  <div ref="pageRef" class="replay-page">
    <header class="trace-header">
      <button type="button" class="back" @click="goBack">
        <el-icon :size="15"><Back /></el-icon>
        <span>返回调研台</span>
      </button>

      <div v-if="task" class="trace-title">
        <span>推理链回放</span>
        <h1>{{ task.query }}</h1>
      </div>

      <div v-if="task" class="trace-summary">
        <span class="status-tag" :class="task.status.toLowerCase()">{{ statusLabel(task.status) }}</span>
        <div><strong>{{ task.totalSteps || steps.length }}</strong><span>步骤</span></div>
        <div><strong>{{ formatDuration(task.elapsedMs) }}</strong><span>总耗时</span></div>
        <div v-if="task.reviewScore"><strong>{{ (task.reviewScore * 100).toFixed(0) }}</strong><span>评分</span></div>
      </div>
    </header>

    <section v-if="steps.length" class="role-ribbon" aria-label="Agent 轨迹概览">
      <button
        v-for="lane in lanes"
        :key="lane.role"
        type="button"
        :class="{ active: currentRole === lane.role }"
        @click="seekTo(stepsByRole(lane.role)[0]?.stepIndex || 0)"
      >
        <span class="ribbon-icon"><component :is="lane.icon" /></span>
        <span><strong>{{ lane.label }}</strong><small>{{ lane.duty }}</small></span>
        <em>{{ countByRole(lane.role) }}</em>
      </button>
    </section>

    <main class="trace-workspace">
      <aside class="trace-index">
        <header>
          <div>
            <span>执行轨迹</span>
            <strong>{{ playIndex }} / {{ steps.length }}</strong>
          </div>
          <span>{{ formatTime(task?.createTime) }}</span>
        </header>

        <div v-if="loading" class="trace-index-loading" aria-label="正在加载">
          <span v-for="i in 5" :key="i"></span>
        </div>

        <ol v-else-if="steps.length" ref="timelineRef" class="trace-step-list">
          <li v-for="s in steps" :key="s.id" :ref="(el) => stepRefs.set(s.stepIndex, el as any)">
            <button
              type="button"
              :class="{ active: s.stepIndex === playIndex, passed: s.stepIndex < playIndex, future: s.stepIndex > playIndex, failed: s.status === 'FAILED' }"
              @click="seekTo(s.stepIndex)"
            >
              <span class="trace-step-number">{{ String(s.stepIndex).padStart(2, '0') }}</span>
              <span class="trace-step-copy">
                <small>{{ roleLabel(s.agentRole) }}</small>
                <strong>{{ s.stepName }}</strong>
              </span>
              <span class="trace-step-time">{{ formatStepMs(s.elapsedMs) }}</span>
            </button>
          </li>
        </ol>

        <div v-else class="trace-index-empty">暂无可回放步骤</div>
      </aside>

      <article class="trace-inspector">
        <div v-if="loading" class="inspector-loading">
          <span></span><span></span><span></span><span></span>
        </div>

        <div v-else-if="!steps.length" class="inspector-empty">
          <strong>没有找到执行记录</strong>
          <span>该任务暂时无法回放，请返回调研台选择其他任务。</span>
        </div>

        <div v-else-if="!currentStep" class="inspector-intro">
          <span class="intro-mark"><el-icon :size="26"><VideoPlay /></el-icon></span>
          <div>
            <span>TRACE PLAYER</span>
            <h2>逐步查看四位 Agent 如何得出结论</h2>
            <p>播放后，左侧轨迹会按真实执行时长依次展开输入、输出与评审结果。</p>
          </div>
          <button type="button" @click="play">
            <el-icon :size="18"><VideoPlay /></el-icon>
            <span>开始回放</span>
          </button>
        </div>

        <template v-else>
          <header class="inspector-head">
            <div class="inspector-role">
              <span class="role-icon"><component :is="currentStepIcon" /></span>
              <div>
                <small>{{ roleLabel(currentStep.agentRole) }}</small>
                <h2>{{ currentStep.stepName }}</h2>
              </div>
            </div>
            <div class="inspector-meta">
              <span>#{{ currentStep.stepIndex }}</span>
              <span :class="currentStep.status.toLowerCase()">{{ statusText(currentStep.status) }}</span>
              <span>{{ formatStepMs(currentStep.elapsedMs) }}</span>
            </div>
          </header>

          <div class="inspector-body">
            <section v-if="currentStep.subtask" class="trace-block trace-block--brief">
              <h3>本步任务</h3>
              <p>{{ currentStep.subtask }}</p>
            </section>
            <section v-if="currentStep.input" class="trace-block">
              <h3>输入</h3>
              <pre>{{ truncate(currentStep.input, 1200) }}</pre>
            </section>
            <section v-if="currentStep.output" class="trace-block trace-block--output">
              <h3>输出</h3>
              <pre v-html="formatOutput(currentStep.output, currentStep.agentRole)"></pre>
            </section>
            <section v-if="currentStep.errorMsg" class="trace-block trace-block--error">
              <h3>错误</h3>
              <p>{{ currentStep.errorMsg }}</p>
            </section>

            <button v-if="canFork(currentStep)" type="button" class="fork-btn" @click="openForkDialog(currentStep)">
              <el-icon :size="15"><EditPen /></el-icon>
              <span>修改这一步并重跑后续流程</span>
            </button>
          </div>
        </template>

        <footer v-if="steps.length" class="controls">
          <div class="playback-buttons">
            <button type="button" @click="seekTo(0)" :disabled="playIndex === 0" aria-label="回到起点"><el-icon><DArrowLeft /></el-icon></button>
            <button type="button" @click="stepBack" :disabled="playIndex === 0" aria-label="上一步"><el-icon><ArrowLeft /></el-icon></button>
            <button type="button" class="play-btn" @click="togglePlay" :aria-label="isPlaying ? '暂停' : '播放'">
              <el-icon v-if="!isPlaying"><VideoPlay /></el-icon>
              <el-icon v-else><VideoPause /></el-icon>
            </button>
            <button type="button" @click="stepForward" :disabled="playIndex >= steps.length" aria-label="下一步"><el-icon><ArrowRight /></el-icon></button>
            <button type="button" @click="seekTo(steps.length)" :disabled="playIndex >= steps.length" aria-label="跳到末尾"><el-icon><DArrowRight /></el-icon></button>
          </div>

          <div class="scrubber">
            <button type="button" class="scrubber-track" @click="onScrub($event)" aria-label="跳转到指定进度">
              <span class="scrubber-fill" :style="{ transform: `scaleX(${scrubberPct / 100})` }"></span>
              <span class="scrubber-thumb" :style="{ left: scrubberPct + '%' }"></span>
            </button>
            <span>{{ playIndex }} / {{ steps.length }}</span>
          </div>

          <div class="speed-group" aria-label="播放速度">
            <button v-for="sp in speeds" :key="sp" type="button" :class="{ active: speed === sp }" @click="setSpeed(sp)">{{ sp }}×</button>
          </div>
        </footer>
      </article>
    </main>

    <Teleport to="body">
      <div v-if="forkDialog.open" class="fork-modal" @click.self="closeForkDialog">
        <div class="fork-card">
          <header class="fork-head">
            <div class="fork-head-l">
              <span class="fork-kbd">TIME-TRAVEL</span>
              <h2 class="fork-title">编辑并重跑 #{{ forkDialog.step?.stepIndex }}</h2>
              <p class="fork-sub">修改当前输出，后续 Agent 将基于新内容重新执行。</p>
            </div>
            <button class="fork-close" @click="closeForkDialog">
              <el-icon :size="16"><Close /></el-icon>
            </button>
          </header>

          <div class="fork-hint-box" v-if="forkHintText">
            <el-icon :size="14"><InfoFilled /></el-icon>
            <span>{{ forkHintText }}</span>
          </div>

          <label class="fork-lbl" for="fork-output">编辑后的输出</label>
          <textarea
            id="fork-output"
            v-model="forkDialog.editedOutput"
            class="fork-textarea mono"
            rows="14"
            spellcheck="false"
            placeholder="编辑后的输出（JSON 或文本）"
          ></textarea>

          <label class="fork-lbl" for="fork-summary">编辑说明（可选）</label>
          <input
            id="fork-summary"
            v-model="forkDialog.editSummary"
            type="text"
            class="fork-input"
            placeholder="例如：删除离题子任务，强化数据维度"
            maxlength="100"
          />

          <footer class="fork-foot">
            <button class="fork-cancel" @click="closeForkDialog" :disabled="forkDialog.submitting">取消</button>
            <button class="fork-submit" @click="submitFork" :disabled="forkDialog.submitting || !forkDialog.editedOutput.trim()">
              <el-icon v-if="forkDialog.submitting" :size="14" class="spin"><Loading /></el-icon>
              <span>{{ forkDialog.submitting ? '正在创建分支…' : '提交并启动 Fork' }}</span>
            </button>
          </footer>
        </div>
      </div>
    </Teleport>

    <Teleport to="body">
      <div v-if="forkRun.open" class="fork-running" @click.self="dismissForkRun">
        <div class="fr-card">
          <div class="fr-spinner"></div>
          <h3 class="fr-title">Fork 任务执行中</h3>
          <p class="fr-status">{{ forkRun.statusText }}</p>
          <div class="fr-progress">
            <div class="fr-progress-fill" :style="{ width: (forkRun.progress * 100) + '%' }"></div>
          </div>
          <div class="fr-lanes">
            <span v-for="r in lanes" :key="r.role" class="fr-lane" :class="{ active: forkRun.currentRole === r.role }">
              <span class="fr-dot" :style="{ background: r.fg }"></span>
              <span>{{ r.label.split(' · ')[0] }}</span>
            </span>
          </div>
          <button class="fr-cancel" @click="dismissForkRun">在后台继续</button>
        </div>
      </div>
    </Teleport>

  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Back, VideoPlay, VideoPause, ArrowLeft, ArrowRight, DArrowLeft, DArrowRight,
  Aim, Search, EditPen, Stamp, Close, InfoFilled, Loading,
} from '@element-plus/icons-vue'
import { crewApi, type AgentTask, type AgentStep } from '@/api/crew'
import { useUserStore } from '@/stores/user'

const route  = useRoute()
const router = useRouter()
const taskId = Number(route.params.taskId)

const loading   = ref(true)
const pageRef   = ref<HTMLElement | null>(null)
const task      = ref<AgentTask | null>(null)
const steps     = ref<AgentStep[]>([])
const playIndex = ref(0)
const isPlaying = ref(false)
const speed     = ref(1)
const speeds    = [0.5, 1, 2, 4]

const timelineRef = ref<HTMLElement | null>(null)
const stepRefs    = new Map<number, HTMLElement>()
let playTimer: number | null = null

// ─── 4 Agent 泳道配置 ───
const lanes = [
  { role: 'PLANNER',    label: 'Planner',    duty: '任务分解',  icon: Aim,    tone: '#EEF1FF', fg: '#3D5AFE', line: '#DBE2FF' },
  { role: 'RESEARCHER', label: 'Researcher', duty: '并行调研',  icon: Search, tone: '#E0F2FE', fg: '#0369A1', line: '#BAE6FD' },
  { role: 'WRITER',     label: 'Writer',     duty: '报告撰写',  icon: EditPen,tone: '#DCFCE7', fg: '#047857', line: '#BBF7D0' },
  { role: 'CRITIC',     label: 'Critic',     duty: '质量评审',  icon: Stamp,  tone: '#FEF3C7', fg: '#B45309', line: '#FDE68A' },
]

// ─── 计算属性 ───
const currentRole = computed(() => {
  if (playIndex.value === 0 || playIndex.value > steps.value.length) return null
  const s = steps.value[playIndex.value - 1]
  return s?.agentRole
})
const currentStep = computed(() => {
  if (playIndex.value === 0 || playIndex.value > steps.value.length) return null
  return steps.value[playIndex.value - 1] || null
})
const currentStepIcon = computed(() => {
  const lane = lanes.find(item => item.role === currentStep.value?.agentRole)
  return lane?.icon || Aim
})
const scrubberPct = computed(() => {
  if (steps.value.length === 0) return 0
  return Math.min(100, (playIndex.value / steps.value.length) * 100)
})

// ─── 数据 ───
async function load() {
  loading.value = true
  try {
    const res: any = await crewApi.getTask(taskId)
    const data = res?.data ?? res
    task.value  = data?.task ?? null
    const rawSteps: AgentStep[] = data?.steps ?? []
    // 后端 enum.getCode() 返回大写，此处兼容旧数据的驼峰格式
    steps.value = [...rawSteps]
      .map(s => ({ ...s, agentRole: (s.agentRole || '').toUpperCase() }))
      .sort((a, b) => a.stepIndex - b.stepIndex)
    playIndex.value = 0
  } catch (e) {
    ElMessage.error('加载任务失败')
  } finally {
    loading.value = false
  }
}

// ─── 播放控制 ───
function togglePlay() {
  if (playIndex.value >= steps.value.length) playIndex.value = 0
  isPlaying.value ? pause() : play()
}
function play() {
  isPlaying.value = true
  scheduleNext()
}
function pause() {
  isPlaying.value = false
  if (playTimer !== null) { clearTimeout(playTimer); playTimer = null }
}
function scheduleNext() {
  if (!isPlaying.value) return
  if (playIndex.value >= steps.value.length) { pause(); return }

  // 每一步的"展示时长"基于实际耗时但有上下限，速度倍率影响
  const s = steps.value[playIndex.value]
  const baseMs = s && s.elapsedMs ? Math.min(Math.max(s.elapsedMs, 600), 3500) : 1200
  const delay = Math.round(baseMs / speed.value)

  playTimer = window.setTimeout(() => {
    playIndex.value++
    scrollToCurrent()
    scheduleNext()
  }, delay)
}
function stepForward() {
  if (playIndex.value < steps.value.length) { playIndex.value++; scrollToCurrent() }
}
function stepBack() {
  if (playIndex.value > 0) { playIndex.value--; scrollToCurrent() }
}
function seekTo(i: number) {
  pause()
  playIndex.value = Math.max(0, Math.min(i, steps.value.length))
  scrollToCurrent()
}
function setSpeed(sp: number) {
  speed.value = sp
  if (isPlaying.value) { pause(); play() }
}
function onScrub(e: MouseEvent) {
  const el = e.currentTarget as HTMLElement
  const rect = el.getBoundingClientRect()
  const pct = (e.clientX - rect.left) / rect.width
  seekTo(Math.round(pct * steps.value.length))
}

async function scrollToCurrent() {
  await nextTick()
  const idx = Math.min(playIndex.value, steps.value.length)
  const el = stepRefs.get(idx) || stepRefs.get(idx - 1)
  if (el && timelineRef.value) {
    el.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

// ─── UI 辅助 ───
function countByRole(role: string)     { return steps.value.filter(s => s.agentRole === role).length }
function stepsByRole(role: string)     { return steps.value.filter(s => s.agentRole === role) }
function roleLabel(role: string)       { return lanes.find(l => l.role === role)?.label || role }
function roleTone(role: string)        { return lanes.find(l => l.role === role)?.tone || '#F1F4FA' }
function roleFg(role: string)          { return lanes.find(l => l.role === role)?.fg   || '#4B5670' }
function nodeStyle(s: AgentStep) {
  const lane = lanes.find(l => l.role === s.agentRole)
  if (!lane) return {}
  if (s.stepIndex < playIndex.value)  return { background: lane.fg,  borderColor: lane.fg }
  if (s.stepIndex === playIndex.value) return { background: '#fff', borderColor: lane.fg, boxShadow: `0 0 0 4px ${lane.tone}` }
  return { background: '#fff', borderColor: '#D8DEEA' }
}
function statusText(s: string)         { return s === 'DONE' ? '已完成' : s === 'FAILED' ? '失败' : s === 'RUNNING' ? '进行中' : '已跳过' }
function statusLabel(s: string) {
  const m: Record<string, string> = {
    PENDING:'待启动', PLANNING:'规划中', RESEARCHING:'调研中', WRITING:'撰写中',
    REVIEWING:'评审中', REVISING:'重写中', COMPLETED:'已完成', FAILED:'失败'
  }
  return m[s] || s
}
function formatTime(t?: string) {
  if (!t) return ''
  const d = new Date(t.replace(' ', 'T'))
  return d.toLocaleString('zh-CN', { month:'2-digit', day:'2-digit', hour:'2-digit', minute:'2-digit' })
}
function formatDuration(ms?: number) {
  if (!ms) return '-'
  if (ms < 1000) return ms + 'ms'
  if (ms < 60_000) return (ms / 1000).toFixed(1) + 's'
  return (ms / 60_000).toFixed(1) + 'min'
}
function formatStepMs(ms?: number) {
  if (!ms) return ''
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`
}
function truncate(s: string, n: number) { return s.length > n ? s.slice(0, n) + '…' : s }

/** 输出格式化：JSON 美化 + HTML escape */
function formatOutput(raw: string, _role: string) {
  if (!raw) return ''
  let pretty = raw
  const trimmed = raw.trim()
  if ((trimmed.startsWith('{') && trimmed.endsWith('}')) ||
      (trimmed.startsWith('[') && trimmed.endsWith(']'))) {
    try { pretty = JSON.stringify(JSON.parse(trimmed), null, 2) } catch {}
  }
  pretty = pretty.length > 1200 ? pretty.slice(0, 1200) + '\n…（已截断）' : pretty
  return pretty
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
}

function goBack() { router.push('/crew') }

// ─────────────────────────────────────────────
// Time-Travel · Fork
// ─────────────────────────────────────────────
const userStore = useUserStore()

const forkDialog = reactive({
  open: false,
  step: null as AgentStep | null,
  editedOutput: '',
  editSummary: '',
  submitting: false,
})

const forkRun = reactive({
  open: false,
  taskId: 0,
  progress: 0,
  currentRole: '' as string,
  statusText: '准备启动…',
  source: null as EventSource | null,
})

function canFork(s: AgentStep): boolean {
  if (!task.value) return false
  if (s.status !== 'DONE') return false
  // 只允许对已完成 / 失败的任务做 Fork
  const allowedTaskStatus = ['COMPLETED', 'FAILED']
  return allowedTaskStatus.includes(task.value.status)
}

function roleLabelText(role?: string): string {
  if (!role) return ''
  const map: Record<string, string> = {
    PLANNER: 'Planner · 任务规划',
    RESEARCHER: 'Researcher · 调研',
    WRITER: 'Writer · 撰写',
    CRITIC: 'Critic · 评审',
  }
  return map[role.toUpperCase()] || role
}

const forkHintText = computed(() => {
  const role = (forkDialog.step?.agentRole || '').toUpperCase()
  switch (role) {
    case 'PLANNER':
      return '编辑 Plan JSON 后，N 个 Researcher 将基于新子任务并行重新调研，再由 Writer 与 Critic 重跑'
    case 'RESEARCHER':
      return '编辑 Finding 后，其他并行 Researcher 保留，Writer 用合并后的发现重写，Critic 再评审'
    case 'WRITER':
      return '编辑报告后，Critic 将基于新报告重新评分（不重新检索）'
    case 'CRITIC':
      return '编辑评分 JSON 后直接收尾。若 passed=false 且未超重写上限，将触发 Writer 重写一轮'
    default:
      return ''
  }
})

function openForkDialog(s: AgentStep) {
  forkDialog.step = s
  forkDialog.editedOutput = prettifyForEditor(s.output || '')
  forkDialog.editSummary = ''
  forkDialog.open = true
}

function closeForkDialog() {
  if (forkDialog.submitting) return
  forkDialog.open = false
  forkDialog.step = null
}

function prettifyForEditor(raw: string): string {
  if (!raw) return ''
  const t = raw.trim()
  if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
    try { return JSON.stringify(JSON.parse(t), null, 2) } catch { /* fallthrough */ }
  }
  return raw
}

async function submitFork() {
  if (!forkDialog.step) return
  const stepIdx = forkDialog.step.stepIndex
  forkDialog.submitting = true
  try {
    const res: any = await crewApi.forkTask(taskId, {
      fromStepIndex: stepIdx,
      editedOutput: forkDialog.editedOutput,
      editSummary: forkDialog.editSummary || undefined,
    })
    const data = res?.data ?? res
    const newId = data.taskId
    if (!newId) throw new Error('Fork 创建失败：未返回 taskId')

    forkDialog.open = false
    forkDialog.submitting = false

    // 启动进度蒙层并订阅 SSE
    forkRun.taskId = newId
    forkRun.progress = 0
    forkRun.currentRole = ''
    forkRun.statusText = '正在启动 Fork…'
    forkRun.open = true

    const token = userStore.token || ''
    const source = crewApi.streamFork(newId, token)
    forkRun.source = source

    source.addEventListener('task.start', () => {
      forkRun.statusText = '正在恢复历史步骤…'
    })
    source.addEventListener('fork.replay-step', (e: any) => {
      try {
        const d = JSON.parse(e.data)
        forkRun.currentRole = (d.role || '').toUpperCase()
        forkRun.statusText = `恢复 #${d.stepIndex} · ${roleLabelText(d.role)}`
      } catch { /* ignore */ }
    })
    source.addEventListener('agent.start', (e: any) => {
      try {
        const d = JSON.parse(e.data)
        forkRun.currentRole = (d.role || '').toUpperCase()
        forkRun.statusText = `${roleLabelText(d.role)} 启动`
        if (typeof d.progress === 'number') forkRun.progress = d.progress
      } catch { /* ignore */ }
    })
    source.addEventListener('researcher.finding', (e: any) => {
      try {
        const d = JSON.parse(e.data)
        const f = d.data?.finding
        if (f) forkRun.statusText = `Researcher 完成：${f.title || ''}`
      } catch { /* ignore */ }
    })
    source.addEventListener('writer.token', () => {
      forkRun.statusText = 'Writer 正在撰写…'
    })
    source.addEventListener('critic.review', () => {
      forkRun.statusText = 'Critic 已完成评审'
    })
    source.addEventListener('task.done', () => {
      forkRun.progress = 1
      forkRun.statusText = '完成！跳转到新 Fork 任务回放…'
      source.close()
      forkRun.source = null
      setTimeout(() => {
        forkRun.open = false
        router.replace(`/crew/replay/${newId}`)
      }, 700)
    })
    source.addEventListener('task.failed', (e: any) => {
      let msg = 'Fork 任务执行失败'
      try { msg += '：' + (JSON.parse(e.data).data?.error || '') } catch { /* ignore */ }
      ElMessage.error(msg)
      source.close()
      forkRun.source = null
      forkRun.open = false
    })
    source.onerror = () => {
      ElMessage.warning('SSE 连接异常，请到任务列表查看 Fork 状态')
      source.close()
      forkRun.source = null
      forkRun.open = false
    }
  } catch (e: any) {
    forkDialog.submitting = false
    ElMessage.error('Fork 创建失败：' + (e?.message || ''))
  }
}

function dismissForkRun() {
  // 关闭蒙层但保留 SSE 在后台跑（任务仍在后端执行）
  forkRun.open = false
}

// ─── 生命周期 ───
onMounted(() => {
  pageRef.value?.scrollTo({ top: 0 })
  load()
})
onBeforeUnmount(() => {
  pause()
  if (forkRun.source) { try { forkRun.source.close() } catch { /* ignore */ } }
})
</script>

<style scoped media="not all">
@media not all {
/* ─────────────────────────────────────────────
   页面骨架
   ───────────────────────────────────────────── */
.replay-page {
  height: 100%;
  display: grid;
  grid-template-rows: auto auto 1fr auto;
  background: var(--bg-page);
  overflow: hidden;
}

/* ─────────────────────────────────────────────
   Hero
   ───────────────────────────────────────────── */
.hero {
  position: relative;
  padding: 26px 32px 20px;
  background: var(--bg-surface);
  border-bottom: 1px solid var(--line);
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 20px;
  padding: 7px 16px 7px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 999px;
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.01em;
  cursor: pointer;
  transition: all 220ms var(--ease);
  box-shadow: var(--shadow-xs);
}
.back:hover {
  color: var(--brand);
  border-color: var(--brand-soft-2);
  background: var(--brand-soft);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-sm);
  transform: translateX(-2px);
}
.back:active {
  transform: translateX(-1px) scale(0.98);
}
.back :deep(.el-icon) {
  color: currentColor;
  transition: transform 220ms var(--ease);
}
.back:hover :deep(.el-icon) {
  transform: translateX(-3px);
}

.hero-tags { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.kbd-tag {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.12em;
  padding: 3px 8px;
  border-radius: 4px;
  background: var(--ink-1);
  color: #fff;
}
.status-tag {
  font-size: 11px;
  font-weight: 600;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  background: var(--brand-soft);
  color: var(--brand-ink);
}
.status-tag.completed { background: var(--success-soft); color: var(--success-ink); }
.status-tag.failed    { background: var(--danger-soft);  color: var(--danger-ink); }
.time-tag {
  font-size: 11.5px;
  color: var(--ink-3);
  font-family: 'JetBrains Mono', monospace;
}

.hero-query {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 24px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.022em;
  line-height: 1.35;
  margin-bottom: 14px;
}

.hero-stats { display: flex; align-items: baseline; gap: 22px; }
.stat { display: flex; align-items: baseline; gap: 6px; }
.stat-val {
  font-family: 'Manrope', 'JetBrains Mono', monospace;
  font-size: 22px;
  font-weight: 800;
  color: var(--ink-1);
  letter-spacing: -0.02em;
}
.stat-val.score { color: var(--brand); }
.stat-lab {
  font-size: 12px;
  color: var(--ink-3);
  font-weight: 500;
}
.stat-sep {
  width: 1px;
  height: 22px;
  background: var(--line);
  align-self: center;
}

/* ─────────────────────────────────────────────
   4 Agent 泳道
   ───────────────────────────────────────────── */
.lanes {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  padding: 18px 32px;
  background: linear-gradient(180deg, var(--bg-surface), var(--bg-page));
  border-bottom: 1px solid var(--line);
}
.lane {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 14px 16px 12px;
  box-shadow: var(--shadow-card);
  transition: var(--transition);
}
.lane.active {
  border-color: var(--brand);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-md);
  transform: translateY(-1px);
}
.lane-head { display: flex; align-items: center; gap: 10px; }
.lane-badge {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid;
  font-size: 16px;
  flex-shrink: 0;
}
.lane-meta { flex: 1; min-width: 0; }
.lane-name {
  font-family: 'Manrope', sans-serif;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.01em;
}
.lane-duty {
  font-size: 11.5px;
  color: var(--ink-3);
  margin-top: 1px;
}
.lane-stat-mini {
  text-align: right;
  flex-shrink: 0;
}
.lane-count {
  font-family: 'Manrope', sans-serif;
  font-size: 18px;
  font-weight: 800;
  color: var(--ink-1);
  display: block;
  line-height: 1;
}
.lane-count-lbl {
  font-size: 10.5px;
  color: var(--ink-3);
  margin-top: 2px;
}

.lane-dots {
  display: flex;
  gap: 5px;
  margin-top: 12px;
  flex-wrap: wrap;
  min-height: 16px;
}
.dot {
  width: 12px;
  height: 12px;
  border-radius: 50%;
  border: 1.5px solid var(--line-strong);
  background: var(--bg-surface);
  cursor: pointer;
  transition: all 180ms var(--ease);
}
.dot:hover { transform: scale(1.18); }
.dot.done    { background: currentColor; border-color: currentColor; }
.dot.now     { background: #fff; border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-glow); animation: pulse-soft 1.4s ease-in-out infinite; }
.dot.fail    { background: var(--danger); border-color: var(--danger); }
.dot.pending { opacity: 0.45; }
.lane[data-role] .dot.done { color: currentColor; }
.lane:nth-child(1) .dot { color: #3D5AFE; }
.lane:nth-child(2) .dot { color: #0369A1; }
.lane:nth-child(3) .dot { color: #047857; }
.lane:nth-child(4) .dot { color: #B45309; }

/* ─────────────────────────────────────────────
   时间线
   ───────────────────────────────────────────── */
.content { overflow: hidden; padding: 0 32px; }
.timeline {
  height: 100%;
  overflow-y: auto;
  padding: 28px 0 40px;
  position: relative;
}
.timeline::before {
  content: '';
  position: absolute;
  left: 22px;
  top: 28px;
  bottom: 40px;
  width: 2px;
  background: linear-gradient(180deg, var(--line), var(--line) 60%, transparent);
  z-index: 0;
}

.step {
  position: relative;
  display: grid;
  grid-template-columns: 46px 1fr;
  gap: 14px;
  margin-bottom: 14px;
  cursor: pointer;
  opacity: 1;
  transition: opacity 240ms var(--ease), transform 240ms var(--ease);
}
.step.future { opacity: 0.42; }
.step.future:hover { opacity: 0.7; }
.step.now {
  opacity: 1;
}

.step-rail {
  position: relative;
  display: flex;
  justify-content: center;
  padding-top: 14px;
}
.rail-node {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid var(--line-strong);
  background: var(--bg-surface);
  z-index: 1;
  transition: all 240ms var(--ease);
}

.step-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 12px 16px 14px;
  box-shadow: var(--shadow-xs);
  transition: all 240ms var(--ease);
}
.step.now .step-card {
  border-color: var(--brand);
  box-shadow: 0 0 0 4px var(--brand-glow), var(--shadow-md);
}
.step.past .step-card { background: var(--bg-surface); }
.step.failed .step-card { border-color: var(--danger); }

.step-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.step-num {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: var(--ink-4);
  font-weight: 700;
}
.step-role {
  font-family: 'Manrope', sans-serif;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  padding: 2px 8px;
  border-radius: 4px;
}
.step-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink-1);
  flex: 1;
}
.step-status {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 7px;
  border-radius: var(--radius-pill);
  background: var(--bg-subtle);
  color: var(--ink-3);
}
.step-status.done    { background: var(--success-soft); color: var(--success-ink); }
.step-status.failed  { background: var(--danger-soft);  color: var(--danger-ink); }
.step-status.running { background: var(--brand-soft);   color: var(--brand-ink); }
.step-time {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: var(--ink-3);
}

.step-body { margin-top: 12px; display: flex; flex-direction: column; gap: 8px; }
.step-block {
  background: var(--bg-subtle);
  border-radius: var(--radius-sm);
  padding: 8px 12px;
}
.step-block.error {
  background: var(--danger-soft);
  color: var(--danger-ink);
}
.block-lbl {
  font-size: 10.5px;
  color: var(--ink-3);
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  margin-bottom: 4px;
}
.block-val {
  font-size: 13px;
  color: var(--ink-1);
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.block-val.mono   { font-family: 'JetBrains Mono', monospace; font-size: 12px; }
.block-val.output {
  max-height: 240px;
  overflow-y: auto;
  background: #0F1A33;
  color: #DCE3F2;
  border-radius: var(--radius-xs);
  padding: 10px 12px;
  margin: 0;
}

/* 结束标记 */
.finish {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 30px 0 60px;
  margin-left: 46px;
}
.finish-ring {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--brand-hover), var(--brand));
  box-shadow: 0 0 0 6px var(--brand-soft);
}
.finish-text {
  font-family: 'Manrope', sans-serif;
  font-weight: 700;
  color: var(--brand-ink);
  letter-spacing: -0.01em;
  font-size: 15px;
}

/* ─────────────────────────────────────────────
   Empty
   ───────────────────────────────────────────── */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 80px 20px;
  color: var(--ink-3);
  gap: 16px;
}
.empty-ring {
  width: 64px;
  height: 64px;
  border: 3px solid var(--line);
  border-radius: 50%;
  border-top-color: var(--brand);
}

/* ─────────────────────────────────────────────
   底部控制
   ───────────────────────────────────────────── */
.controls {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 32px;
  background: var(--bg-surface);
  border-top: 1px solid var(--line);
  box-shadow: 0 -4px 12px rgba(11, 20, 38, 0.04);
}
.ctrl-btn {
  width: 36px;
  height: 36px;
  border-radius: var(--radius-sm);
  background: var(--bg-subtle);
  color: var(--ink-2);
  display: flex;
  align-items: center;
  justify-content: center;
  transition: var(--transition);
}
.ctrl-btn:hover:not(:disabled) {
  background: var(--brand-soft);
  color: var(--brand);
}
.ctrl-btn:disabled { opacity: 0.4; cursor: not-allowed; }

.play-btn {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: linear-gradient(180deg, var(--brand-hover), var(--brand));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--shadow-brand);
  transition: var(--transition);
}
.play-btn:hover { filter: brightness(1.06); }
.play-btn:active { filter: brightness(0.95); }

.scrubber {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 0 8px;
}
.scrubber-track {
  flex: 1;
  height: 6px;
  background: var(--line);
  border-radius: 3px;
  position: relative;
  cursor: pointer;
}
.scrubber-fill {
  position: absolute;
  inset: 0 auto 0 0;
  background: linear-gradient(90deg, var(--brand-hover), var(--brand));
  border-radius: 3px;
}
.scrubber-thumb {
  position: absolute;
  top: 50%;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid var(--brand);
  transform: translate(-50%, -50%);
  box-shadow: var(--shadow-sm);
  transition: left 180ms var(--ease);
  pointer-events: none;
}
.scrubber-label {
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  color: var(--ink-3);
  font-weight: 600;
  white-space: nowrap;
  min-width: 58px;
  text-align: right;
}

.speed-group {
  display: flex;
  gap: 4px;
  background: var(--bg-subtle);
  border-radius: var(--radius-sm);
  padding: 3px;
}
.speed-btn {
  padding: 5px 10px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  font-weight: 600;
  color: var(--ink-3);
  border-radius: calc(var(--radius-sm) - 2px);
  transition: var(--transition);
}
.speed-btn.active { background: var(--bg-surface); color: var(--brand); box-shadow: var(--shadow-xs); }
.speed-btn:hover:not(.active) { color: var(--ink-1); }

/* Scrollbar inside output blocks */
.output::-webkit-scrollbar { width: 6px; }
.output::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.18); border-radius: 6px; }

/* ─────────────────────────────────────────────
   Time-Travel · Fork 按钮（每步底部）
   ───────────────────────────────────────────── */
.step-actions {
  margin-top: 6px;
  display: flex;
  justify-content: flex-end;
}
.fork-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 11px 5px 9px;
  border-radius: 8px;
  background: transparent;
  border: 1px dashed var(--line-strong);
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: all 180ms var(--ease);
}
.fork-btn .fork-hint {
  font-size: 10.5px;
  color: var(--ink-4);
  font-weight: 500;
  padding-left: 4px;
  border-left: 1px solid var(--line);
  margin-left: 2px;
}
.fork-btn:hover {
  border-style: solid;
  border-color: var(--brand);
  background: var(--brand-soft);
  color: var(--brand-ink);
}
.fork-btn:hover .fork-hint { color: var(--brand); border-left-color: var(--brand-soft-2); }
}
</style>

<style scoped>
.replay-page {
  --trace-ink: #23242a;
  --trace-soft: #6c6d75;
  --trace-paper: #fffdfa;
  --trace-charcoal: #24252b;
  --trace-line: rgba(35, 36, 42, 0.12);
  --trace-accent: #e8bd3f;
  width: 100%;
  height: 100%;
  min-height: 0;
  padding: clamp(22px, 3vw, 42px);
  overflow: auto;
  background:
    radial-gradient(circle at 4% 100%, rgba(232, 189, 63, 0.11), transparent 25rem),
    transparent;
  color: var(--trace-ink);
  scrollbar-gutter: stable;
}

button,
textarea,
input { font: inherit; }

button:focus-visible,
textarea:focus-visible,
input:focus-visible {
  outline: 3px solid rgba(189, 141, 8, 0.28);
  outline-offset: 3px;
}

.trace-header,
.role-ribbon,
.trace-workspace {
  width: min(100%, 1420px);
  margin-inline: auto;
}

.trace-header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 22px;
  align-items: start;
  padding-bottom: 24px;
}

.back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 13px;
  border: 1px solid var(--trace-line);
  border-radius: 11px;
  background: rgba(255, 253, 250, 0.72);
  color: #54565e;
  font-size: 13px;
  cursor: pointer;
  transition: background 180ms ease, color 180ms ease, transform 180ms ease;
}
.back:hover { background: var(--trace-paper); color: var(--trace-ink); transform: translateY(-1px); }
.back:active { transform: scale(0.98); }

.trace-title { min-width: 0; }
.trace-title > span { display: block; margin-bottom: 7px; color: #84650d; font-size: 11px; font-weight: 800; letter-spacing: 0.12em; }
.trace-title h1 { max-width: 850px; margin: 0; color: var(--trace-ink); font-size: clamp(25px, 2.6vw, 38px); font-weight: 760; line-height: 1.28; letter-spacing: -0.03em; }

.trace-summary { display: flex; align-items: center; gap: 14px; }
.trace-summary > div { display: grid; gap: 3px; min-width: 58px; text-align: right; }
.trace-summary strong { color: var(--trace-ink); font-size: 16px; }
.trace-summary div span { color: #85868d; font-size: 10px; }
.status-tag { padding: 7px 10px; border-radius: 999px; background: var(--trace-charcoal); color: #f8f5ee; font-size: 11px; font-weight: 750; }
.status-tag.completed { background: rgba(232, 189, 63, 0.22); color: #705405; }
.status-tag.failed { background: rgba(184, 55, 55, 0.1); color: #a33232; }

.role-ribbon {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  overflow: hidden;
  border: 1px solid var(--trace-line);
  border-radius: 16px;
  background: rgba(255, 253, 250, 0.72);
  box-shadow: 0 16px 44px rgba(61, 54, 37, 0.06);
}
.role-ribbon button {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  gap: 11px;
  align-items: center;
  min-height: 68px;
  padding: 10px 16px;
  border: 0;
  border-right: 1px solid rgba(35, 36, 42, 0.09);
  background: transparent;
  color: var(--trace-ink);
  text-align: left;
  cursor: pointer;
  transition: background 180ms ease, color 180ms ease;
}
.role-ribbon button:last-child { border-right: 0; }
.role-ribbon button:hover { background: rgba(232, 189, 63, 0.07); }
.role-ribbon button.active { background: var(--trace-charcoal); color: #f8f5ee; }
.ribbon-icon { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 10px; background: #f0ede7; color: #6d6e75; }
.role-ribbon button.active .ribbon-icon { background: rgba(232, 189, 63, 0.14); color: var(--trace-accent); }
.ribbon-icon :deep(svg) { width: 16px; height: 16px; }
.role-ribbon button > span:nth-child(2) { display: grid; gap: 3px; min-width: 0; }
.role-ribbon strong { font-size: 12px; }
.role-ribbon small { overflow: hidden; color: #85868d; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.role-ribbon button.active small { color: #9f9f9b; }
.role-ribbon em { color: #8a8b92; font-size: 12px; font-style: normal; font-weight: 700; }
.role-ribbon button.active em { color: var(--trace-accent); }

.trace-workspace {
  display: grid;
  grid-template-columns: minmax(270px, 320px) minmax(0, 1fr);
  gap: 20px;
  align-items: stretch;
  min-height: 680px;
  margin-top: 20px;
  padding-bottom: 24px;
}

.trace-index {
  display: flex;
  min-width: 0;
  max-height: calc(100dvh - 260px);
  min-height: 620px;
  flex-direction: column;
  overflow: hidden;
  border-radius: 18px;
  background: var(--trace-charcoal);
  color: #f8f5ee;
  box-shadow: 0 22px 64px rgba(35, 34, 30, 0.17);
}
.trace-index > header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  padding: 21px 20px 17px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}
.trace-index > header > div { display: grid; gap: 4px; }
.trace-index > header span { color: #969590; font-size: 10px; }
.trace-index > header strong { font-size: 19px; }
.trace-step-list { flex: 1; margin: 0; padding: 8px 0 16px; overflow: auto; list-style: none; }
.trace-step-list li { padding: 0 9px; }
.trace-step-list button {
  position: relative;
  display: grid;
  grid-template-columns: 28px minmax(0, 1fr) auto;
  gap: 11px;
  align-items: center;
  width: 100%;
  min-height: 64px;
  padding: 8px 10px;
  border: 0;
  border-radius: 11px;
  background: transparent;
  color: #b1b0ab;
  text-align: left;
  cursor: pointer;
  transition: background 180ms ease, color 180ms ease, opacity 180ms ease, transform 180ms ease;
}
.trace-step-list button:hover { background: rgba(255, 255, 255, 0.06); color: #f6f3ec; }
.trace-step-list button.active { background: rgba(232, 189, 63, 0.12); color: #fff9e8; }
.trace-step-list button.active::before { content: ''; position: absolute; top: 12px; bottom: 12px; left: 0; width: 3px; border-radius: 99px; background: var(--trace-accent); }
.trace-step-list button.future { opacity: 0.46; }
.trace-step-list button.passed { opacity: 0.82; }
.trace-step-list button.failed { color: #f0a0a0; }
.trace-step-number { color: #7d7c78; font-size: 10px; font-weight: 800; letter-spacing: 0.06em; }
.active .trace-step-number { color: var(--trace-accent); }
.trace-step-copy { display: grid; gap: 4px; min-width: 0; }
.trace-step-copy small { color: #8e8d89; font-size: 9px; font-weight: 750; text-transform: uppercase; }
.trace-step-copy strong { overflow: hidden; font-size: 11px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; }
.trace-step-time { color: #777671; font-size: 9px; }
.trace-index-loading { display: grid; gap: 12px; padding: 18px; }
.trace-index-loading span { display: block; height: 46px; border-radius: 10px; background: rgba(255, 255, 255, 0.07); }
.trace-index-empty { display: grid; flex: 1; place-items: center; color: #8d8c87; font-size: 12px; }

.trace-inspector {
  display: flex;
  min-width: 0;
  min-height: 680px;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--trace-line);
  border-radius: 18px;
  background: var(--trace-paper);
  box-shadow: 0 22px 64px rgba(61, 54, 37, 0.08);
}
.inspector-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  min-height: 94px;
  padding: 0 26px;
  border-bottom: 1px solid rgba(35, 36, 42, 0.09);
}
.inspector-role { display: flex; align-items: center; gap: 14px; min-width: 0; }
.role-icon { display: grid; width: 44px; height: 44px; flex: 0 0 auto; place-items: center; border-radius: 13px; background: var(--trace-charcoal); color: var(--trace-accent); }
.role-icon :deep(svg) { width: 19px; height: 19px; }
.inspector-role > div { display: grid; gap: 4px; min-width: 0; }
.inspector-role small { color: #86670e; font-size: 10px; font-weight: 800; letter-spacing: 0.08em; }
.inspector-role h2 { overflow: hidden; margin: 0; font-size: 21px; text-overflow: ellipsis; white-space: nowrap; }
.inspector-meta { display: flex; gap: 7px; align-items: center; }
.inspector-meta span { padding: 6px 8px; border-radius: 999px; background: #f0ede7; color: #686971; font-size: 10px; font-weight: 680; }
.inspector-meta .done { background: rgba(232, 189, 63, 0.2); color: #6e5206; }
.inspector-meta .failed { background: rgba(184, 55, 55, 0.1); color: #9d3030; }
.inspector-body { display: grid; flex: 1; align-content: start; gap: 18px; padding: 26px clamp(22px, 4vw, 46px) 34px; overflow: auto; }
.trace-block { min-width: 0; }
.trace-block h3 { margin: 0 0 9px; color: #7b5e0b; font-size: 10px; font-weight: 800; letter-spacing: 0.08em; }
.trace-block p,
.trace-block pre { margin: 0; color: #494a51; font-size: 12px; line-height: 1.75; }
.trace-block pre { max-height: 360px; padding: 16px 18px; overflow: auto; border: 1px solid rgba(35, 36, 42, 0.09); border-radius: 12px; background: #f4f1eb; font-family: 'SFMono-Regular', Consolas, monospace; white-space: pre-wrap; word-break: break-word; }
.trace-block--brief { padding: 16px 18px; border: 1px solid rgba(232, 189, 63, 0.32); border-radius: 12px; background: #f7f2e5; }
.trace-block--error { padding: 15px 18px; border-radius: 12px; background: rgba(184, 55, 55, 0.07); }
.trace-block--error h3,
.trace-block--error p { color: #9d3030; }
.fork-btn {
  display: inline-flex;
  width: fit-content;
  align-items: center;
  gap: 8px;
  min-height: 40px;
  margin-top: 2px;
  padding: 0 14px;
  border: 0;
  border-radius: 11px;
  background: var(--trace-charcoal);
  color: #f8f5ee;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  transition: background 180ms ease, transform 180ms ease;
}
.fork-btn:hover { background: #37383f; transform: translateY(-1px); }
.fork-btn:active { transform: scale(0.98); }

.inspector-intro,
.inspector-empty { display: grid; flex: 1; place-content: center; justify-items: center; gap: 22px; padding: 48px; text-align: center; }
.intro-mark { display: grid; width: 64px; height: 64px; place-items: center; border: 1px solid rgba(35, 36, 42, 0.1); border-radius: 18px; background: #f2eee5; color: #805f06; box-shadow: 10px 10px 0 rgba(232, 189, 63, 0.14); }
.inspector-intro > div { display: grid; justify-items: center; gap: 9px; max-width: 560px; }
.inspector-intro > div > span { color: #806006; font-size: 10px; font-weight: 800; letter-spacing: 0.12em; }
.inspector-intro h2 { margin: 0; font-size: clamp(25px, 3vw, 38px); line-height: 1.2; letter-spacing: -0.035em; }
.inspector-intro p { max-width: 480px; margin: 0; color: var(--trace-soft); font-size: 13px; line-height: 1.7; }
.inspector-intro > button { display: inline-flex; align-items: center; gap: 9px; min-height: 44px; padding: 0 17px; border: 0; border-radius: 12px; background: var(--trace-charcoal); color: #fff; font-size: 13px; font-weight: 720; cursor: pointer; transition: transform 180ms ease, background 180ms ease; }
.inspector-intro > button:hover { background: #36373e; transform: translateY(-1px); }
.inspector-empty strong { font-size: 18px; }
.inspector-empty span { max-width: 420px; color: var(--trace-soft); font-size: 13px; }
.inspector-loading { display: grid; flex: 1; align-content: start; gap: 16px; padding: 34px; }
.inspector-loading span { display: block; height: 18px; border-radius: 8px; background: #eeeae2; }
.inspector-loading span:first-child { width: 46%; height: 42px; }
.inspector-loading span:nth-child(3) { width: 86%; }
.inspector-loading span:nth-child(4) { width: 70%; }

.trace-inspector .controls {
  position: sticky;
  right: auto;
  bottom: 0;
  left: auto;
  z-index: 2;
  display: grid;
  grid-template-columns: auto minmax(160px, 1fr) auto;
  gap: 18px;
  align-items: center;
  width: 100%;
  height: auto;
  min-height: 72px;
  padding: 12px 18px;
  border: 0;
  border-top: 1px solid rgba(35, 36, 42, 0.1);
  border-radius: 0;
  background: rgba(255, 253, 250, 0.96);
  box-shadow: none;
  backdrop-filter: blur(14px);
}
.playback-buttons { display: flex; align-items: center; gap: 4px; }
.playback-buttons button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: #64656d;
  cursor: pointer;
  transition: background 180ms ease, color 180ms ease, transform 180ms ease;
}
.playback-buttons button:hover:not(:disabled) { background: #efebe3; color: var(--trace-ink); }
.playback-buttons button:active:not(:disabled) { transform: scale(0.96); }
.playback-buttons button:disabled { opacity: 0.28; cursor: not-allowed; }
.playback-buttons .play-btn { width: 42px; height: 42px; border-radius: 12px; background: var(--trace-charcoal); color: var(--trace-accent); }
.playback-buttons .play-btn:hover { background: #36373e; color: #f2ca59; }
.scrubber { display: flex; gap: 10px; align-items: center; min-width: 0; }
.scrubber-track { position: relative; display: block; width: 100%; height: 24px; padding: 0; border: 0; background: transparent; cursor: pointer; }
.scrubber-track::before { content: ''; position: absolute; top: 11px; right: 0; left: 0; height: 2px; border-radius: 99px; background: #d9d5cc; }
.scrubber-fill { position: absolute; top: 11px; left: 0; width: 100%; height: 2px; transform-origin: left center; border-radius: 99px; background: #9a7207; }
.scrubber-thumb { position: absolute; top: 7px; width: 10px; height: 10px; margin-left: -5px; border: 2px solid var(--trace-paper); border-radius: 50%; background: #9a7207; box-shadow: 0 0 0 1px rgba(35, 36, 42, 0.16); }
.scrubber > span { flex: 0 0 auto; color: #85868d; font-size: 10px; }
.speed-group { display: flex; gap: 4px; }
.speed-group button { min-width: 34px; height: 30px; padding: 0 7px; border: 0; border-radius: 8px; background: transparent; color: #76777e; font-size: 10px; cursor: pointer; }
.speed-group button:hover { background: #efebe3; }
.speed-group button.active { background: rgba(232, 189, 63, 0.2); color: #6f5205; font-weight: 800; }

.fork-modal,
.fork-running { background: rgba(27, 28, 32, 0.58); backdrop-filter: blur(10px); }
.fork-card,
.fr-card { border: 1px solid rgba(255, 255, 255, 0.45); border-radius: 18px; background: #fffdfa; box-shadow: 0 30px 90px rgba(20, 20, 22, 0.28); }
.fork-card { width: min(720px, calc(100vw - 32px)); padding: 26px; }
.fork-head { padding: 0 0 20px; border-bottom: 1px solid rgba(35, 36, 42, 0.12); }
.fork-kbd { color: #7d5d07; background: rgba(232, 189, 63, 0.18); }
.fork-title { color: #23242a; font-size: 22px; }
.fork-sub { color: #6c6d75; }
.fork-close { border: 0; background: #f0ede7; color: #55565e; }
.fork-close:hover { background: #e7e2d8; color: #23242a; }
.fork-hint-box { border-color: rgba(232, 189, 63, 0.28); background: rgba(232, 189, 63, 0.09); color: #6f550d; }
.fork-lbl { display: block; margin: 18px 0 8px; color: #23242a; font-size: 12px; font-weight: 720; }
.fork-textarea,
.fork-input { width: 100%; border: 1px solid rgba(35, 36, 42, 0.14); border-radius: 12px; background: #f7f3eb; color: #23242a; }
.fork-textarea:focus,
.fork-input:focus { border-color: rgba(189, 141, 8, 0.65); box-shadow: 0 0 0 4px rgba(232, 189, 63, 0.12); }
.fork-textarea { min-height: 250px; padding: 14px; resize: vertical; }
.fork-input { height: 42px; padding: 0 13px; }
.fork-foot { margin-top: 20px; padding-top: 16px; border-top: 1px solid rgba(35, 36, 42, 0.12); }
.fork-cancel,
.fork-submit { min-height: 40px; border-radius: 11px; }
.fork-cancel { background: #f0ede7; color: #4f5058; }
.fork-submit { background: #24252b; color: #fff; box-shadow: none; }
.fork-submit:hover:not(:disabled) { background: #36373e; }
.fr-card { color: #23242a; }
.fr-title { color: #23242a; }
.fr-status { color: #6c6d75; }
.fr-spinner { border-color: rgba(35, 36, 42, 0.12); border-top-color: #9a7207; }
.fr-progress { background: rgba(35, 36, 42, 0.1); }
.fr-progress-fill { background: #9a7207; }
.fr-lane.active { background: rgba(232, 189, 63, 0.16); color: #6f5205; }

@media (prefers-reduced-motion: no-preference) {
  .trace-header { animation: trace-enter 520ms cubic-bezier(.16, 1, .3, 1) both; }
  .role-ribbon { animation: trace-enter 620ms 60ms cubic-bezier(.16, 1, .3, 1) both; }
  .trace-workspace { animation: trace-enter 700ms 110ms cubic-bezier(.16, 1, .3, 1) both; }
}
@keyframes trace-enter {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; scroll-behavior: auto !important; transition-duration: 0.01ms !important; }
}

@media (max-width: 1050px) {
  .trace-header { grid-template-columns: auto minmax(0, 1fr); }
  .trace-summary { grid-column: 2; justify-content: flex-start; }
  .trace-workspace { grid-template-columns: 260px minmax(0, 1fr); }
  .role-ribbon strong { font-size: 11px; }
}

@media (max-width: 820px) {
  .replay-page { padding: 18px 14px 30px; }
  .trace-header { grid-template-columns: 1fr; }
  .trace-title,
  .trace-summary { grid-column: 1; }
  .trace-summary { flex-wrap: wrap; }
  .trace-summary > div { text-align: left; }
  .role-ribbon { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .role-ribbon button:nth-child(2) { border-right: 0; }
  .role-ribbon button:nth-child(-n+2) { border-bottom: 1px solid rgba(35, 36, 42, 0.09); }
  .trace-workspace { grid-template-columns: 1fr; }
  .trace-index { min-height: 0; max-height: 320px; }
  .trace-inspector { min-height: 620px; }
}

@media (max-width: 560px) {
  .role-ribbon button { grid-template-columns: 30px minmax(0, 1fr); padding: 8px 10px; }
  .role-ribbon em { display: none; }
  .inspector-head { align-items: flex-start; padding: 18px; flex-direction: column; }
  .inspector-meta { flex-wrap: wrap; }
  .inspector-body { padding: 20px 16px 26px; }
  .trace-inspector .controls { grid-template-columns: 1fr; gap: 9px; }
  .playback-buttons,
  .speed-group { justify-content: center; }
  .scrubber { order: -1; }
  .fork-card { padding: 20px 16px; }
}
</style>

<style>
/* ─────────────────────────────────────────────
   Fork Modal（teleport 到 body，不能用 scoped）
   ───────────────────────────────────────────── */
.fork-modal {
  position: fixed;
  inset: 0;
  background: rgba(11, 20, 38, 0.42);
  backdrop-filter: blur(6px);
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  animation: fade-in 200ms ease;
}
@keyframes fade-in { from { opacity: 0; } to { opacity: 1; } }

.fork-card {
  width: min(680px, 100%);
  max-height: calc(100vh - 64px);
  background: var(--bg-surface);
  border-radius: 18px;
  border: 1px solid var(--line);
  box-shadow: 0 40px 90px rgba(11, 20, 38, 0.18), 0 12px 28px rgba(11, 20, 38, 0.08);
  padding: 22px 24px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  overflow-y: auto;
}

.fork-head { display: flex; align-items: flex-start; gap: 12px; }
.fork-head-l { flex: 1; }
.fork-kbd {
  display: inline-block;
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.14em;
  padding: 3px 8px;
  border-radius: 4px;
  background: linear-gradient(135deg, #6B8AFF, #3D5AFE);
  color: #fff;
  margin-bottom: 8px;
}
.fork-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 18px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.018em;
  line-height: 1.35;
  margin: 0;
}
.fork-sub {
  font-size: 12.5px;
  color: var(--ink-3);
  margin: 4px 0 0;
}
.fork-close {
  width: 28px; height: 28px;
  border-radius: 8px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 180ms ease;
}
.fork-close:hover { background: var(--bg-hover); color: var(--ink-1); }

.fork-hint-box {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: var(--brand-soft);
  border: 1px solid var(--brand-soft-2);
  border-radius: 10px;
  font-size: 12.5px;
  color: var(--brand-ink);
  line-height: 1.55;
}

.fork-lbl {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: var(--ink-3);
  text-transform: uppercase;
  margin-top: 4px;
}

.fork-textarea {
  width: 100%;
  background: #0F1A33;
  color: #DCE3F2;
  border: 1px solid #1B2746;
  border-radius: 10px;
  padding: 12px 14px;
  font-size: 12.5px;
  font-family: 'JetBrains Mono', monospace;
  line-height: 1.6;
  resize: vertical;
  min-height: 220px;
  max-height: 380px;
  outline: none;
  transition: border-color 180ms ease;
}
.fork-textarea:focus { border-color: #3D5AFE; box-shadow: 0 0 0 3px rgba(0,0,0,0.25); }

.fork-input {
  width: 100%;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13.5px;
  color: var(--ink-1);
  outline: none;
  transition: all 180ms ease;
}
.fork-input:focus { border-color: var(--brand); box-shadow: 0 0 0 4px var(--brand-glow); }
.fork-input::placeholder { color: var(--ink-4); }

.fork-foot { display: flex; justify-content: flex-end; gap: 10px; margin-top: 6px; }
.fork-cancel {
  padding: 9px 18px;
  border-radius: 10px;
  background: var(--bg-subtle);
  color: var(--ink-2);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 180ms ease;
}
.fork-cancel:hover { background: var(--bg-hover); }
.fork-submit {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 22px;
  border-radius: 10px;
  background: linear-gradient(180deg, #5570FF, #3D5AFE);
  color: #fff;
  font-size: 13px;
  font-weight: 700;
  letter-spacing: 0.01em;
  cursor: pointer;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.32);
  transition: filter 180ms ease;
}
.fork-submit:hover:not(:disabled) { filter: brightness(1.06); }
.fork-submit:disabled { opacity: 0.55; cursor: not-allowed; }
.fork-submit .spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* ─────────────────────────────────────────────
   Fork 执行进度蒙层
   ───────────────────────────────────────────── */
.fork-running {
  position: fixed;
  inset: 0;
  background: rgba(11, 20, 38, 0.62);
  backdrop-filter: blur(10px);
  z-index: 1999;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  animation: fade-in 240ms ease;
}
.fr-card {
  background: var(--bg-surface);
  border-radius: 18px;
  border: 1px solid var(--line);
  box-shadow: 0 40px 90px rgba(11, 20, 38, 0.30);
  padding: 28px 30px 22px;
  width: min(420px, 100%);
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
}
.fr-spinner {
  width: 48px;
  height: 48px;
  border: 3px solid var(--line);
  border-top-color: var(--brand);
  border-radius: 50%;
  animation: spin 1s linear infinite;
}
.fr-title {
  font-family: 'Manrope', sans-serif;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-1);
  margin: 4px 0 0;
}
.fr-status {
  font-size: 12.5px;
  color: var(--ink-3);
  margin: 0;
  min-height: 18px;
}
.fr-progress {
  width: 100%;
  height: 6px;
  background: var(--line);
  border-radius: 3px;
  overflow: hidden;
  margin-top: 4px;
}
.fr-progress-fill {
  height: 100%;
  background: linear-gradient(90deg, #5570FF, #3D5AFE);
  border-radius: 3px;
}
.fr-lanes {
  display: flex;
  gap: 8px;
  margin-top: 6px;
  flex-wrap: wrap;
  justify-content: center;
}
.fr-lane {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 9px;
  border-radius: 999px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  font-size: 11.5px;
  font-weight: 600;
  transition: all 180ms ease;
}
.fr-lane.active {
  background: var(--brand-soft);
  color: var(--brand-ink);
}
.fr-dot {
  width: 7px; height: 7px;
  border-radius: 50%;
}
.fr-cancel {
  margin-top: 6px;
  padding: 7px 16px;
  border-radius: 999px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
}
.fr-cancel:hover { background: var(--bg-hover); color: var(--ink-1); }
</style>
