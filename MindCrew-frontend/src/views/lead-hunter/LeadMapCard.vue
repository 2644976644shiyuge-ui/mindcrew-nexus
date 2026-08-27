<template>
  <div class="map-card cfg-card">
    <div class="map-head">
      <div class="map-title"><el-icon><Location /></el-icon><span>全球客户分布</span></div>
      <div class="map-sub">
        <span class="map-total">{{ scopeLabel }} <b>{{ totalCompanies }}</b> 家</span>
        <span class="map-tip">悬停地图查看国家明细</span>
      </div>
    </div>

    <!-- 大洲切换（4×2 等宽网格） -->
    <div class="continent-grid">
      <button v-for="c in CONTINENTS" :key="c.key" class="c-chip" :class="{ active: active === c.key }"
              @click="switchContinent(c.key)">{{ c.label }}</button>
    </div>

    <div ref="chartEl" class="map-chart"></div>

    <!-- Top 国家榜 -->
    <div class="top-list" v-if="topCountries.length">
      <div v-for="t in topCountries" :key="t.country" class="top-item">
        <span class="top-name">{{ t.country }}</span>
        <div class="top-bar"><div class="top-fill" :style="{ width: barWidth(t.count) }"></div></div>
        <span class="top-num">{{ t.count }}</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts'
import { Location } from '@element-plus/icons-vue'
import type { CountryStat } from '@/api/leadHunter'
// 合规世界地图（@surbowl/world-geo-json-zh：含中国南海十段线、藏南/黑瞎子岛国界修正，台湾归属正确）
import worldGeo from '@/assets/geo/world.geo.json'

const props = withDefaults(defineProps<{ stats: CountryStat[]; scopeLabel?: string }>(), {
  scopeLabel: '个人累计'
})

// ═══ 大洲视图（世界全图 + 七大洲聚焦） ═══
const CONTINENTS = [
  { key: 'world', label: '世界', center: undefined as [number, number] | undefined, zoom: 1 },
  { key: 'asia', label: '亚洲', center: [88, 40] as [number, number], zoom: 2.6 },
  { key: 'europe', label: '欧洲', center: [16, 56] as [number, number], zoom: 4.2 },
  { key: 'namerica', label: '北美洲', center: [-101, 47] as [number, number], zoom: 2.7 },
  { key: 'samerica', label: '南美洲', center: [-62, -16] as [number, number], zoom: 2.7 },
  { key: 'africa', label: '非洲', center: [20, 3] as [number, number], zoom: 2.6 },
  { key: 'oceania', label: '大洋洲', center: [142, -26] as [number, number], zoom: 3.2 },
  { key: 'antarctica', label: '南极洲', center: [15, -78] as [number, number], zoom: 2.2 }
]
const active = ref('world')

