<template>
  <div class="cme-page">
    <div class="cme-head">
      <div class="eyebrow"><span class="pulse"></span>CUSTOMER MATCHING ENGINE · AI 销售情报引擎</div>
      <h1 class="cme-title">客户匹配引擎</h1>
      <p class="cme-sub">输入客户名称，AI 自动分析公司画像 · 产品匹配 · 竞品情报 · Fit Score · 销售策略</p>
    </div>

    <!-- 搜索栏 -->
    <div class="search-bar cfg-card">
      <el-input v-model="query" placeholder="请输入客户名称 / 域名（如 Audio Installations / zycoo.com）"
                size="large" clearable @keyup.enter="analyze" class="search-input">
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>
      <el-button class="match-btn" :loading="loading" @click="analyze" :disabled="!query.trim()">
        <el-icon v-if="!loading"><Aim /></el-icon>{{ loading ? 'AI 分析中…' : '开始匹配' }}
      </el-button>
    </div>

    <!-- 分析过程动画 -->
    <div class="process-bar cfg-card" v-if="loading || result">
      <div class="process-step" v-for="(s,i) in STEPS" :key="i" :class="stepStatus(i)">
        <span class="step-ic">
          <span v-if="loading && currentStep === i" class="spinner"></span>
          <el-icon v-else-if="isStepDone(i)"><CircleCheckFilled /></el-icon>
          <span v-else class="dot"></span>
        </span>
        <span class="step-txt">{{ s }}</span>
      </div>
    </div>

    <!-- 分析报告 -->
    <div class="report" v-if="result">
      <!-- Fit Score 横幅 -->
      <div class="score-banner cfg-card">
        <div class="score-left">
          <div class="score-label">ZYCOO Fit Score</div>
          <div class="score-num" :class="result.grade">{{ result.fitScore }}<span>/100</span></div>
          <div class="score-grade" :class="result.grade">{{ gradeText(result.grade) }}</div>
        </div>
        <div class="score-right">
          <div class="breakdown-row" v-for="(v,k) in result.scoreBreakdown" :key="k">
            <span class="bk-label">{{ k }}</span>
            <div class="bk-bar"><div class="bk-fill" :style="{ width: (v / (k === '产品匹配' ? 30 : k === '行业匹配' ? 20 : 15) * 100) + '%' }"></div></div>
            <span class="bk-val">{{ v }}</span>
          </div>
        </div>
      </div>

      <div class="report-grid">
        <!-- 公司情报 -->
        <div class="report-card cfg-card" v-if="result.company">
          <div class="rc-title"><el-icon><OfficeBuilding /></el-icon>Company Intelligence</div>
          <div class="ci-row" v-if="result.company.name"><span>公司名</span><b>{{ result.company.name }}</b></div>
          <div class="ci-row" v-if="result.company.country"><span>国家</span><b>{{ result.company.country }}</b></div>
          <div class="ci-row" v-if="result.company.customerType"><span>类型</span><b>{{ result.company.customerType }}</b></div>
          <div class="ci-row" v-if="result.company.companySize"><span>规模</span><b>{{ result.company.companySize }}</b></div>
          <div class="ci-row" v-if="result.company.industry"><span>行业</span><b>{{ result.company.industry }}</b></div>
          <div class="ci-row" v-if="result.company.majorBusiness"><span>主营</span><b>{{ result.company.majorBusiness }}</b></div>
          <div class="ci-row" v-if="result.company.website"><span>官网</span><b class="link" @click="openUrl(result.company.website)">{{ result.company.website }}</b></div>
          <div class="ci-row"><span>联系人</span><b>{{ result.contactCount }} 个</b></div>
          <div class="ci-row" v-if="result.found" style="color:var(--green)"><span>状态</span><b>✓ 已在客户库中</b></div>
          <div class="ci-row" v-else style="color:var(--ink3)"><span>状态</span><b>未在客户库（基于公开信息分析）</b></div>
        </div>

        <!-- 业务能力 -->
        <div class="report-card cfg-card" v-if="result.businessCapability.length">
          <div class="rc-title"><el-icon><Cpu /></el-icon>Business Capability</div>
          <div class="cap-item" v-for="cap in result.businessCapability" :key="cap">
            <span class="cap-check">✓</span>{{ cap }}
          </div>
        </div>

        <!-- 产品匹配 -->
        <div class="report-card cfg-card wide" v-if="result.productMatches.length">
          <div class="rc-title"><el-icon><Goods /></el-icon>Product Match</div>
          <div class="prod-card" v-for="(p,i) in result.productMatches" :key="i">
            <div class="prod-head">
              <span class="prod-no">{{ i+1 }}</span>
              <span class="prod-name">{{ p.product }}</span>
              <span class="prod-score" :class="scoreLevel(p.score)">{{ p.score }}%</span>
            </div>
            <div class="prod-reasons">
              <div v-for="(r,j) in (p.reasons||[])" :key="j" class="prod-reason">· {{ r }}</div>
            </div>
            <div class="prod-models" v-if="p.models?.length">
              推荐型号：<b v-for="m in p.models" :key="m" class="model-tag">{{ m }}</b>
            </div>
          </div>
        </div>

        <!-- 应用场景 -->
        <div class="report-card cfg-card" v-if="result.applications.length">
          <div class="rc-title"><el-icon><Aim /></el-icon>Recommended Applications</div>
          <div class="app-item" v-for="(a,i) in result.applications" :key="i">
            <div class="app-head">
              <span class="app-name">{{ a.name }}</span>
              <span class="app-stars">{{ '★'.repeat(a.stars) }}<span class="dim">{{ '★'.repeat(5-a.stars) }}</span></span>
            </div>
            <div class="app-reason">{{ a.reason }}</div>
            <div class="app-solution">推荐方案：<b>{{ a.solution }}</b></div>
          </div>
        </div>

        <!-- 竞品情报 -->
        <div class="report-card cfg-card" v-if="result.competitorIntel?.brand">
          <div class="rc-title"><el-icon><WarnTriangleFilled /></el-icon>Competitor Intelligence</div>
          <div class="ci-row"><span>发现竞品</span><b class="comp-brand">{{ result.competitorIntel.brand }}</b></div>
          <div class="ci-row"><span>关系</span><b>{{ result.competitorIntel.relation }}</b></div>
          <div class="ci-row"><span>替代机会</span><b>{{ '★'.repeat(result.competitorIntel.opportunity || 0) }}</b></div>
          <div class="ai-strategy" v-if="result.competitorIntel.strategy">
            <b>AI 策略：</b>{{ result.competitorIntel.strategy }}
          </div>
        </div>

        <!-- 销售策略 -->
        <div class="report-card cfg-card wide" v-if="result.salesStrategy?.first_contact_angle">
          <div class="rc-title"><el-icon><ChatLineSquare /></el-icon>Sales Strategy</div>
          <div class="strat-section">
            <div class="strat-label">First Contact Angle</div>
            <div class="strat-content">{{ result.salesStrategy.first_contact_angle }}</div>
          </div>
          <div class="strat-section" v-if="result.salesStrategy.email_subjects?.length">
            <div class="strat-label">推荐邮件主题</div>
            <div class="email-sub" v-for="(s,i) in result.salesStrategy.email_subjects" :key="i">
              <span class="email-no">{{ i+1 }}</span>{{ s }}
            </div>
          </div>
        </div>

        <!-- AI 解释 -->
        <div class="report-card cfg-card wide" v-if="result.whyExplanation">
          <div class="rc-title"><el-icon><QuestionFilled /></el-icon>Why? · AI 评分解释</div>
          <div class="why-text">{{ result.whyExplanation }}</div>
        </div>
      </div>

      <!-- 行动按钮 -->
      <div class="action-bar cfg-card">
        <button class="act-btn primary" @click="findSimilar"><el-icon><Promotion /></el-icon>寻找类似客户</button>
        <button class="act-btn" @click="viewGraph"><el-icon><Share /></el-icon>查看知识图谱</button>
        <button class="act-btn disabled" disabled>生成开发邮件 <span class="v2">v2</span></button>
        <button class="act-btn disabled" disabled>加入 CRM <span class="v2">v2</span></button>
        <button class="act-btn disabled" disabled>生成报价方案 <span class="v2">v2</span></button>
      </div>
    </div>

    <!-- 空状态 -->
    <div class="empty-state cfg-card" v-if="!loading && !result">
      <el-icon :size="48" color="#AEAEB2"><Aim /></el-icon>
      <p class="empty-title">输入客户名称开始 AI 分析</p>
      <p class="empty-hint">支持公司名 / 域名 / 官网 URL · 也可输入客户库中已有公司获取更丰富数据</p>
      <div class="example-row">
        <button class="ex-chip" v-for="ex in EXAMPLES" :key="ex" @click="query=ex; analyze()">{{ ex }}</button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Search, Aim, CircleCheckFilled, OfficeBuilding, Cpu, Goods, WarnTriangleFilled,
  ChatLineSquare, QuestionFilled, Promotion, Share
} from '@element-plus/icons-vue'
import { customerMatchingApi, type AnalysisResult } from '@/api/customerMatching'

