import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'

// #3 · 受功能权限控制的顶层路径段（与后端 FeatureCatalog / 侧栏菜单对齐）。
//   只拦截这些段；其它子页(如 user-usage)与基线页(chat/profile)放行，后端仍有兜底鉴权。
const CONTROLLED_FEATURES = new Set<string>([
  'digital-employees', 'crew', 'coach', 'voice-call', 'collections',
  'feedback-review', 'golden-pair', 'conv-search',
  'users', 'org', 'brand-settings', 'coach-stats',
  'ai-config', 'persona', 'llm-provider', 'coach-rule',
  'audit-log', 'pii-config',
  'mcp', 'dashboard', 'usage-reconcile', 'api-key', 'dingtalk-bot',
  'workflow',
])

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    // 登录页
    {
      path: '/login',
      name: 'Login',
      component: () => import('@/views/auth/LoginView.vue'),
      meta: { requiresAuth: false }
    },
    // 注册页
    {
      path: '/register',
      name: 'Register',
      component: () => import('@/views/auth/RegisterView.vue'),
      meta: { requiresAuth: false }
    },
    // 找回密码
    {
      path: '/forgot-password',
      name: 'ForgotPassword',
      component: () => import('@/views/auth/ForgotPasswordView.vue'),
      meta: { requiresAuth: false }
    },
    // 主布局（需要认证）
    {
      path: '/',
      component: () => import('@/layout/MainLayout.vue'),
      meta: { requiresAuth: true },
      children: [
        // 首页工作台
        {
          path: '',
          name: 'Home',
          component: () => import('@/views/home/HomeView.vue'),
          meta: { title: '工作台', icon: 'HomeFilled' }
        },
        // 管理员控制台首页（所有后台功能的统一入口）
        {
          path: 'admin',
          name: 'AdminDashboard',
          component: () => import('@/views/admin/AdminDashboardView.vue'),
          meta: { title: '管理员控制台', icon: 'Setting', requiresAdmin: true }
        },
        // 系统编排（工作流）· 仅管理员
        {
          path: 'workflow',
          name: 'Workflow',
          component: () => import('@/views/workflow/WorkflowView.vue'),
          meta: { title: '系统编排', icon: 'Operation', requiresAdmin: true }
        },
        // 智能问答
        {
          path: 'chat',
          name: 'Chat',
          component: () => import('@/views/chat/ChatView.vue'),
          meta: { title: '智能问答', icon: 'ChatDotRound' }
        },
        // 数字员工 · 默认进入稳定的员工列表。3D 办公室独立部署时从列表进入。
        {
          path: 'digital-employees',
          name: 'DigitalEmployeeList',
          component: () => import('@/views/digital-employee/DigitalEmployeeListView.vue'),
          meta: { title: '数字员工', icon: 'Avatar' }
        },
        // 兼容旧的员工列表链接
        {
          path: 'digital-employees/list',
          redirect: { name: 'DigitalEmployeeList' },
        },
        {
          path: 'digital-employees/office',
          name: 'DigitalEmployeeOffice',
          component: () => import('@/views/digital-employee/ThreeDOfficeView.vue'),
          meta: { title: '3D 办公室', icon: 'View' }
        },
        {
          path: 'digital-employees/:id/chat',
          name: 'DigitalEmployeeChat',
          component: () => import('@/views/digital-employee/DigitalEmployeeChatView.vue'),
          meta: { title: '数字员工对话', icon: 'ChatDotRound' }
        },
        {
          path: 'ppt-studio',
          redirect: {
            name: 'DigitalEmployeeList',
            query: { capability: 'ppt' },
          },
        },
        {
          path: 'admin/digital-employees',
          name: 'DigitalEmployeeAdmin',
          component: () => import('@/views/digital-employee/DigitalEmployeeAdminView.vue'),
          meta: { title: '数字员工管理', icon: 'Setting', requiresAdmin: true }
        },
        {
          path: 'admin/digital-employees/create',
          name: 'DigitalEmployeeCreate',
          component: () => import('@/views/digital-employee/DigitalEmployeeEditorView.vue'),
          meta: { title: '创建数字员工', requiresAdmin: true }
        },
        {
          path: 'admin/digital-employees/:id/edit',
          name: 'DigitalEmployeeEdit',
          component: () => import('@/views/digital-employee/DigitalEmployeeEditorView.vue'),
          meta: { title: '编辑数字员工', requiresAdmin: true }
        },
        // Multi-Agent 调研引擎
        {
          path: 'crew',
          name: 'Crew',
          component: () => import('@/views/crew/CrewView.vue'),
          meta: { title: 'Agent 调研', icon: 'MagicStick' }
        },
        // 教练模式 · 任务 9
        {
          path: 'coach',
          name: 'Coach',
          component: () => import('@/views/coach/CoachView.vue'),
          meta: { title: '教练模式', icon: 'Trophy' }
        },
        // 全球获客数字员工 (Global Lead Hunter)
        {
          path: 'lead-hunter',
          name: 'LeadHunter',
          component: () => import('@/views/lead-hunter/LeadHunterView.vue'),
          meta: { title: '全球获客', icon: 'Promotion' }
        },
        // 市场机会地图 (Market Opportunity Map)
        {
          path: 'market-opportunity',
          name: 'MarketOpportunity',
          component: () => import('@/views/market-opportunity/MarketOpportunityView.vue'),
          meta: { title: '市场机会地图', icon: 'DataAnalysis' }
        },
        // 客户匹配引擎 (Customer Matching Engine)
        {
          path: 'customer-matching',
          name: 'CustomerMatching',
          component: () => import('@/views/customer-matching/CustomerMatchingView.vue'),
          meta: { title: '客户匹配引擎', icon: 'Connection' }
        },
        // 语音通话 · 任务 14
        {
          path: 'voice-call',
          name: 'VoiceCall',
          component: () => import('@/views/voice/VoiceCallView.vue'),
          meta: { title: '语音通话', icon: 'PhoneFilled' }
        },
        // 问答排行
        {
          path: 'qa-ranking',
          name: 'QaRanking',
          component: () => import('@/views/workbench/QaRankingView.vue'),
          meta: { title: '问答排行', icon: 'TrendCharts', requiresAdmin: true }
        },
        // 技能包管理
        {
          path: 'skill-pack',
          name: 'SkillPack',
          component: () => import('@/views/admin/SkillPackView.vue'),
          meta: { title: '技能包', icon: 'MagicStick', requiresAdmin: true }
        },
        // 教练规则管理
        {
          path: 'coach-rule',
          name: 'CoachRule',
          component: () => import('@/views/admin/CoachRuleView.vue'),
          meta: { title: '教练规则', icon: 'Trophy', requiresAdmin: true }
        },
        // Trace Replay · 推理链回放
        {
          path: 'crew/replay/:taskId',
          name: 'CrewReplay',
          component: () => import('@/views/crew/CrewReplayView.vue'),
          meta: { title: '推理链回放', icon: 'VideoCamera' }
        },
        // Agent Communication Graph · 通信图谱
        {
          path: 'crew/graph/:taskId',
          name: 'CrewGraph',
          component: () => import('@/views/crew/CrewGraphView.vue'),
          meta: { title: 'Agent 通信图谱', icon: 'Share' }
        },
        {
          path: 'chat/:id',
          name: 'ChatDetail',
          component: () => import('@/views/chat/ChatView.vue'),
          meta: { title: '智能问答', icon: 'ChatDotRound' }
        },
        // 知识库（集合）列表 · 任务 15
        {
          path: 'collections',
          name: 'CollectionList',
          component: () => import('@/views/knowledge/CollectionListView.vue'),
          meta: { title: '我的知识库', icon: 'FolderOpened', requiresAdmin: true }
        },
        {
          path: 'collections/:id',
          name: 'CollectionDetail',
          component: () => import('@/views/knowledge/CollectionDetailView.vue'),
          meta: { title: '知识库详情', icon: 'FolderOpened', requiresAdmin: true }
        },
        // 知识库 · 文档管理入口（普通用户可见但只读 · 后端写接口已 ADMIN 拦截）
        {
          path: 'knowledge',
          name: 'Knowledge',
          component: () => import('@/views/knowledge/KnowledgeView.vue'),
          meta: { title: '知识库', icon: 'Document' }
        },
        // 知识图谱 · 按知识库聚合展示实体/关系
        {
          path: 'knowledge-graph',
          name: 'KnowledgeGraph',
          component: () => import('@/views/knowledge/KnowledgeGraphView.vue'),
          meta: { title: '知识图谱', icon: 'Share', requiresAdmin: true }
        },
        // MCP 控制台
        {
          path: 'mcp',
          name: 'Mcp',
          component: () => import('@/views/mcp/McpView.vue'),
          meta: { title: 'MCP 控制台', icon: 'Grid', requiresAdmin: true }
        },
        // 数据统计
        {
          path: 'dashboard',
          name: 'Dashboard',
          component: () => import('@/views/dashboard/DashboardView.vue'),
          meta: { title: '数据大屏', icon: 'DataAnalysis', requiresAdmin: true }
        },
        // 用户管理
        {
          path: 'users',
          name: 'Users',
          component: () => import('@/views/user/UserManageView.vue'),
          meta: { title: '用户管理', icon: 'User', requiresAdmin: true }
        },
        // AI 配置中心
        {
          path: 'ai-config',
          name: 'AiConfig',
          component: () => import('@/views/admin/AiConfigView.vue'),
          meta: { title: 'AI配置中心', icon: 'SetUp', requiresAdmin: true }
        },
        // Soul 人格管理
        {
          path: 'persona',
          name: 'Persona',
          component: () => import('@/views/admin/PersonaView.vue'),
          meta: { title: 'Soul 人格', icon: 'StarFilled', requiresAdmin: true }
        },
        // LLM Provider · 跨厂商模型切换
        {
          path: 'llm-provider',
          name: 'LlmProvider',
          component: () => import('@/views/admin/LlmProviderView.vue'),
          meta: { title: '大模型 Provider', icon: 'Connection', requiresAdmin: true }
        },
        // 反馈审核 · 任务 6
        {
          path: 'feedback-review',
          name: 'FeedbackReview',
          component: () => import('@/views/admin/FeedbackReviewView.vue'),
          meta: { title: '反馈审核', icon: 'ChatLineSquare', requiresAdmin: true }
        },
        // 经验库（原 Golden Pair 库）· 任务 6
        {
          path: 'golden-pair',
          name: 'GoldenPair',
          component: () => import('@/views/admin/GoldenPairView.vue'),
          meta: { title: '经验库', icon: 'CircleCheckFilled', requiresAdmin: true }
        },
        // 历史对话搜索 · 任务 13.5
        {
          path: 'conv-search',
          name: 'ConvSearch',
          component: () => import('@/views/admin/ConvSearchView.vue'),
          meta: { title: '历史对话搜索', icon: 'Search' }
        },
        // 组织与职位 · 任务 7
        {
          path: 'org',
          name: 'Org',
          component: () => import('@/views/admin/OrgView.vue'),
          meta: { title: '组织与职位', icon: 'OfficeBuilding', requiresAdmin: true }
        },
        {
          path: 'brand-settings',
          name: 'BrandSettings',
          component: () => import('@/views/admin/BrandSettingsView.vue'),
          meta: { title: '品牌设置', icon: 'Brush', requiresAdmin: true }
        },
        // 数据源配置 · NL2SQL 外部业务库
        {
          path: 'datasource',
          name: 'DataSource',
          component: () => import('@/views/admin/DataSourceView.vue'),
          meta: { title: '数据源配置', icon: 'Coin', requiresAdmin: true }
        },
        // 教练学习统计 · 任务 9.4
        {
          path: 'coach-stats',
          name: 'CoachStats',
          component: () => import('@/views/admin/CoachStatsView.vue'),
          meta: { title: '教练学习统计', icon: 'Trophy', requiresAdmin: true }
        },
        // 用户用量详情 · 任务 13.6
        {
          path: 'user-usage/:id',
          name: 'UserUsageDetail',
          component: () => import('@/views/admin/UserUsageDetailView.vue'),
          meta: { title: '用户用量详情', icon: 'DataLine', requiresAdmin: true }
        },
        // 阿里云对账 · 任务 13.7
        {
          path: 'usage-reconcile',
          name: 'UsageReconcile',
          component: () => import('@/views/admin/UsageReconcileView.vue'),
          meta: { title: '账单对账', icon: 'Wallet', requiresAdmin: true }
        },
        // 审计日志 · 任务 12.1
        {
          path: 'audit-log',
          name: 'AuditLog',
          component: () => import('@/views/admin/AuditLogView.vue'),
          meta: { title: '审计日志', icon: 'Document', requiresAdmin: true }
        },
        // PII 脱敏配置 · 任务 12.2
        {
          path: 'pii-config',
          name: 'PiiConfig',
          component: () => import('@/views/admin/PiiConfigView.vue'),
          meta: { title: 'PII 脱敏', icon: 'Lock', requiresAdmin: true }
        },
        // API Key 管理 · 任务 15
        {
          path: 'api-key',
          name: 'ApiKey',
          component: () => import('@/views/admin/ApiKeyView.vue'),
          meta: { title: 'API Key 管理', icon: 'Key', requiresAdmin: true }
        },
        // 钉钉机器人
        {
          path: 'dingtalk-bot',
          name: 'DingtalkBot',
          component: () => import('@/views/admin/DingtalkBotView.vue'),
          meta: { title: '钉钉机器人', icon: 'ChatDotRound', requiresAdmin: true }
        },
        // 个人中心
        {
          path: 'profile',
          name: 'Profile',
          component: () => import('@/views/user/ProfileView.vue'),
          meta: { title: '个人中心', icon: 'Setting' }
        }
      ]
    },
    // 404
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

// 全局路由守卫
router.beforeEach(async (to, _from, next) => {
  const userStore = useUserStore()

  if (to.meta.requiresAuth === false) {
    // 已登录访问登录页，跳转首页
    if (userStore.isLoggedIn && (to.path === '/login' || to.path === '/register')) {
      return next('/')
    }
    return next()
  }

  // 未登录跳转登录页
  if (!userStore.isLoggedIn) {
    return next(`/login?redirect=${to.path}`)
  }

  // 加载用户信息
  if (!userStore.userInfo) {
    try {
      await userStore.fetchUserInfo()
    } catch {
      userStore.logout()
      return next('/login')
    }
  }

  // #3 · 功能权限路由拦截：无权限直接访问受控页面时跳回首页（菜单已隐藏，这里防手输 URL）
  const seg = to.path.split('/')[1] || ''
  if (CONTROLLED_FEATURES.has(seg) && !userStore.hasFeature(seg)) {
    return next('/chat')
  }

  next()
})

export default router
