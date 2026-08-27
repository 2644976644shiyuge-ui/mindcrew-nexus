<template>
  <div class="mom-page">
    <!-- ═══ 顶部全局概览 ═══ -->
    <div class="mom-head">
      <div class="eyebrow"><span class="pulse"></span>MARKET OPPORTUNITY MAP · 市场机会情报驾驶舱</div>
      <h1 class="mom-title">市场机会地图</h1>
      <p class="mom-sub">基于全球客户数据 + 竞品安装数据 + 行业分布的 AI 机会指数 · 点击地图任意国家查看情报面板</p>
    </div>

    <!-- 顶部 KPI 条 -->
    <div class="kpi-row" v-loading="loading">
      <div class="kpi-card"><b>{{ overview.length }}</b><span>覆盖国家/地区</span></div>
      <div class="kpi-card"><b>{{ totalCompanies }}</b><span>全球客户样本</span></div>
      <div class="kpi-card hi" v-if="topMarket"><b>{{ topMarket.score }}</b><span>最高机会指数 · {{ topMarket.country }}</span></div>
      <div class="kpi-card warn"><b>{{ totalCompetitors }}</b><span>竞品安装客户（替换机会）</span></div>
      <div class="kpi-card s-grade" v-if="sMarkets.length"><b>{{ sMarkets.length }}</b><span>S 级战略市场</span></div>
    </div>

    <!-- 图层切换 -->
    <div class="layer-row">
      <span class="layer-label">图层：</span>
      <button v-for="l in LAYERS" :key="l.key" class="layer-chip" :class="{ active: layer === l.key }"
              @click="switchLayer(l.key)">{{ l.label }}</button>
      <span class="layer-hint">· 评分图例：</span>
      <span class="legend-item c">C &lt;55</span>
      <span class="legend-item b">B 55-69</span>
      <span class="legend-item a">A 70-84</span>
      <span class="legend-item s">S 85+</span>
    </div>

    <!-- 主体：左地图 + 右情报面板 -->
    <div class="mom-body">
      <div class="map-wrap cfg-card">
        <div ref="chartEl" class="map-chart"></div>
        <div class="map-foot" v-if="selectedCountry">
          已选：<b>{{ selectedCountry }}</b>
          <span class="grade-tag" :class="selectedDetail?.grade">{{ selectedDetail?.grade }} 级</span>
          <span class="score-tag">机会指数 {{ selectedDetail?.score ?? '-' }}/100</span>
        </div>
        <div class="map-foot" v-else>提示：点击地图上的国家查看详细情报</div>
      </div>

      <!-- 右侧情报面板 -->
      <aside class="intel-panel cfg-card" v-loading="loadingDetail">
        <template v-if="selectedDetail">
          <div class="intel-head">
            <div>
              <div class="intel-country">{{ selectedDetail.country }}</div>
              <div class="intel-meta">
                机会指数 <b>{{ selectedDetail.score }}</b>/100 ·
                <span class="grade-tag" :class="selectedDetail.grade">{{ gradeText(selectedDetail.grade) }}市场</span>
              </div>
            </div>
          </div>

          <div class="intel-sec">
            <div class="sec-title">市场画像</div>
            <div class="stat-grid">
              <div class="stat"><b>{{ selectedDetail.companyCount }}</b><span>客户样本</span></div>
              <div class="stat" v-for="(v,k) in selectedDetail.customerTypes" :key="k"><b>{{ v }}</b><span>{{ k }}</span></div>
            </div>
          </div>

          <div class="intel-sec" v-if="selectedDetail.topIndustries.length">
            <div class="sec-title">热门行业 Top {{ selectedDetail.topIndustries.length }}</div>
            <div class="ind-row" v-for="ind in selectedDetail.topIndustries" :key="ind.name">
              <span class="ind-name">{{ ind.name }}</span>
              <span class="ind-stars">{{ '★'.repeat(ind.stars) }}<span class="dim">{{ '★'.repeat(5 - ind.stars) }}</span></span>
              <span class="ind-count">{{ ind.count }} 家</span>
            </div>
          </div>

          <div class="intel-sec" v-if="selectedDetail.competitorTotal > 0">
            <div class="sec-title">主要竞争品牌（替换机会）</div>
            <div class="comp-row" v-for="(v,k) in selectedDetail.competitors" :key="k">
              <span class="comp-name">{{ k }}</span>
              <div class="comp-bar"><div class="comp-fill" :style="{ width: compWidth(v) }"></div></div>
              <span class="comp-num">{{ v }} 家</span>
            </div>
            <div class="ai-judge">AI 判断：该区域存在明显 IP Audio 替换机会</div>
          </div>
          <div class="intel-sec" v-else>
            <div class="sec-title">竞争品牌</div>
            <div class="empty-hint">暂未检测到竞品安装客户</div>
          </div>

          <div class="intel-sec" v-if="selectedDetail.recommendedProducts.length">
            <div class="sec-title">推荐产品（知识图谱匹配）</div>
            <div class="prod-row" v-for="(p,i) in selectedDetail.recommendedProducts" :key="p">
              <span class="prod-idx">{{ i+1 }}</span>
              <span class="prod-name">{{ p }}</span>
              <span class="prod-match">{{ [95,89,86][i] || 80 }}% 匹配</span>
            </div>
          </div>

          <div class="intel-sec" v-if="actions.length">
            <div class="sec-title">AI 行动建议</div>
            <div class="action-item" v-for="(a,i) in actions" :key="i">
              <span class="action-idx">{{ i+1 }}</span>{{ a }}
            </div>
          </div>

          <button class="launch-btn" @click="launchLeadHunter">
            <el-icon><Promotion /></el-icon>启动市场开发（接入全球获客）
          </button>
        </template>
        <div v-else class="intel-empty">
          <el-icon :size="32"><Aim /></el-icon>
          <p>点击地图上的国家</p>
          <p class="dim">查看该市场的 AI 情报分析</p>
        </div>
      </aside>
    </div>

    <!-- 排行榜 -->
    <div class="rank-card cfg-card" v-if="overview.length">
      <div class="cfg-label"><el-icon><Trophy /></el-icon>全球市场机会排行</div>
      <div class="rank-list">
        <div v-for="(op,i) in overview.slice(0,10)" :key="op.country" class="rank-item"
             @click="selectCountry(op.country)">
          <span class="rank-no">{{ i+1 }}</span>
          <span class="rank-country">{{ op.country }}</span>
          <div class="rank-bar"><div class="rank-fill" :class="op.grade" :style="{ width: op.score + '%' }"></div></div>
          <span class="rank-score">{{ op.score }}</span>
          <span class="rank-grade" :class="op.grade">{{ op.grade }}</span>
          <span class="rank-count">{{ op.companyCount }} 家</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { Promotion, Aim, Trophy } from '@element-plus/icons-vue'
