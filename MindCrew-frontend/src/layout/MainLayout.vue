<template>
  <div class="main-layout" :class="{ 'sidebar-collapsed': isCollapsed }">
    <aside class="desktop-sidebar" aria-label="主导航">
      <div class="sidebar-head">
        <router-link to="/" class="brand" :title="brandStore.systemName">
          <span class="brand-mark"><BrandLogo :size="24" color="#fffdf8" accent-color="#f1c84b" /></span>
          <span v-if="!isCollapsed" class="brand-name">{{ brandStore.systemName }}</span>
        </router-link>
        <button
          class="collapse-btn"
          type="button"
          :aria-label="isCollapsed ? '展开导航' : '收起导航'"
          :title="isCollapsed ? '展开导航' : '收起导航'"
          @click="toggleSidebar"
        >
          <el-icon size="15"><Fold v-if="!isCollapsed" /><Expand v-else /></el-icon>
        </button>
      </div>

      <nav class="desktop-nav">
        <section v-for="(group, gi) in menuGroups" :key="group.title" v-show="groupHasVisibleItems(group)" class="nav-group">
          <button v-if="!isCollapsed" class="nav-group-header" type="button" @click="toggleGroup(gi)">
            <span class="group-label">{{ group.title }}</span>
            <el-icon class="group-arrow" :class="{ collapsed: !isGroupExpanded(gi) }" size="11"><ArrowDown /></el-icon>
          </button>
          <div class="nav-group-items" :class="{ 'is-collapsed': !isCollapsed && !isGroupExpanded(gi) }">
            <router-link
              v-for="item in group.items"
              :key="item.path"
              v-show="canAccess(item)"
              :to="item.path"
              class="nav-item"
              :class="{ active: isActive(item.path) }"
              :title="item.label"
            >
              <span class="nav-icon-wrap">
                <el-icon size="17"><component :is="item.icon" /></el-icon>
              </span>
              <span v-if="!isCollapsed" class="nav-label">{{ item.label }}</span>
            </router-link>
          </div>
        </section>
      </nav>

      <div class="sidebar-foot">
        <span class="sidebar-status" aria-hidden="true"></span>
        <span v-if="!isCollapsed">企业 AI 工作台</span>
      </div>
    </aside>

    <div class="workspace">
      <header class="top-nav">
        <button class="mobile-menu-btn" @click="mobileDrawerOpen = true" aria-label="打开菜单">
          <el-icon size="20"><Menu /></el-icon>
        </button>

        <div class="page-context">
          <span class="context-section">{{ currentGroupTitle }}</span>
          <strong>{{ currentMenu?.label || brandStore.systemName }}</strong>
        </div>

        <div class="spacer"></div>

        <button class="search-command" type="button" @click="router.push('/conv-search')">
          <el-icon :size="15"><Search /></el-icon>
          <span>搜索对话记录</span>
          <span class="shortcut-hint" aria-hidden="true">⌘ K</span>
        </button>

        <el-dropdown trigger="click" @command="handleCommand">
          <button class="header-user" type="button" aria-label="打开账户菜单">
            <el-avatar :size="32" :src="userStore.userInfo?.avatar">
              {{ (userStore.userInfo?.nickname || 'U').charAt(0).toUpperCase() }}
            </el-avatar>
            <span class="header-username">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
            <el-icon size="12" color="var(--ink-4)"><ArrowDown /></el-icon>
          </button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">
                <el-icon><User /></el-icon> 个人中心
              </el-dropdown-item>
              <el-dropdown-item v-if="isAdmin" command="admin-home" divided>
                <el-icon><SetUp /></el-icon> 管理员控制台
              </el-dropdown-item>
              <el-dropdown-item command="logout" divided>
                <el-icon><SwitchButton /></el-icon> 退出登录
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>

      <main class="main-content">
        <div class="page-body">
          <router-view v-slot="{ Component }">
            <transition name="page-fade" mode="out-in">
              <keep-alive :include="['ChatView']">
                <component :is="Component" :key="route.fullPath" />
              </keep-alive>
            </transition>
          </router-view>
        </div>
      </main>
    </div>

    <div v-if="mobileDrawerOpen" class="mobile-drawer-mask" @click="mobileDrawerOpen = false"></div>
    <aside class="mobile-drawer" :class="{ open: mobileDrawerOpen }">
      <div class="drawer-head">
        <span class="drawer-brand">{{ brandStore.systemName }}</span>
        <button class="drawer-close" @click="mobileDrawerOpen = false" aria-label="关闭菜单"><el-icon><Close /></el-icon></button>
      </div>
      <nav class="drawer-nav">
        <template v-for="(group, gi) in menuGroups" :key="gi">
          <div v-if="groupHasVisibleItems(group)" class="nav-group">
            <button class="nav-group-header" type="button" @click="toggleGroup(gi)">
              <span class="group-label">{{ group.title }}</span>
              <el-icon class="group-arrow" :class="{ collapsed: !isGroupExpanded(gi) }" size="11"><ArrowDown /></el-icon>
            </button>
            <div class="nav-group-items" :class="{ 'is-collapsed': !isGroupExpanded(gi) }">
              <router-link
                v-for="item in group.items"
                :key="item.path"
                v-show="canAccess(item)"
                :to="item.path"
                class="nav-item"
                :class="{ active: isActive(item.path) }"
                @click="mobileDrawerOpen = false"
              >
                <span class="nav-icon-wrap"><el-icon size="17"><component :is="item.icon" /></el-icon></span>
                <span class="nav-label">{{ item.label }}</span>
              </router-link>
            </div>
          </div>
        </template>
      </nav>
      <div class="drawer-foot">
        <el-avatar :size="28" :src="userStore.userInfo?.avatar">{{ (userStore.userInfo?.nickname || 'U').charAt(0).toUpperCase() }}</el-avatar>
        <span class="drawer-user">{{ userStore.userInfo?.nickname || userStore.userInfo?.username }}</span>
        <button class="drawer-logout" @click="handleLogout" aria-label="退出登录"><el-icon size="15"><SwitchButton /></el-icon></button>
      </div>
    </aside>

    <GlobalUploadDock />
    <GlobalUploadDialog />
    <PptTaskNotifier />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { startIdleLogout } from '@/utils/idleLogout'
