<template>
  <div class="home-page">
    <div class="home-shell">
      <header class="home-heading">
        <div>
          <div class="heading-kicker">企业智能工作台</div>
          <h1>{{ greeting }}，{{ displayName }}</h1>
          <p>从企业知识出发，快速获得答案并推动工作。</p>
        </div>
        <time class="current-date">{{ currentDate }}</time>
      </header>

      <section class="ask-panel" aria-labelledby="ask-title">
        <div class="ask-copy">
          <span class="ask-label" id="ask-title">智能问答</span>
          <span>搜索知识、回顾对话，或直接提出一个问题</span>
        </div>
        <div class="ask-box">
          <el-icon :size="19"><Search /></el-icon>
          <input
            v-model="question"
            aria-label="输入问题"
            placeholder="例如：总结 SH10 在美国市场的核心竞争力"
            @keydown.enter.exact="handleAskEnter"
          />
          <button type="button" :disabled="!question.trim()" @click="goAsk">开始提问</button>
        </div>
        <div class="prompt-row" aria-label="示例问题">
          <span>可以试试</span>
          <button v-for="prompt in promptExamples" :key="prompt" type="button" @click="question = prompt">
            {{ prompt }}
          </button>
        </div>
      </section>

      <section class="section-block" aria-labelledby="quick-title">
        <div class="section-heading">
          <div>
            <h2 id="quick-title">常用工作</h2>
            <p>从这里进入高频任务</p>
          </div>
        </div>

        <div class="quick-grid">
          <button class="quick-feature" type="button" @click="router.push(quickActions[0]!.path)">
            <span class="feature-icon"><el-icon :size="24"><ChatDotRound /></el-icon></span>
            <span class="feature-copy">
              <span class="feature-kicker">知识驱动的企业问答</span>
              <strong>{{ quickActions[0]!.title }}</strong>
              <span>{{ quickActions[0]!.desc }}</span>
            </span>
            <span class="feature-action">进入问答 <span aria-hidden="true">→</span></span>
          </button>

          <button
            v-for="action in quickActions.slice(1)"
            :key="action.path"
            class="quick-item"
            type="button"
            @click="router.push(action.path)"
          >
            <span class="quick-icon"><el-icon :size="19"><component :is="action.icon" /></el-icon></span>
            <span class="quick-copy"><strong>{{ action.title }}</strong><span>{{ action.desc }}</span></span>
            <span class="quick-arrow" aria-hidden="true">→</span>
          </button>

          <div class="quick-item online-item" aria-live="polite">
            <span class="quick-icon status-icon"><span class="status-dot"></span></span>
            <span class="quick-copy">
              <strong>同时在线</strong>
              <span v-if="onlineReady">近 {{ windowMinutes }} 分钟活跃 {{ onlineTotal ?? 0 }} 人</span>
              <span v-else>正在读取活跃状态</span>
            </span>
            <span v-if="onlineUsers.length" class="avatar-stack" aria-label="最近活跃用户">
              <span
                v-for="(user, index) in onlineUsers.slice(0, 3)"
                :key="user.id ?? index"
                class="mini-avatar"
                :style="{ background: avatarColor(user.id) }"
                :title="user.nickname"
              >{{ avatarChar(user.nickname) }}</span>
            </span>
          </div>
        </div>
      </section>

      <section class="overview" aria-labelledby="overview-title">
        <div class="section-heading compact">
          <div>
            <h2 id="overview-title">工作概览</h2>
            <p>来自当前系统的实时数据</p>
          </div>
        </div>
        <div class="metric-strip">
          <div v-for="metric in metrics" :key="metric.label" class="metric-item">
            <span class="metric-label">{{ metric.label }}</span>
            <strong :class="{ healthy: metric.label === '服务状态' }">{{ metric.value }}</strong>
            <span>{{ metric.help }}</span>
          </div>
        </div>
      </section>

      <section class="content-grid">
        <article class="content-panel">
          <div class="panel-heading">
            <div><h2>最近文档</h2><p>继续处理最近更新的知识内容</p></div>
            <router-link to="/knowledge">查看全部</router-link>
          </div>
          <div v-if="recentDocs.length" class="data-list">
            <button v-for="doc in recentDocs.slice(0, 5)" :key="doc.id" type="button" @click="openDoc(doc)">
              <span class="file-type">{{ fileExt(doc.name) }}</span>
              <span class="data-main">
                <strong>{{ doc.name }}</strong>
                <span>{{ doc.fileType || '文档' }}<template v-if="fmtSize(doc.fileSize)"> · {{ fmtSize(doc.fileSize) }}</template></span>
              </span>
              <time>{{ fromNow(doc.updateTime || doc.createTime) }}</time>
            </button>
          </div>
          <div v-else class="empty-state">
            <el-icon :size="22"><Document /></el-icon>
            <span>还没有最近文档</span>
            <router-link to="/knowledge">添加知识</router-link>
          </div>
        </article>

        <article class="content-panel">
          <div class="panel-heading">
            <div><h2>知识与对话</h2><p>快速返回正在使用的上下文</p></div>
            <router-link to="/collections">管理知识库</router-link>
          </div>
          <div class="subsection-title">常用知识库</div>
          <div v-if="topCollections.length" class="data-list compact-list">
            <button v-for="(collection, index) in topCollections" :key="collection.id" type="button" @click="openCollection(collection.id)">
              <span class="collection-icon"><el-icon :size="16"><component :is="kbTint(index).icon" /></el-icon></span>
              <span class="data-main">
                <strong>{{ collection.name }}</strong>
                <span>{{ getCategoryLabel(collection.categoryCode) }}</span>
              </span>
              <span class="count-label">{{ collection.docCount ?? 0 }} 篇</span>
            </button>
          </div>
          <div v-else class="inline-empty">暂无知识库</div>

          <div class="subsection-heading">
            <span class="subsection-title">最近对话</span>
            <router-link to="/conv-search">搜索对话</router-link>
          </div>
          <div v-if="recentConvs.length" class="data-list compact-list conversations">
            <button v-for="conversation in recentConvs.slice(0, 3)" :key="conversation.id" type="button" @click="openConversation(conversation.id)">
              <span class="collection-icon"><el-icon :size="16"><ChatDotRound /></el-icon></span>
              <span class="data-main"><strong>{{ conversation.title || '未命名会话' }}</strong><span>{{ fromNow(conversation.lastActive || conversation.updateTime) }}</span></span>
              <span class="row-arrow" aria-hidden="true">→</span>
            </button>
          </div>
          <div v-else class="inline-empty">暂无最近对话</div>
        </article>
      </section>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  ChatDotRound, CircleCheckFilled, Document, Files, FolderOpened,
  OfficeBuilding, Promotion, Reading, Search, UserFilled,
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'
import { knowledgeApi } from '@/api/knowledge'
import { chatApi } from '@/api/chat'
import { statsApi } from '@/api/stats'
import request from '@/utils/request'