import { marketOpportunityApi, type CountryOpportunity } from '@/api/marketOpportunity'
// 合规世界地图（含中国南海十段线、台湾归属正确）
import worldGeo from '@/assets/geo/world.geo.json'

const router = useRouter()

const LAYERS = [
  { key: 'opportunity', label: '机会指数' },
  { key: 'customer', label: '客户分布' },
  { key: 'competitor', label: '竞品分布' }
]
const layer = ref<'opportunity' | 'customer' | 'competitor'>('opportunity')

const overview = ref<CountryOpportunity[]>([])
const loading = ref(false)
const selectedCountry = ref('')
const selectedDetail = ref<CountryOpportunity | null>(null)
const actions = ref<string[]>([])
const loadingDetail = ref(false)

const totalCompanies = computed(() => overview.value.reduce((s, o) => s + o.companyCount, 0))
const totalCompetitors = computed(() => overview.value.reduce((s, o) => s + o.competitorTotal, 0))
const topMarket = computed(() => overview.value[0])
const sMarkets = computed(() => overview.value.filter(o => o.grade === 'S'))

// EN→CN 国家名映射（与地图底图中文简称对齐）
const EN2CN: Record<string, string> = {
  'united states': '美国', 'united states - east': '美国', 'united states - south': '美国', 'united states - west': '美国',
  'canada': '加拿大', 'mexico': '墨西哥', 'brazil': '巴西', 'argentina': '阿根廷', 'chile': '智利',
  'united kingdom': '英国', 'uk': '英国', 'germany': '德国', 'france': '法国', 'netherlands': '荷兰',
  'spain': '西班牙', 'italy': '意大利', 'sweden': '瑞典', 'poland': '波兰', 'switzerland': '瑞士',
  'uae': '阿联酋', 'united arab emirates': '阿联酋', 'saudi arabia': '沙特阿拉伯', 'turkey': '土耳其',
  'israel': '以色列', 'south africa': '南非',
  'australia': '澳大利亚', 'new zealand': '新西兰',
  'japan': '日本', 'south korea': '韩国', 'singapore': '新加坡', 'india': '印度', 'indonesia': '印度尼西亚',
  'malaysia': '马来西亚', 'thailand': '泰国', 'vietnam': '越南', 'philippines': '菲律宾'
}
const cnToEn: Record<string, string> = (() => {
  const m: Record<string, string> = {}
  for (const [en, cn] of Object.entries(EN2CN)) if (!m[cn]) m[cn] = en
  return m
})()

