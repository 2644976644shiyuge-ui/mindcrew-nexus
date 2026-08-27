<template>
  <div class="lh-page">
    <!-- ═══ 页头 ═══ -->
    <div class="lh-head">
      <div class="eyebrow">GLOBAL LEAD HUNTER · 数字员工</div>
      <h1 class="lh-title">全球获客</h1>
      <p class="lh-sub">基于知识库画像与 ICP 的全球客户自动发现 · 公司验证 · 联系人挖掘 · 邮箱验证 · 历史去重，一键导出结构化线索表</p>
    </div>

    <!-- ═══ 阶段一：任务配置（左配置 + 右地图栏） ═══ -->
    <div v-if="phase === 'config'" class="lh-config">
      <div class="config-left">
      <div class="cfg-card">
        <div class="cfg-block">
          <div class="cfg-label"><el-icon><Location /></el-icon>目标国家 / 地区</div>
          <el-select v-model="form.countries" multiple filterable allow-create default-first-option
                     placeholder="选择或输入国家（英文）" class="cfg-select">
            <el-option v-for="c in COUNTRY_OPTIONS" :key="c" :label="countryLabel(c)" :value="c" />
          </el-select>
        </div>

        <div class="cfg-block">
          <div class="cfg-label"><el-icon><OfficeBuilding /></el-icon>客户类型</div>
          <div class="chip-row">
            <button v-for="t in CUSTOMER_TYPES" :key="t" class="chip"
                    :class="{ active: form.customerTypes.includes(t) }"
                    @click="toggle(form.customerTypes, t)">{{ t }}</button>
          </div>
        </div>

        <div class="cfg-block">
          <div class="cfg-label"><el-icon><Goods /></el-icon>关注产品线 <span class="cfg-hint">（不选 = 全产品线）</span></div>
          <div class="chip-row">
            <button v-for="p in PRODUCT_OPTIONS" :key="p" class="chip"
                    :class="{ active: form.products.includes(p) }"
                    @click="toggle(form.products, p)">{{ p }}</button>
          </div>
        </div>

        <div class="cfg-block">
          <div class="cfg-label"><el-icon><DataLine /></el-icon>目标线索数：<b class="accent-num">{{ form.targetCount }}</b> 条</div>
          <el-slider v-model="form.targetCount" :min="10" :max="200" :step="5" show-stops />
        </div>

        <div class="cfg-footer">
          <div class="cfg-note">流程：知识库分析 → ICP 画像 → 全球发现 → 公司验证 → 联系人 → 邮箱验证 → 去重 → 评分 → 导出<br/>仅发现与验证，<b>绝不自动发邮件 / 不碰 LinkedIn / 不自动上传 CRM</b></div>
          <el-button class="start-btn" :disabled="!canStart" :loading="starting" @click="start">
            <el-icon v-if="!starting"><Promotion /></el-icon>{{ starting ? '正在启动…' : '启动全球获客' }}
          </el-button>
        </div>
      </div>

      <!-- 历史任务 -->
      <div class="cfg-card history-card" v-if="sessions.length">
        <div class="cfg-label"><el-icon><Clock /></el-icon>历史任务</div>
        <div class="session-list">
          <div v-for="s in sessions" :key="s.id" class="session-item" @click="viewSession(s.id)">
            <span class="s-id">#{{ s.id }}</span>
            <span class="s-countries">{{ s.countries }}</span>
            <span class="s-count">{{ s.targetCount }} 条</span>
            <span class="s-status" :class="s.status">{{ statusText(s.status) }}</span>
            <span class="s-time">{{ shortTime(s.createTime) }}</span>
            <el-icon class="s-go"><ArrowRight /></el-icon>
          </div>
        </div>
      </div>
      </div>

      <!-- 右侧：全球客户分布地图（世界 + 七大洲，悬停国家查看客户数） -->
      <aside class="run-map">
        <LeadMapCard :stats="mapStats" scope-label="个人累计" />
      </aside>
    </div>

    <!-- ═══ 阶段二/三：运行中 + 结果（左主区 + 右地图栏） ═══ -->
    <div v-else class="lh-run">
      <div class="run-main">
      <!-- 运行状态卡 -->
      <div class="cfg-card">
        <div class="run-head">
          <div>
            <div class="run-title">
              任务 #{{ sessionId }}
              <el-tag v-if="session?.status === 'queued'" size="small" type="warning">
                排队中{{ queuePosition > 0 ? `（第 ${queuePosition} 位）` : '' }}
              </el-tag>
              <el-tag v-else-if="session?.status === 'running'" size="small" class="tag-running">运行中</el-tag>
              <el-tag v-else-if="session?.status === 'done'" size="small" type="success">已完成</el-tag>
              <el-tag v-else-if="session?.status === 'cancelled'" size="small" type="info">已取消</el-tag>
              <el-tag v-else-if="session" size="small" type="danger">失败</el-tag>
            </div>
            <div class="run-meta">{{ session?.countries }} · 目标 {{ session?.targetCount }} 条</div>
          </div>
          <div class="run-actions">
            <el-button text :loading="pollingStatus" @click="refreshStatus">
              <el-icon><Refresh /></el-icon>刷新状态
            </el-button>
            <el-button v-if="session?.status === 'running' || session?.status === 'queued'"
                       text type="danger" @click="cancelTask">
              取消任务
            </el-button>
            <el-button v-if="session?.status !== 'running' && session?.status !== 'queued'" text @click="backToConfig">
              <el-icon><Plus /></el-icon>新建任务
            </el-button>
          </div>
        </div>

        <el-progress :percentage="session?.progress ?? 0" :stroke-width="8"
                     :color="'#0071E3'" :show-text="true" class="run-progress" />

        <!-- 11 步 Agent 工作流 -->
        <div class="steps-grid">
          <div v-for="st in steps" :key="st.index" class="step-item" :class="st.status">
            <span class="step-icon">
              <span v-if="st.status === 'running'" class="spinner"></span>
              <el-icon v-else-if="st.status === 'done'"><CircleCheckFilled /></el-icon>
              <el-icon v-else-if="st.status === 'failed'"><CircleCloseFilled /></el-icon>
              <span v-else class="step-dot"></span>
            </span>
            <div class="step-body">
              <div class="step-name">
                <span class="step-idx">{{ st.index }}</span>{{ st.title }}
              </div>
              <div class="step-detail" v-if="st.detail">{{ st.detail }}</div>
            </div>
          </div>
        </div>

        <!-- 实时统计 -->
        <div class="stat-row">
          <div class="stat-chip"><b>{{ stats.discovered }}</b><span>发现公司</span></div>
          <div class="stat-chip"><b>{{ stats.contacts }}</b><span>联系人</span></div>
          <div class="stat-chip ok"><b>{{ stats.emailVerified }}</b><span>邮箱已验证</span></div>
          <div class="stat-chip warn"><b>{{ stats.rejected }}</b><span>本次筛选</span></div>
          <div class="stat-chip final"><b>{{ stats.finalLeads }}</b><span>最终线索</span></div>
        </div>

        <!-- ICP 摘要 -->
        <div v-if="session?.icpSummary" class="icp-box">
          <div class="icp-title"><el-icon><Aim /></el-icon>ICP 客户画像</div>
          <div class="icp-content">{{ session.icpSummary }}</div>
        </div>

        <!-- 失败信息 -->
        <el-alert v-if="session?.status === 'failed' || session?.status === 'cancelled'"
                  :type="session?.status === 'failed' ? 'error' : 'info'" :closable="false"
                  :title="session?.errorMsg || (session?.status === 'cancelled' ? '任务已取消' : '任务执行失败')" class="fail-alert" />
      </div>

      <!-- 结果表 -->
      <div class="cfg-card result-card" v-if="session?.status === 'done'">
        <div class="result-head">
          <div class="cfg-label" style="margin:0"><el-icon><Document /></el-icon>线索结果 <span class="cfg-hint">（{{ total }} 条）</span></div>
          <div class="export-btns">
            <el-button size="small" @click="doExport('xlsx')" :loading="exporting" :disabled="total === 0">
              <el-icon><Download /></el-icon>Excel
            </el-button>
            <el-button size="small" @click="doExport('csv')" :loading="exporting" :disabled="total === 0">
              <el-icon><Download /></el-icon>CSV
            </el-button>
          </div>
        </div>

        <div class="filter-row">
          <el-input v-model="filter.keyword" placeholder="搜索公司 / 人名 / 邮箱 / 行业…" clearable
                    style="width: 260px" @keyup.enter="loadLeads(1)">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-select v-model="filter.emailStatus" placeholder="邮箱状态" clearable style="width: 140px">
            <el-option label="Verified" value="verified" />
            <el-option label="Accept-all" value="accept-all" />
            <el-option label="Unverified" value="unverified" />
            <el-option label="Invalid" value="invalid" />
          </el-select>
          <el-select v-model="filter.minScore" placeholder="最低 ICP 分" clearable style="width: 130px">
            <el-option v-for="v in [40, 50, 60, 70, 80]" :key="v" :label="`${v}+`" :value="v" />
          </el-select>
          <el-checkbox v-model="filter.onlyWithContact">仅看有联系人的公司</el-checkbox>
          <el-button type="primary" plain size="default" @click="loadLeads(1)">筛选</el-button>
        </div>

        <el-table :data="rows" v-loading="loadingLeads" class="lead-table"
                  :row-key="(r: any) => `${r.companyId}-${r.contactId ?? 0}`">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="expand-grid">
                <div class="eg-item"><span>Industry</span><b>{{ row.industry || '-' }}</b></div>
                <div class="eg-item"><span>Major Business</span><b>{{ row.majorBusiness || '-' }}</b></div>
                <div class="eg-item"><span>主营业务（中文）</span><b>{{ row.majorBusinessCn || '-' }}</b></div>
                <div class="eg-item"><span>Address</span><b>{{ fullAddress(row) }}</b></div>
                <div class="eg-item"><span>Company Size</span><b>{{ row.companySize || '-' }}</b></div>
                <div class="eg-item"><span>Competitor</span><b>{{ row.competitor || '-' }}</b></div>
                <div class="eg-item"><span>Source</span><b class="src-link" @click="openUrl(row.source)">{{ row.source || '-' }}</b></div>
                <div class="eg-item"><span>Contact Source</span><b>{{ row.contactSource || '-' }}</b></div>
                <div class="eg-item"><span>Verification</span><b>{{ row.verificationStatus || '-' }}</b></div>
                <div class="eg-item"><span>Remarks</span><b>{{ row.remarks || '-' }}</b></div>
                <div class="eg-item"><span>Search Date</span><b>{{ row.searchDate || '-' }}</b></div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="Company" min-width="180">
            <template #default="{ row }">
              <div class="co-cell">
                <b class="co-name">{{ row.company }}</b>
                <span class="co-web" @click="openUrl(row.website)">{{ row.website }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="country" label="Country" width="120" />
          <el-table-column label="Contact" min-width="190">
            <template #default="{ row }">
              <div v-if="row.person || row.email">
                <b>{{ row.person || '-' }}</b>
                <div class="ct-title">{{ row.title || '' }}</div>
              </div>
              <span v-else class="no-ct">无联系人</span>
            </template>
          </el-table-column>
          <el-table-column label="Email" min-width="210">
            <template #default="{ row }">
              <div v-if="row.email" class="email-cell">
                <span class="email-text">{{ row.email }}</span>
                <span class="es-tag" :class="row.emailStatus">{{ esText(row.emailStatus) }}</span>
              </div>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="customerType" label="Type" width="130" />
          <el-table-column label="ICP" width="80" align="center">
            <template #default="{ row }">
              <span class="score-badge" :class="scoreLevel(row.icpScore)">{{ row.icpScore ?? '-' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="Contact 分" width="90" align="center">
            <template #default="{ row }">
              <span class="score-badge sm" :class="scoreLevel(row.contactScore)">{{ row.contactScore ?? '-' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="pager-row" v-if="total > filter.size">
          <el-pagination background layout="prev, pager, next" :total="total"
                         :page-size="filter.size" :current-page="filter.page"
                         @current-change="loadLeads" />
        </div>
      </div>
      </div>

      <!-- 右侧：全球客户分布地图（世界 + 七大洲，悬停国家查看客户数） -->
      <aside class="run-map">
        <LeadMapCard :stats="mapStats" scope-label="本任务" />
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Location, OfficeBuilding, Goods, DataLine, Promotion, Clock, ArrowRight, Plus,
  CircleCheckFilled, CircleCloseFilled, Aim, Document, Download, Search, Refresh
} from '@element-plus/icons-vue'
import {
  leadHunterApi, downloadExport,
  type HuntStep, type HuntStats, type HuntSession, type LeadRow, type CountryStat
} from '@/api/leadHunter'
import LeadMapCard from './LeadMapCard.vue'

// ═══ 配置选项 ═══
const COUNTRY_OPTIONS = [
  'United States - East', 'United States - South', 'United States - West',
  'Canada', 'Mexico', 'Brazil',
  'United Kingdom', 'Germany', 'France', 'Netherlands', 'Spain', 'Italy', 'Sweden', 'Poland',
  'UAE', 'Saudi Arabia', 'Turkey', 'Israel', 'South Africa',
  'Australia', 'New Zealand',
  'Japan', 'South Korea', 'Singapore', 'India', 'Indonesia', 'Malaysia', 'Thailand', 'Vietnam'
]
const CUSTOMER_TYPES = [
  'Distributor', 'Reseller', 'System Integrator', 'Dealer', 'Project Contractor',
  'End User', 'Online Shop', 'VOIP/Cloud Service Provider', 'Consultancy'
]
const PRODUCT_OPTIONS = ['IP PA System', 'IP Intercom', 'Unified Communication', 'Conference AV', 'Passive Speakers']

const form = reactive({
  countries: ['United States - West'] as string[],
  customerTypes: ['Distributor', 'Reseller'] as string[],
  products: [] as string[],
  targetCount: 20
})

function toggle(arr: string[], v: string) {
  const i = arr.indexOf(v)
  if (i >= 0) arr.splice(i, 1)
  else arr.push(v)
}

// 美国三大区下拉展示中文名（值仍为英文区域名，供检索/入库/去重）
const US_REGION_LABELS: Record<string, string> = {
  'United States - East': '美国东部（NY·NJ·MA·PA·IL 等 24 州+DC）',
  'United States - South': '美国南部（FL·GA·NC·VA·TX 等 14 州）',
  'United States - West': '美国西部（CA·WA·OR·AZ·CO 等 13 州）'
}
function countryLabel(c: string) {
  return US_REGION_LABELS[c] || c
}

const canStart = computed(() => form.countries.length > 0)

// ═══ 状态机 ═══
type Phase = 'config' | 'run'
const phase = ref<Phase>('config')
const starting = ref(false)
const sessionId = ref<number>(0)
const session = ref<HuntSession | null>(null)
const queuePosition = ref(0)
const steps = ref<HuntStep[]>([])
const stats = reactive<HuntStats>({
  discovered: 0, verifiedCompanies: 0, contacts: 0,
  emailVerified: 0, duplicates: 0, rejected: 0, finalLeads: 0
})
const sessions = ref<HuntSession[]>([])
let pollTimer: ReturnType<typeof setInterval> | null = null
const pollingStatus = ref(false)
let pollFailures = 0
let lastMappedDiscovered = -1

// 全球分布地图数据
const mapStats = ref<CountryStat[]>([])
async function loadMapStats() {
  try {
    const currentSessionId = phase.value === 'run' && sessionId.value > 0 ? sessionId.value : undefined
    const list = await leadHunterApi.mapStats(currentSessionId)
    mapStats.value = (list || []).map((r: any) => ({
      country: String(r.country || ''),
      count: Number(r.cnt ?? r.count ?? 0)
    }))
  } catch { /* ignore */ }
}

// 结果表
const rows = ref<LeadRow[]>([])
const total = ref(0)
const loadingLeads = ref(false)
const exporting = ref(false)
const filter = reactive({
  keyword: '', emailStatus: '', minScore: undefined as number | undefined,
  onlyWithContact: false, page: 1, size: 20
})

// ═══ 启动 ═══
async function start() {
  if (!form.countries.length) return
  starting.value = true
  try {
    const { sessionId: sid } = await leadHunterApi.start({
      countries: form.countries,
      customerTypes: form.customerTypes,
      products: form.products,
      targetCount: form.targetCount
    })
    sessionId.value = sid
    phase.value = 'run'
    resetRun()
    loadMapStats()
    startPolling()
  } catch (e: any) {
    ElMessage.error(e?.message || '启动失败')
  } finally {
    starting.value = false
  }
}

function resetRun() {
  session.value = null
  queuePosition.value = 0
  steps.value = []
  Object.assign(stats, {
    discovered: 0, verifiedCompanies: 0, contacts: 0,
    emailVerified: 0, duplicates: 0, rejected: 0, finalLeads: 0
  })
  rows.value = []
  total.value = 0
  pollFailures = 0
  lastMappedDiscovered = -1
  mapStats.value = []
}

function startPolling() {
  stopPolling()
  pollFailures = 0
  pollTimer = setInterval(pollStatus, 2000)
  pollStatus()
}

function stopPolling() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null }
}

async function pollStatus(manual = false) {
  if (pollingStatus.value || !sessionId.value) return
  pollingStatus.value = true
  try {
    const st = await leadHunterApi.status(sessionId.value)
    pollFailures = 0
    session.value = st.session
    queuePosition.value = st.queuePosition || 0
    steps.value = st.steps || []
    if (st.stats) {
      Object.assign(stats, st.stats)
      if (st.stats.discovered !== lastMappedDiscovered) {
        lastMappedDiscovered = st.stats.discovered
        loadMapStats()
      }
    }
    if (st.session.status !== 'running' && st.session.status !== 'queued') {
      stopPolling()
      if (st.session.status === 'done') {
        loadLeads(1)
        loadMapStats()
      }
    }
  } catch (e: any) {
    pollFailures++
    if (manual || pollFailures === 3) {
      ElMessage.warning(e?.message || '状态连接暂时中断，正在自动重试')
    }
    if (pollFailures >= 10) {
      stopPolling()
      ElMessage.error('已停止自动重试，请点击“刷新状态”恢复')
    }
  } finally {
    pollingStatus.value = false
  }
}

async function refreshStatus() {
  await pollStatus(true)
  if ((session.value?.status === 'running' || session.value?.status === 'queued') && !pollTimer) {
    pollFailures = 0
    pollTimer = setInterval(pollStatus, 2000)
  }
}

async function cancelTask() {
  try {
    await ElMessageBox.confirm('取消后将释放排队/执行资源，已产生的中间结果会保留。', '取消全球获客任务', {
      confirmButtonText: '确认取消', cancelButtonText: '继续执行', type: 'warning'
    })
    await leadHunterApi.cancel(sessionId.value)
    stopPolling()
    await pollStatus(true)
    ElMessage.success('任务已取消')
  } catch (e: any) {
    if (e === 'cancel' || e === 'close') return
    ElMessage.error(e?.message || '取消任务失败')
  }
}

// ═══ 结果 ═══
async function loadLeads(page = 1) {
  filter.page = page
  loadingLeads.value = true
  try {
    const res = await leadHunterApi.leads(sessionId.value, {
      keyword: filter.keyword || undefined,
      emailStatus: filter.emailStatus || undefined,
      minScore: filter.minScore,
      onlyWithContact: filter.onlyWithContact || undefined,
      page: filter.page,
      size: filter.size
    })
    rows.value = res.records
    total.value = res.total
  } catch (e: any) {
    ElMessage.error(e?.message || '加载结果失败')
  } finally {
    loadingLeads.value = false
  }
}

async function doExport(format: 'xlsx' | 'csv') {
  exporting.value = true
  try {
    await downloadExport(sessionId.value, format)
    ElMessage.success('导出成功')
  } catch (e: any) {
    ElMessage.error(e?.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

// ═══ 历史 ═══
async function loadSessions() {
  try {
    sessions.value = await leadHunterApi.sessions()
  } catch { /* ignore */ }
}

function viewSession(id: number) {
  sessionId.value = id
  phase.value = 'run'
  resetRun()
  loadMapStats()
  startPolling()
}

function backToConfig() {
  stopPolling()
  phase.value = 'config'
  loadSessions()
  loadMapStats()
}

// ═══ 展示工具 ═══
function statusText(s: string) {
  return s === 'done' ? '已完成' : s === 'failed' ? '失败' : s === 'cancelled' ? '已取消' : s === 'queued' ? '排队中' : '运行中'
}
function shortTime(t?: string) {
  return t ? t.replace('T', ' ').slice(0, 16) : ''
}
function esText(s: string) {
  const map: Record<string, string> = {
    verified: '已验证', 'accept-all': '接收全部', unverified: '未验证', invalid: '无效'
  }
  return map[s] || s || ''
}
function scoreLevel(v?: number | null) {
  if (v == null) return ''
  if (v >= 80) return 'hi'
  if (v >= 60) return 'mid'
  return 'lo'
}
function fullAddress(r: LeadRow) {
  const parts = [r.address, r.city, r.state, r.zip].filter(Boolean)
  return parts.length ? parts.join(', ') : '-'
}
function openUrl(url?: string) {
  if (url) window.open(url, '_blank')
}

loadSessions()
loadMapStats()

// 从市场机会地图跳转过来时预选国家
const route = useRoute()
if (route.query.country && typeof route.query.country === 'string') {
  form.countries = [route.query.country]
}

onBeforeUnmount(stopPolling)
</script>

<style scoped>
.lh-page {
  --bg: #F5F5F7; --card: #FFFFFF; --hair: rgba(0, 0, 0, .08);
  --ink: #1D1D1F; --ink2: #6E6E73; --ink3: #AEAEB2;
  --accent: #0071E3; --green: #34C759; --warn: #FF9F0A; --red: #FF3B30;
  min-height: 100%;
  height: 100%;
  overflow-y: auto;
  overflow-x: hidden;
  background: var(--bg);
  color: var(--ink);
  padding: 28px clamp(16px, 4vw, 48px) 48px;
}

/* ═══ 页头 ═══ */
.eyebrow { display: inline-flex; align-items: center; gap: 8px; font-size: 11px; font-weight: 700; color: var(--accent); letter-spacing: .08em; text-transform: uppercase; }
.pulse { width: 6px; height: 6px; border-radius: 50%; background: var(--green); animation: pulse 2s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1 } 50% { opacity: .3 } }
.lh-title { font-size: 34px; font-weight: 800; letter-spacing: -.02em; margin: 16px 0 6px; }
.lh-sub { color: var(--ink2); font-size: 14px; line-height: 1.6; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* ═══ 配置卡 ═══ */
.lh-config { max-width: 1520px; display: grid; grid-template-columns: minmax(0, 860px) minmax(340px, 1fr); gap: 22px; align-items: flex-start; }
.config-left { min-width: 0; }
.cfg-card { background: var(--card); border: 1px solid var(--hair); border-radius: 20px; padding: 28px; box-shadow: 0 1px 3px rgba(0, 0, 0, .04); margin-top: 22px; }
.cfg-block { margin-bottom: 24px; }
.cfg-label { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; margin-bottom: 12px; color: var(--ink); }
.cfg-label .el-icon { color: var(--accent); }
.cfg-hint { font-weight: 400; font-size: 12px; color: var(--ink3); }
.cfg-select { width: 100%; }
.chip-row { display: flex; flex-wrap: wrap; gap: 8px; }
.chip { padding: 8px 16px; border-radius: 980px; border: 1px solid var(--hair); background: #fff; font-size: 13px; color: var(--ink2); cursor: pointer; transition: all .2s; }
.chip:hover { border-color: rgba(0, 113, 227, .35); color: var(--ink); }
.chip.active { background: rgba(0, 113, 227, .08); border-color: rgba(0, 113, 227, .5); color: var(--accent); font-weight: 600; }
.accent-num { color: var(--accent); font-size: 18px; }
.cfg-footer { display: flex; justify-content: space-between; align-items: flex-end; gap: 24px; border-top: 1px solid var(--hair); padding-top: 22px; }
.cfg-note { font-size: 12px; color: var(--ink3); line-height: 1.8; }
.start-btn { background: #1D1D1F; color: #fff; border: 0; border-radius: 980px; padding: 14px 34px; font-size: 15px; font-weight: 600; height: auto; }
.start-btn:hover:not(:disabled) { background: #17181c; transform: translateY(-1px); }
.start-btn:disabled { opacity: .4; }

/* 历史 */
.history-card { padding: 20px 28px; }
.session-list { display: flex; flex-direction: column; }
.session-item { display: flex; align-items: center; gap: 14px; padding: 12px 8px; border-bottom: 1px solid rgba(0, 0, 0, .04); cursor: pointer; transition: background .15s; font-size: 13px; }
.session-item:hover { background: rgba(0, 113, 227, .04); }
.session-item:last-child { border-bottom: 0; }
.s-id { color: var(--ink3); font-family: 'SF Mono', Menlo, monospace; }
.s-countries { flex: 1; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.s-count { color: var(--ink2); }
.s-status { font-size: 12px; padding: 2px 10px; border-radius: 980px; }
.s-status.done { background: #E8F8ED; color: #1F9254; }
.s-status.failed { background: #FFE4E6; color: #D43A2F; }
.s-status.cancelled { background: #F2F2F7; color: var(--ink2); }
.s-status.running { background: #EBF3FF; color: var(--accent); }
.s-status.queued { background: #FFF1E0; color: #B26A00; }
.s-time { color: var(--ink3); font-size: 12px; }
.s-go { color: var(--ink3); }

/* ═══ 运行 ═══ */
.lh-run { max-width: 1520px; display: grid; grid-template-columns: minmax(0, 1fr) minmax(360px, 520px); gap: 22px; align-items: flex-start; }
.run-main { min-width: 0; }
.run-map { min-width: 0; position: sticky; top: 20px; }
.run-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 14px; }
.run-title { font-size: 20px; font-weight: 700; display: flex; align-items: center; gap: 10px; }
.run-meta { color: var(--ink2); font-size: 13px; margin-top: 4px; }
.run-progress { margin-bottom: 22px; }

.steps-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 10px; margin-bottom: 20px; }
.step-item { display: flex; gap: 12px; align-items: flex-start; padding: 12px 14px; border-radius: 14px; border: 1px solid var(--hair); background: #fff; transition: all .25s; }
.step-item.running { border-color: rgba(0, 113, 227, .4); background: #F7FBFF; }
.step-item.done { opacity: .75; }
.step-item.failed { border-color: rgba(255, 59, 48, .4); background: #FFF7F7; }
.step-icon { width: 22px; height: 22px; flex-shrink: 0; display: flex; align-items: center; justify-content: center; }
.step-icon .el-icon { font-size: 20px; color: var(--green); }
.step-item.failed .step-icon .el-icon { color: var(--red); }
.step-dot { width: 10px; height: 10px; border-radius: 50%; border: 2px solid var(--ink3); opacity: .5; }
.spinner { width: 16px; height: 16px; border: 2.5px solid rgba(0, 113, 227, .2); border-top-color: var(--accent); border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg) } }
.step-name { font-size: 13.5px; font-weight: 600; }
.step-idx { display: inline-block; min-width: 20px; color: var(--ink3); font-size: 11px; font-family: 'SF Mono', Menlo, monospace; }
.step-detail { font-size: 12px; color: var(--ink2); margin-top: 3px; line-height: 1.5; }

.stat-row { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 18px; }
.stat-chip { flex: 1; min-width: 110px; background: #F5F5F7; border-radius: 14px; padding: 14px 16px; text-align: center; }
.stat-chip b { display: block; font-size: 24px; font-weight: 800; color: var(--ink); }
.stat-chip span { font-size: 12px; color: var(--ink2); }
.stat-chip.ok b { color: var(--green); }
.stat-chip.warn b { color: var(--warn); }
.stat-chip.final { background: #EBF3FF; }
.stat-chip.final b { color: var(--accent); }

.icp-box { background: #F7FBFF; border: 1px solid rgba(0, 113, 227, .15); border-radius: 14px; padding: 16px 18px; }
.icp-title { display: flex; align-items: center; gap: 6px; font-weight: 700; font-size: 13px; color: var(--accent); margin-bottom: 8px; }
.icp-content { font-size: 13px; color: var(--ink2); line-height: 1.8; white-space: pre-wrap; }
.fail-alert { margin-top: 14px; }

/* ═══ 结果表 ═══ */
.result-card { padding: 20px 24px; }
.result-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.export-btns .el-button { border-radius: 980px; }
.filter-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; }
.lead-table { width: 100%; }
.co-cell .co-name { display: block; font-size: 13.5px; }
.co-cell .co-web { font-size: 12px; color: var(--accent); cursor: pointer; }
.co-cell .co-web:hover { text-decoration: underline; }
.ct-title { font-size: 12px; color: var(--ink2); }
.no-ct { color: var(--ink3); font-size: 12px; }
.email-cell .email-text { font-size: 12.5px; font-family: 'SF Mono', Menlo, monospace; }
.es-tag { display: inline-block; margin-left: 6px; font-size: 11px; padding: 1px 8px; border-radius: 980px; }
.es-tag.verified { background: #E8F8ED; color: #1F9254; }
.es-tag.accept-all { background: #FFF1E0; color: #B26A00; }
.es-tag.unverified { background: #F2F2F7; color: var(--ink2); }
.es-tag.invalid { background: #FFE4E6; color: #D43A2F; }
.score-badge { display: inline-flex; align-items: center; justify-content: center; min-width: 38px; padding: 3px 8px; border-radius: 980px; font-weight: 700; font-size: 13px; }
.score-badge.hi { background: #E8F8ED; color: #1F9254; }
.score-badge.mid { background: #EBF3FF; color: var(--accent); }
.score-badge.lo { background: #F2F2F7; color: var(--ink2); }
.score-badge.sm { font-size: 12px; min-width: 32px; }
.expand-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px 24px; padding: 8px 16px; }
.eg-item span { display: block; font-size: 11px; color: var(--ink3); text-transform: uppercase; letter-spacing: .06em; margin-bottom: 3px; }
.eg-item b { font-size: 13px; font-weight: 500; word-break: break-all; }
.src-link { color: var(--accent); cursor: pointer; }
.pager-row { display: flex; justify-content: center; margin-top: 18px; }
.tag-running { background: #EBF3FF; color: var(--accent); border-color: transparent; }

@media (max-width: 1280px) {
  .lh-run, .lh-config { flex-direction: column; }
  .run-map { width: 100%; position: static; }
}

@media (max-width: 720px) {
  .cfg-footer { flex-direction: column; align-items: stretch; }
  .start-btn { width: 100%; }
  .steps-grid { grid-template-columns: 1fr; }
}
</style>