const router = useRouter()
const userStore = useUserStore()
const displayName = computed(() =>
  (userStore.userInfo as any)?.nickname || (userStore.userInfo as any)?.username || '管理员')
const currentDate = new Intl.DateTimeFormat('zh-CN', {
  month: 'long', day: 'numeric', weekday: 'long',
}).format(new Date())

const clockTick = ref(0)
let clockTimer: number | undefined
const greeting = computed(() => {
  void clockTick.value
  const hour = new Date().getHours()
  if (hour >= 5 && hour < 11) return '早上好'
  if (hour >= 11 && hour < 13) return '中午好'
  if (hour >= 13 && hour < 18) return '下午好'
  return '晚上好'
})

const question = ref('')
const collections = ref<KnowledgeCollection[]>([])
const docTotal = ref<number | string>('--')
const monthAsk = ref<number | string>('--')
const recentDocs = ref<any[]>([])
const recentConvs = ref<any[]>([])
const promptExamples = ['总结本周重点工作', '分析一份合同风险', '生成项目汇报提纲']
const quickActions = [
  { title: '智能问答', desc: '基于企业知识快速获得准确答案', icon: ChatDotRound, path: '/chat' },
  { title: '数字员工', desc: '调用专业智能体处理复杂任务', icon: UserFilled, path: '/digital-employees' },
  { title: '知识库', desc: '组织、更新和维护企业知识', icon: FolderOpened, path: '/collections' },
  { title: '全球获客', desc: '发现目标客户并推进获客任务', icon: Promotion, path: '/lead-hunter' },
]

const metrics = computed(() => [
  { label: '知识文档', value: docTotal.value, help: '已收录文档' },
  { label: '知识空间', value: collections.value.length || '--', help: '可用知识库' },
  { label: '本月提问', value: monthAsk.value, help: '本月消息' },
  { label: '服务状态', value: '正常', help: '当前服务可用', icon: CircleCheckFilled },
])
const topCollections = computed(() => collections.value.slice(0, 4))
const KB_TINTS = [
  { icon: Document }, { icon: Reading }, { icon: OfficeBuilding }, { icon: Files },
]
function kbTint(index: number) { return KB_TINTS[index % KB_TINTS.length]! }