function gradeText(g: string) {
  return g === 'S' ? 'S 级战略' : g === 'A' ? 'A 级重点' : g === 'B' ? 'B 级潜力' : 'C 级观察'
}
function compWidth(v: number) {
  const max = Math.max(1, ...Object.values(selectedDetail.value?.competitors || { _: 1 }))
  return Math.max(8, Math.round(v / max * 100)) + '%'
}

// ═══ ECharts ═══
const chartEl = ref<HTMLElement>()
let chart: echarts.ECharts | null = null
let ro: ResizeObserver | null = null

function scoreColor(score: number) {
  if (score >= 85) return '#0071E3'   // S 高亮深蓝
  if (score >= 70) return '#3B82C4'   // A 中蓝
  if (score >= 55) return '#7BAFD4'   // B 浅蓝
  if (score > 0)  return '#C7D6E5'    // C 极浅蓝（有数据，区别于无数据灰色）
  return '#EDEDF0'                    // 无数据 灰
}
function gradeOpacity(score: number) { return score > 0 ? 1 : 0.35 }

function seriesData() {
  // 按中文名聚合（美国三大区合并）
  const agg = new Map<string, { score: number; count: number; comp: number }>()
  for (const op of overview.value) {
    const cn = EN2CN[op.country.trim().toLowerCase()]
    if (!cn) continue
    const cur = agg.get(cn) || { score: 0, count: 0, comp: 0 }
    cur.score = Math.max(cur.score, op.score) // 取最高分
    cur.count += op.companyCount
    cur.comp += op.competitorTotal
    agg.set(cn, cur)
  }
  return agg
}