import BrandLogo from '@/components/BrandLogo.vue'
import GlobalUploadDock from '@/components/GlobalUploadDock.vue'
import GlobalUploadDialog from '@/components/GlobalUploadDialog.vue'
import PptTaskNotifier from '@/components/PptTaskNotifier.vue'
import { useBrandStore } from '@/stores/brand'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const brandStore = useBrandStore()
const isCollapsed = ref(localStorage.getItem('sidebar-collapsed') === '1')

// 移动端抽屉
const mobileDrawerOpen = ref(false)
watch(() => route.path, () => { mobileDrawerOpen.value = false })
watch(mobileDrawerOpen, v => {
  document.body.style.overflow = v ? 'hidden' : ''
})

// 菜单分组（与原来一致）
interface MenuItem {
  path: string
  label: string
  icon: string
  color: string
  badge?: string
  requiresAdmin?: boolean
  allUsers?: boolean
}
interface MenuGroup { title: string; items: MenuItem[] }
const menuGroups: MenuGroup[] = [
  {
    title: '工作台',
    items: [
      { path: '/',          label: '首页',        icon: 'HomeFilled',   color: '#0071E3' },
      { path: '/chat',      label: '智能问答',    icon: 'ChatDotRound', color: '#0071E3' },
      { path: '/digital-employees', label: '数字员工', icon: 'Avatar', color: '#22C55E' },
      { path: '/crew',      label: 'Agent 调研',  icon: 'MagicStick',   color: '#0a0a0a' },
      { path: '/coach',      label: '教练模式',    icon: 'Trophy',       color: '#0a0a0a' },
      { path: '/lead-hunter', label: '全球获客',   icon: 'Promotion',    color: '#F97316', allUsers: true },
      { path: '/customer-matching', label: '客户匹配', icon: 'Connection',  color: '#0071E3', allUsers: true },
      { path: '/market-opportunity', label: '市场机会', icon: 'DataAnalysis', color: '#AF52DE', allUsers: true },
      { path: '/qa-ranking', label: '问答排行',    icon: 'TrendCharts',  color: '#F59E0B', requiresAdmin: true },
      { path: '/workflow',   label: '系统编排',    icon: 'Operation',    color: '#06B6D4', requiresAdmin: true },
      { path: '/collections', label: '知识库',     icon: 'FolderOpened', color: '#0EA5E9', allUsers: true },
      { path: '/knowledge',  label: '所有文档',    icon: 'Document',     color: '#64748B', allUsers: true },
      { path: '/knowledge-graph', label: '知识图谱', icon: 'Share',     color: '#22D3EE', allUsers: true },
    ],
  },
  {
    title: '反馈与质量',
    items: [
      { path: '/feedback-review', label: '反馈审核',        icon: 'ChatLineSquare',    color: '#F59E0B', requiresAdmin: true },
      { path: '/golden-pair',     label: '经验库', icon: 'CircleCheckFilled', color: '#34D399', requiresAdmin: true },
      { path: '/conv-search',     label: '历史对话搜索',    icon: 'Search',            color: '#0a0a0a' },
    ],
  },
  {
    title: '组织与权限',
    items: [
      { path: '/users',       label: '用户管理',    icon: 'UserFilled',    color: '#EF4444', requiresAdmin: true },
      { path: '/org',         label: '组织与职位',  icon: 'OfficeBuilding', color: '#0a0a0a', requiresAdmin: true },
      { path: '/brand-settings', label: '品牌设置', icon: 'Brush',          color: '#EC4899', requiresAdmin: true },
      { path: '/datasource',  label: '数据源配置',  icon: 'Coin',           color: '#0EA5E9', requiresAdmin: true },
      { path: '/coach-stats', label: '教练学习统计', icon: 'Trophy',         color: '#0a0a0a', requiresAdmin: true },
    ],
  },
  {
    title: 'AI 模型配置',
    items: [
      { path: '/ai-config',    label: 'AI 配置',         icon: 'SetUp',      color: '#a1a1aa', requiresAdmin: true },
      { path: '/persona',      label: 'Soul 人格',        icon: 'StarFilled', color: '#EC4899', requiresAdmin: true },
      { path: '/skill-pack',   label: '技能包',          icon: 'MagicStick', color: '#0a0a0a', requiresAdmin: true },
      { path: '/coach-rule',   label: '教练规则',        icon: 'Trophy',     color: '#F59E0B', requiresAdmin: true },
      { path: '/llm-provider', label: '大模型 Provider', icon: 'Connection', color: '#06B6D4', requiresAdmin: true },
    ],
  },
  {
    title: '合规与安全',
    items: [
      { path: '/audit-log',  label: '审计日志', icon: 'Document', color: '#F59E0B', requiresAdmin: true },
      { path: '/pii-config', label: 'PII 脱敏', icon: 'Lock',     color: '#EF4444', requiresAdmin: true },
    ],
  },
  {
    title: '运维与监控',
    items: [
      { path: '/mcp',       label: 'MCP 控制台',   icon: 'Grid',         color: '#10B981', requiresAdmin: true },
      { path: '/dashboard', label: '数据大屏',     icon: 'DataAnalysis', color: '#F59E0B', requiresAdmin: true },
      { path: '/usage-reconcile', label: '账单对账', icon: 'Wallet',     color: '#B45309', requiresAdmin: true },
      { path: '/api-key',   label: 'API Key 管理', icon: 'Key',         color: '#0EA5E9', requiresAdmin: true },
      { path: '/dingtalk-bot', label: '钉钉机器人',  icon: 'ChatDotRound', color: '#3D7EFF', requiresAdmin: true },
    ],
  },
  {
    title: '个人',
    items: [
      { path: '/profile', label: '个人中心', icon: 'Setting', color: '#64748B' },
    ],
  },
]

