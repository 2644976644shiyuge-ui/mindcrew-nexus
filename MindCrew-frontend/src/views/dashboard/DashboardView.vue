<template>
  <div class="dashboard-page page-container">
    <!-- 页头 -->
    <div class="dash-header">
      <div>
        <h2 class="page-h2">数据 <span class="gradient-text">大屏</span></h2>
        <p class="page-desc">{{ brandStore.systemName }} 平台实时运行数据概览</p>
      </div>
      <div class="header-right">
        <el-radio-group v-model="timeRange" size="small" @change="loadData">
          <el-radio-button value="today">今日</el-radio-button>
          <el-radio-button value="week">近7天</el-radio-button>
          <el-radio-button value="month">近30天</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" size="small" circle :loading="loading" @click="loadData" />
      </div>
    </div>

    <div v-loading="loading" class="dash-body">
      <!-- ── 核心指标 ── -->
      <div class="metric-strip">
        <div
          v-for="m in metrics"
          :key="m.label"
          class="metric-card"
          :style="{ '--mc': m.color }"
        >
          <div class="mc-icon" :style="{ background: m.bg, border: `1px solid ${m.border}` }">
            <el-icon size="20" :color="m.color"><component :is="m.icon" /></el-icon>
          </div>
          <div class="mc-body">
            <div class="mc-val">
              <count-up :end-val="m.value" :duration="1.2" />
            </div>
            <div class="mc-label">{{ m.label }}</div>
          </div>
          <!-- 底部高亮线 -->
          <div class="mc-bar"></div>
        </div>
      </div>

      <!-- ── 第一行图表 ── -->
      <div class="chart-row">
        <!-- 问答趋势 -->
        <el-card class="chart-card wide">
          <template #header>
            <div class="card-head">
              <span>问答量趋势</span>
              <el-tag size="small" effect="light">近 14 天</el-tag>
            </div>
          </template>
          <div ref="trendChartRef" class="chart-box"></div>
        </el-card>

        <!-- 文档处理状态 -->
        <el-card class="chart-card narrow">
          <template #header>
            <div class="card-head"><span>文档处理状态</span></div>
          </template>
          <div ref="docStatusChartRef" class="chart-box"></div>
        </el-card>
      </div>

      <!-- ── 第二行图表 ── -->
      <div class="chart-row">
        <!-- 用户角色分布 -->
        <el-card class="chart-card narrow">
          <template #header>
            <div class="card-head"><span>用户角色分布</span></div>
          </template>
          <div ref="roleChartRef" class="chart-box"></div>
        </el-card>

        <!-- 热门关键词 -->
        <el-card class="chart-card wide">
          <template #header>
            <div class="card-head"><span>热门关键词 TOP 15</span></div>
          </template>
          <div v-if="hotKeywords.length" class="keyword-cloud">
            <span
              v-for="(kw, i) in hotKeywords"
              :key="kw.name"
              class="kw-tag"
              :style="getKwStyle(i, kw.value)"
            >{{ kw.name }}</span>
          </div>
          <div v-else class="chart-empty">暂无足够数据</div>
        </el-card>
      </div>

      <!-- ── 反馈质量 ── -->
      <div class="chart-row">
        <el-card class="chart-card" style="flex:1">
          <template #header>
            <div class="card-head"><span>回答反馈质量</span></div>
          </template>
          <div class="quality-grid">
            <div class="quality-item">
              <div class="quality-label">用户满意度（有用 / 总反馈）</div>
              <el-progress
                :percentage="satisfactionPct"
                :stroke-width="10"
                color="#34d399"
                :format="() => totalFeedback ? `${satisfactionPct.toFixed(1)}%` : '暂无反馈'"
              />
            </div>
            <div class="qd-row qd-row-3">
              <div class="qd-cell">
                <div class="qd-val" style="color:#34d399">{{ data?.feedbackStats?.useful ?? 0 }}</div>
                <div class="qd-lbl">有用反馈</div>
              </div>
              <div class="qd-cell">
                <div class="qd-val" style="color:#f87171">{{ data?.feedbackStats?.useless ?? 0 }}</div>
                <div class="qd-lbl">无用反馈</div>
              </div>
              <div class="qd-cell">
                <div class="qd-val" style="color:#38bdf8">{{ data?.fallbackStats?.normal ?? 0 }}</div>
                <div class="qd-lbl">周期内回答数</div>
              </div>
            </div>
          </div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { LineChart, PieChart, BarChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { statsApi, type DashboardData } from '@/api/stats'
import { useBrandStore } from '@/stores/brand'

echarts.use([LineChart, PieChart, BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

const loading       = ref(false)
const brandStore    = useBrandStore()
const timeRange     = ref('week')
const data          = ref<DashboardData | null>(null)
const trendChartRef = ref<HTMLElement>()
const docStatusChartRef = ref<HTMLElement>()
const roleChartRef = ref<HTMLElement>()

let trendChart: echarts.ECharts | null = null
let docStatusChart: echarts.ECharts | null = null
let roleChart: echarts.ECharts | null = null

const ECHART_THEME = {
  backgroundColor: 'transparent',
  textStyle: { color: '#64748b' },
  tooltip: {
    backgroundColor: '#1c2230',
    borderColor: 'rgba(255,255,255,0.08)',
    textStyle: { color: '#e2e8f0' },
  },
}

const metrics = computed(() => [
  { label: '总问答数',   value: data.value?.totalMessages   ?? 0, icon: 'ChatDotRound', color: '#38bdf8', bg: 'rgba(56,189,248,0.1)',  border: 'rgba(56,189,248,0.2)' },
  { label: '文档总数',   value: data.value?.totalKnowledge  ?? 0, icon: 'Document',     color: '#71717a', bg: 'rgba(129,140,248,0.1)', border: 'rgba(129,140,248,0.2)' },
  { label: '用户总数',   value: data.value?.totalUsers      ?? 0, icon: 'UserFilled',   color: '#34d399', bg: 'rgba(52,211,153,0.1)',  border: 'rgba(52,211,153,0.2)' },
  { label: '周期内问答', value: data.value?.periodMessages  ?? 0, icon: 'TrendCharts',  color: '#fbbf24', bg: 'rgba(251,191,36,0.1)',  border: 'rgba(251,191,36,0.2)' },
])

const totalFeedback = computed(() => {
  const s = data.value?.feedbackStats
  return s ? s.useful + s.useless : 0
})
const satisfactionPct = computed(() => {
  const s = data.value?.feedbackStats
  if (!s) return 0
  const t = s.useful + s.useless
  return t > 0 ? Math.round(s.useful / t * 100) : 0
})

const hotKeywords = computed(() => data.value?.hotKeywords || [])

const loadData = async () => {
  loading.value = true
  try {
    const res = await statsApi.getDashboard(timeRange.value)
    data.value = res
  } catch { data.value = null } // 用 mock 数据
  finally { loading.value = false }
  await nextTick()
  renderCharts()
}

const renderCharts = () => {
  renderTrend()
  renderDocStatus()
  renderRole()
}

const renderTrend = () => {
  if (!trendChartRef.value) return
  if (!trendChart) trendChart = echarts.init(trendChartRef.value, null, { renderer: 'canvas' })
  const raw = data.value?.dailyTrend || []
  trendChart.setOption({
    ...ECHART_THEME,
    grid: { left: 40, right: 20, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      data: raw.map(d => d.date),
      axisLine: { lineStyle: { color: 'rgba(255,255,255,0.06)' } },
      axisLabel: { color: '#475569', fontSize: 11 },
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: 'rgba(255,255,255,0.04)' } },
      axisLabel: { color: '#475569', fontSize: 11 },
    },
    series: [{
      type: 'line',
      data: raw.map(d => d.count),
      smooth: true,
      lineStyle: { color: '#38bdf8', width: 2 },
      itemStyle: { color: '#38bdf8' },
      areaStyle: {
        color: { type: 'linear', x: 0, y: 0, x2: 0, y2: 1,
          colorStops: [{ offset: 0, color: 'rgba(56,189,248,0.25)' }, { offset: 1, color: 'rgba(56,189,248,0.02)' }] }
      },
      symbol: 'circle', symbolSize: 5,
    }],
    tooltip: { ...ECHART_THEME.tooltip, trigger: 'axis' },
  })
}

// 文档处理状态分布（真实：knowledgeStatusStats）
const renderDocStatus = () => {
  if (!docStatusChartRef.value) return
  if (!docStatusChart) docStatusChart = echarts.init(docStatusChartRef.value, null, { renderer: 'canvas' })
  const raw = data.value?.knowledgeStatusStats || []
  const colorMap: Record<string, string> = { '就绪': '#34d399', '处理中': '#38bdf8', '上传中': '#fbbf24', '失败': '#f87171' }
  docStatusChart.setOption({
    ...ECHART_THEME,
    legend: { bottom: 0, textStyle: { color: '#64748b', fontSize: 11 } },
    series: [{
      type: 'pie', radius: ['42%', '68%'], center: ['50%', '45%'],
      data: raw.filter(d => d.value > 0).map(d => ({ ...d, itemStyle: { color: colorMap[d.name] || '#64748b' } })),
      label: { show: false },
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.4)' } },
    }],
    tooltip: { ...ECHART_THEME.tooltip, trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  })
}

// 用户角色分布（真实：userRoleDistribution）
const renderRole = () => {
  if (!roleChartRef.value) return
  if (!roleChart) roleChart = echarts.init(roleChartRef.value, null, { renderer: 'canvas' })
  const raw = data.value?.userRoleDistribution || []
  const colors = ['#71717a', '#38bdf8', '#34d399', '#fbbf24', '#f87171', '#64748b']
  roleChart.setOption({
    ...ECHART_THEME,
    legend: { bottom: 0, textStyle: { color: '#64748b', fontSize: 11 } },
    series: [{
      type: 'pie', radius: ['42%', '68%'], center: ['50%', '45%'],
      data: raw.map((d, i) => ({ ...d, itemStyle: { color: colors[i % colors.length] } })),
      label: { show: false },
      emphasis: { itemStyle: { shadowBlur: 10, shadowColor: 'rgba(0,0,0,0.4)' } },
    }],
    tooltip: { ...ECHART_THEME.tooltip, trigger: 'item', formatter: '{b}: {c} ({d}%)' },
  })
}

const getKwStyle = (i: number, v: number) => {
  const colors = ['#38bdf8','#71717a','#34d399','#fbbf24','#f87171']
  const maxV = hotKeywords.value[0]?.value ?? 1
  const size = 12 + Math.floor((v / maxV) * 10)
  return { color: colors[i % colors.length], fontSize: `${size}px`, opacity: 0.6 + (v / maxV) * 0.4 }
}

onMounted(loadData)
onUnmounted(() => { trendChart?.dispose(); docStatusChart?.dispose(); roleChart?.dispose() })
</script>

<style scoped>
.dashboard-page { display: flex; flex-direction: column; gap: 18px; overflow-y: auto; }

.dash-header {
  display: flex; align-items: flex-start; justify-content: space-between; gap: 16px;
}
.page-h2 { font-size: 22px; font-weight: 700; color: #e2e8f0; margin-bottom: 4px; }
.page-desc { font-size: 13px; color: #64748b; }
.header-right { display: flex; align-items: center; gap: 10px; flex-shrink: 0; }

/* 指标条 */
.metric-strip {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
}
.metric-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
  position: relative;
  overflow: hidden;
}
.mc-bar {
  position: absolute;
  bottom: 0; left: 0; right: 0;
  height: 2px;
  background: var(--mc);
  opacity: 0.5;
}
.mc-icon {
  width: 44px; height: 44px;
  border-radius: 11px;
  display: flex; align-items: center; justify-content: center;
  flex-shrink: 0;
}
.mc-body { flex: 1; }
.mc-val { font-size: 26px; font-weight: 800; font-family: 'JetBrains Mono', monospace; color: #e2e8f0; line-height: 1; }
.mc-label { font-size: 12px; color: #64748b; margin-top: 4px; }
.mc-trend { font-size: 11px; font-weight: 700; align-self: flex-start; }
.mc-trend.up   { color: #34d399; }
.mc-trend.down { color: #f87171; }

/* 图表行 */
.dash-body { display: flex; flex-direction: column; gap: 14px; }
.chart-row { display: flex; gap: 14px; }
.chart-card { flex: 1; }
.chart-card.wide  { flex: 2.5; }
.chart-card.narrow { flex: 1; }
.chart-box { height: 240px; }
.card-head { display: flex; align-items: center; justify-content: space-between; font-size: 14px; font-weight: 600; color: #e2e8f0; }

/* 排行榜 */
.rank-list { display: flex; flex-direction: column; gap: 8px; padding: 4px 0; }
.rank-item { display: flex; align-items: center; gap: 10px; }
.rank-no {
  width: 22px; height: 22px; border-radius: 6px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 700; color: #475569; flex-shrink: 0;
}
.rank-no.top { background: rgba(56,189,248,0.1); border-color: rgba(56,189,248,0.3); color: var(--primary); }
.rank-name { width: 180px; font-size: 12.5px; color: #94a3b8; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex-shrink: 0; }
.rank-bar-wrap { flex: 1; background: var(--bg-elevated); border-radius: 2px; height: 6px; overflow: hidden; }
.rank-bar { height: 100%; background: linear-gradient(90deg, #38bdf8, #71717a); border-radius: 2px; transition: width 0.8s cubic-bezier(0.34,1.56,0.64,1); }
.rank-val { width: 40px; text-align: right; font-size: 12px; color: #64748b; font-family: monospace; flex-shrink: 0; }

/* 质量统计 */
.quality-grid { display: flex; flex-direction: column; gap: 14px; padding: 4px 0; }
.quality-item { display: flex; flex-direction: column; gap: 7px; }
.quality-label { font-size: 12px; color: #64748b; font-weight: 600; }
.qd-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; margin-top: 4px; }
.qd-row.qd-row-3 { grid-template-columns: repeat(3, 1fr); }
.chart-empty { display: flex; align-items: center; justify-content: center; height: 200px; color: #475569; font-size: 13px; }
.qd-cell { text-align: center; background: var(--bg-elevated); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 10px 4px; }
.qd-val { font-size: 20px; font-weight: 800; font-family: 'JetBrains Mono', monospace; }
.qd-lbl { font-size: 10px; color: #475569; margin-top: 3px; }

/* 关键词云 */
.keyword-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 0;
  align-items: center;
}
.kw-tag {
  font-weight: 600;
  cursor: default;
  padding: 3px 10px;
  border-radius: 20px;
  background: rgba(255,255,255,0.03);
  border: 1px solid rgba(255,255,255,0.06);
  transition: var(--transition);
}
.kw-tag:hover { background: rgba(56,189,248,0.08); border-color: rgba(56,189,248,0.2); }
</style>