function buildOption() {
  const agg = seriesData()
  const data: { name: string; value: number; itemStyle?: any }[] = []
  for (const [cn, v] of agg) {
    const metric = layer.value === 'competitor' ? v.comp : layer.value === 'customer' ? v.count : v.score
    data.push({
      name: cn,
      value: metric,
      itemStyle: {
        areaColor: scoreColor(v.score),
        opacity: layer.value === 'opportunity' ? gradeOpacity(v.score) : 0.9
      }
    })
  }
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(255,255,255,.96)',
      borderColor: 'rgba(0,0,0,.08)',
      textStyle: { color: '#1D1D1F', fontSize: 12 },
      formatter: (p: any) => {
        const v = agg.get(p.name)
        if (!v) return `<b>${p.name}</b><br/><span style="color:#AEAEB2">暂无数据</span>`
        const en = cnToEn[p.name] || ''
        return `<b>${p.name}</b> <span style="color:#6E6E73;font-size:11px">${en}</span><br/>` +
               `机会指数：<b style="color:#0071E3">${v.score}</b>/100<br/>` +
               `客户样本：${v.count} 家<br/>` +
               `竞品安装：${v.comp} 家`
      }
    },
    series: [{
      type: 'map', map: 'world', roam: false,
      center: undefined, zoom: 1.2,
      itemStyle: { borderColor: 'rgba(0,0,0,.18)', borderWidth: .5, areaColor: '#EDEDF0' },
      emphasis: {
        itemStyle: { areaColor: '#FF9F0A', borderColor: '#1D1D1F', borderWidth: 1.2 },
        label: { show: true, color: '#1D1D1F', fontSize: 11, fontWeight: 600 }
      },
      select: { itemStyle: { areaColor: '#FF9F0A' }, label: { show: true } },
      label: { show: false },
      data
    }]
  }
}

function renderMap() {
  if (!chart) return
  chart.setOption(buildOption() as any, true)
}

function switchLayer(k: string) {
  layer.value = k as 'opportunity' | 'customer' | 'competitor'
  renderMap()
}

async function selectCountry(country: string) {
  selectedCountry.value = country
  loadingDetail.value = true
  try {
    const [detail, act] = await Promise.all([
      marketOpportunityApi.detail(country),
      marketOpportunityApi.actions(country)
    ])
    selectedDetail.value = detail
    actions.value = act
  } catch (e: any) {
    selectedDetail.value = null
  } finally {
    loadingDetail.value = false
  }
}

function launchLeadHunter() {
  if (!selectedCountry.value) return
  router.push({ path: '/lead-hunter', query: { country: selectedCountry.value } })
}

async function loadOverview() {
  loading.value = true
  try {
    overview.value = await marketOpportunityApi.overview()
    renderMap()
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  echarts.registerMap('world', worldGeo as any)
  if (chartEl.value) {
    chart = echarts.init(chartEl.value)
    chart.on('click', (params: any) => {
      const cn = params.name
      const en = cnToEn[cn]
      if (en) {
        // 找到对应的原始国家名（可能多个 US 区域，取第一个）
        const op = overview.value.find(o => EN2CN[o.country.trim().toLowerCase()] === cn)
        if (op) selectCountry(op.country)
      }
    })
    ro = new ResizeObserver(() => chart?.resize())
    ro.observe(chartEl.value)
  }
  loadOverview()
})

watch(overview, renderMap, { deep: true })
onBeforeUnmount(() => {
  ro?.disconnect()
  chart?.dispose()
})
</script>