const menuItems = menuGroups.flatMap(g => g.items)
const canAccess = (item: MenuItem) =>
  item.allUsers || item.path === '/' || userStore.hasFeature(item.path.replace(/^\//, ''))
const groupHasVisibleItems = (group: MenuGroup) => group.items.some(canAccess)
const isActive = (path: string) =>
  path === '/' ? route.path === '/' : route.path.startsWith(path)

const currentMenu = computed(() => menuItems.find(m => isActive(m.path)))
const currentGroupTitle = computed(() =>
  menuGroups.find(group => group.items.some(item => isActive(item.path)))?.title || '工作台'
)

const toggleSidebar = () => {
  isCollapsed.value = !isCollapsed.value
  localStorage.setItem('sidebar-collapsed', isCollapsed.value ? '1' : '0')
}

// 分组折叠状态（移动端抽屉用）
const collapsedGroups = ref<Set<number>>(
  new Set(JSON.parse(localStorage.getItem('sidebar-collapsed-groups') || '[]'))
)
const isGroupExpanded = (gi: number) => !collapsedGroups.value.has(gi)
const activeGroupIndex = computed(() =>
  menuGroups.findIndex(g => g.items.some(it => isActive(it.path)))
)
const toggleGroup = (gi: number) => {
  const next = new Set(collapsedGroups.value)
  if (next.has(gi)) next.delete(gi)
  else next.add(gi)
  collapsedGroups.value = next
  localStorage.setItem('sidebar-collapsed-groups', JSON.stringify([...next]))
}
watch(activeGroupIndex, (gi) => {
  if (gi < 0) return
  if (collapsedGroups.value.has(gi)) {
    const next = new Set(collapsedGroups.value)
    next.delete(gi)
    collapsedGroups.value = next
    localStorage.setItem('sidebar-collapsed-groups', JSON.stringify([...next]))
  }
}, { immediate: true })

const handleCommand = async (command: string) => {
  if (command === 'profile') {
    router.push('/profile')
  } else if (command === 'admin-home') {
    router.push('/admin')
  } else if (command === 'logout') {
    handleLogout()
  } else if (command.startsWith('nav:')) {
    router.push(command.slice(4))
  }
}

const isAdmin = computed(() => userStore.isAdmin)

const handleLogout = async () => {
  await ElMessageBox.confirm('确认退出登录？', '提示', { type: 'warning' })
  userStore.logout()
  router.push('/login')
}

// 闲置自动下线
const IDLE_TIMEOUT_MS = 60 * 60 * 1000
let stopIdleLogout: (() => void) | null = null

const handleGlobalShortcut = (event: KeyboardEvent) => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    router.push('/conv-search')
  }
}

