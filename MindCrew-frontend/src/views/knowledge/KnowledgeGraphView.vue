<template>
  <div class="kg-page">
    <!-- 顶部条 -->
    <header class="kg-topbar">
      <div class="kg-title">
        <span class="kg-kbd">KNOWLEDGE GRAPH</span>
        <span class="kg-sub">知识图谱</span>
      </div>

      <div class="kg-controls">
        <el-select
          v-model="collectionId"
          placeholder="选择知识库"
          size="default"
          filterable
          class="kg-select"
          @change="onCollectionChange"
        >
          <el-option
            v-for="c in collections"
            :key="c.id"
            :label="c.name"
            :value="c.id"
          />
        </el-select>

        <div class="kg-stats" v-if="graph">
          <div class="kg-cell"><span class="kg-val">{{ graph.nodes.length }}</span><span class="kg-lbl">实体</span></div>
          <div class="kg-cell"><span class="kg-val">{{ graph.edges.length }}</span><span class="kg-lbl">关系</span></div>
          <div class="kg-cell"><span class="kg-val">{{ graph.categories.length }}</span><span class="kg-lbl">类型</span></div>
        </div>

        <el-button
          type="primary"
          :loading="building"
          :disabled="!collectionId"
          @click="rebuild"
        >
          {{ building ? buildProgress : '生成 / 重新生成图谱' }}
        </el-button>
      </div>
    </header>

    <!-- 主舞台 -->
    <main class="kg-stage">
      <div ref="chartRef" class="kg-echart"></div>

      <!-- 图例 -->
      <div class="kg-legend" v-if="graph && graph.categories.length">
        <div class="kg-legend-title">实体类型</div>
        <div class="kg-legend-list">
          <div v-for="(t, i) in graph.categories" :key="t" class="kg-legend-row">
            <span class="kg-dot" :style="{ background: colorAt(i), boxShadow: `0 0 8px ${colorAt(i)}` }"></span>
            <span class="kg-legend-name">{{ t }}</span>
            <span class="kg-legend-count">×{{ countByType(t) }}</span>
          </div>
        </div>
      </div>

      <!-- 节点详情 -->
      <aside class="kg-detail" :class="{ open: selected }">
        <template v-if="selected">
          <header class="kg-detail-head">
            <span class="kg-detail-type" :style="{ background: selectedColor + '22', color: selectedColor }">
              {{ selected.type }}
            </span>
            <span class="kg-detail-name">{{ selected.name }}</span>
            <button class="kg-detail-close" @click="selected = null">
              <el-icon :size="14"><Close /></el-icon>
            </button>
          </header>
          <div class="kg-detail-body">
            <div class="kg-block" v-if="selected.description">
              <div class="kg-block-lbl">简述</div>
              <div class="kg-block-val">{{ selected.description }}</div>
            </div>
            <div class="kg-block" v-if="neighbors.length">
              <div class="kg-block-lbl">关联实体（{{ neighbors.length }}）</div>
              <div class="kg-rel-list">
                <div v-for="(n, idx) in neighbors" :key="idx" class="kg-rel-row">
                  <span class="kg-rel-arrow" :class="{ out: n.dir === 'out' }">{{ n.dir === 'out' ? '→' : '←' }}</span>
                  <span class="kg-rel-label">{{ n.relation }}</span>
                  <span class="kg-rel-target">{{ n.other }}</span>
                </div>
              </div>
            </div>
          </div>
        </template>
        <div class="kg-detail-empty" v-else>
          <p>点击节点查看实体详情</p>
          <p class="kg-hint">拖拽节点 · 滚轮缩放 · 双击重置</p>
        </div>
      </aside>

      <!-- 空状态 -->
      <div class="kg-empty-stage" v-if="!loading && graph && graph.nodes.length === 0">
        <div class="kg-empty-ring"></div>
        <p>该知识库还没有图谱数据</p>
        <p class="kg-hint">点击右上角「生成图谱」从文档中抽取实体与关系</p>
      </div>

      <div class="kg-loading" v-if="loading">
        <div class="kg-loading-ring"></div>
        <p>正在加载知识图谱…</p>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close } from '@element-plus/icons-vue'
import * as echarts from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'
import { knowledgeGraphApi, type GraphData, type GraphNode } from '@/api/knowledgeGraph'

