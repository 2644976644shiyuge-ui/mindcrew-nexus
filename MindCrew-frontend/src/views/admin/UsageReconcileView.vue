<script setup lang="ts">
/**
 * 阿里云 BSS 对账 · 任务 13.7
 * 管理员查看每天「阿里官方账单 vs 我们内部计算」的差异
 */
import { ref, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { usageApi } from '@/api/usage'

const router = useRouter()

interface ReconcileRow {
  id: number
  statDate: string
  productCode: string
  productName: string
  officialAmountCny: number
  ourCalcAmountCny: number
  diffAmountCny: number
  diffPct: number
  alerted: number
  note?: string
  updateTime?: string
}

interface Status {
  enabled: boolean
  credentialConfigured: boolean
  tableReady: boolean
  productCodes: string[]
  alertThresholdPct: number
  latestDate?: string
  latestUpdateTime?: string
}

const status = ref<Status | null>(null)
const list = ref<ReconcileRow[]>([])
const loading = ref(false)
const days = ref(30)
const runDate = ref<string>('')   // 手动触发的日期

const loadStatus = async () => {
  try {
    const res: any = await usageApi.reconcileStatus()
    status.value = res?.data ?? res
  } catch (e: any) {
    ElMessage.error('加载配置失败：' + (e?.message || ''))
  }
}

const loadList = async () => {
  loading.value = true
  try {
    const res: any = await usageApi.reconcileList(days.value)
    list.value = res?.data ?? res ?? []
  } finally { loading.value = false }
}

const runReconcile = async () => {
  if (!runDate.value) { ElMessage.warning('请选日期'); return }
  loading.value = true
  try {
    await usageApi.reconcileRun(runDate.value)
    ElMessage.success('对账完成')
    await loadList()
    await loadStatus()
  } catch (e: any) {
    ElMessage.error('对账失败：' + (e?.response?.data?.message || e?.message || ''))
  } finally { loading.value = false }
}

const showDetail = (row: ReconcileRow) => {
  ElMessageBox.alert(
    `<div style="line-height:1.8;font-size:13px">
      <div>日期：<b>${row.statDate}</b></div>
      <div>产品：<b>${row.productName}</b>（${row.productCode}）</div>
      <hr style="margin:8px 0;border:none;border-top:1px solid #e2e8f0" />
      <div>阿里官方账单：<b style="color:#b45309">¥${Number(row.officialAmountCny).toFixed(4)}</b></div>
      <div>我们内部计算：<b style="color:#0a0a0a">¥${Number(row.ourCalcAmountCny).toFixed(4)}</b></div>
      <div>差额：<b>${row.diffAmountCny > 0 ? '+' : ''}¥${Number(row.diffAmountCny).toFixed(4)}</b></div>
      <div>差异比例：<b style="color:${row.alerted ? '#ef4444' : '#10b981'}">${(row.diffPct * 100).toFixed(2)}%</b> ${row.alerted ? '⚠ 已告警' : '✓ 正常'}</div>
      ${row.note ? `<div style="margin-top:6px;color:#64748b">备注：${row.note}</div>` : ''}
    </div>`,
    `对账详情 · ${row.statDate} / ${row.productCode}`,
    { dangerouslyUseHTMLString: true, confirmButtonText: '关闭' }
  )
}

const fmtMonth = (s?: string) => {
  if (!s) return '-'
  const [y, m] = s.split('-')
  return m ? `${y}年${Number(m)}月` : s
}
const formatMoney = (n: number) => '¥' + Number(n ?? 0).toFixed(4)
const formatPct = (n: number) => (Number(n ?? 0) * 100).toFixed(2) + '%'

const statusTagType = computed(() => {
  if (!status.value) return 'info'
  if (!status.value.tableReady) return 'danger'
  if (!status.value.enabled) return 'info'
  if (!status.value.credentialConfigured) return 'warning'
  return 'success'
})
const statusText = computed(() => {
  if (!status.value) return '加载中…'
  if (!status.value.tableReady) return '⚠ 表未建 · 跑 ./sql/run.sh usage-reconcile-schema.sql'
  if (!status.value.enabled) return '未启用 · 设 BSS_ENABLED=true 启用'
  if (!status.value.credentialConfigured) return '缺凭证 · 配 BSS_ACCESS_KEY / BSS_SECRET_KEY'
  return `运行中 · 监测产品：${status.value.productCodes.join(', ')}`
})

// 默认日期 = 昨天
const yesterday = () => {
  const d = new Date(); d.setDate(d.getDate() - 1)
  return d.toISOString().slice(0, 10)
}

onMounted(async () => {
  runDate.value = yesterday()
  await loadStatus()
  await loadList()
})
</script>

<template>
  <div class="reconcile-page">
    <header class="header">
      <button class="back-btn" @click="router.back()">
        <el-icon size="14"><ArrowLeft /></el-icon> 返回
      </button>
      <h2 class="title">阿里云账单对账</h2>
      <span class="title-tag">真实账单 vs 内部计算</span>
    </header>

    <!-- 状态卡 -->
    <section class="status-card">
      <div class="status-row">
        <span class="status-label">对账状态</span>
        <el-tag :type="statusTagType" effect="light" size="default">{{ statusText }}</el-tag>
      </div>
      <div class="status-row" v-if="status?.latestDate">
        <span class="status-label">最近一次对账</span>
        <span>{{ status.latestDate }} <span class="muted">（{{ status.latestUpdateTime?.slice(0, 19).replace('T', ' ') }}）</span></span>
      </div>
      <div class="status-row">
        <span class="status-label">告警阈值</span>
        <span>差异 &gt; <b>{{ ((status?.alertThresholdPct ?? 0.1) * 100).toFixed(0) }}%</b> 触发</span>
      </div>
    </section>

    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="seg-group">
        <button
          v-for="d in [7, 30, 60, 90]"
          :key="d"
          :class="['seg-btn', { active: days === d }]"
          @click="days = d; loadList()"
        >近 {{ d }} 天</button>
      </div>
      <el-button :icon="Refresh" @click="loadList">刷新</el-button>

      <div class="manual-run">
        <span class="muted">手动跑：</span>
        <el-date-picker
          v-model="runDate"
          type="date"
          value-format="YYYY-MM-DD"
          :disabled-date="(d: Date) => d >= new Date(new Date().setHours(0,0,0,0))"
          size="default"
          placeholder="选日期"
        />
        <el-button
          type="primary"
          :icon="VideoPlay"
          :disabled="!status?.enabled || !status?.credentialConfigured"
          @click="runReconcile"
        >立即对账</el-button>
      </div>
    </div>

    <!-- 对账表 -->
    <section class="table-card" v-loading="loading">
      <el-table :data="list" stripe>
        <el-table-column prop="statDate" label="账期" width="110" sortable>
          <template #default="{ row }">{{ fmtMonth(row.statDate) }}</template>
        </el-table-column>
        <el-table-column label="产品" width="160">
          <template #default="{ row }">
            <div>
              <div class="prod-name">{{ row.productName }}</div>
              <div class="prod-code">{{ row.productCode }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="阿里官方" width="140" align="right">
          <template #default="{ row }">
            <span class="cell-official">{{ formatMoney(row.officialAmountCny) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="我们计算" width="140" align="right">
          <template #default="{ row }">
            <span v-if="row.ourCalcAmountCny == null" class="cell-ref">-</span>
            <span v-else class="cell-ours">{{ formatMoney(row.ourCalcAmountCny) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="差额" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.ourCalcAmountCny == null" class="cell-ref">-</span>
            <span v-else :class="{ pos: row.diffAmountCny > 0, neg: row.diffAmountCny < 0 }">
              {{ row.diffAmountCny > 0 ? '+' : '' }}{{ formatMoney(row.diffAmountCny) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="差异比例" width="120" align="right">
          <template #default="{ row }">
            <span v-if="row.ourCalcAmountCny == null" class="cell-ref">-</span>
            <el-tag
              v-else
              :type="row.alerted ? 'danger' : 'success'"
              size="small"
              effect="light"
            >{{ formatPct(row.diffPct) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.ourCalcAmountCny == null" type="info" size="small">参考</el-tag>
            <el-tag v-else-if="row.alerted" type="danger" size="small">⚠ 告警</el-tag>
            <el-tag v-else type="success" size="small">✓ 正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" @click="showDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!loading && !list.length" class="empty">
        暂无对账记录 · 配置好后等待每天凌晨 3:30 自动跑，或手动选日期触发
      </div>
    </section>

    <!-- 说明 -->
    <section class="help">
      <h4>📌 使用说明</h4>
      <ul>
        <li>阿里云账单 <b>T+1 出</b>，不能对账今天</li>
        <li>BSS 接口只能拿到「整个账户某产品」的总额，<b>无法分到用户</b></li>
        <li>对账主要用来校准 <code>model_pricing</code> 表的单价是否还准</li>
        <li>差异超过 <b>{{ ((status?.alertThresholdPct ?? 0.1) * 100).toFixed(0) }}%</b> 自动标红告警，请去阿里云费用中心比对实际账单</li>
        <li>配置缺失时所有调用安全无操作，不影响主流程</li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.reconcile-page { padding: 28px 32px 48px; background: var(--bg-page); min-height: 100%; overflow-y: auto; }
.header { display: flex; align-items: center; gap: 14px; margin-bottom: 22px; }
.back-btn {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 6px 12px; background: var(--bg-surface);
  border: 1px solid var(--line); border-radius: 6px;
  font-size: 12.5px; color: var(--ink-2); cursor: pointer;
}
.back-btn:hover { background: var(--bg-hover); color: var(--ink-1); }
.title { font-size: 22px; font-weight: 700; color: var(--ink-1); }
.title-tag {
  font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #e4e4e7, #d4d4d8); color: #0a0a0a;
}

.status-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 16px 22px;
  margin-bottom: 18px;
  display: flex; flex-direction: column; gap: 8px;
}
.status-row { display: flex; align-items: center; gap: 12px; font-size: 13px; color: var(--ink-2); }
.status-label { color: var(--ink-3); min-width: 96px; font-weight: 600; }
.muted { color: var(--ink-3); font-size: 12px; }

.toolbar {
  display: flex; align-items: center; gap: 12px; margin-bottom: 14px; flex-wrap: wrap;
}
.seg-group {
  display: inline-flex; background: var(--bg-surface);
  border: 1px solid var(--line); border-radius: 10px; padding: 4px; gap: 2px;
}
.seg-btn {
  appearance: none; border: none; background: transparent;
  padding: 6px 14px; font-size: 12.5px; font-weight: 600;
  color: var(--ink-2);
  border-radius: 7px; cursor: pointer;
  transition: all 180ms ease;
}
.seg-btn:hover { background: rgba(0, 0, 0, 0.06); color: var(--ink-1); }
.seg-btn.active {
  background: linear-gradient(135deg, #0a0a0a 0%, #6366f1 100%);
  color: #fff;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.32);
}
.manual-run { display: flex; align-items: center; gap: 8px; margin-left: auto; }

.table-card { background: var(--bg-surface); border: 1px solid var(--line); border-radius: 12px; padding: 14px 18px; margin-bottom: 18px; }
.prod-name { font-size: 13px; font-weight: 600; color: var(--ink-1); }
.prod-code { font-size: 11px; color: var(--ink-3); font-family: 'JetBrains Mono', monospace; }
.cell-official { font-family: 'JetBrains Mono', monospace; color: #b45309; font-weight: 600; }
.cell-ours     { font-family: 'JetBrains Mono', monospace; color: #0a0a0a; font-weight: 600; }
.cell-ref      { color: var(--ink-4); }
.pos { color: #ef4444; font-weight: 600; font-family: 'JetBrains Mono', monospace; }
.neg { color: #10b981; font-weight: 600; font-family: 'JetBrains Mono', monospace; }
.empty { padding: 36px; text-align: center; color: var(--ink-3); font-size: 13px; }

.help { background: rgba(0,0,0,0.04); border: 1px solid rgba(0,0,0,0.12); border-radius: 10px; padding: 14px 18px; font-size: 12.5px; }
.help h4 { font-size: 13px; color: var(--ink-1); margin-bottom: 6px; }
.help ul { padding-left: 18px; color: var(--ink-2); }
.help li { margin-bottom: 4px; line-height: 1.65; }
.help code { background: rgba(0,0,0,0.10); padding: 1px 6px; border-radius: 4px; font-family: 'JetBrains Mono', monospace; font-size: 11.5px; color: #0a0a0a; }
</style>