const CATEGORY_LABELS: Record<string, string> = {
  product: '产品文档', tech: '技术文档', policy: '公司制度', training: '培训资料',
  legal: '法务资料', finance: '财务资料',
}
function getCategoryLabel(code?: string) { return code ? (CATEGORY_LABELS[code] || code) : '未分类' }

function goAsk() {
  const value = question.value.trim()
  if (!value) return
  router.push({ path: '/chat', query: { q: value } })
}
function handleAskEnter(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229 || event.shiftKey) return
  event.preventDefault()
  goAsk()
}
function openCollection(id: number) { router.push(`/collections/${id}`) }
function openDoc(_doc: any) { router.push('/knowledge') }
function openConversation(id: number) { router.push(`/chat/${id}`) }

function fromNow(value?: string) {
  if (!value) return ''
  const time = new Date(value).getTime()
  if (Number.isNaN(time)) return ''
  const minutes = Math.floor((Date.now() - time) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days}天前`
  return new Date(value).toLocaleDateString()
}
function fileExt(name?: string) {
  if (!name) return 'DOC'
  const extension = name.split('.').pop()?.toUpperCase() || 'DOC'
  return extension.length > 4 ? 'DOC' : extension
}
function fmtSize(bytes?: number) {
  if (!bytes || bytes <= 0) return ''
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

const onlineTotal = ref<number | null>(null)
const windowMinutes = ref(5)
const onlineUsers = ref<Array<{ id?: number; nickname: string; avatar?: string }>>([])
const onlineReady = ref(false)
async function loadOnline() {
  try {
    const response: any = await request.get('/stats/online/users')
    const data = response?.data ?? response ?? {}
    windowMinutes.value = Number(data.windowMinutes ?? 5)
    onlineUsers.value = data.recent ?? []
    onlineTotal.value = Math.max(Number(data.total ?? 0), onlineUsers.value.length)
  } catch (error) {
    console.warn('[Home] online users failed', error)
    onlineTotal.value = 0
  } finally {
    onlineReady.value = true
  }
}
function avatarChar(name?: string) { return (name ?? '?').charAt(0).toUpperCase() }
function avatarColor(id?: number) {
  const palette = ['#1D4ED8', '#2563EB', '#3B82F6', '#475569']
  return palette[(id ?? 0) % palette.length]!
}

async function loadHomeData() {
  await Promise.allSettled([
    (async () => {
      const response: any = await collectionApi.list()
      collections.value = response?.data ?? response ?? []
    })(),
    (async () => {
      const response: any = await knowledgeApi.stats()
      const total = response?.total ?? response?.data?.total
      if (total != null) docTotal.value = Number(total).toLocaleString()
    })(),
    (async () => {
      const response: any = await statsApi.getDashboard('month')
      if (response?.periodMessages != null) monthAsk.value = Number(response.periodMessages).toLocaleString()
      if (docTotal.value === '--' && response?.totalKnowledge != null) {
        docTotal.value = Number(response.totalKnowledge).toLocaleString()
      }
    })(),
    (async () => {
      const response: any = await knowledgeApi.list({ current: 1, size: 6 })
      recentDocs.value = response?.records || response?.data?.records || []
    })(),
    (async () => {
      const response: any = await chatApi.listConversations({ current: 1, size: 6 })
      recentConvs.value = response?.records || response?.data?.records || []
    })(),
    loadOnline(),
  ])
}

onMounted(() => {
  clockTimer = window.setInterval(() => { clockTick.value += 1 }, 60_000)
  void loadHomeData()
})
onBeforeUnmount(() => {
  if (clockTimer) window.clearInterval(clockTimer)
})
</script>

<style scoped>
* { box-sizing: border-box; }
.home-page { height: 100%; overflow: auto; background: var(--bg-page); color: var(--ink-1); }
.home-shell { width: min(1180px, calc(100% - 48px)); margin: 0 auto; padding: 34px 0 64px; }
.home-heading, .section-heading, .panel-heading, .subsection-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 24px; }
.heading-kicker { margin-bottom: 7px; color: var(--brand); font-size: 12px; font-weight: 700; letter-spacing: .08em; }
.home-heading h1 { margin: 0; color: var(--ink-1); font-size: clamp(28px, 3vw, 38px); font-weight: 720; letter-spacing: -.035em; line-height: 1.2; }
.home-heading p, .section-heading p, .panel-heading p { margin: 7px 0 0; color: var(--ink-3); font-size: 13px; }
.current-date { padding-top: 24px; color: var(--ink-3); font-size: 13px; white-space: nowrap; }

.ask-panel { margin-top: 28px; padding: 22px; border: 1px solid var(--line); border-radius: var(--radius-lg); background: var(--surface); box-shadow: var(--shadow-card); }
.ask-copy { display: flex; align-items: baseline; gap: 12px; margin-bottom: 12px; color: var(--ink-3); font-size: 13px; }
.ask-label { color: var(--ink-1); font-size: 15px; font-weight: 700; }
.ask-box { display: flex; align-items: center; gap: 12px; min-height: 56px; padding: 7px 8px 7px 16px; border: 1px solid var(--line-strong); border-radius: 10px; background: var(--surface); color: var(--ink-4); transition: border-color 160ms ease, box-shadow 160ms ease; }
.ask-box:focus-within { border-color: var(--brand); box-shadow: 0 0 0 3px var(--brand-soft); }
.ask-box input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--ink-1); font: inherit; font-size: 15px; }
.ask-box input::placeholder { color: var(--ink-4); }
.ask-box button { min-height: 40px; padding: 0 18px; border: 0; border-radius: var(--radius-sm); background: var(--brand); color: #fff; font-size: 13px; font-weight: 650; cursor: pointer; }
.ask-box button:hover { background: var(--brand-hover); }
.ask-box button:disabled { background: var(--line-strong); cursor: not-allowed; }
.prompt-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-top: 12px; color: var(--ink-4); font-size: 12px; }
.prompt-row button { padding: 5px 9px; border: 1px solid var(--line-soft); border-radius: 6px; background: var(--surface-subtle); color: var(--ink-2); font: inherit; cursor: pointer; }
.prompt-row button:hover { border-color: var(--line-strong); color: var(--brand); }

.section-block, .overview, .content-grid { margin-top: 32px; }
.section-heading { margin-bottom: 14px; }
.section-heading h2, .panel-heading h2 { margin: 0; color: var(--ink-1); font-size: 17px; font-weight: 700; letter-spacing: -.015em; }
.quick-grid { display: grid; grid-template-columns: 1.4fr 1fr 1fr; grid-template-rows: repeat(2, minmax(132px, auto)); gap: 12px; }
.quick-feature, .quick-item { appearance: none; width: 100%; border: 1px solid var(--line); border-radius: var(--radius-lg); background: var(--surface); color: var(--ink-1); font: inherit; text-align: left; }
.quick-feature { grid-row: span 2; display: flex; flex-direction: column; align-items: flex-start; min-height: 276px; padding: 24px; border-color: #CBD5E1; background: linear-gradient(145deg, #F8FAFF 0%, #FFFFFF 70%); cursor: pointer; transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease; }
.quick-feature:hover, .quick-item:is(button):hover { border-color: #AABBDD; box-shadow: var(--shadow-md); transform: translateY(-1px); }
.feature-icon, .quick-icon, .collection-icon { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 auto; color: var(--brand); background: var(--brand-soft); }
.feature-icon { width: 48px; height: 48px; border-radius: 10px; }
.feature-copy { display: flex; flex-direction: column; gap: 7px; margin-top: 34px; }
.feature-kicker { color: var(--brand); font-size: 12px; font-weight: 650; }
.feature-copy strong { font-size: 25px; letter-spacing: -.025em; }
.feature-copy > span:last-child { max-width: 290px; color: var(--ink-3); font-size: 13px; line-height: 1.6; }
.feature-action { margin-top: auto; color: var(--brand); font-size: 13px; font-weight: 650; }
.quick-item { display: flex; align-items: center; gap: 12px; min-height: 132px; padding: 17px; cursor: pointer; transition: border-color 160ms ease, box-shadow 160ms ease, transform 160ms ease; }
.quick-icon, .collection-icon { width: 36px; height: 36px; border-radius: 8px; }
.quick-copy { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 5px; }
.quick-copy strong { font-size: 14px; font-weight: 680; }
.quick-copy > span { color: var(--ink-3); font-size: 12px; line-height: 1.5; }
.quick-arrow { color: var(--ink-4); }
.online-item { cursor: default; }
.status-icon { background: #ECFDF3; }
.status-dot { width: 8px; height: 8px; border-radius: 50%; background: var(--success); }
.avatar-stack { display: flex; padding-left: 8px; }
.mini-avatar { display: inline-grid; width: 26px; height: 26px; margin-left: -8px; place-items: center; border: 2px solid var(--surface); border-radius: 50%; color: #fff; font-size: 10px; font-weight: 700; }

.overview { padding: 20px 22px 0; border: 1px solid var(--line); border-radius: var(--radius-lg); background: var(--surface); }
.section-heading.compact { margin-bottom: 17px; }
.metric-strip { display: grid; grid-template-columns: repeat(4, 1fr); border-top: 1px solid var(--line-soft); }
.metric-item { display: flex; min-width: 0; flex-direction: column; gap: 5px; padding: 18px 22px 21px; border-right: 1px solid var(--line-soft); }
.metric-item:first-child { padding-left: 0; }
.metric-item:last-child { border-right: 0; }
.metric-label { color: var(--ink-3); font-size: 12px; }
.metric-item strong { color: var(--ink-1); font-size: 24px; line-height: 1.15; letter-spacing: -.025em; }
.metric-item strong.healthy { color: var(--success); font-size: 21px; }
.metric-item > span:last-child { color: var(--ink-4); font-size: 11px; }

.content-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.content-panel { min-width: 0; padding: 22px; border: 1px solid var(--line); border-radius: var(--radius-lg); background: var(--surface); box-shadow: var(--shadow-card); }
.panel-heading { align-items: center; margin-bottom: 18px; }
.panel-heading a, .subsection-heading a { color: var(--brand); font-size: 12px; font-weight: 600; text-decoration: none; }
.data-list { display: flex; flex-direction: column; }
.data-list button { display: flex; align-items: center; gap: 11px; width: 100%; min-height: 58px; padding: 9px 4px; border: 0; border-bottom: 1px solid var(--line-soft); background: transparent; color: var(--ink-1); font: inherit; text-align: left; cursor: pointer; }
.data-list button:last-child { border-bottom: 0; }
.data-list button:hover .data-main strong { color: var(--brand); }
.file-type { display: inline-grid; width: 38px; height: 38px; place-items: center; border-radius: 7px; background: var(--surface-subtle); color: var(--ink-3); font-size: 9px; font-weight: 750; }
.data-main { display: flex; flex: 1; min-width: 0; flex-direction: column; gap: 3px; }
.data-main strong { overflow: hidden; font-size: 13px; font-weight: 620; text-overflow: ellipsis; white-space: nowrap; transition: color 150ms ease; }
.data-main span, .data-list time, .count-label { color: var(--ink-4); font-size: 11px; white-space: nowrap; }
.subsection-title { color: var(--ink-3); font-size: 11px; font-weight: 700; letter-spacing: .04em; }
.compact-list { margin-top: 5px; }
.collection-icon { width: 32px; height: 32px; }
.subsection-heading { align-items: center; margin-top: 19px; padding-top: 17px; border-top: 1px solid var(--line-soft); }
.conversations { margin-top: 5px; }
.row-arrow { color: var(--ink-4); }
.empty-state, .inline-empty { display: flex; align-items: center; justify-content: center; color: var(--ink-4); font-size: 12px; }
.empty-state { min-height: 240px; flex-direction: column; gap: 9px; }
.empty-state a { color: var(--brand); text-decoration: none; }
.inline-empty { min-height: 75px; }
button:focus-visible, a:focus-visible { outline: 2px solid var(--brand); outline-offset: 2px; }
.ask-box input:focus-visible { outline: none; }

@media (max-width: 920px) {
  .quick-grid { grid-template-columns: 1fr 1fr; grid-template-rows: auto; }
  .quick-feature { grid-column: span 2; grid-row: auto; min-height: 230px; }
  .feature-copy { margin-top: 24px; }
  .content-grid { grid-template-columns: 1fr; }
}
@media (max-width: 640px) {
  .home-shell { width: min(100% - 28px, 1180px); padding-top: 22px; }
  .home-heading { flex-direction: column; gap: 2px; }
  .current-date { padding-top: 7px; }
  .ask-panel { margin-top: 20px; padding: 16px; }
  .ask-copy > span:last-child { display: none; }
  .ask-box { align-items: stretch; flex-wrap: wrap; padding: 12px; }
  .ask-box > svg { align-self: center; }
  .ask-box input { min-height: 36px; }
  .ask-box button { width: 100%; }
  .quick-grid { grid-template-columns: 1fr; }
  .quick-feature { grid-column: auto; min-height: 220px; }
  .quick-item { min-height: 104px; }
  .metric-strip { grid-template-columns: 1fr 1fr; }
  .metric-item { border-bottom: 1px solid var(--line-soft); }
  .metric-item:nth-child(2) { border-right: 0; }
  .metric-item:nth-child(3), .metric-item:nth-child(4) { border-bottom: 0; }
  .metric-item:nth-child(3) { padding-left: 0; }
  .content-panel { padding: 18px; }
}
@media (prefers-reduced-motion: reduce) {
  .quick-feature, .quick-item, .data-main strong { transition: none; }
}
</style>