echarts.use([GraphChart, TooltipComponent, LegendComponent, CanvasRenderer])

const route = useRoute()

// 8 类实体配色（与后端 normalizeType 的 8 类对应顺序无关，按 category 下标取色）
const PALETTE = ['#6B8AFF', '#22D3EE', '#34D399', '#FBBF24', '#F472B6', '#a1a1aa', '#FB7185', '#94A3B8']
function colorAt(i: number) { return PALETTE[i % PALETTE.length] }

const collections = ref<KnowledgeCollection[]>([])
const collectionId = ref<number | null>(null)
const graph = ref<GraphData | null>(null)
const loading = ref(false)
const building = ref(false)
const buildProgress = ref('生成中…')
const selected = ref<GraphNode | null>(null)

const chartRef = ref<HTMLElement | null>(null)
const chart = shallowRef<echarts.ECharts | null>(null)

const selectedColor = computed(() => selected.value ? colorAt(selected.value.category) : '#fff')

/** 选中节点的关联实体（出/入边） */
const neighbors = computed(() => {
  if (!selected.value || !graph.value) return [] as { dir: 'out' | 'in'; relation: string; other: string }[]
  const name = selected.value.name
  const out = graph.value.edges
    .filter(e => e.source === name)
    .map(e => ({ dir: 'out' as const, relation: e.relation, other: e.target }))
  const inc = graph.value.edges
    .filter(e => e.target === name)
    .map(e => ({ dir: 'in' as const, relation: e.relation, other: e.source }))
  return [...out, ...inc]
})

function countByType(type: string) {
  return graph.value ? graph.value.nodes.filter(n => n.type === type).length : 0
}

async function loadCollections() {
  try {
    collections.value = await collectionApi.list()
  } catch (e: any) {
    ElMessage.error('加载知识库列表失败')
  }
}

async function loadGraph() {
  if (!collectionId.value) { graph.value = null; return }
  loading.value = true
  selected.value = null
  try {
    graph.value = await knowledgeGraphApi.getByCollection(collectionId.value)
    await nextTick()
    renderChart()
  } catch (e: any) {
    console.error('[KG] 加载图谱失败 collectionId=' + collectionId.value, e)
    ElMessage.error(`加载图谱失败：${e?.message ?? '未知错误'}（collectionId=${collectionId.value}）`)
  } finally {
    loading.value = false
  }
}

function onCollectionChange() {
  loadGraph()
}

/** 重新生成：对该知识库下所有文档逐个抽取建图，再刷新聚合图谱 */
async function rebuild() {
  if (!collectionId.value) return
  building.value = true
  try {
    const docs: any[] = await collectionApi.listDocs(collectionId.value)
    const ready = docs.filter(d => d.status === 'ready' || d.status === undefined)
    if (ready.length === 0) {
      ElMessage.warning('该知识库下没有可用文档')
      return
    }
    let done = 0
    for (const d of ready) {
      buildProgress.value = `生成中 ${done + 1}/${ready.length}…`
      try {
        await knowledgeGraphApi.build(d.id)
      } catch { /* 单文档失败不阻断整体 */ }
      done++
    }
    ElMessage.success(`图谱生成完成（${done} 篇文档）`)
    await loadGraph()
  } catch (e: any) {
    ElMessage.error('生成图谱失败')
  } finally {
    building.value = false
    buildProgress.value = '生成中…'
  }
}

