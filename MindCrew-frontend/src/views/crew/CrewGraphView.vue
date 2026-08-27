<template>
  <div ref="pageRef" class="graph-page">
    <header class="graph-header">
      <button type="button" class="back" @click="goBack">
        <el-icon :size="15"><Back /></el-icon>
        <span>返回调研台</span>
      </button>

      <div v-if="task" class="graph-title">
        <span>Agent 通信图谱</span>
        <h1>{{ task.query }}</h1>
      </div>

      <div v-if="task" class="graph-actions">
        <div class="graph-stat"><strong>{{ task.totalSteps || steps.length }}</strong><span>节点</span></div>
        <div class="graph-stat"><strong>{{ linkCount }}</strong><span>通信</span></div>
        <button type="button" class="replay-link" @click="goReplay">
          <el-icon :size="15"><VideoPlay /></el-icon>
          <span>推理回放</span>
        </button>
      </div>
    </header>

    <main class="graph-workspace">
      <section class="graph-canvas" aria-label="Agent 通信关系图">
        <header class="canvas-toolbar">
          <div>
            <strong>协作路径</strong>
            <span>拖拽节点调整布局，滚轮缩放画布</span>
          </div>
          <div class="role-legend">
            <span v-for="r in roles" :key="r.role">
              <i :style="{ background: r.color }"></i>
              {{ r.label.split(' · ')[0] }}
              <em>{{ countByRole(r.role) }}</em>
            </span>
          </div>
        </header>

        <div ref="chartRef" class="echart"></div>

        <div v-if="!loading && !steps.length" class="graph-empty">
          <strong>暂无可展示的通信节点</strong>
          <span>该任务还没有生成 Agent 执行步骤。</span>
        </div>

        <div v-if="loading" class="graph-loading" aria-label="正在解析通信链路">
          <div class="loading-nodes"><span></span><span></span><span></span><span></span></div>
          <p>正在解析 Agent 通信链路</p>
        </div>
      </section>

      <aside class="detail-panel" :class="{ 'has-selection': selected }">
        <div v-if="!selected" class="detail-empty">
          <span class="detail-mark"><el-icon :size="26"><Aim /></el-icon></span>
          <div>
            <span>节点检查器</span>
            <h2>选择一个节点查看详情</h2>
            <p>这里会显示该 Agent 的任务、输入、输出、耗时与执行状态。</p>
          </div>
        </div>

        <template v-else>
          <header class="detail-head">
            <div class="detail-role" :style="{ color: selectedColor }"><component :is="selectedIcon" /></div>
            <div class="detail-meta">
              <span>{{ selectedRoleLabel }}</span>
              <h2>{{ selected.stepName }}</h2>
            </div>
            <button type="button" class="detail-close" @click="selected = null" aria-label="关闭详情"><el-icon :size="15"><Close /></el-icon></button>
          </header>

          <div class="detail-tags">
            <span>#{{ selected.stepIndex }}</span>
            <span :class="selected.status.toLowerCase()">{{ statusText(selected.status) }}</span>
            <span>{{ formatStepMs(selected.elapsedMs) }}</span>
          </div>

          <div class="detail-body">
            <section v-if="selected.subtask" class="detail-block detail-block--brief">
              <h3>本步任务</h3>
              <p>{{ selected.subtask }}</p>
            </section>
            <section v-if="selected.input" class="detail-block">
              <h3>输入</h3>
              <pre>{{ truncate(selected.input, 700) }}</pre>
            </section>
            <section v-if="selected.output" class="detail-block">
              <h3>输出</h3>
              <pre>{{ formatOutput(selected.output) }}</pre>
            </section>
            <section v-if="selected.errorMsg" class="detail-block detail-block--error">
              <h3>错误</h3>
              <p>{{ selected.errorMsg }}</p>
            </section>
          </div>

          <footer class="detail-foot">
            <button type="button" @click="goReplay">
              <el-icon :size="14"><VideoPlay /></el-icon>
              <span>在推理回放中定位</span>
            </button>
          </footer>
        </template>
      </aside>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Back, Close, VideoPlay, Aim, Search, EditPen, Stamp } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, GraphicComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { crewApi, type AgentTask, type AgentStep } from '@/api/crew'

echarts.use([GraphChart, TooltipComponent, GraphicComponent, CanvasRenderer])

