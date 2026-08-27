<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import {
  ChatLineSquare, CircleCheckFilled, Search,
  UserFilled, OfficeBuilding, Brush, Coin, Trophy,
  SetUp, StarFilled, MagicStick, Connection,
  Document as DocIcon, Lock,
  Grid, DataAnalysis, Wallet, Key, ChatDotRound as DingIcon,
} from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()

interface AdminItem {
  title: string
  desc: string
  path: string
  icon: any
  color: string
}
interface AdminGroup {
  title: string
  desc: string
  items: AdminItem[]
}
const groups: AdminGroup[] = [
  {
    title: '反馈与质量',
    desc: '校正反哺闭环 · 让 AI 越用越好',
    items: [
      { title: '反馈审核',     desc: '查看用户对回答的好评/差评',  path: '/feedback-review', icon: ChatLineSquare,  color: '#F59E0B' },
      { title: '经验库',       desc: '维护高质量的问答范例',       path: '/golden-pair',     icon: CircleCheckFilled, color: '#34D399' },
      { title: '历史对话搜索', desc: '回溯任意历史对话与上下文',   path: '/conv-search',     icon: Search,            color: '#0071E3' },
    ],
  },
  {
    title: '组织与权限',
    desc: '用户、职位、品牌与数据源',
    items: [
      { title: '用户管理',     desc: '账号、密码、有效期管理',     path: '/users',          icon: UserFilled,       color: '#EF4444' },
      { title: '组织与职位',   desc: '部门/职位的 KB 权限分配',     path: '/org',            icon: OfficeBuilding,   color: '#0071E3' },
      { title: '品牌设置',     desc: 'Logo / 系统名 / 主题色',     path: '/brand-settings', icon: Brush,             color: '#EC4899' },
      { title: '数据源配置',   desc: 'MySQL / MinIO / 外部 API',   path: '/datasource',     icon: Coin,              color: '#0EA5E9' },
      { title: '教练学习统计', desc: '查看教练模式训练指标',       path: '/coach-stats',    icon: Trophy,            color: '#AF52DE' },
    ],
  },
  {
    title: 'AI 模型配置',
    desc: 'LLM / 人格 / 技能 / 规则',
    items: [
      { title: 'AI 配置',         desc: '全局提示词与能力开关', path: '/ai-config',    icon: SetUp,      color: '#a1a1aa' },
      { title: 'Soul 人格',        desc: '数字员工的人格设定',   path: '/persona',      icon: StarFilled, color: '#EC4899' },
      { title: '技能包',          desc: '可复用的能力组合',     path: '/skill-pack',   icon: MagicStick, color: '#0071E3' },
      { title: '教练规则',        desc: '教练模式的行为准则',   path: '/coach-rule',   icon: Trophy,     color: '#F59E0B' },
      { title: '大模型 Provider', desc: '接入 OpenAI/Claude 等', path: '/llm-provider', icon: Connection, color: '#06B6D4' },
    ],
  },
  {
    title: '合规与安全',
    desc: '审计与隐私脱敏',
    items: [
      { title: '审计日志', desc: '所有用户操作的可追溯记录', path: '/audit-log',  icon: DocIcon, color: '#F59E0B' },
      { title: 'PII 脱敏',  desc: '敏感字段的自动识别与脱敏',  path: '/pii-config', icon: Lock,    color: '#EF4444' },
    ],
  },
  {
    title: '运维与监控',
    desc: '服务状态与第三方集成',
    items: [
      { title: 'MCP 控制台',   desc: '管理所有 MCP 工具与权限', path: '/mcp',             icon: Grid,         color: '#10B981' },
      { title: '数据大屏',     desc: '运营核心指标的实时看板', path: '/dashboard',       icon: DataAnalysis, color: '#F59E0B' },
      { title: '账单对账',     desc: '用量统计与成本核对',      path: '/usage-reconcile',icon: Wallet,       color: '#B45309' },
      { title: 'API Key 管理', desc: '生成与回收对外 API 密钥', path: '/api-key',         icon: Key,          color: '#0EA5E9' },
      { title: '钉钉机器人',   desc: '与钉钉工作台打通',        path: '/dingtalk-bot',    icon: DingIcon,     color: '#3D7EFF' },
    ],
  },
]
const totalCount = groups.reduce((s, g) => s + g.items.length, 0)
</script>

<template>
  <div class="admin-home">
    <header class="admin-hero">
      <div class="hero-text">
        <h1>管理员控制台</h1>
        <p>集中管理权限、模型、质量和系统运行配置</p>
      </div>
      <div class="hero-count">共 {{ totalCount }} 项配置</div>
    </header>

    <section
      v-for="g in groups"
      :key="g.title"
      class="admin-group"
    >
      <div class="group-head">
        <h2>{{ g.title }}</h2>
        <span class="group-desc">{{ g.desc }}</span>
      </div>
      <div class="admin-grid">
        <article
          v-for="item in g.items"
          :key="item.path"
          class="admin-card"
          @click="router.push(item.path)"
        >
          <span class="card-icon">
            <el-icon :size="20"><component :is="item.icon" /></el-icon>
          </span>
          <div class="card-body">
            <div class="card-title">{{ item.title }}</div>
            <div class="card-desc">{{ item.desc }}</div>
          </div>
          <div class="card-go">前往 →</div>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.admin-home {
  padding: 28px 32px 64px;
  background: transparent;
  height: 100%;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
}

/* Hero */
.admin-hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 1180px;
  margin: 0 auto 28px;
  padding-bottom: 22px;
  border-bottom: 1px solid var(--line);
  gap: 24px;
  flex-wrap: wrap;
}
.hero-text h1 {
  font-size: 28px;
  font-weight: 700;
  letter-spacing: -.02em;
  color: var(--ink-1);
  margin: 0 0 8px;
}
.hero-text p { font-size: 14px; color: var(--ink-3); margin: 0; }
.hero-count {
  font-size: 12px;
  color: var(--ink-3);
  font-weight: 600;
}

/* 分组 */
.admin-group { max-width: 1180px; margin: 0 auto 30px; }
.group-head { margin-bottom: 14px; display: flex; align-items: baseline; gap: 12px; }
.group-head h2 {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: -.01em;
  color: var(--ink-1);
}
.group-desc { font-size: 12px; color: var(--ink-4); }

.admin-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
.admin-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 16px 18px;
  background: rgba(255,255,255,.85);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  cursor: pointer;
  transition: border-color var(--transition), transform var(--transition), box-shadow var(--transition);
}
.admin-card:hover {
  border-color: #AABBDD;
  transform: translateY(-1px);
  box-shadow: var(--shadow-md);
}
.card-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: var(--brand-soft);
  color: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.card-body { flex: 1; min-width: 0; }
.card-title { font-size: 14px; font-weight: 650; color: var(--ink-1); margin-bottom: 3px; }
.card-desc { font-size: 12px; color: var(--ink-3); line-height: 1.45; }
.card-go {
  font-size: 12px;
  font-weight: 600;
  opacity: 0;
  transform: translateX(-4px);
  transition: opacity .2s, transform .2s;
  flex-shrink: 0;
  color: var(--brand);
}
.admin-card:hover .card-go { opacity: 1; transform: translateX(0); }

@media (max-width: 768px) {
  .admin-home { padding: 20px 16px 48px; }
  .admin-hero { flex-direction: column; align-items: flex-start; }
}
</style>