function renderChart() {
  if (!chartRef.value) return
  chart.value?.dispose()
  chart.value = echarts.init(chartRef.value, undefined, { renderer: 'canvas' })

  const g = graph.value
  if (!g) return

  try {
    // 节点大小按权重映射到 18-58
    const maxVal = Math.max(1, ...g.nodes.map(n => n.value || 1))

    // ★ 关键修复：按 name dedupe nodes（ECharts graph 按 name 索引，重名会导致 dataIndex undefined）
    const nodeByName = new Map<string, any>()
    for (const n of g.nodes) {
      const k = String(n.name)
      if (!nodeByName.has(k)) {
        nodeByName.set(k, { ...n, value: n.value || 1 })
      } else {
        // 重名节点累加 value（ECharts 用 value 控制大小）
        const existing = nodeByName.get(k)!
        existing.value = (existing.value || 1) + (n.value || 1)
      }
    }
    const nodes = Array.from(nodeByName.values()).map(n => ({
      id: String(n.name),
      name: String(n.name),
      category: n.category ?? 0,
      value: Math.max(1, n.value || 1),
      symbolSize: 18 + (Math.sqrt((n.value || 1) / maxVal)) * 40,
      label: { show: nodeByName.size <= 60 },
    }))

    // 过滤 source/target 不存在的边（去掉自循环 + 找不到的 + 重复）
    const linkKeys = new Set<string>()
    const links = g.edges
      .filter(e => e.source !== e.target)                                // 去自循环
      .filter(e => nodeByName.has(e.source) && nodeByName.has(e.target)) // 去孤立
      .filter(e => {
        const k = `${e.source}__${e.target}`
        if (linkKeys.has(k)) return false                                // 去重
        linkKeys.add(k)
        return true
      })
      .map(e => ({
        source: String(e.source),
        target: String(e.target),
        value: e.relation || '',
      }))

    const option: any = {
      backgroundColor: 'transparent',
      tooltip: {
        backgroundColor: 'rgba(15, 23, 42, 0.96)',
        borderColor: 'rgba(255,255,255,0.08)',
        borderWidth: 1,
        textStyle: { color: '#fff', fontSize: 12 },
        padding: [10, 14],
        formatter: (p: any) => {
          if (p.dataType === 'edge') {
            const sourceNode = nodeByName.get(p.data.source)
            const targetNode = nodeByName.get(p.data.target)
            const desc = sourceNode?.description || targetNode?.description ? `<div style="color:#94A0BD;font-size:11px;margin-top:3px">${sourceNode?.description ?? targetNode?.description ?? ''}</div>` : ''
            return `<span style="color:#E6EAF6">${p.data.source} <b style="color:#6B8AFF">${p.data.value}</b> ${p.data.target}</span>${desc}`
          }
          const n = nodeByName.get(p.name)
          if (!n) return p.name
          const color = colorAt(n.category)
          const desc = n.description ? `<div style="color:#94A0BD;font-size:11px;max-width:240px;white-space:normal">${n.description}</div>` : ''
          return `<div style="font-weight:700;color:${color};margin-bottom:3px">${n.name}</div>
            <div style="color:#94A0BD;font-size:11px;margin-bottom:2px">${n.type} · 提及 ${n.value}</div>${desc}`
        },
      },
      legend: [{ show: false }],
      animationDuration: 1200,
      animationEasingUpdate: 'quinticInOut',
      color: PALETTE,
      series: [{
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        categories: (g.categories || []).map((c: any, i: number) => ({ name: c, itemStyle: { color: PALETTE[i % PALETTE.length] } })),
        force: {
          repulsion: 280,
          edgeLength: [90, 200],
          gravity: 0.06,
          layoutAnimation: true,
        },
        label: {
          position: 'right',
          color: '#C9D3F0',
          fontSize: 12,
          formatter: (p: any) => p.data.name,
        },
        lineStyle: { color: 'source', width: 1.4, opacity: 0.5, curveness: 0.12 },
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: [0, 8],
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 3, opacity: 1 },
          label: { color: '#fff', fontSize: 13 },
        },
        data: nodes,
        links,
      }],
    }

    chart.value.setOption(option)
    chart.value.on('click', (params: any) => {
      if (params.dataType === 'node' && params.name) {
        selected.value = nodeByName.get(params.name) ?? null
      }
    })
    chart.value.on('dblclick', () => chart.value?.dispatchAction({ type: 'restore' }))
    window.addEventListener('resize', resize)
  } catch (e) {
    console.error('[KG] ECharts 渲染失败', e)
    ElMessage.error(`图谱渲染失败：${(e as any)?.message ?? e}`)
  }
}

function resize() { chart.value?.resize() }

onMounted(async () => {
  await loadCollections()
  // 支持从知识库详情页跳转携带 ?collectionId=
  const qid = Number(route.query.collectionId)
  if (qid) {
    collectionId.value = qid
  } else if (collections.value.length) {
    collectionId.value = collections.value[0]?.id ?? null
  }
  if (collectionId.value) await loadGraph()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resize)
  chart.value?.dispose()
})
</script>

