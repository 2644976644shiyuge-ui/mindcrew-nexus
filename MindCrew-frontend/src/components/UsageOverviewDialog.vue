<script setup lang="ts">
/**
 * 全员花费总览弹窗 · 管理员快捷查看
 * 用法：<UsageOverviewDialog v-model="visible" :users="users" />
 *
 * 数据：调 /v2/usage/overview · 拿到 totalCostCny + perUser + perDay
 * 显示：区间汇总卡 + 按用户 Top 排行表 + 按日柱状图（简版 svg）
 */
import { ref, watch, computed } from 'vue'
import { usageApi } from '@/api/usage'
import type { UserInfo } from '@/api/user'

interface UserRow {
  userId: number
  costCny: number
  chatCount: number
  inputTokens: number
  outputTokens: number
  embeddingTokens: number
  visionCalls: number
  asrSeconds: number
}
interface DayRow { date: string; costCny: number; chatCount: number }
interface OverviewData {
  from: string
  to: string
  totalCostCny: number
  totalChatCount: number
  totalInputTokens: number
  totalOutputTokens: number
  totalEmbeddingTokens: number
  totalVisionCalls: number
  totalAsrSeconds: number
  userCount: number
  perUser: UserRow[]
  perDay: DayRow[]
}

const props = defineProps<{ modelValue: boolean; users: UserInfo[] }>()
const emit = defineEmits<{ (e: 'update:modelValue', v: boolean): void }>()

const visible = computed({
  get: () => props.modelValue,
  set: (v: boolean) => emit('update:modelValue', v),
})

type RangeKey = 'today' | 'week' | 'month' | 'quarter'
const rangeKey = ref<RangeKey>('month')
const customRange = ref<[string, string] | null>(null)
const loading = ref(false)
const data = ref<OverviewData | null>(null)

const rangeOptions: { key: RangeKey; label: string }[] = [
  { key: 'today',   label: '今日' },
  { key: 'week',    label: '本周' },
  { key: 'month',   label: '本月' },
  { key: 'quarter', label: '近 90 天' },
]

const rangeForKey = (k: RangeKey): [string, string] => {
  const today = new Date()
  const fmt = (d: Date) => d.toISOString().slice(0, 10)
  const subDays = (n: number) => {
    const d = new Date(today)
    d.setDate(d.getDate() - n)
    return d
  }
  if (k === 'today')   return [fmt(today), fmt(today)]
  if (k === 'week')    return [fmt(subDays(6)), fmt(today)]
  if (k === 'month')   return [fmt(new Date(today.getFullYear(), today.getMonth(), 1)), fmt(today)]
  return [fmt(subDays(89)), fmt(today)]
}

const load = async () => {
  loading.value = true
  try {
    const [from, to] = customRange.value ?? rangeForKey(rangeKey.value)
    const res: any = await usageApi.overview(from, to)
    data.value = res?.data ?? res
  } finally {
    loading.value = false
  }
}

const selectRange = (k: RangeKey) => {
  rangeKey.value = k
  customRange.value = null
  load()
}
const onCustomRangeChange = () => { if (customRange.value) load() }

// 弹窗第一次打开时拉数据
watch(() => props.modelValue, (v) => {
  if (v && !data.value) load()
})

// userId → 用户信息映射
const userMap = computed(() => {
  const m: Record<number, UserInfo> = {}
  props.users.forEach(u => { m[u.id] = u })
  return m
})

const formatMoney = (n?: number) => {
  const v = Number(n ?? 0)
  if (v === 0) return '¥0'
  if (v >= 1) return '¥' + v.toFixed(2)
  if (v >= 0.01) return '¥' + v.toFixed(4)
  return '¥' + v.toFixed(6)
}
const formatNum = (n: number) => new Intl.NumberFormat('en-US').format(Number(n ?? 0))

// 日趋势柱状图最大值（归一化）
const maxDayCost = computed(() => {
  if (!data.value?.perDay?.length) return 1
  return Math.max(...data.value.perDay.map(d => Number(d.costCny) || 0), 0.0001)
})

const top10 = computed(() => data.value?.perUser?.slice(0, 10) || [])
</script>