// ═══ 英文国家名 → 地图中文名（合规底图使用中文简称） ═══
const EN2CN: Record<string, string> = {
  'united states': '美国', 'usa': '美国',
  'united states - east': '美国', 'united states - south': '美国', 'united states - west': '美国',
  'canada': '加拿大', 'mexico': '墨西哥', 'brazil': '巴西',
  'argentina': '阿根廷', 'chile': '智利', 'colombia': '哥伦比亚', 'peru': '秘鲁',
  'united kingdom': '英国', 'uk': '英国', 'ireland': '爱尔兰', 'france': '法国', 'germany': '德国',
  'netherlands': '荷兰', 'belgium': '比利时', 'spain': '西班牙', 'portugal': '葡萄牙', 'italy': '意大利',
  'switzerland': '瑞士', 'austria': '奥地利', 'denmark': '丹麦', 'norway': '挪威', 'sweden': '瑞典',
  'finland': '芬兰', 'iceland': '冰岛', 'poland': '波兰', 'czech republic': '捷克', 'czechia': '捷克',
  'slovakia': '斯洛伐克', 'hungary': '匈牙利', 'romania': '罗马尼亚', 'bulgaria': '保加利亚',
  'greece': '希腊', 'croatia': '克罗地亚', 'serbia': '塞尔维亚', 'ukraine': '乌克兰',
  'russia': '俄罗斯', 'turkey': '土耳其', 'turkiye': '土耳其', 'israel': '以色列',
  'united arab emirates': '阿联酋', 'uae': '阿联酋', 'saudi arabia': '沙特阿拉伯', 'qatar': '卡塔尔',
  'kuwait': '科威特', 'oman': '阿曼', 'bahrain': '巴林', 'jordan': '约旦', 'lebanon': '黎巴嫩',
  'south africa': '南非', 'nigeria': '尼日利亚', 'kenya': '肯尼亚', 'egypt': '埃及',
  'morocco': '摩洛哥', 'algeria': '阿尔及利亚', 'tunisia': '突尼斯', 'ghana': '加纳',
  'australia': '澳大利亚', 'new zealand': '新西兰', 'fiji': '斐济',
  'japan': '日本', 'south korea': '韩国', 'korea': '韩国', 'singapore': '新加坡', 'india': '印度',
  'indonesia': '印度尼西亚', 'malaysia': '马来西亚', 'thailand': '泰国', 'vietnam': '越南',
  'philippines': '菲律宾', 'pakistan': '巴基斯坦', 'bangladesh': '孟加拉国', 'sri lanka': '斯里兰卡',
  'nepal': '尼泊尔', 'myanmar': '缅甸', 'cambodia': '柬埔寨'
}

// 中文名 → 英文（tooltip 反查展示）
const cnName = computed(() => {
  const m: Record<string, string> = {}
  for (const [en, cn] of Object.entries(EN2CN)) if (!m[cn]) m[cn] = en
  return m
})

const totalCompanies = computed(() => props.stats.reduce((s, x) => s + x.count, 0))
// 美国三大区（East/South/West）在展示层聚合为 United States
const topCountries = computed(() => {
  const merged = new Map<string, number>()
  for (const s of props.stats) {
    const key = /^united states( |-|$)/i.test(s.country) ? 'United States' : s.country
    merged.set(key, (merged.get(key) || 0) + s.count)
  }
  return [...merged.entries()]
    .map(([country, count]) => ({ country, count }))
    .sort((a, b) => b.count - a.count).slice(0, 6)
})
const maxCount = computed(() => Math.max(1, ...topCountries.value.map(s => s.count)))
function barWidth(n: number) {
  return Math.max(8, Math.round(n / maxCount.value * 100)) + '%'
}

// ═══ ECharts ═══
const chartEl = ref<HTMLElement>()
let chart: echarts.ECharts | null = null
let ro: ResizeObserver | null = null

function seriesData() {
  // 按地图中文名聚合求和（美国三大区会归并到「美国」）
  const agg = new Map<string, number>()
  for (const s of props.stats) {
    const cn = EN2CN[s.country.trim().toLowerCase()]
    if (cn) agg.set(cn, (agg.get(cn) || 0) + s.count)
  }
  return [...agg.entries()].map(([name, value]) => ({ name, value }))
}

