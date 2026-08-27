<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { marked } from 'marked'
import { ElMessage } from 'element-plus'
import { dataSourceApi } from '@/api/datasource'

echarts.use([BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])

interface DbResult {
  datasourceName?: string
  question?: string
  sql?: string
  columns?: string[]
  rows?: any[][]
  rowCount?: number
  chartType?: string
  chartXField?: string | null
  chartYField?: string | null
}

const props = defineProps<{ result: DbResult }>()

// ── 二次分析:数据解读/归因/预测 ──
const analyzing = ref('')                 // 当前进行中的类型,''=空闲
const analysisType = ref('')              // 已出结果的类型
const analysisHtml = ref('')
const ANALYSIS_LABELS: Record<string, string> = {
  interpret: '数据解读', attribution: '归因分析', forecast: '智能预测',
}
async function runAnalyze(type: string) {
  if (analyzing.value) return
  analyzing.value = type
  try {
    const res: any = await dataSourceApi.analyze({
      type,
      question: props.result.question,
      datasourceName: props.result.datasourceName,
      sql: props.result.sql,
      columns: props.result.columns,
      rows: props.result.rows,
    })
    const text = res?.data ?? res ?? ''
    analysisType.value = type
    analysisHtml.value = marked.parse(String(text)) as string
  } catch (e: any) {
    ElMessage.error('分析失败：' + (e?.message ?? e))
  } finally {
    analyzing.value = ''
  }
}

const columns = computed<string[]>(() => props.result.columns ?? [])
const rows = computed<any[][]>(() => props.result.rows ?? [])
const showTable = ref(true)
const showSql = ref(false)

// 手动切换的图表类型（默认用后端给的；用户可在卡片上改）
const manualType = ref<string | null>(null)
const chartType = computed(() => manualType.value ?? props.result.chartType ?? 'none')
const canChart = computed(() => rows.value.length > 0 && columns.value.length >= 1)
const hasChart = computed(() => ['bar', 'line', 'pie'].includes(chartType.value) && canChart.value)

function switchType(t: string) {
  manualType.value = t
  nextTick(renderChart)
}

// 计算 x / y 列索引（带兜底：x=第0列，y=第1列）
const xIdx = computed(() => {
  const f = props.result.chartXField
  const i = f ? columns.value.indexOf(f) : -1
  return i >= 0 ? i : 0
})
const yIdx = computed(() => {
  const f = props.result.chartYField
  const i = f ? columns.value.indexOf(f) : -1
  if (i >= 0) return i
  return columns.value.length > 1 ? 1 : 0
})

const chartRef = ref<HTMLDivElement | null>(null)
let chart: echarts.ECharts | null = null

function buildOption(): Record<string, any> {
  const cols = columns.value
  const data = rows.value
  const xi = xIdx.value
  const yi = yIdx.value
  const labels = data.map(r => String(r[xi] ?? ''))
  const values = data.map(r => {
    const v = r[yi]
    const n = typeof v === 'number' ? v : parseFloat(String(v ?? ''))
    return Number.isFinite(n) ? n : 0
  })
  const yName = cols[yi] ?? ''
  const xName = cols[xi] ?? ''

  if (chartType.value === 'pie') {
    return {
      tooltip: { trigger: 'item' },
      legend: { type: 'scroll', bottom: 0 },
      series: [{
        type: 'pie', radius: ['35%', '65%'],
        data: labels.map((name, i) => ({ name, value: values[i] ?? 0 })),
      }],
    }
  }
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: 40, right: 16, top: 24, bottom: 48, containLabel: true },
    xAxis: { type: 'category', data: labels, name: xName, axisLabel: { interval: 0, rotate: labels.length > 6 ? 35 : 0 } },
    yAxis: { type: 'value', name: yName },
    series: [{ type: chartType.value === 'line' ? 'line' : 'bar', data: values, smooth: true, barMaxWidth: 48 }],
  }
}

function renderChart() {
  if (!hasChart.value || !chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value, undefined, { renderer: 'canvas' })
  chart.setOption(buildOption(), true)
}

function onResize() { chart?.resize() }

// 下载当前图表为 PNG
function downloadPng() {
  if (!chart) return
  const url = chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#fff' })
  const a = document.createElement('a')
  a.href = url
  a.download = `${props.result.datasourceName || 'chart'}-${Date.now()}.png`
  a.click()
}