const router = useRouter()
const query = ref('')
const loading = ref(false)
const result = ref<AnalysisResult | null>(null)
const currentStep = ref(0)

const STEPS = ['公司信息', '官网业务', '行业属性', '产品匹配', '竞品关系', '历史数据']
const EXAMPLES = ['Audio Installations', 'Commend', 'Audiobrands', 'd&b audiotechnik']

let stepTimer: ReturnType<typeof setInterval> | null = null

function stepStatus(i: number) {
  if (loading.value && i < currentStep.value) return 'done'
  if (loading.value && i === currentStep.value) return 'running'
  if (result.value) return 'done'
  return 'pending'
}
function isStepDone(i: number) {
  return result.value || (loading.value && i < currentStep.value)
}

async function analyze() {
  if (!query.value.trim()) return
  loading.value = true
  result.value = null
  currentStep.value = 0
  stepTimer = setInterval(() => {
    if (currentStep.value < STEPS.length - 1) currentStep.value++
  }, 1800)
  try {
    result.value = await customerMatchingApi.analyze(query.value.trim())
  } catch (e: any) {
    ElMessage.error(e?.message || '分析失败')
  } finally {
    if (stepTimer) { clearInterval(stepTimer); stepTimer = null }
    loading.value = false
  }
}

function gradeText(g: string) {
  return g === 'A+' ? 'A+ 立即开发' : g === 'A' ? 'A 重点跟进' : g === 'B' ? 'B 持续培育' : 'C 观察池'
}
function scoreLevel(s: number) {
  return s >= 90 ? 'hi' : s >= 75 ? 'mid' : 'lo'
}
function openUrl(url?: string) { if (url) window.open(url, '_blank') }
function findSimilar() {
  router.push({ path: '/lead-hunter', query: { country: result.value?.company?.country || '' } })
}
function viewGraph() { router.push('/knowledge-graph') }
</script>