function buildOption() {
  const cur = CONTINENTS.find(c => c.key === active.value)!
  const data = seriesData()
  return {
    tooltip: {
      trigger: 'item',
      backgroundColor: 'rgba(255,255,255,.96)',
      borderColor: 'rgba(0,0,0,.08)',
      textStyle: { color: '#1D1D1F', fontSize: 12 },
      formatter: (p: any) => {
        const en = cnName.value[p.name] || ''
        const cnt = p.value ?? 0
        const enTip = en && en !== p.name ? ` <span style="color:#6E6E73;font-size:11px">${en}</span>` : ''
        return cnt > 0
          ? `<b>${p.name}</b>${enTip}<br/><span style="color:#0071E3;font-weight:700">${cnt}</span> 家客户`
          : `<b>${p.name}</b>${enTip}<br/><span style="color:#AEAEB2">暂无线索</span>`
      }
    },
    visualMap: {
      type: 'continuous', min: 0, max: Math.max(3, maxCount.value),
      left: 10, bottom: 10, itemWidth: 10, itemHeight: 70,
      text: ['多', '少'], textStyle: { color: '#6E6E73', fontSize: 10 },
      inRange: { color: ['#CDE3FF', '#5AA9F5', '#0071E3'] },
      calculable: false
    },
    series: [{
      type: 'map', map: 'world', roam: true,
      center: cur.center, zoom: cur.zoom,
      scaleLimit: { min: 0.8, max: 12 },
      itemStyle: { borderColor: 'rgba(0,0,0,.18)', borderWidth: .5, areaColor: '#EDEDF0' },
      emphasis: {
        itemStyle: { areaColor: '#FF9F0A', borderColor: '#1D1D1F', borderWidth: 1 },
        label: { show: true, color: '#1D1D1F', fontSize: 11, fontWeight: 600 }
      },
      select: { disabled: true },
      label: { show: false },
      data
    }]
  }
}

function switchContinent(key: string) {
  active.value = key
  if (chart) chart.setOption(buildOption() as any)
}

function render() {
  if (!chart) return
  chart.setOption(buildOption() as any, true)
}

onMounted(() => {
  echarts.registerMap('world', worldGeo as any)
  if (chartEl.value) {
    chart = echarts.init(chartEl.value)
    render()
    ro = new ResizeObserver(() => chart?.resize())
    ro.observe(chartEl.value)
  }
})

watch(() => props.stats, render, { deep: true })
onBeforeUnmount(() => {
  ro?.disconnect()
  chart?.dispose()
})
</script>

<style scoped>
.map-card { padding: 20px 22px; }

/* 标题区：标题一行 + 数据/提示一行 */
.map-head { margin-bottom: 14px; }
.map-title { display: flex; align-items: center; gap: 7px; font-size: 14px; font-weight: 600; color: #1D1D1F; }
.map-title .el-icon { color: #0071E3; }
.map-sub { display: flex; justify-content: space-between; align-items: center; margin-top: 4px; padding-left: 24px; }
.map-total { font-size: 12px; color: #6E6E73; }
.map-total b { color: #0071E3; font-weight: 700; font-family: 'SF Mono', Menlo, monospace; }
.map-tip { font-size: 11px; color: #AEAEB2; }

/* 4×2 等宽大洲网格 */
.continent-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 6px; margin-bottom: 14px; }
.c-chip { padding: 7px 0; width: 100%; text-align: center; border-radius: 10px; border: 1px solid rgba(0,0,0,.08); background: #fff; font-size: 12.5px; color: #6E6E73; cursor: pointer; transition: all .18s; white-space: nowrap; }
.c-chip:hover { border-color: rgba(0,113,227,.35); color: #1D1D1F; background: #F7FBFF; }
.c-chip.active { background: linear-gradient(135deg, #0071E3, #1F8FFF); border-color: #0071E3; color: #fff; font-weight: 600; box-shadow: 0 2px 6px rgba(0,113,227,.25); }
.map-chart { width: 100%; height: 420px; border-radius: 14px; overflow: hidden; }

@media (max-width: 360px) {
  .continent-grid { grid-template-columns: repeat(2, 1fr); }
}

.top-list { margin-top: 14px; display: flex; flex-direction: column; gap: 8px; }
.top-item { display: flex; align-items: center; gap: 10px; font-size: 12.5px; }
.top-name { width: 118px; flex-shrink: 0; font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.top-bar { flex: 1; height: 8px; background: #F2F2F7; border-radius: 980px; overflow: hidden; }
.top-fill { height: 100%; border-radius: 980px; background: linear-gradient(90deg, #5AA9F5, #0071E3); transition: width .5s var(--ease, ease); }
.top-num { width: 34px; text-align: right; font-weight: 700; color: #0071E3; font-family: 'SF Mono', Menlo, monospace; }
</style>