const route  = useRoute()
const router = useRouter()
const taskId = Number(route.params.taskId)

const loading  = ref(true)
const pageRef  = ref<HTMLElement | null>(null)
const task     = ref<AgentTask | null>(null)
const steps    = ref<AgentStep[]>([])
const selected = ref<AgentStep | null>(null)
const linkCount = ref(0)

const chartRef = ref<HTMLElement | null>(null)
const chart    = shallowRef<echarts.ECharts | null>(null)

// ─────────── 角色配置 ───────────
const roles = [
  { role: 'PLANNER',    label: 'Planner · 任务规划',    color: '#E8BD3F', icon: Aim },
  { role: 'RESEARCHER', label: 'Researcher · 并行调研', color: '#B98A10', icon: Search },
  { role: 'WRITER',     label: 'Writer · 报告撰写',     color: '#8A8B92', icon: EditPen },
  { role: 'CRITIC',     label: 'Critic · 质量评审',     color: '#5B5C64', icon: Stamp },
]
const colorByRole = Object.fromEntries(roles.map(r => [r.role, r.color]))

const selectedColor = computed(() => selected.value ? colorByRole[selected.value.agentRole] : '#fff')
const selectedIcon = computed(() => {
  const r = roles.find(x => x.role === selected.value?.agentRole)
  return r?.icon || Aim
})
const selectedRoleLabel = computed(() => {
  const r = roles.find(x => x.role === selected.value?.agentRole)
  return r?.label.split(' · ')[0] || selected.value?.agentRole
})

// ─────────── 数据加载 ───────────
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
    await nextTick()
    renderGraph()
  } catch (e) {
    ElMessage.error('加载任务失败')
  } finally {
    loading.value = false
  }
}

// ─────────── 构造 nodes & links ───────────
function buildGraphData() {
  const nodes: any[] = []
  const links: any[] = []

  const planners    = steps.value.filter(s => s.agentRole === 'PLANNER')
  const researchers = steps.value.filter(s => s.agentRole === 'RESEARCHER')
  const writers     = steps.value.filter(s => s.agentRole === 'WRITER')
  const critics     = steps.value.filter(s => s.agentRole === 'CRITIC')

  // Planner 节点（通常 1 个）
  planners.forEach((s) => {
    nodes.push(buildNode(s, 'PLANNER', 60))
  })

  // Researcher 节点（N 个并行）
  researchers.forEach((s) => {
    nodes.push(buildNode(s, 'RESEARCHER', 44, shortLabel(s.subtask || s.stepName)))
    // 边：Planner → 每个 Researcher
    planners.forEach(p => {
      links.push({
        source: nodeId(p),
        target: nodeId(s),
        value: '子任务下发',
      })
    })
  })

  // Writer 节点（可能 1-2 个：原稿 + 重写）
  writers.forEach((s, i) => {
    nodes.push(buildNode(s, 'WRITER', 54, i === 0 ? 'Writer' : 'Writer · 重写'))
    if (i === 0) {
      // 所有 Researcher → 首个 Writer
      researchers.forEach(r => {
        links.push({
          source: nodeId(r),
          target: nodeId(s),
          value: '调研结论',
        })
      })
    } else {
      // 上一轮 Critic → 重写的 Writer
      const prevCritic = critics[i - 1]
      if (prevCritic) {
        links.push({
          source: nodeId(prevCritic),
          target: nodeId(s),
          value: '重写反馈',
          lineStyle: { type: 'dashed' },
        })
      }
    }
  })

  // Critic 节点
  critics.forEach((s, i) => {
    nodes.push(buildNode(s, 'CRITIC', 54, i === 0 ? 'Critic' : 'Critic · 再审'))
    const correspondingWriter = writers[i]
    if (correspondingWriter) {
      links.push({
        source: nodeId(correspondingWriter),
        target: nodeId(s),
        value: '报告评审',
      })
    }
  })

  linkCount.value = links.length
  return { nodes, links }
}