<style scoped>
.kg-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: radial-gradient(ellipse at top, #0f1629 0%, #0a0e1a 60%, #070a12 100%);
  color: #e6eaf6;
}

/* 顶部条 */
.kg-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 22px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-wrap: wrap;
}
.kg-title { display: flex; align-items: baseline; gap: 10px; }
.kg-kbd {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px; letter-spacing: 2px; color: #6B8AFF;
  border: 1px solid rgba(107, 138, 255, 0.4); border-radius: 4px; padding: 2px 8px;
}
.kg-sub { font-size: 16px; font-weight: 600; }
.kg-controls { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.kg-select { width: 220px; }
.kg-stats { display: flex; gap: 18px; }
.kg-cell { display: flex; flex-direction: column; align-items: center; }
.kg-val { font-size: 18px; font-weight: 700; color: #fff; line-height: 1.1; }
.kg-lbl { font-size: 11px; color: #94a0bd; }

/* 舞台 */
.kg-stage { position: relative; flex: 1; overflow: hidden; }
.kg-echart { position: absolute; inset: 0; }

/* 图例 */
.kg-legend {
  position: absolute; left: 18px; bottom: 18px;
  background: rgba(15, 23, 42, 0.7); backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 12px;
  padding: 12px 14px; min-width: 150px;
}
.kg-legend-title { font-size: 11px; letter-spacing: 1px; color: #94a0bd; margin-bottom: 8px; }
.kg-legend-list { display: flex; flex-direction: column; gap: 6px; }
.kg-legend-row { display: flex; align-items: center; gap: 8px; }
.kg-dot { width: 9px; height: 9px; border-radius: 50%; flex-shrink: 0; }
.kg-legend-name { font-size: 12px; color: #d6ddf2; flex: 1; }
.kg-legend-count { font-size: 11px; color: #7c89a8; font-family: ui-monospace, monospace; }

/* 详情面板 */
.kg-detail {
  position: absolute; right: -340px; top: 18px; bottom: 18px; width: 320px;
  background: rgba(15, 23, 42, 0.82); backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 14px;
  padding: 16px; transition: right 0.32s cubic-bezier(0.4, 0, 0.2, 1);
  display: flex; flex-direction: column;
}
.kg-detail.open { right: 18px; }
.kg-detail-head { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; }
.kg-detail-type { font-size: 11px; padding: 2px 8px; border-radius: 6px; font-weight: 600; }
.kg-detail-name { font-size: 16px; font-weight: 700; flex: 1; }
.kg-detail-close {
  background: transparent; border: none; color: #94a0bd; cursor: pointer;
  display: flex; align-items: center; padding: 4px;
}
.kg-detail-close:hover { color: #fff; }
.kg-detail-body { flex: 1; overflow-y: auto; }
.kg-block { margin-bottom: 16px; }
.kg-block-lbl { font-size: 11px; letter-spacing: 1px; color: #94a0bd; margin-bottom: 6px; }
.kg-block-val { font-size: 13px; line-height: 1.6; color: #d6ddf2; }
.kg-rel-list { display: flex; flex-direction: column; gap: 7px; }
.kg-rel-row { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.kg-rel-arrow { color: #6B8AFF; font-weight: 700; }
.kg-rel-arrow.out { color: #34D399; }
.kg-rel-label { color: #94a0bd; }
.kg-rel-target { color: #e6eaf6; font-weight: 500; }
.kg-detail-empty { margin: auto; text-align: center; color: #7c89a8; }
.kg-hint { font-size: 11px; color: #5d6987; margin-top: 6px; }

/* 空状态 / 加载 */
.kg-empty-stage, .kg-loading {
  position: absolute; inset: 0; display: flex; flex-direction: column;
  align-items: center; justify-content: center; gap: 10px; color: #94a0bd; pointer-events: none;
}
.kg-empty-ring, .kg-loading-ring {
  width: 56px; height: 56px; border-radius: 50%;
  border: 3px solid rgba(107, 138, 255, 0.25); border-top-color: #6B8AFF;
}
.kg-loading-ring { animation: kg-spin 0.9s linear infinite; }
@keyframes kg-spin { to { transform: rotate(360deg); } }
</style>