<style scoped>
.mom-page {
  --bg: #F5F5F7; --card: #FFFFFF; --hair: rgba(0, 0, 0, .08);
  --ink: #1D1D1F; --ink2: #6E6E73; --ink3: #AEAEB2;
  --accent: #0071E3; --green: #34C759; --warn: #FF9F0A; --red: #FF3B30;
  height: 100%; overflow-y: auto; overflow-x: hidden;
  background: var(--bg); color: var(--ink);
  padding: 28px clamp(16px, 4vw, 48px) 48px;
}
.mom-head { margin-bottom: 18px; }
.eyebrow { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--ink2); letter-spacing: .12em; text-transform: uppercase; padding: 6px 16px; border: 1px solid var(--hair); border-radius: 980px; background: rgba(255,255,255,.6); }
.pulse { width: 6px; height: 6px; border-radius: 50%; background: var(--green); animation: pulse 2s ease-in-out infinite; }
@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.3} }
.mom-title { font-size: 34px; font-weight: 800; letter-spacing: -.02em; margin: 16px 0 6px; }
.mom-sub { color: var(--ink2); font-size: 14px; line-height: 1.6; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.kpi-row { display: flex; gap: 12px; margin: 20px 0 16px; flex-wrap: wrap; }
.kpi-card { flex: 1; min-width: 150px; background: var(--card); border: 1px solid var(--hair); border-radius: 16px; padding: 16px 20px; }
.kpi-card b { display: block; font-size: 28px; font-weight: 800; color: var(--ink); }
.kpi-card span { font-size: 12px; color: var(--ink2); }
.kpi-card.hi b { color: var(--accent); }
.kpi-card.warn b { color: var(--warn); }
.kpi-card.s-grade b { color: #AF52DE; }

.layer-row { display: flex; align-items: center; gap: 8px; margin-bottom: 14px; flex-wrap: wrap; }
.layer-label { font-size: 13px; font-weight: 600; color: var(--ink); }
.layer-chip { padding: 6px 14px; border-radius: 980px; border: 1px solid var(--hair); background: #fff; font-size: 12.5px; color: var(--ink2); cursor: pointer; transition: all .18s; }
.layer-chip:hover { border-color: rgba(0,113,227,.35); color: var(--ink); }
.layer-chip.active { background: var(--accent); border-color: var(--accent); color: #fff; font-weight: 600; }
.layer-hint { font-size: 11px; color: var(--ink3); margin-left: 8px; }
.legend-item { font-size: 11px; padding: 2px 8px; border-radius: 6px; margin-right: 4px; }
.legend-item.c { background: #C7D6E5; color: #4A6B8A; }
.legend-item.b { background: #7BAFD4; color: #fff; }
.legend-item.a { background: #3B82C4; color: #fff; }
.legend-item.s { background: #0071E3; color: #fff; }

.mom-body { display: flex; gap: 18px; align-items: flex-start; }
.map-wrap { flex: 1; min-width: 0; padding: 18px; }
.map-chart { width: 100%; height: 520px; border-radius: 14px; overflow: hidden; }
.map-foot { margin-top: 12px; font-size: 13px; color: var(--ink2); display: flex; align-items: center; gap: 10px; }
.map-foot b { color: var(--ink); }
.grade-tag { font-size: 11px; padding: 2px 10px; border-radius: 980px; font-weight: 600; }
.grade-tag.S { background: #F3E8FF; color: #AF52DE; }
.grade-tag.A { background: #EBF3FF; color: var(--accent); }
.grade-tag.B { background: #FFF1E0; color: #B26A00; }
.grade-tag.C { background: #F2F2F7; color: var(--ink2); }
.score-tag { font-size: 12px; color: var(--accent); font-weight: 600; }

.intel-panel { width: 380px; flex-shrink: 0; padding: 22px; max-height: 620px; overflow-y: auto; }
.intel-empty { height: 400px; display: flex; flex-direction: column; align-items: center; justify-content: center; color: var(--ink3); gap: 8px; }
.intel-empty .dim { font-size: 12px; }
.intel-head { padding-bottom: 14px; border-bottom: 1px solid var(--hair); margin-bottom: 16px; }
.intel-country { font-size: 22px; font-weight: 800; }
.intel-meta { font-size: 13px; color: var(--ink2); margin-top: 4px; }
.intel-meta b { color: var(--accent); font-size: 16px; }
.intel-sec { margin-bottom: 18px; }
.sec-title { font-size: 12px; font-weight: 700; color: var(--ink2); text-transform: uppercase; letter-spacing: .06em; margin-bottom: 10px; }
.stat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.stat { background: #F5F5F7; border-radius: 10px; padding: 10px; text-align: center; }
.stat b { display: block; font-size: 20px; font-weight: 800; color: var(--ink); }
.stat span { font-size: 10px; color: var(--ink2); }
.ind-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; font-size: 13px; }
.ind-name { flex: 1; text-transform: capitalize; }
.ind-stars { color: var(--warn); font-size: 12px; }
.ind-stars .dim { color: var(--ink3); }
.ind-count { font-size: 11px; color: var(--ink2); }
.comp-row { display: flex; align-items: center; gap: 10px; padding: 5px 0; font-size: 12.5px; }
.comp-name { width: 80px; flex-shrink: 0; font-weight: 550; }
.comp-bar { flex: 1; height: 6px; background: #F2F2F7; border-radius: 980px; overflow: hidden; }
.comp-fill { height: 100%; border-radius: 980px; background: linear-gradient(90deg, #FF9F0A, #FF3B30); }
.comp-num { width: 50px; text-align: right; font-weight: 600; color: var(--warn); }
.ai-judge { margin-top: 8px; font-size: 12px; color: var(--accent); font-style: italic; padding: 8px 12px; background: #F7FBFF; border-radius: 10px; border-left: 3px solid var(--accent); }
.empty-hint { font-size: 12px; color: var(--ink3); padding: 12px 0; }
.prod-row { display: flex; align-items: center; gap: 10px; padding: 8px 0; font-size: 13px; }
.prod-idx { width: 20px; height: 20px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 11px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.prod-name { flex: 1; font-weight: 550; }
.prod-match { font-size: 11px; color: var(--green); font-weight: 600; }
.action-item { display: flex; align-items: flex-start; gap: 10px; padding: 8px 0; font-size: 13px; line-height: 1.5; }
.action-idx { width: 18px; height: 18px; border-radius: 50%; background: #1D1D1F; color: #fff; font-size: 10px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 1px; }
.launch-btn { width: 100%; margin-top: 14px; padding: 14px; border-radius: 980px; background: linear-gradient(135deg, #0071E3, #1F8FFF); color: #fff; border: 0; font-size: 14px; font-weight: 600; cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px; transition: all .2s; }
.launch-btn:hover { transform: translateY(-1px); box-shadow: 0 6px 18px rgba(0,113,227,.35); }

.rank-card { margin-top: 18px; padding: 22px; }
.rank-list { display: flex; flex-direction: column; }
.rank-item { display: flex; align-items: center; gap: 14px; padding: 12px 8px; border-bottom: 1px solid rgba(0,0,0,.04); cursor: pointer; transition: background .15s; font-size: 13px; }
.rank-item:hover { background: rgba(0,113,227,.04); }
.rank-item:last-child { border-bottom: 0; }
.rank-no { width: 24px; font-weight: 800; color: var(--ink3); font-family: 'SF Mono', Menlo, monospace; }
.rank-country { width: 180px; font-weight: 600; }
.rank-bar { flex: 1; height: 8px; background: #F2F2F7; border-radius: 980px; overflow: hidden; }
.rank-fill { height: 100%; border-radius: 980px; }
.rank-fill.S { background: linear-gradient(90deg, #AF52DE, #0071E3); }
.rank-fill.A { background: var(--accent); }
.rank-fill.B { background: #5AA9F5; }
.rank-fill.C { background: var(--ink3); }
.rank-score { width: 34px; text-align: right; font-weight: 700; font-family: 'SF Mono', Menlo, monospace; }
.rank-grade { width: 24px; text-align: center; font-size: 11px; font-weight: 700; padding: 2px 6px; border-radius: 6px; }
.rank-grade.S { background: #F3E8FF; color: #AF52DE; }
.rank-grade.A { background: #EBF3FF; color: var(--accent); }
.rank-grade.B { background: #FFF1E0; color: #B26A00; }
.rank-grade.C { background: #F2F2F7; color: var(--ink2); }
.rank-count { width: 70px; text-align: right; color: var(--ink2); font-size: 12px; }

.cfg-card { background: var(--card); border: 1px solid var(--hair); border-radius: 20px; box-shadow: 0 1px 3px rgba(0,0,0,.04); }
.cfg-label { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; margin-bottom: 14px; color: var(--ink); }
.cfg-label .el-icon { color: var(--accent); }

@media (max-width: 1100px) {
  .mom-body { flex-direction: column; }
  .intel-panel { width: 100%; max-height: none; }
}
</style>