function buildNode(s: AgentStep, role: string, size: number, label?: string) {
  const color = colorByRole[role]
  return {
    id: nodeId(s),
    name: label || roleLabelShort(role),
    symbolSize: size,
    category: role,
    value: s.elapsedMs,
    rawStep: s,
    itemStyle: {
      color: color,
      shadowBlur: 12,
      shadowColor: 'rgba(53, 49, 39, 0.18)',
      borderColor: '#FFFDFA',
      borderWidth: 2,
    },
    label: {
      show: true,
      position: 'bottom',
      color: '#3F4047',
      fontSize: 11,
      fontWeight: 600,
      distance: 8,
      formatter: (p: any) => p.name,
    },
  }
}

function nodeId(s: AgentStep) { return `${s.agentRole}-${s.stepIndex}` }
function roleLabelShort(r: string) {
  return { PLANNER: 'Planner', RESEARCHER: 'Researcher', WRITER: 'Writer', CRITIC: 'Critic' }[r] || r
}
function shortLabel(s: string, n = 14) {
  if (!s) return 'Researcher'
  return s.length > n ? s.slice(0, n) + '…' : s
}

// ─────────── 渲染图表 ───────────
function renderGraph() {
  if (!chartRef.value) return
  chart.value?.dispose()
  chart.value = echarts.init(chartRef.value, undefined, { renderer: 'canvas' })

  const { nodes, links } = buildGraphData()

  const option: any = {
    backgroundColor: 'transparent',
    tooltip: {
      backgroundColor: 'rgba(255, 253, 250, 0.98)',
      borderColor: 'rgba(35, 36, 42, 0.12)',
      borderWidth: 1,
      textStyle: { color: '#23242A', fontSize: 12 },
      padding: [10, 14],
      formatter: (p: any) => {
        if (p.dataType === 'edge') {
          return `<span style="color:#686971">${p.data.value}</span>`
        }
        const s: AgentStep = p.data.rawStep
        if (!s) return p.name
        const color = colorByRole[s.agentRole]
        return `
          <div style="font-weight:700;color:${color};margin-bottom:4px">
            ${roleLabelShort(s.agentRole)} · #${s.stepIndex}
          </div>
          <div style="color:#23242A;margin-bottom:2px">${s.stepName}</div>
          <div style="color:#777880;font-size:11px">耗时 ${formatStepMs(s.elapsedMs)}</div>
        `
      },
    },
    animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
    animationDuration: 900,
    animationEasingUpdate: 'quinticInOut',
    series: [{
      type: 'graph',
      layout: 'force',
      roam: true,
      draggable: true,
      categories: roles.map(r => ({ name: r.role })),
      force: {
        repulsion: 320,
        edgeLength: [110, 180],
        gravity: 0.08,
        layoutAnimation: true,
      },
      lineStyle: {
        color: '#9D9277',
        width: 1.4,
        opacity: 0.62,
        curveness: 0.18,
      },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [0, 10],
      edgeLabel: {
        show: false,
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 3, opacity: 1 },
        itemStyle: { shadowBlur: 18 },
        label: { color: '#23242A', fontSize: 13 },
      },
      data: nodes,
      links: links,
    }],
  }

  chart.value.setOption(option)

  chart.value.on('click', (params: any) => {
    if (params.dataType === 'node' && params.data?.rawStep) {
      selected.value = params.data.rawStep
    }
  })

  chart.value.on('dblclick', () => {
    chart.value?.dispatchAction({ type: 'restore' })
  })

  // 响应窗口缩放
  window.addEventListener('resize', resize)
}

function resize() { chart.value?.resize() }