// 下载查询结果为 CSV（带 BOM，Excel 中文不乱码）
function downloadCsv() {
  const esc = (v: any) => {
    const s = v == null ? '' : String(v)
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s
  }
  const lines = [columns.value.map(esc).join(',')]
  for (const r of rows.value) lines.push(r.map(esc).join(','))
  const blob = new Blob(['﻿' + lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `${props.result.datasourceName || 'data'}-${Date.now()}.csv`
  a.click()
  URL.revokeObjectURL(a.href)
}

onMounted(() => {
  nextTick(renderChart)
  window.addEventListener('resize', onResize)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  chart?.dispose()
  chart = null
})
watch(() => props.result, () => nextTick(renderChart), { deep: true })
</script>

<template>
  <div class="db-card">
    <div class="db-head">
      <el-icon class="db-ico"><Histogram /></el-icon>
      <span class="db-title">{{ result.datasourceName || '数据库' }} · 查询结果</span>
      <span class="db-count">{{ result.rowCount ?? rows.length }} 行</span>
      <div class="db-spacer" />
      <!-- 图表类型切换 -->
      <template v-if="canChart">
        <button class="db-toggle" :class="{ on: chartType === 'bar' }" @click="switchType('bar')">柱状</button>
        <button class="db-toggle" :class="{ on: chartType === 'line' }" @click="switchType('line')">折线</button>
        <button class="db-toggle" :class="{ on: chartType === 'pie' }" @click="switchType('pie')">饼图</button>
      </template>
      <button v-if="hasChart" class="db-toggle" @click="downloadPng">下载图片</button>
      <button v-if="rows.length" class="db-toggle" @click="downloadCsv">下载CSV</button>
      <button class="db-toggle" @click="showTable = !showTable">{{ showTable ? '隐藏表格' : '显示表格' }}</button>
      <button v-if="result.sql" class="db-toggle" @click="showSql = !showSql">SQL</button>
    </div>

    <div v-if="hasChart" ref="chartRef" class="db-chart"></div>

    <div v-if="showSql && result.sql" class="db-sql"><code>{{ result.sql }}</code></div>

    <div v-if="showTable" class="db-table-wrap">
      <table class="db-table">
        <thead>
          <tr><th v-for="(c, ci) in columns" :key="ci">{{ c }}</th></tr>
        </thead>
        <tbody>
          <tr v-for="(row, ri) in rows.slice(0, 100)" :key="ri">
            <td v-for="(cell, ci) in row" :key="ci">{{ cell == null ? '' : cell }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="rows.length > 100" class="db-more">仅显示前 100 行，共 {{ rows.length }} 行</div>
    </div>

    <!-- 二次分析：数据解读 / 归因分析 / 智能预测 -->
    <div v-if="rows.length" class="db-analyze">
      <button class="db-an-btn" :disabled="!!analyzing" @click="runAnalyze('interpret')">
        {{ analyzing === 'interpret' ? '解读中…' : '数据解读' }}
      </button>
      <button class="db-an-btn" :disabled="!!analyzing" @click="runAnalyze('attribution')">
        {{ analyzing === 'attribution' ? '分析中…' : '归因分析' }}
      </button>
      <button class="db-an-btn" :disabled="!!analyzing" @click="runAnalyze('forecast')">
        {{ analyzing === 'forecast' ? '预测中…' : '智能预测' }}
      </button>
    </div>
    <div v-if="analysisHtml" class="db-analysis">
      <div class="db-analysis-title">{{ ANALYSIS_LABELS[analysisType] || '分析结果' }}</div>
      <div class="md-body" v-html="analysisHtml"></div>
    </div>
  </div>
</template>

<style scoped>
.db-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 10px 12px;
  margin: 10px 0;
  background: var(--el-fill-color-blank);
}
.db-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.db-ico { color: var(--el-color-primary); }
.db-title { font-weight: 600; font-size: 13px; }
.db-count { font-size: 12px; color: var(--el-text-color-secondary); background: var(--el-fill-color); padding: 1px 8px; border-radius: 10px; }
.db-spacer { flex: 1; }
.db-toggle { border: none; background: transparent; color: var(--el-color-primary); cursor: pointer; font-size: 12px; padding: 1px 6px; border-radius: 4px; }
.db-toggle.on { background: var(--el-color-primary); color: #fff; }
.db-chart { width: 100%; height: 280px; }
.db-sql { background: var(--el-fill-color-light); border-radius: 6px; padding: 8px 10px; margin: 6px 0; overflow-x: auto; }
.db-sql code { font-size: 12px; color: var(--el-text-color-regular); white-space: pre-wrap; word-break: break-all; }
.db-table-wrap { max-height: 320px; overflow: auto; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.db-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.db-table th, .db-table td { border-bottom: 1px solid var(--el-border-color-lighter); padding: 6px 10px; text-align: left; white-space: nowrap; }
.db-table th { background: var(--el-fill-color-light); position: sticky; top: 0; font-weight: 600; }
.db-more { padding: 6px 10px; font-size: 12px; color: var(--el-text-color-secondary); }
.db-analyze { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; }
.db-an-btn {
  border: 1px solid var(--el-color-primary); background: var(--el-color-primary-light-9);
  color: var(--el-color-primary); border-radius: 6px; padding: 4px 12px; font-size: 12px; cursor: pointer;
}
.db-an-btn:disabled { opacity: .6; cursor: default; }
.db-analysis { margin-top: 10px; padding: 10px 12px; border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px; background: var(--el-fill-color-light); }
.db-analysis-title { font-weight: 600; font-size: 13px; margin-bottom: 6px; color: var(--el-color-primary); }
.db-analysis :deep(.md-body) { font-size: 13px; line-height: 1.7; color: var(--el-text-color-primary); }
.db-analysis :deep(.md-body h1),
.db-analysis :deep(.md-body h2),
.db-analysis :deep(.md-body h3) { font-size: 14px; margin: 8px 0 4px; }
.db-analysis :deep(.md-body p) { margin: 4px 0; }
.db-analysis :deep(.md-body ul),
.db-analysis :deep(.md-body ol) { margin: 4px 0; padding-left: 20px; }
.db-analysis :deep(.md-body table) { border-collapse: collapse; margin: 6px 0; }
.db-analysis :deep(.md-body th),
.db-analysis :deep(.md-body td) { border: 1px solid var(--el-border-color-lighter); padding: 4px 8px; font-size: 12px; }
</style>