<style scoped>
.cme-page {
  --bg: #F5F5F7; --card: #FFFFFF; --hair: rgba(0,0,0,.08);
  --ink: #1D1D1F; --ink2: #6E6E73; --ink3: #AEAEB2;
  --accent: #0071E3; --green: #34C759; --warn: #FF9F0A; --red: #FF3B30;
  height: 100%; overflow-y: auto; overflow-x: hidden;
  background: var(--bg); color: var(--ink);
  padding: 28px clamp(16px,4vw,48px) 48px;
}
.cme-head { margin-bottom: 18px; }
.eyebrow { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; color: var(--ink2); letter-spacing: .12em; text-transform: uppercase; padding: 6px 16px; border: 1px solid var(--hair); border-radius: 980px; background: rgba(255,255,255,.6); }
.pulse { width: 6px; height: 6px; border-radius: 50%; background: var(--green); animation: pulse 2s ease-in-out infinite; }
@keyframes pulse { 0%,100%{opacity:1} 50%{opacity:.3} }
.cme-title { font-size: 34px; font-weight: 800; letter-spacing: -.02em; margin: 16px 0 6px; }
.cme-sub { color: var(--ink2); font-size: 14px; }

.search-bar { display: flex; gap: 12px; padding: 18px; margin-bottom: 16px; }
.search-input { flex: 1; }
.match-btn { background: linear-gradient(135deg,#0071E3,#1F8FFF); color: #fff; border: 0; border-radius: 980px; padding: 0 28px; font-size: 14px; font-weight: 600; height: 40px; }
.match-btn:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 4px 14px rgba(0,113,227,.3); }

.process-bar { display: flex; gap: 8px; padding: 16px 18px; margin-bottom: 16px; flex-wrap: wrap; }
.process-step { display: flex; align-items: center; gap: 8px; padding: 8px 16px; border-radius: 980px; border: 1px solid var(--hair); font-size: 13px; color: var(--ink2); transition: all .3s; }
.process-step.done { border-color: rgba(52,199,89,.4); background: #F0FBF4; color: var(--green); }
.process-step.running { border-color: rgba(0,113,227,.4); background: #F7FBFF; color: var(--accent); }
.step-ic { display: flex; align-items: center; justify-content: center; width: 18px; height: 18px; }
.process-step.done .step-ic .el-icon { font-size: 16px; color: var(--green); }
.spinner { width: 14px; height: 14px; border: 2px solid rgba(0,113,227,.2); border-top-color: var(--accent); border-radius: 50%; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg) } }
.dot { width: 8px; height: 8px; border-radius: 50%; border: 2px solid var(--ink3); opacity: .4; }

.score-banner { display: flex; gap: 32px; padding: 24px 28px; margin-bottom: 16px; align-items: center; }
.score-left { flex-shrink: 0; text-align: center; }
.score-label { font-size: 12px; color: var(--ink2); text-transform: uppercase; letter-spacing: .08em; }
.score-num { font-size: 56px; font-weight: 800; line-height: 1.1; }
.score-num span { font-size: 18px; color: var(--ink3); font-weight: 400; }
.score-num.A\+ { color: #AF52DE; } .score-num.A { color: var(--accent); }
.score-num.B { color: var(--warn); } .score-num.C { color: var(--ink3); }
.score-grade { font-size: 13px; font-weight: 600; padding: 4px 14px; border-radius: 980px; margin-top: 4px; display: inline-block; }
.score-grade.A\+ { background: #F3E8FF; color: #AF52DE; }
.score-grade.A { background: #EBF3FF; color: var(--accent); }
.score-grade.B { background: #FFF1E0; color: #B26A00; }
.score-grade.C { background: #F2F2F7; color: var(--ink2); }
.score-right { flex: 1; display: flex; flex-direction: column; gap: 6px; }
.breakdown-row { display: flex; align-items: center; gap: 10px; font-size: 12px; }
.bk-label { width: 80px; color: var(--ink2); }
.bk-bar { flex: 1; height: 6px; background: #F2F2F7; border-radius: 980px; overflow: hidden; }
.bk-fill { height: 100%; border-radius: 980px; background: linear-gradient(90deg,#5AA9F5,#0071E3); }
.bk-val { width: 28px; text-align: right; font-weight: 700; font-family: 'SF Mono',Menlo,monospace; }

.report-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.report-card { padding: 20px; }
.report-card.wide { grid-column: 1 / -1; }
.rc-title { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 700; margin-bottom: 14px; color: var(--ink); }
.rc-title .el-icon { color: var(--accent); }
.ci-row { display: flex; gap: 10px; padding: 5px 0; font-size: 13px; border-bottom: 1px solid rgba(0,0,0,.03); }
.ci-row:last-child { border-bottom: 0; }
.ci-row span { width: 60px; color: var(--ink3); flex-shrink: 0; }
.ci-row b { font-weight: 550; }
.ci-row .link { color: var(--accent); cursor: pointer; }
.cap-item { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 13px; }
.cap-check { color: var(--green); font-weight: 700; }
.prod-card { padding: 14px; border: 1px solid var(--hair); border-radius: 14px; margin-bottom: 12px; }
.prod-card:last-child { margin-bottom: 0; }
.prod-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.prod-no { width: 22px; height: 22px; border-radius: 50%; background: var(--accent); color: #fff; font-size: 12px; font-weight: 700; display: flex; align-items: center; justify-content: center; }
.prod-name { flex: 1; font-weight: 600; font-size: 14px; }
.prod-score { font-weight: 800; font-size: 16px; }
.prod-score.hi { color: var(--green); } .prod-score.mid { color: var(--accent); } .prod-score.lo { color: var(--ink3); }
.prod-reasons { font-size: 12px; color: var(--ink2); line-height: 1.7; margin-bottom: 8px; }
.prod-models { font-size: 12px; color: var(--ink2); }
.model-tag { display: inline-block; padding: 2px 10px; margin: 0 4px 0 0; border-radius: 6px; background: #F2F2F7; font-weight: 600; color: var(--ink); }
.app-item { padding: 12px 0; border-bottom: 1px solid rgba(0,0,0,.03); }
.app-item:last-child { border-bottom: 0; }
.app-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.app-name { font-weight: 600; font-size: 14px; }
.app-stars { color: var(--warn); font-size: 13px; }
.app-stars .dim { color: var(--ink3); }
.app-reason { font-size: 12px; color: var(--ink2); margin-bottom: 4px; }
.app-solution { font-size: 12px; color: var(--ink2); }
.comp-brand { color: var(--red); }
.ai-strategy { margin-top: 10px; padding: 10px 14px; background: #FFF7F7; border: 1px solid color-mix(in srgb, var(--red) 30%, transparent); border-radius: 8px; font-size: 12px; line-height: 1.6; }
.strat-section { margin-bottom: 14px; }
.strat-section:last-child { margin-bottom: 0; }
.strat-label { font-size: 12px; font-weight: 700; color: var(--ink2); text-transform: uppercase; letter-spacing: .06em; margin-bottom: 6px; }
.strat-content { font-size: 14px; line-height: 1.6; padding: 12px 16px; background: #F7FBFF; border: 1px solid color-mix(in srgb, var(--accent) 24%, transparent); border-radius: 8px; }
.email-sub { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 13px; }
.email-no { width: 18px; height: 18px; border-radius: 50%; background: #1D1D1F; color: #fff; font-size: 10px; font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.why-text { font-size: 14px; line-height: 1.8; color: var(--ink); padding: 14px 18px; background: #F5F5F7; border-radius: 12px; }

.action-bar { display: flex; gap: 10px; padding: 18px; margin-top: 16px; flex-wrap: wrap; }
.act-btn { display: flex; align-items: center; gap: 6px; padding: 12px 22px; border-radius: 980px; border: 1px solid var(--hair); background: #fff; font-size: 13px; font-weight: 600; color: var(--ink); cursor: pointer; transition: all .18s; }
.act-btn:hover:not(.disabled) { border-color: var(--accent); color: var(--accent); }
.act-btn.primary { background: linear-gradient(135deg,#0071E3,#1F8FFF); color: #fff; border: 0; }
.act-btn.primary:hover { transform: translateY(-1px); box-shadow: 0 4px 14px rgba(0,113,227,.3); }
.act-btn.disabled { opacity: .4; cursor: not-allowed; }
.v2 { font-size: 9px; padding: 1px 5px; border-radius: 4px; background: var(--ink3); color: #fff; }

.empty-state { padding: 60px 40px; text-align: center; }
.empty-title { font-size: 16px; font-weight: 600; color: var(--ink); margin: 16px 0 6px; }
.empty-hint { font-size: 13px; color: var(--ink3); margin-bottom: 20px; }
.example-row { display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; }
.ex-chip { padding: 8px 18px; border-radius: 980px; border: 1px solid var(--hair); background: #fff; font-size: 13px; color: var(--ink2); cursor: pointer; transition: all .18s; }
.ex-chip:hover { border-color: var(--accent); color: var(--accent); background: #F7FBFF; }

.cfg-card { background: var(--card); border: 1px solid var(--hair); border-radius: 20px; box-shadow: 0 1px 3px rgba(0,0,0,.04); }

@media (max-width: 900px) {
  .report-grid { grid-template-columns: 1fr; }
  .score-banner { flex-direction: column; gap: 16px; }
}
</style>