// ─────────── UI 辅助 ───────────
function countByRole(role: string) { return steps.value.filter(s => s.agentRole === role).length }
function statusText(s: string) { return s === 'DONE' ? '已完成' : s === 'FAILED' ? '失败' : s === 'RUNNING' ? '进行中' : '已跳过' }
function formatStepMs(ms?: number) {
  if (!ms) return '-'
  return ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(2)}s`
}
function formatDuration(ms?: number) {
  if (!ms) return '-'
  if (ms < 1000) return ms + 'ms'
  if (ms < 60_000) return (ms / 1000).toFixed(1) + 's'
  return (ms / 60_000).toFixed(1) + 'min'
}
function truncate(s: string, n: number) { return s.length > n ? s.slice(0, n) + '…' : s }
function formatOutput(raw: string) {
  if (!raw) return ''
  let pretty = raw
  const t = raw.trim()
  if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
    try { pretty = JSON.stringify(JSON.parse(t), null, 2) } catch {}
  }
  return pretty.length > 1500 ? pretty.slice(0, 1500) + '\n…（已截断）' : pretty
}
function starStyle(i: number) {
  const seed = (i * 7919) % 100
  const top = (i * 31) % 100
  const left = ((i * 53) + 17) % 100
  const dur = 3 + (seed % 4)
  const delay = (seed % 7) * 0.4
  const size = 1 + (seed % 3) * 0.6
  return {
    top: top + '%',
    left: left + '%',
    width: size + 'px',
    height: size + 'px',
    animationDuration: dur + 's',
    animationDelay: -delay + 's',
  }
}

function goBack()   { router.push('/crew') }
function goReplay() { if (taskId) router.push(`/crew/replay/${taskId}`) }

// ─────────── 生命周期 ───────────
onMounted(() => {
  pageRef.value?.scrollTo({ top: 0 })
  load()
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart.value?.dispose()
})
</script>

<style scoped media="not all">
@media not all {
/* ─────────────────────────────────────────────
   命令中心：暗色作战面板风
   ───────────────────────────────────────────── */
.graph-page {
  position: relative;
  height: 100%;
  display: grid;
  grid-template-rows: auto 1fr;
  background:
    radial-gradient(900px 600px at 50% 0%, rgba(0, 0, 0, 0.20), transparent 60%),
    radial-gradient(700px 500px at 100% 100%, rgba(14, 165, 233, 0.14), transparent 60%),
    linear-gradient(180deg, #060A18 0%, #0A1226 50%, #0F1B33 100%);
  color: #E6EAF6;
  overflow: hidden;
}

/* ── 顶部 ── */
.topbar {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 18px 28px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(15, 27, 51, 0.45);
  backdrop-filter: blur(20px);
  z-index: 5;
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  color: #C6CEE5;
  font-size: 12.5px;
  font-weight: 500;
  transition: var(--transition);
}
.back:hover { background: rgba(255, 255, 255, 0.08); color: #fff; border-color: rgba(255, 255, 255, 0.18); }

.topbar-main {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}
.kbd-tag {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.14em;
  padding: 3px 9px;
  border-radius: 4px;
  background: linear-gradient(135deg, #6B8AFF, #3D5AFE);
  color: #fff;
  flex-shrink: 0;
}
.task-query {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 17px;
  font-weight: 700;
  color: #fff;
  letter-spacing: -0.018em;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.topbar-stats {
  display: flex;
  align-items: center;
  gap: 22px;
  flex-shrink: 0;
}
.ts-cell { display: flex; align-items: baseline; gap: 6px; }
.ts-val {
  font-family: 'Manrope', 'JetBrains Mono', monospace;
  font-size: 20px;
  font-weight: 800;
  color: #fff;
  letter-spacing: -0.01em;
}
.ts-lbl {
  font-size: 11px;
  color: #94A0BD;
  font-weight: 500;
}

/* ── 舞台 ── */
.stage {
  position: relative;
  overflow: hidden;
}

/* 星空层 */
.starfield {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.star {
  position: absolute;
  border-radius: 50%;
  background: #C7D1FF;
  box-shadow: 0 0 6px #6B8AFF;
  opacity: 0;
  animation: twinkle infinite ease-in-out;
}
@keyframes twinkle {
  0%, 100% { opacity: 0; transform: scale(0.6); }
  50%      { opacity: 0.7; transform: scale(1); }
}

/* 色环 */
.role-orbits {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}
.role-orbits .orbit {
  position: absolute;
  border-radius: 50%;
  border: 1px solid rgba(255, 255, 255, 0.04);
}
.role-orbits .orbit:nth-child(1) { width: 36%; height: 56%; }
.role-orbits .orbit:nth-child(2) { width: 56%; height: 78%; }
.role-orbits .orbit:nth-child(3) { width: 78%; height: 100%; }

.echart {
  position: absolute;
  inset: 0;
  z-index: 2;
}

/* ── 图例 ── */
.legend {
  position: absolute;
  left: 24px;
  bottom: 24px;
  z-index: 3;
  padding: 14px 16px;
  border-radius: var(--radius);
  background: rgba(11, 20, 38, 0.66);
  border: 1px solid rgba(255, 255, 255, 0.08);
  backdrop-filter: blur(14px);
  min-width: 220px;
}
.legend-title {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: #94A0BD;
  margin-bottom: 10px;
}
.legend-list { display: flex; flex-direction: column; gap: 7px; }
.legend-row { display: flex; align-items: center; gap: 10px; }
.legend-dot {
  width: 10px; height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
}
.legend-name {
  flex: 1;
  font-size: 12.5px;
  color: #D7DDEC;
  font-weight: 500;
}
.legend-count {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11.5px;
  font-weight: 700;
  color: #94A0BD;
}

/* ── 右侧详情面板 ── */
.detail-panel {
  position: absolute;
  top: 24px;
  right: 24px;
  bottom: 24px;
  width: 380px;
  z-index: 4;
  background: rgba(11, 20, 38, 0.72);
  backdrop-filter: blur(16px);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: var(--radius-lg);
  padding: 18px 18px 14px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 30px 60px rgba(0, 0, 0, 0.4);
  transform: translateX(0);
  transition: transform 280ms var(--ease), opacity 280ms var(--ease);
}

.detail-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  gap: 16px;
  color: #94A0BD;
}
.empty-ring {
  width: 48px;
  height: 48px;
  border: 2px solid rgba(255, 255, 255, 0.12);
  border-top-color: #6B8AFF;
  border-radius: 50%;
  animation: spin 2.5s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.empty-text p { font-size: 13px; line-height: 1.7; }
.empty-hint {
  margin-top: 6px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px !important;
  color: #6B7B98;
  letter-spacing: 0.02em;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 14px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.detail-role {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  flex-shrink: 0;
}
.detail-meta { flex: 1; min-width: 0; }
.detail-role-name {
  font-family: 'Manrope', sans-serif;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  letter-spacing: -0.005em;
}
.detail-step-name {
  font-size: 12px;
  color: #94A0BD;
  margin-top: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.detail-close {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  color: #94A0BD;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: var(--transition);
}
.detail-close:hover { background: rgba(255, 255, 255, 0.12); color: #fff; }

.detail-tags {
  display: flex;
  gap: 6px;
  margin: 12px 0 8px;
  flex-wrap: wrap;
}
.d-tag {
  font-size: 10.5px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 4px;
  background: rgba(255, 255, 255, 0.06);
  color: #C6CEE5;
  letter-spacing: 0.04em;
}
.d-tag.mono { font-family: 'JetBrains Mono', monospace; }
.d-tag.done    { background: rgba(52, 211, 153, 0.18); color: #6EE7B7; }
.d-tag.failed  { background: rgba(239, 68, 68, 0.18);  color: #FCA5A5; }
.d-tag.running { background: rgba(107, 138, 255, 0.18); color: #BFD0FF; }

.detail-body {
  flex: 1;
  overflow-y: auto;
  margin-top: 4px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-right: 4px;
}
.d-block {
  background: rgba(255, 255, 255, 0.035);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
}
.d-block.error { background: rgba(239, 68, 68, 0.10); color: #FECACA; }
.d-lbl {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.1em;
  color: #94A0BD;
  margin-bottom: 5px;
}
.d-val {
  font-size: 12.5px;
  line-height: 1.65;
  color: #DCE3F2;
  white-space: pre-wrap;
  word-break: break-word;
}
.d-val.mono { font-family: 'JetBrains Mono', monospace; font-size: 11.5px; }
.d-val.output {
  background: rgba(0, 0, 0, 0.30);
  border-radius: var(--radius-xs);
  padding: 10px 12px;
  margin: 0;
  max-height: 240px;
  overflow-y: auto;
  color: #B7C2DC;
}
.d-val.output::-webkit-scrollbar { width: 5px; }
.d-val.output::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.12); border-radius: 5px; }

.detail-foot {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
}
.d-btn {
  width: 100%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 9px 14px;
  border-radius: var(--radius-sm);
  background: rgba(107, 138, 255, 0.16);
  color: #BFD0FF;
  font-size: 12.5px;
  font-weight: 600;
  border: 1px solid rgba(107, 138, 255, 0.30);
  transition: var(--transition);
}
.d-btn:hover { background: rgba(107, 138, 255, 0.26); color: #fff; }

/* ── Loading ── */
.loading {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 16px;
  background: rgba(6, 10, 24, 0.5);
  backdrop-filter: blur(8px);
  z-index: 100;
  color: #94A0BD;
  font-size: 13px;
}
.loading-ring {
  width: 44px;
  height: 44px;
  border: 3px solid rgba(255, 255, 255, 0.12);
  border-top-color: #6B8AFF;
  border-radius: 50%;
  animation: spin 1.2s linear infinite;
}

@media (max-width: 900px) {
  .detail-panel { width: 92%; right: 4%; left: 4%; top: auto; bottom: 16px; height: 50%; }
  .legend { display: none; }
}
}
</style>

<style scoped>
.graph-page {
  --graph-ink: #23242a;
  --graph-soft: #6a6b73;
  --graph-paper: #fffdfa;
  --graph-charcoal: #24252b;
  --graph-line: rgba(35, 36, 42, 0.12);
  --graph-accent: #e8bd3f;
  width: 100%;
  height: 100%;
  min-height: 0;
  padding: clamp(22px, 3vw, 42px);
  overflow: auto;
  background:
    radial-gradient(circle at 95% 2%, rgba(232, 189, 63, 0.13), transparent 24rem),
    transparent;
  color: var(--graph-ink);
  scrollbar-gutter: stable;
}

button { font: inherit; }
button:focus-visible { outline: 3px solid rgba(189, 141, 8, 0.28); outline-offset: 3px; }

.graph-header,
.graph-workspace {
  width: min(100%, 1440px);
  margin-inline: auto;
}

.graph-header {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 22px;
  align-items: start;
  padding-bottom: 24px;
}

.back,
.replay-link,
.detail-foot button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  border: 0;
  border-radius: 11px;
  cursor: pointer;
  transition: background 180ms ease, color 180ms ease, border-color 180ms ease, transform 180ms ease;
}

.back {
  padding: 0 13px;
  border: 1px solid var(--graph-line);
  background: rgba(255, 253, 250, 0.72);
  color: #55565e;
  font-size: 13px;
}
.back:hover { background: var(--graph-paper); color: var(--graph-ink); transform: translateY(-1px); }
.back:active { transform: scale(0.98); }

.graph-title { min-width: 0; }
.graph-title > span { display: block; margin-bottom: 7px; color: #806109; font-size: 11px; font-weight: 800; letter-spacing: 0.12em; }
.graph-title h1 { max-width: 860px; margin: 0; color: var(--graph-ink); font-size: clamp(25px, 2.6vw, 38px); font-weight: 760; line-height: 1.28; letter-spacing: -0.03em; }

.graph-actions { display: flex; align-items: center; gap: 14px; }
.graph-stat { display: grid; gap: 3px; min-width: 48px; text-align: right; }
.graph-stat strong { color: var(--graph-ink); font-size: 16px; }
.graph-stat span { color: #85868d; font-size: 10px; }
.replay-link { padding: 0 14px; background: var(--graph-charcoal); color: #f8f5ee; font-size: 12px; font-weight: 720; }
.replay-link:hover { background: #37383f; transform: translateY(-1px); }
.replay-link:active { transform: scale(0.98); }

.graph-workspace {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(300px, 360px);
  gap: 20px;
  align-items: stretch;
  min-height: min(760px, calc(100dvh - 210px));
  padding-bottom: 20px;
}

.graph-canvas {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 680px;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--graph-line);
  border-radius: 18px;
  background:
    radial-gradient(circle at 50% 45%, rgba(232, 189, 63, 0.08), transparent 22rem),
    var(--graph-paper);
  box-shadow: 0 22px 64px rgba(61, 54, 37, 0.08);
}

.canvas-toolbar {
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  min-height: 78px;
  padding: 0 22px;
  border-bottom: 1px solid rgba(35, 36, 42, 0.09);
  background: rgba(255, 253, 250, 0.9);
}
.canvas-toolbar > div:first-child { display: grid; gap: 4px; }
.canvas-toolbar strong { font-size: 15px; }
.canvas-toolbar > div:first-child span { color: #85868d; font-size: 11px; }
.role-legend { display: flex; flex-wrap: wrap; gap: 8px 14px; justify-content: flex-end; }
.role-legend > span { display: inline-flex; align-items: center; gap: 6px; color: #62636b; font-size: 10px; }
.role-legend i { width: 7px; height: 7px; border-radius: 50%; }
.role-legend em { color: #9a9ba1; font-style: normal; font-weight: 700; }
.echart { position: relative; z-index: 1; flex: 1; width: 100%; min-height: 580px; }

.detail-panel {
  position: relative;
  top: auto;
  right: auto;
  display: flex;
  width: auto;
  height: auto;
  min-height: 680px;
  flex-direction: column;
  overflow: hidden;
  border: 0;
  border-radius: 18px;
  background:
    radial-gradient(circle at 110% -10%, rgba(232, 189, 63, 0.27), transparent 18rem),
    var(--graph-charcoal);
  color: #f7f4ed;
  box-shadow: 0 22px 64px rgba(35, 34, 30, 0.19);
  transform: none;
}
.detail-panel.has-selection { transform: none; }
.detail-empty { display: grid; flex: 1; place-content: center; justify-items: start; gap: 26px; padding: 36px; text-align: left; }
.detail-mark { display: grid; width: 58px; height: 58px; place-items: center; border: 1px solid rgba(232, 189, 63, 0.28); border-radius: 17px; background: rgba(232, 189, 63, 0.1); color: var(--graph-accent); }
.detail-empty > div { display: grid; gap: 9px; }
.detail-empty > div > span { color: #e6c45f; font-size: 10px; font-weight: 800; letter-spacing: 0.1em; }
.detail-empty h2 { max-width: 260px; margin: 0; color: #f7f4ed; font-size: 27px; line-height: 1.18; letter-spacing: -0.03em; }
.detail-empty p { max-width: 280px; margin: 0; color: #aaa9a4; font-size: 12px; line-height: 1.7; }

.detail-head {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr) 34px;
  gap: 13px;
  align-items: center;
  padding: 22px 20px 18px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}
.detail-role { display: grid; width: 42px; height: 42px; place-items: center; border: 1px solid rgba(255, 255, 255, 0.12); border-radius: 12px; background: rgba(255, 255, 255, 0.06); }
.detail-role :deep(svg) { width: 18px; height: 18px; }
.detail-meta { display: grid; gap: 4px; min-width: 0; }
.detail-meta span { color: #dfbd59; font-size: 10px; font-weight: 800; letter-spacing: 0.06em; }
.detail-meta h2 { overflow: hidden; margin: 0; color: #f7f4ed; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }
.detail-close { display: grid; width: 34px; height: 34px; place-items: center; border: 0; border-radius: 10px; background: transparent; color: #8e8e8a; cursor: pointer; }
.detail-close:hover { background: rgba(255, 255, 255, 0.08); color: #f7f4ed; }
.detail-tags { display: flex; gap: 7px; padding: 14px 20px 0; }
.detail-tags span { padding: 5px 7px; border-radius: 999px; background: rgba(255, 255, 255, 0.07); color: #a7a6a1; font-size: 9px; font-weight: 700; }
.detail-tags .done { background: rgba(232, 189, 63, 0.14); color: #efce6e; }
.detail-tags .failed { background: rgba(230, 111, 111, 0.11); color: #f0a0a0; }
.detail-body { display: grid; flex: 1; align-content: start; gap: 18px; padding: 20px; overflow: auto; }
.detail-block h3 { margin: 0 0 8px; color: #d9b54b; font-size: 9px; font-weight: 800; letter-spacing: 0.08em; }
.detail-block p,
.detail-block pre { margin: 0; color: #c7c5bf; font-size: 11px; line-height: 1.7; }
.detail-block pre { max-height: 240px; padding: 13px 14px; overflow: auto; border: 1px solid rgba(255, 255, 255, 0.09); border-radius: 11px; background: rgba(255, 255, 255, 0.045); font-family: 'SFMono-Regular', Consolas, monospace; white-space: pre-wrap; word-break: break-word; }
.detail-block--brief { padding: 13px 14px; border-left: 3px solid var(--graph-accent); border-radius: 0 10px 10px 0; background: rgba(232, 189, 63, 0.08); }
.detail-block--error { padding: 13px 14px; border-radius: 10px; background: rgba(211, 73, 73, 0.09); }
.detail-block--error h3,
.detail-block--error p { color: #f0a0a0; }
.detail-foot { padding: 15px 20px 20px; border-top: 1px solid rgba(255, 255, 255, 0.09); }
.detail-foot button { width: 100%; padding: 0 14px; background: var(--graph-accent); color: #302a1c; font-size: 12px; font-weight: 780; }
.detail-foot button:hover { background: #f0ca5b; transform: translateY(-1px); }
.detail-foot button:active { transform: scale(0.98); }

.graph-loading {
  position: absolute;
  inset: 79px 0 0;
  z-index: 3;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 22px;
  background: rgba(255, 253, 250, 0.9);
  color: var(--graph-soft);
  font-size: 12px;
  backdrop-filter: blur(8px);
}
.loading-nodes { position: relative; width: 120px; height: 84px; }
.loading-nodes span { position: absolute; width: 24px; height: 24px; border: 3px solid var(--graph-paper); border-radius: 50%; background: #b98a10; box-shadow: 0 5px 16px rgba(53, 49, 39, 0.16); }
.loading-nodes span:first-child { top: 0; left: 47px; background: #e8bd3f; }
.loading-nodes span:nth-child(2) { bottom: 0; left: 6px; }
.loading-nodes span:nth-child(3) { right: 6px; bottom: 0; background: #8a8b92; }
.loading-nodes span:nth-child(4) { right: 47px; bottom: 8px; width: 18px; height: 18px; background: #5b5c64; }
.loading-nodes::before,
.loading-nodes::after { content: ''; position: absolute; top: 41px; width: 58px; height: 1px; background: #c8c1b0; transform-origin: center; }
.loading-nodes::before { left: 13px; transform: rotate(-33deg); }
.loading-nodes::after { right: 13px; transform: rotate(33deg); }
.graph-empty { position: absolute; inset: 79px 0 0; z-index: 2; display: grid; place-content: center; justify-items: center; gap: 8px; background: var(--graph-paper); text-align: center; }
.graph-empty strong { font-size: 17px; }
.graph-empty span { color: var(--graph-soft); font-size: 12px; }

@media (prefers-reduced-motion: no-preference) {
  .graph-header { animation: graph-enter 520ms cubic-bezier(.16, 1, .3, 1) both; }
  .graph-canvas { animation: graph-enter 640ms 60ms cubic-bezier(.16, 1, .3, 1) both; }
  .detail-panel { animation: graph-enter 700ms 110ms cubic-bezier(.16, 1, .3, 1) both; }
  .loading-nodes span { animation: graph-breathe 1.5s ease-in-out infinite; }
  .loading-nodes span:nth-child(2) { animation-delay: 100ms; }
  .loading-nodes span:nth-child(3) { animation-delay: 200ms; }
  .loading-nodes span:nth-child(4) { animation-delay: 300ms; }
}
@keyframes graph-enter {
  from { opacity: 0; transform: translateY(16px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes graph-breathe {
  0%, 100% { transform: scale(1); opacity: 0.72; }
  50% { transform: scale(1.13); opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation: none !important; transition-duration: 0.01ms !important; }
}

@media (max-width: 1050px) {
  .graph-header { grid-template-columns: auto minmax(0, 1fr); }
  .graph-actions { grid-column: 2; justify-content: flex-start; }
  .graph-stat { text-align: left; }
  .graph-workspace { grid-template-columns: minmax(0, 1fr) 300px; }
  .canvas-toolbar { align-items: flex-start; padding-block: 16px; flex-direction: column; }
  .role-legend { justify-content: flex-start; }
  .graph-loading,
  .graph-empty { inset-block-start: 119px; }
}

@media (max-width: 820px) {
  .graph-page { padding: 18px 14px 30px; }
  .graph-header { grid-template-columns: 1fr; }
  .graph-title,
  .graph-actions { grid-column: 1; }
  .graph-actions { flex-wrap: wrap; }
  .graph-workspace { grid-template-columns: 1fr; }
  .graph-canvas { min-height: 600px; }
  .detail-panel { width: 100%; min-height: 520px; }
}

@media (max-width: 520px) {
  .graph-title h1 { font-size: 25px; }
  .replay-link { width: 100%; }
  .canvas-toolbar { padding: 15px; }
  .role-legend { display: grid; grid-template-columns: repeat(2, 1fr); width: 100%; }
  .detail-empty { padding: 28px 22px; }
}
</style>