<template>
  <el-dialog
    v-model="visible"
    title="全员花费总览"
    width="900px"
    top="6vh"
    :close-on-click-modal="false"
  >
    <div class="overview-wrap" v-loading="loading">
      <!-- 时间筛选 -->
      <div class="filter-bar">
        <div class="seg-group">
          <button
            v-for="opt in rangeOptions"
            :key="opt.key"
            :class="['seg-btn', { active: !customRange && rangeKey === opt.key }]"
            @click="selectRange(opt.key)"
          >{{ opt.label }}</button>
        </div>
        <el-date-picker
          v-model="customRange"
          type="daterange"
          size="default"
          value-format="YYYY-MM-DD"
          start-placeholder="自定义起"
          end-placeholder="自定义止"
          @change="onCustomRangeChange"
        />
      </div>

      <!-- 汇总指标卡 -->
      <div class="kpi-grid">
        <div class="kpi cost">
          <div class="kpi-label">
            区间总成本
            <el-tooltip placement="top" effect="dark">
              <template #content>
                <div style="max-width:260px;line-height:1.55">
                  Chat 类按 LLM 真实 token × 定价（准）<br />
                  视觉/视频/Embedding/TTS/ASR 类按规则估算（误差 20-50%）<br />
                  <b>非阿里官方账单</b>，仅用于内部预算 / 趋势对比
                </div>
              </template>
              <span class="kpi-badge">估算</span>
            </el-tooltip>
          </div>
          <div class="kpi-value">{{ formatMoney(data?.totalCostCny) }}</div>
          <div class="kpi-sub">{{ data?.userCount ?? 0 }} 个用户产生用量</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">总对话数</div>
          <div class="kpi-value">{{ formatNum(data?.totalChatCount ?? 0) }}</div>
          <div class="kpi-sub">含 Golden Pair 命中</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">Token 总量</div>
          <div class="kpi-value">{{ formatNum((data?.totalInputTokens ?? 0) + (data?.totalOutputTokens ?? 0)) }}</div>
          <div class="kpi-sub">in {{ formatNum(data?.totalInputTokens ?? 0) }} · out {{ formatNum(data?.totalOutputTokens ?? 0) }}</div>
        </div>
        <div class="kpi">
          <div class="kpi-label">多模态调用</div>
          <div class="kpi-value">{{ formatNum(data?.totalVisionCalls ?? 0) }}</div>
          <div class="kpi-sub">视觉/视频 · ASR {{ formatNum(data?.totalAsrSeconds ?? 0) }} 秒</div>
        </div>
      </div>

      <!-- 按日趋势 -->
      <div class="block trend" v-if="data?.perDay?.length">
        <div class="block-title">按日成本趋势</div>
        <div class="bars">
          <div
            v-for="d in data.perDay"
            :key="d.date"
            class="bar-wrap"
            :title="`${d.date}：${formatMoney(d.costCny)} · ${formatNum(d.chatCount)} 对话`"
          >
            <div class="bar" :style="{ height: ((Number(d.costCny) / maxDayCost) * 100) + '%' }"></div>
            <div class="bar-date">{{ d.date.slice(5) }}</div>
          </div>
        </div>
      </div>

      <!-- 用户排行 Top 10 -->
      <div class="block">
        <div class="block-title">
          用户成本排行 · Top 10
          <span class="block-sub">（共 {{ data?.perUser?.length ?? 0 }} 个用户）</span>
        </div>
        <el-table :data="top10" stripe size="small">
          <el-table-column label="#" type="index" width="50" />
          <el-table-column label="用户" min-width="180">
            <template #default="{ row }">
              <div class="user-cell">
                <el-avatar :size="28" :src="userMap[row.userId]?.avatar">
                  {{ (userMap[row.userId]?.nickname || userMap[row.userId]?.username || '?').charAt(0) }}
                </el-avatar>
                <div>
                  <div class="cell-name">{{ userMap[row.userId]?.nickname || userMap[row.userId]?.username || `#${row.userId}` }}</div>
                  <div class="cell-sub">@{{ userMap[row.userId]?.username || '-' }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="对话" prop="chatCount" align="right" width="80">
            <template #default="{ row }">{{ formatNum(row.chatCount) }}</template>
          </el-table-column>
          <el-table-column label="Token" align="right" width="120">
            <template #default="{ row }">{{ formatNum(row.inputTokens + row.outputTokens) }}</template>
          </el-table-column>
          <el-table-column label="视觉" prop="visionCalls" align="right" width="80">
            <template #default="{ row }">{{ formatNum(row.visionCalls) }}</template>
          </el-table-column>
          <el-table-column label="ASR(s)" prop="asrSeconds" align="right" width="80">
            <template #default="{ row }">{{ formatNum(row.asrSeconds) }}</template>
          </el-table-column>
          <el-table-column label="成本" align="right" width="120">
            <template #default="{ row }">
              <span class="cost-cell">{{ formatMoney(row.costCny) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!loading && !top10.length" class="empty">该区间内无用量记录</div>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.overview-wrap { padding: 4px 0; }

.filter-bar { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; flex-wrap: wrap; }
.seg-group {
  display: inline-flex;
  background: var(--bg-surface, #f8fafc);
  border: 1px solid var(--line, #e2e8f0);
  border-radius: 10px;
  padding: 4px;
  gap: 2px;
}
.seg-btn {
  appearance: none; border: none; background: transparent;
  padding: 7px 16px; font-size: 13px; font-weight: 600;
  color: var(--ink-2, #475569);
  border-radius: 7px; cursor: pointer;
  transition: all 180ms ease;
}
.seg-btn:hover { color: var(--ink-1, #0f172a); background: rgba(0, 0, 0, 0.06); }
.seg-btn.active {
  background: linear-gradient(135deg, #0a0a0a 0%, #6366f1 100%);
  color: #fff;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.32);
}

.kpi-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 22px;
}
.kpi {
  background: var(--bg-surface, #fff);
  border: 1px solid var(--line, #e2e8f0);
  border-radius: 12px;
  padding: 14px 18px;
}
.kpi.cost {
  background: linear-gradient(135deg, rgba(245,158,11,0.10) 0%, var(--bg-surface, #fff) 80%);
  border-color: rgba(245,158,11,0.32);
}
.kpi-label { font-size: 11.5px; color: var(--ink-3, #94a3b8); font-weight: 600; text-transform: uppercase; letter-spacing: 0.05em; display: flex; align-items: center; gap: 4px; }
.kpi-badge {
  display: inline-block;
  font-size: 9.5px; font-weight: 600;
  padding: 1px 6px;
  border-radius: 999px;
  background: rgba(245,158,11,0.15);
  color: #b45309;
  border: 1px solid rgba(245,158,11,0.3);
  cursor: help;
  text-transform: none;
  letter-spacing: 0;
}
.kpi-value {
  font-size: 22px; font-weight: 800;
  font-family: 'Manrope', sans-serif;
  color: var(--ink-1, #0f172a);
  letter-spacing: -0.025em;
  line-height: 1.15; margin: 6px 0 2px;
}
.kpi.cost .kpi-value { color: #b45309; }
.kpi-sub { font-size: 11px; color: var(--ink-3, #94a3b8); }

.block { margin-bottom: 20px; }
.block-title {
  font-size: 13px; font-weight: 700; color: var(--ink-1, #0f172a);
  margin-bottom: 10px;
}
.block-sub { font-size: 11px; color: var(--ink-3, #94a3b8); font-weight: 500; margin-left: 6px; }

/* 按日柱状图 */
.bars {
  display: flex; align-items: flex-end;
  gap: 6px;
  height: 110px;
  padding: 6px 4px 0;
  background: var(--bg-surface, #f8fafc);
  border: 1px solid var(--line, #e2e8f0);
  border-radius: 10px;
  overflow-x: auto;
}
.bar-wrap {
  flex: 0 0 28px;
  display: flex; flex-direction: column; align-items: center; justify-content: flex-end;
  height: 100%;
}
.bar {
  width: 18px;
  min-height: 2px;
  background: linear-gradient(180deg, #0a0a0a 0%, #6366f1 100%);
  border-radius: 3px 3px 0 0;
  transition: opacity 0.15s;
}
.bar-wrap:hover .bar { opacity: 0.8; }
.bar-date { font-size: 10px; color: var(--ink-3, #94a3b8); margin-top: 4px; white-space: nowrap; }

.user-cell { display: flex; align-items: center; gap: 8px; }
.cell-name { font-size: 13px; font-weight: 600; color: var(--ink-1, #0f172a); }
.cell-sub { font-size: 11px; color: var(--ink-3, #94a3b8); }
.cost-cell { font-family: 'JetBrains Mono', monospace; color: #b45309; font-weight: 700; }
.empty { padding: 32px; text-align: center; color: var(--ink-3, #94a3b8); font-size: 13px; }

@media (max-width: 720px) {
  .kpi-grid { grid-template-columns: repeat(2, 1fr); }
}
</style>