onMounted(() => {
  window.addEventListener('keydown', handleGlobalShortcut)
  stopIdleLogout = startIdleLogout(IDLE_TIMEOUT_MS, () => {
    userStore.logout()
    router.push('/login')
  })
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalShortcut)
  stopIdleLogout?.()
})
</script>

<style scoped>
.main-layout {
  display: flex;
  flex-direction: column;
  height: 100vh;
  height: 100dvh;
  height: var(--app-height, 100dvh);
  overflow: hidden;
  background: transparent;
}

/* ═══ 全局浅色呼吸光斑 ═══ */
.blob {
  position: fixed;
  border-radius: 50%;
  filter: blur(110px);
  pointer-events: none;
  z-index: 0;
  will-change: transform, opacity;
}
.b1 { width: 640px; height: 640px; top: -200px; left: -200px; background: radial-gradient(circle, #FFD7E4 0%, transparent 65%); opacity: .55; animation: breathe1 11s ease-in-out infinite; }
.b2 { width: 580px; height: 580px; top: -100px; right: -180px; background: radial-gradient(circle, #C5DBFF 0%, transparent 65%); opacity: .5; animation: breathe2 13s ease-in-out infinite; }
.b3 { width: 520px; height: 520px; top: 30%; right: -160px; background: radial-gradient(circle, #DDD0FF 0%, transparent 65%); opacity: .45; animation: breathe3 15s ease-in-out infinite; }
.b4 { width: 500px; height: 500px; bottom: -180px; left: -100px; background: radial-gradient(circle, #D4F4E0 0%, transparent 65%); opacity: .4; animation: breathe4 17s ease-in-out infinite; }
@keyframes breathe1 { 0%,100%{transform:translate(0,0) scale(1);opacity:.45} 50%{transform:translate(50px,60px) scale(1.15);opacity:.65} }
@keyframes breathe2 { 0%,100%{transform:translate(0,0) scale(1);opacity:.4} 50%{transform:translate(-40px,80px) scale(1.12);opacity:.6} }
@keyframes breathe3 { 0%,100%{transform:translate(0,0) scale(1);opacity:.35} 50%{transform:translate(-30px,-50px) scale(1.18);opacity:.55} }
@keyframes breathe4 { 0%,100%{transform:translate(0,0) scale(1);opacity:.3} 50%{transform:translate(70px,-30px) scale(1.14);opacity:.5} }

/* ═══ 顶部导航 ═══ */
.top-nav {
  position: relative;
  z-index: 100;
  display: flex;
  align-items: center;
  gap: 18px;
  height: 56px;
  padding: 0 22px;
  background: rgba(255, 255, 255, .72);
  backdrop-filter: blur(20px) saturate(180%);
  -webkit-backdrop-filter: blur(20px) saturate(180%);
  border-bottom: .5px solid var(--line);
  flex-shrink: 0;
}
.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  text-decoration: none;
  color: var(--ink-1);
  height: 22px;
  line-height: 1;
}
.brand-mark { display: flex; align-items: center; flex-shrink: 0; }
.brand-name {
  font-family: -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'SF Pro Text', 'PingFang SC', 'Hiragino Sans GB', sans-serif;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -.015em;
  line-height: 1;
  white-space: nowrap;
}
.top-nav-links { display: flex; align-items: center; gap: 2px; margin-left: 6px; }
.top-link {
  padding: 6px 13px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
  color: var(--ink-3);
  text-decoration: none;
  transition: color 180ms, background 180ms;
  white-space: nowrap;
}
.top-link:hover { color: var(--ink-1); background: rgba(0,0,0,.04); }
.top-link.active { color: #0071E3; font-weight: 600; background: rgba(0,113,227,.08); }
.spacer { flex: 1; }
.search-pill {
  display: flex;
  align-items: center;
  gap: 7px;
  height: 30px;
  padding: 0 13px;
  background: var(--bg-subtle);
  border: 1px solid var(--line);
  border-radius: 980px;
  font-size: 12.5px;
  color: var(--ink-4);
  cursor: pointer;
  transition: border-color 200ms, box-shadow 200ms;
}
.search-pill:hover { border-color: rgba(0,113,227,.4); box-shadow: 0 4px 14px rgba(0,113,227,.10); }
.header-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 10px;
  transition: background 180ms;
}
.header-user:hover { background: rgba(0,0,0,.04); }
.header-username { font-size: 13px; font-weight: 550; color: var(--ink-1); max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mobile-menu-btn {
  display: none;
  width: 32px; height: 32px;
  align-items: center; justify-content: center;
  background: transparent; border: 0; border-radius: 8px;
  color: var(--ink-2); cursor: pointer;
}
.mobile-menu-btn:hover { background: rgba(0,0,0,.05); }

/* 下拉菜单图标 */
.dd-icon {
  width: 20px; height: 20px;
  border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  margin-right: 6px;
  vertical-align: -4px;
}

/* ═══ 主内容区 ═══ */
.main-content {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: transparent;
  position: relative;
  z-index: 1;
}
.page-body { flex: 1; min-height: 0; overflow: hidden; }
.page-body :deep(.slide-up-enter-active), .page-body :deep(.slide-up-leave-active) { transition: all 220ms var(--ease); }
.page-body :deep(.slide-up-enter-from) { opacity: 0; transform: translateY(10px); }
.page-body :deep(.slide-up-leave-to) { opacity: 0; transform: translateY(-6px); }

/* ═══ 移动端抽屉 ═══ */
.mobile-drawer-mask {
  position: fixed; inset: 0; z-index: 800;
  background: rgba(15, 23, 42, .4);
  backdrop-filter: blur(2px);
}
.mobile-drawer {
  position: fixed; top: 0; left: 0; bottom: 0; z-index: 900;
  width: 280px;
  background: #fff;
  display: flex; flex-direction: column;
  transform: translateX(-100%);
  transition: transform 260ms var(--ease);
  box-shadow: 12px 0 40px rgba(0,0,0,.12);
}
.mobile-drawer.open { transform: translateX(0); }
.drawer-head {
  display: flex; align-items: center; justify-content: space-between;
  height: 56px; padding: 0 18px;
  border-bottom: 1px solid var(--line);
}
.drawer-brand { font-weight: 700; font-size: 15px; }
.drawer-close { width: 30px; height: 30px; border-radius: 8px; border: 0; background: transparent; color: var(--ink-2); cursor: pointer; }
.drawer-close:hover { background: var(--bg-subtle); }
.drawer-nav { flex: 1; overflow-y: auto; padding: 10px 12px; }
.drawer-foot {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 18px; border-top: 1px solid var(--line);
}
.drawer-user { flex: 1; font-size: 13px; font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.drawer-logout { width: 30px; height: 30px; border-radius: 8px; border: 0; background: transparent; color: var(--ink-3); cursor: pointer; }
.drawer-logout:hover { background: var(--danger-soft); color: var(--danger); }
.nav-group { margin-bottom: 6px; }
.nav-group-header {
  width: 100%; display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px; border: 0; background: transparent;
  font-size: 11px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase;
  color: var(--ink-4); cursor: pointer;
}
.group-arrow { transition: transform 200ms; }
.group-arrow.collapsed { transform: rotate(-90deg); }
.nav-group-items { overflow: hidden; transition: max-height 240ms var(--ease); max-height: 400px; }
.nav-group-items.is-collapsed { max-height: 0; }
.nav-item {
  display: flex; align-items: center; gap: 10px;
  padding: 9px 10px; border-radius: 10px;
  color: var(--ink-2); text-decoration: none; font-size: 13px;
  transition: background 150ms, color 150ms;
}
.nav-item:hover { background: var(--bg-subtle); color: var(--ink-1); }
.nav-item.active { background: rgba(0,113,227,.08); color: #0071E3; font-weight: 600; }
.nav-icon-wrap { display: inline-flex; color: var(--icon-color, var(--ink-3)); }
.nav-label { flex: 1; }

@media (max-width: 1024px) {
  .top-nav-links { display: none; }
}
@media (max-width: 768px) {
  .mobile-menu-btn { display: inline-flex; }
  .search-pill { display: none; }
  .header-username { display: none; }
  .brand-name { display: none; }
  .top-nav { padding: 0 14px; gap: 10px; }
}

/* Product workspace refresh: one navigation model for the full application. */
.main-layout {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  grid-template-rows: minmax(0, 1fr);
  height: var(--app-height, 100dvh);
  min-height: 0;
  overflow: hidden;
  background: var(--bg-page);
  transition: grid-template-columns 180ms var(--ease);
}

.main-layout.sidebar-collapsed { grid-template-columns: 76px minmax(0, 1fr); }

.desktop-sidebar {
  position: relative;
  z-index: 110;
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  background: var(--sidebar-bg);
  border-right: 1px solid var(--line);
}

.sidebar-head {
  display: flex;
  height: 64px;
  flex: 0 0 64px;
  align-items: center;
  gap: 10px;
  padding: 0 14px 0 16px;
  border-bottom: 1px solid var(--line-soft);
}

.sidebar-head .brand { min-width: 0; flex: 1; height: 38px; }
.sidebar-head .brand-name {
  overflow: hidden;
  color: var(--ink-1);
  font-size: 15px;
  font-weight: 700;
  text-overflow: ellipsis;
}

.collapse-btn {
  display: inline-flex;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  align-items: center;
  justify-content: center;
  border: 1px solid transparent;
  border-radius: 8px;
  color: var(--ink-4);
  transition: color 150ms ease, background 150ms ease, border-color 150ms ease;
}
.collapse-btn:hover { color: var(--ink-1); background: var(--bg-hover); border-color: var(--line); }

.desktop-nav {
  flex: 1;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 12px 10px 20px;
  scrollbar-width: thin;
}

.desktop-nav .nav-group { margin-bottom: 14px; }
.desktop-nav .nav-group-header {
  padding: 6px 10px;
  color: var(--ink-4);
  font-size: 11.5px;
  font-weight: 650;
  letter-spacing: 0;
  text-transform: none;
}
.desktop-nav .nav-group-items { display: grid; gap: 2px; }
.desktop-nav .nav-item {
  position: relative;
  min-height: 38px;
  padding: 8px 10px;
  border-radius: 9px;
  color: var(--ink-3);
  font-size: 13px;
  font-weight: 500;
}
.desktop-nav .nav-item:hover { color: var(--ink-1); background: var(--bg-hover); }
.desktop-nav .nav-item.active {
  color: var(--brand-ink);
  background: var(--brand-soft);
  font-weight: 650;
}
.desktop-nav .nav-item.active::before {
  position: absolute;
  top: 9px;
  bottom: 9px;
  left: 0;
  width: 3px;
  border-radius: 0 3px 3px 0;
  background: var(--brand);
  content: '';
}
.desktop-nav .nav-icon-wrap { width: 20px; justify-content: center; color: currentColor; }

.sidebar-collapsed .sidebar-head { justify-content: center; padding: 0; }
.sidebar-collapsed .sidebar-head .brand { flex: 0 0 auto; }
.sidebar-collapsed .collapse-btn {
  position: absolute;
  right: -13px;
  bottom: -16px;
  background: var(--bg-surface);
  border-color: var(--line);
  box-shadow: var(--shadow-sm);
}
.sidebar-collapsed .desktop-nav { padding-inline: 9px; padding-top: 24px; }
.sidebar-collapsed .desktop-nav .nav-group { margin-bottom: 10px; }
.sidebar-collapsed .desktop-nav .nav-item { justify-content: center; padding-inline: 0; }
.sidebar-collapsed .desktop-nav .nav-item.active::before { top: 8px; bottom: 8px; }

.sidebar-foot {
  display: flex;
  min-height: 48px;
  flex: 0 0 48px;
  align-items: center;
  gap: 9px;
  padding: 0 18px;
  border-top: 1px solid var(--line-soft);
  color: var(--ink-4);
  font-size: 11.5px;
  white-space: nowrap;
}
.sidebar-status { width: 7px; height: 7px; flex: 0 0 7px; border-radius: 50%; background: var(--success); }
.sidebar-collapsed .sidebar-foot { justify-content: center; padding-inline: 0; }

.workspace { display: flex; min-width: 0; min-height: 0; flex-direction: column; overflow: hidden; }

.top-nav {
  position: relative;
  z-index: 100;
  display: flex;
  height: 64px;
  flex: 0 0 64px;
  align-items: center;
  gap: 14px;
  padding: 0 22px;
  background: rgba(255, 255, 255, .94);
  border-bottom: 1px solid var(--line);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
}

.page-context { display: flex; min-width: 0; flex-direction: column; line-height: 1.2; }
.page-context .context-section { color: var(--ink-4); font-size: 11px; }
.page-context strong {
  overflow: hidden;
  margin-top: 3px;
  color: var(--ink-1);
  font-size: 15px;
  font-weight: 700;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.search-command {
  display: inline-flex;
  width: min(240px, 24vw);
  height: 36px;
  align-items: center;
  gap: 9px;
  padding: 0 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--bg-subtle);
  color: var(--ink-4);
  font-size: 12.5px;
  text-align: left;
  transition: border-color 150ms ease, background 150ms ease, color 150ms ease;
}
.search-command:hover { border-color: var(--line-strong); background: var(--bg-surface); color: var(--ink-2); }

.header-user {
  min-height: 40px;
  padding: 4px 7px 4px 5px;
  border: 1px solid transparent;
  background: transparent;
}
.header-user:hover { background: var(--bg-hover); border-color: var(--line); }
.header-username { color: var(--ink-2); font-size: 12.5px; font-weight: 600; }

.main-content { min-width: 0; min-height: 0; background: var(--bg-page); }
.page-body { min-width: 0; min-height: 0; }
.page-body :deep(.page-fade-enter-active), .page-body :deep(.page-fade-leave-active) { transition: opacity 140ms ease; }
.page-body :deep(.page-fade-enter-from), .page-body :deep(.page-fade-leave-to) { opacity: 0; }

.mobile-drawer {
  width: min(86vw, 320px);
  background: var(--bg-surface);
  box-shadow: 18px 0 48px rgba(15, 23, 42, .16);
}
.drawer-head { height: 64px; }
.drawer-brand { color: var(--ink-1); font-weight: 700; }
.drawer-nav .nav-group-header { letter-spacing: 0; text-transform: none; }
.drawer-nav .nav-item { min-height: 42px; }

@media (max-width: 1024px) {
  .main-layout,
  .main-layout.sidebar-collapsed { grid-template-columns: minmax(0, 1fr); }
  .desktop-sidebar { display: none; }
  .mobile-menu-btn { display: inline-flex; }
  .page-context .context-section { display: none; }
  .top-nav { padding-inline: 16px; }
}

@media (max-width: 768px) {
  .top-nav { height: 56px; flex-basis: 56px; gap: 9px; padding-inline: 12px; }
  .search-command { width: 36px; padding: 0; justify-content: center; }
  .search-command span { display: none; }
  .header-username { display: none; }
  .page-context strong { font-size: 14px; }
}

@media (prefers-reduced-motion: reduce) {
  .main-layout,
  .nav-group-items,
  .mobile-drawer,
  .page-body :deep(.page-fade-enter-active),
  .page-body :deep(.page-fade-leave-active) { transition: none !important; }
}
</style>
