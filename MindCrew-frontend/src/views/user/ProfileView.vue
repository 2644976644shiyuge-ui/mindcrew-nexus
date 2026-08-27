<template>
  <div class="profile-page">
    <!-- 面包屑 -->
    <nav class="crumb">
      <span class="crumb-item ghost">个人中心</span>
      <el-icon :size="11" class="crumb-sep"><ArrowRight /></el-icon>
      <span class="crumb-item">账户信息</span>
    </nav>

    <!-- ──────────────────── HERO ──────────────────── -->
    <section class="hero">
      <!-- 装饰球 -->
      <div class="hero-decor">
        <span class="orb orb-1"></span>
        <span class="orb orb-2"></span>
        <span class="orb orb-3"></span>
      </div>

      <div class="hero-grid">
        <div class="hero-avatar-wrap" @click="triggerAvatarUpload">
          <div class="hero-avatar">
            <img v-if="avatarPreview || userInfo?.avatar" :src="avatarPreview || userInfo?.avatar" alt="avatar" />
            <span v-else class="avatar-letter">{{ (userInfo?.nickname || userInfo?.username || '管').charAt(0).toUpperCase() }}</span>
            <div class="avatar-mask" :class="{ uploading: avatarUploading }">
              <el-icon :size="22"><Camera /></el-icon>
            </div>
          </div>
          <input
            ref="avatarInputRef"
            type="file"
            accept="image/jpeg,image/png,image/webp,image/gif"
            hidden
            @change="onAvatarFileChange"
          />
        </div>

        <div class="hero-info">
          <h1 class="hero-name">{{ userInfo?.nickname || userInfo?.username || '管理员' }}</h1>

          <div class="hero-row">
            <span class="hero-handle">@{{ userInfo?.username || 'admin' }}</span>
            <span class="hero-badge" :class="userInfo?.role">
              <el-icon :size="11"><Medal /></el-icon>
              <span>{{ roleLabel }}</span>
            </span>
          </div>

          <p class="hero-desc">{{ heroDesc }}</p>

          <div class="hero-meta">
            <div class="meta-row">
              <span class="meta-ic"><el-icon :size="13"><Calendar /></el-icon></span>
              <span class="meta-k">注册时间</span>
              <span class="meta-v">{{ formatDate(userInfo?.createTime) }}</span>
            </div>
            <div class="meta-row">
              <span class="meta-ic"><el-icon :size="13"><Clock /></el-icon></span>
              <span class="meta-k">最近登录</span>
              <span class="meta-v">{{ formatDateTime(userInfo?.lastLogin) }}</span>
            </div>
          </div>
        </div>

        <button class="hero-cta" @click="scrollToForm">
          <el-icon :size="14"><EditPen /></el-icon>
          <span>编辑资料</span>
        </button>
      </div>
    </section>

    <!-- ──────────────────── STATS（真实数据 · 拿不到就不展示） ──────────────────── -->
    <section v-if="stats.length > 0" class="stats">
      <article v-for="s in stats" :key="s.key" class="stat-card">
        <div class="stat-head">
          <span class="stat-ic" :style="{ background: s.iconBg, color: s.iconColor }">
            <el-icon :size="18"><component :is="s.icon" /></el-icon>
          </span>
          <span class="stat-label">{{ s.label }}</span>
        </div>
        <div class="stat-num">
          <CountUp :end-val="s.value" :duration="1.4" :options="{ useEasing: true, decimalPlaces: s.decimals || 0 }" />
          <span v-if="s.suffix" class="stat-suffix">{{ s.suffix }}</span>
        </div>
      </article>
    </section>

    <!-- ──────────────────── TABS ──────────────────── -->
    <nav class="tabs" ref="tabNavRef">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        :ref="el => setTabRef(tab.key, el)"
        class="tab"
        :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        <el-icon :size="14"><component :is="tab.icon" /></el-icon>
        <span>{{ tab.label }}</span>
      </button>
      <span class="tab-bar" :style="indicatorStyle"></span>
    </nav>

    <!-- ──────────────────── CONTENT (基本信息) ──────────────────── -->
    <section v-if="activeTab === 'basic'" class="content single">
      <!-- 左：表单 -->
      <div class="card form-card">
        <header class="card-head">
          <div>
            <h2 class="card-title">基本信息</h2>
            <p class="card-sub">维护你的基础账号信息，部分字段会用于 Agent 个性化</p>
          </div>
        </header>

        <form class="profile-form" @submit.prevent="saveBasicInfo">
          <div class="field">
            <div class="field-lbl">
              <span class="field-name">昵称</span>
              <span class="field-hint">在系统中显示的名称</span>
            </div>
            <input v-model="basicForm.nickname" class="field-input" placeholder="你的称呼" />
          </div>

          <div class="field">
            <div class="field-lbl">
              <span class="field-name">手机号</span>
              <span class="field-hint">用于账号安全和通知</span>
            </div>
            <input v-model="basicForm.phone" class="field-input" placeholder="未填写" />
          </div>

          <div class="field">
            <div class="field-lbl">
              <span class="field-name">角色</span>
              <span class="field-hint">当前账号角色</span>
            </div>
            <div class="field-static">{{ roleLabel }}</div>
          </div>

          <!-- 邮箱 / 所属团队 字段：后端 UserInfo 暂无对应字段，故不展示，避免 placeholder 给人错觉是已绑定。
               待 user 表加 email / org_name 后再恢复字段。 -->


          <div class="form-foot">
            <button type="submit" class="save-btn" :disabled="basicLoading">
              <span v-if="!basicLoading">保存修改</span>
              <span v-else class="loading-wrap">
                <el-icon class="spin"><Loading /></el-icon>保存中…
              </span>
            </button>
          </div>
        </form>
      </div>

      <!-- 「修改密码」已迁移到独立的「安全设置」tab（见下方 security section）。
           登录设备管理 / 双因素认证 / API 密钥管理 / 账号注销 4 项后端仍无对应接口，
           待对应模块上线后再恢复对应入口。 -->
    </section>

    <!-- ──────────────────── CONTENT (安全设置) ──────────────────── -->
    <section v-else-if="activeTab === 'security'" class="content single">
      <div class="card form-card">
        <header class="card-head">
          <div>
            <h2 class="card-title">修改密码</h2>
            <p class="card-sub">需先验证当前密码，修改成功后请牢记新密码</p>
          </div>
        </header>

        <form class="profile-form" @submit.prevent="changePassword">
          <div class="field">
            <div class="field-lbl">
              <span class="field-name">当前密码</span>
              <span class="field-hint">用于验证身份</span>
            </div>
            <input
              v-model="pwdForm.oldPassword"
              type="password"
              class="field-input"
              placeholder="请输入当前密码"
              autocomplete="current-password"
            />
          </div>

          <div class="field">
            <div class="field-lbl">
              <span class="field-name">新密码</span>
              <span class="field-hint">6-32 位字符</span>
            </div>
            <input
              v-model="pwdForm.newPassword"
              type="password"
              class="field-input"
              placeholder="请输入新密码"
              autocomplete="new-password"
            />
          </div>

          <div class="field">
            <div class="field-lbl">
              <span class="field-name">确认新密码</span>
              <span class="field-hint">再次输入新密码</span>
            </div>
            <input
              v-model="pwdForm.confirmPassword"
              type="password"
              class="field-input"
              placeholder="请再次输入新密码"
              autocomplete="new-password"
            />
          </div>

          <div class="form-foot">
            <button type="submit" class="save-btn" :disabled="pwdLoading">
              <span v-if="!pwdLoading">确认修改</span>
              <span v-else class="loading-wrap">
                <el-icon class="spin"><Loading /></el-icon>提交中…
              </span>
            </button>
          </div>
        </form>
      </div>
    </section>

    <!-- 我的音色（自定义音色复刻） -->
    <section v-else-if="activeTab === 'voice'" class="content single">
      <VoiceCloneCard />
    </section>

    <!-- 登录历史：后端暂未提供接口，先不展示，避免假数据。
         如后续接 GET /user/login-history，在此恢复表格即可。 -->
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed, watch, nextTick, markRaw } from 'vue'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { userApi } from '@/api/user'
import { knowledgeApi } from '@/api/knowledge'
import { usageApi } from '@/api/usage'
import {
  UserFilled, Medal, Clock, Camera, EditPen, ArrowRight,
  Loading, FolderOpened, Histogram, Timer,
  Calendar, Mic, Lock,
} from '@element-plus/icons-vue'
import VoiceCloneCard from '@/components/VoiceCloneCard.vue'

const userStore = useUserStore()
const userInfo = computed(() => userStore.userInfo)

const activeTab = ref('basic')
const basicLoading = ref(false)
const avatarUploading = ref(false)
const avatarPreview = ref<string>('')
const avatarInputRef = ref<HTMLInputElement>()

// 只保留接通真实功能的 tab：基本信息 + 安全设置 + 我的音色。
// 偏好设置当前还是占位，留在 UI 上会误导用户。
const tabs = [
  { key: 'basic', label: '基本信息', icon: markRaw(UserFilled) },
  { key: 'security', label: '安全设置', icon: markRaw(Lock) },
  { key: 'voice', label: '我的音色', icon: markRaw(Mic) },
]

const tabNavRef = ref<HTMLElement>()
const tabRefs: Record<string, HTMLElement | null> = {}
const setTabRef = (k: string, el: any) => { tabRefs[k] = el as HTMLElement | null }
const indicatorStyle = ref({ left: '0px', width: '0px', opacity: '0' })

const updateIndicator = () => {
  const el = tabRefs[activeTab.value]
  if (!el || !tabNavRef.value) return
  const navRect = tabNavRef.value.getBoundingClientRect()
  const btnRect = el.getBoundingClientRect()
  indicatorStyle.value = {
    left: (btnRect.left - navRect.left) + 'px',
    width: btnRect.width + 'px',
    opacity: '1',
  }
}
watch(activeTab, () => nextTick(updateIndicator))
onMounted(() => nextTick(updateIndicator))

const roleLabel = computed(() => {
  const map: Record<string, string> = { admin: '超级管理员', user: '普通用户' }
  return map[userInfo.value?.role || 'user'] || '用户'
})

const heroDesc = computed(() => {
  return userInfo.value?.role === 'admin'
    ? '系统超级管理员，拥有所有功能的访问权限'
    : '系统普通用户，可使用基础问答和调研功能'
})

const triggerAvatarUpload = () => { avatarInputRef.value?.click() }
const onAvatarFileChange = async (e: Event) => {
  const file = (e.target as HTMLInputElement).files?.[0]
  if (!file) return
  avatarPreview.value = URL.createObjectURL(file)
  avatarUploading.value = true
  try {
    await userApi.uploadAvatar(file)
    await userStore.fetchUserInfo()
    ElMessage.success('头像更新成功')
  } catch {
    avatarPreview.value = ''
    ElMessage.error('头像上传失败，请重试')
  } finally {
    avatarUploading.value = false
    if (avatarInputRef.value) avatarInputRef.value.value = ''
  }
}

const formatDate = (s?: string) => {
  if (!s) return '-'
  return new Date(s).toLocaleDateString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).replace(/\//g, '/')
}
const formatDateTime = (s?: string) => {
  if (!s) return '-'
  const d = new Date(s)
  return d.toLocaleString('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false })
}

const basicForm = reactive({ nickname: '', phone: '' })

// 修改密码（登录态，校验原密码）
const pwdLoading = ref(false)
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

onMounted(() => {
  if (userInfo.value) {
    basicForm.nickname = userInfo.value.nickname || ''
    basicForm.phone    = userInfo.value.phone || ''
  }
  loadStats()
})

const saveBasicInfo = async () => {
  basicLoading.value = true
  try {
    await userApi.updateUserInfo(basicForm)
    await userStore.fetchUserInfo()
    ElMessage.success('信息更新成功')
  } catch {
    ElMessage.error('保存失败，请重试')
  } finally {
    basicLoading.value = false
  }
}

const changePassword = async () => {
  const { oldPassword, newPassword, confirmPassword } = pwdForm
  if (!oldPassword || !newPassword || !confirmPassword) {
    ElMessage.warning('请填写完整的密码信息')
    return
  }
  if (newPassword.length < 6 || newPassword.length > 32) {
    ElMessage.warning('新密码长度为 6-32 位')
    return
  }
  if (newPassword !== confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致')
    return
  }
  if (newPassword === oldPassword) {
    ElMessage.warning('新密码不能与原密码相同')
    return
  }
  pwdLoading.value = true
  try {
    await userApi.changePassword({ oldPassword, newPassword })
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
    ElMessage.success('密码修改成功')
  } catch {
    // 后端校验失败（如"原密码不正确"）的具体提示已由 request 响应拦截器统一弹出，
    // 此处仅捕获 reject，避免重复弹错。
  } finally {
    pwdLoading.value = false
  }
}

const scrollToForm = () => {
  activeTab.value = 'basic'
  setTimeout(() => {
    document.querySelector('.form-card')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  }, 100)
}

// ─── stats 卡片：全部走真实 API，拿不到的直接不渲染 ────────────────────
interface StatCard {
  key: string
  label: string
  value: number
  suffix: string
  decimals?: number
  icon: any
  iconBg: string
  iconColor: string
}
const stats = ref<StatCard[]>([])

const loadStats = async () => {
  const cards: StatCard[] = []
  // ① 我创建的文档数量
  try {
    const res: any = await knowledgeApi.list({ current: 1, size: 1 })
    const total = res?.total ?? res?.data?.total ?? 0
    if (typeof total === 'number') {
      cards.push({
        key: 'knowledge', label: '文档数量', value: total, suffix: '',
        icon: markRaw(FolderOpened), iconBg: '#EEF2FF', iconColor: '#5B8FF9',
      })
    }
  } catch (_) { /* 拿不到就不放卡片 */ }
  // ② 本月对话次数 + 本月成本（同一个接口取两项）
  try {
    const res: any = await usageApi.me()
    const data = res?.data ?? res ?? {}
    if (typeof data.chatCount === 'number') {
      cards.push({
        key: 'chat', label: '本月对话次数', value: data.chatCount, suffix: '',
        icon: markRaw(Histogram), iconBg: '#E6FFFA', iconColor: '#10B981',
      })
    }
    if (typeof data.costCny === 'number') {
      cards.push({
        key: 'cost', label: '本月成本', value: data.costCny, suffix: ' 元', decimals: 4,
        icon: markRaw(Timer), iconBg: '#FFF7E6', iconColor: '#FFB547',
      })
    }
  } catch (_) { /* skip */ }
  stats.value = cards
}
</script>

<style scoped>
/* ─────────────────────────────────────────────
   Page chrome
   ───────────────────────────────────────────── */
.profile-page {
  height: 100%;
  overflow-y: auto;
  padding: 22px 32px 56px;
  background: var(--bg-page);
}

/* Breadcrumb */
.crumb {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 22px;
  font-size: 13px;
  color: var(--ink-3);
  font-weight: 500;
}
.crumb-item { transition: color 180ms var(--ease); }
.crumb-item.ghost { color: var(--ink-4); cursor: pointer; }
.crumb-item.ghost:hover { color: var(--ink-2); }
.crumb-sep { color: var(--ink-4); }

/* ─────────────────────────────────────────────
   HERO
   ───────────────────────────────────────────── */
.hero {
  position: relative;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-lg);
  padding: 30px 32px;
  margin-bottom: 22px;
  overflow: hidden;
  box-shadow: var(--shadow-card);
}
.hero-decor {
  position: absolute;
  inset: 0;
  pointer-events: none;
  overflow: hidden;
}
.orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(28px);
  opacity: 0.6;
}
.orb-1 { width: 280px; height: 280px; top: -80px; right: -40px; background: radial-gradient(circle, #C7BFFF 0%, transparent 70%); }
.orb-2 { width: 220px; height: 220px; top: 40px; right: 200px;  background: radial-gradient(circle, #E2D4FF 0%, transparent 70%); }
.orb-3 { width: 160px; height: 160px; top: 100px; right: -20px; background: radial-gradient(circle, #FFD5F1 0%, transparent 70%); }

.hero-grid {
  position: relative;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 28px;
  align-items: center;
}

.hero-avatar-wrap { cursor: pointer; position: relative; }
.hero-avatar {
  width: 132px;
  height: 132px;
  border-radius: 50%;
  background: linear-gradient(135deg, #8B7EFF 0%, #6B5AE6 55%, #4A3FBA 100%);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-family: 'Manrope', sans-serif;
  font-weight: 700;
  position: relative;
  overflow: hidden;
  box-shadow:
    0 18px 40px rgba(107, 90, 230, 0.34),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}
.hero-avatar img { width: 100%; height: 100%; object-fit: cover; }
.avatar-letter {
  font-size: 56px;
  letter-spacing: -0.04em;
}
.avatar-mask {
  position: absolute;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  opacity: 0;
  transition: opacity 200ms var(--ease);
}
.hero-avatar-wrap:hover .avatar-mask { opacity: 1; }
.avatar-mask.uploading { opacity: 1; }
.avatar-mask.uploading .el-icon { animation: spin 1.2s linear infinite; }

.hero-info { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.hero-name {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 26px;
  font-weight: 800;
  color: var(--ink-1);
  letter-spacing: -0.022em;
  line-height: 1.2;
}
.hero-row { display: flex; align-items: center; gap: 10px; }
.hero-handle {
  font-family: 'JetBrains Mono', monospace;
  font-size: 13px;
  color: var(--ink-3);
}
.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 9px;
  border-radius: var(--radius-pill);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.02em;
  background: linear-gradient(135deg, #F0EBFF, #E2D5FF);
  color: #6B5AE6;
  border: 1px solid #D4C2FF;
}
.hero-badge.user { background: var(--bg-subtle); color: var(--ink-3); border-color: var(--line); }

.hero-desc {
  font-size: 13.5px;
  color: var(--ink-2);
  margin-top: 2px;
  line-height: 1.6;
}

.hero-meta {
  display: flex;
  gap: 20px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.meta-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
}
.meta-ic {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--bg-subtle);
  color: var(--ink-3);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.meta-k { color: var(--ink-3); font-weight: 500; }
.meta-v { color: var(--ink-1); font-weight: 600; font-family: 'JetBrains Mono', monospace; }

.hero-cta {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius-sm);
  color: var(--ink-1);
  font-size: 12.5px;
  font-weight: 600;
  cursor: pointer;
  transition: var(--transition);
  align-self: flex-start;
}
.hero-cta:hover {
  border-color: var(--brand);
  color: var(--brand);
  background: var(--brand-soft);
}

/* ─────────────────────────────────────────────
   STATS
   ───────────────────────────────────────────── */
.stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 22px;
}
.stat-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px 18px 14px;
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: var(--transition);
}
.stat-card:hover {
  border-color: var(--brand-soft-2);
  box-shadow: var(--shadow-md);
}
.stat-head { display: flex; align-items: center; gap: 10px; }
.stat-ic {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-label {
  font-size: 12.5px;
  color: var(--ink-3);
  font-weight: 500;
}
.stat-num {
  font-family: 'Manrope', sans-serif;
  font-size: 28px;
  font-weight: 800;
  color: var(--ink-1);
  letter-spacing: -0.02em;
  line-height: 1.1;
  display: flex;
  align-items: baseline;
  gap: 4px;
}
.stat-suffix {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink-3);
  margin-left: 2px;
}
.stat-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.stat-trend {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  font-weight: 600;
}
.stat-trend.up   { color: var(--success-ink); }
.stat-trend.down { color: var(--danger-ink); }
.spark { width: 72px; height: 22px; opacity: 0.7; flex-shrink: 0; }

/* ─────────────────────────────────────────────
   TABS
   ───────────────────────────────────────────── */
.tabs {
  position: relative;
  display: flex;
  gap: 4px;
  padding: 0 4px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 22px;
}
.tab {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 12px 16px;
  background: transparent;
  border: none;
  color: var(--ink-3);
  font-family: 'Manrope', sans-serif;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: color 180ms var(--ease);
  position: relative;
}
.tab:hover { color: var(--ink-1); }
.tab.active { color: var(--brand); }
.tab-bar {
  position: absolute;
  bottom: -1px;
  height: 2px;
  background: linear-gradient(90deg, var(--brand-hover), var(--brand));
  border-radius: 2px;
  transition: left 220ms var(--ease), width 220ms var(--ease);
}

/* ─────────────────────────────────────────────
   CONTENT - 两栏
   ───────────────────────────────────────────── */
.content {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
  gap: 18px;
  margin-bottom: 22px;
}
.content.single { grid-template-columns: 1fr; }

.card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  box-shadow: var(--shadow-card);
  padding: 22px 24px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}
.card-title {
  font-family: 'Manrope', 'Noto Sans SC', sans-serif;
  font-size: 16px;
  font-weight: 700;
  color: var(--ink-1);
  letter-spacing: -0.012em;
}
.card-sub {
  font-size: 12.5px;
  color: var(--ink-3);
  margin-top: 2px;
}

/* 表单 */
.profile-form { display: flex; flex-direction: column; gap: 16px; }
.field {
  display: grid;
  grid-template-columns: 160px 1fr;
  align-items: center;
  gap: 16px;
}
.field-lbl { display: flex; flex-direction: column; gap: 2px; }
.field-name {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-1);
}
.field-hint {
  font-size: 11.5px;
  color: var(--ink-3);
}
.field-input {
  height: 40px;
  padding: 0 14px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--line);
  background: var(--bg-surface);
  font-size: 13.5px;
  color: var(--ink-1);
  font-family: inherit;
  outline: none;
  transition: var(--transition);
}
.field-input::placeholder { color: var(--ink-4); }
.field-input:hover { border-color: var(--line-strong); }
.field-input:focus {
  border-color: var(--brand);
  box-shadow: 0 0 0 4px var(--brand-glow);
}
.field-input.disabled,
.field-input:disabled {
  background: var(--bg-subtle);
  color: var(--ink-3);
  cursor: not-allowed;
}
.field-static {
  height: 40px;
  display: flex;
  align-items: center;
  padding: 0 14px;
  background: var(--bg-subtle);
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  font-size: 13.5px;
  color: var(--ink-2);
}

.form-foot {
  margin-top: 6px;
  padding-top: 12px;
}
.save-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 10px 22px;
  border-radius: var(--radius-sm);
  background: linear-gradient(180deg, var(--brand-hover), var(--brand));
  color: #fff;
  font-family: 'Manrope', sans-serif;
  font-weight: 700;
  font-size: 13.5px;
  letter-spacing: 0.01em;
  cursor: pointer;
  border: none;
  box-shadow: var(--shadow-brand);
  transition: var(--transition);
}
.save-btn:hover:not(:disabled) { filter: brightness(1.06); }
.save-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.loading-wrap { display: inline-flex; align-items: center; gap: 6px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

/* 安全列表 */
.account-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.acc-row {
  display: grid;
  grid-template-columns: 36px 1fr auto auto;
  gap: 12px;
  align-items: center;
  padding: 12px 12px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: var(--transition);
}
.acc-row:hover { background: var(--bg-subtle); }
.acc-row.danger:hover { background: var(--danger-soft); }
.acc-ic {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.acc-body { min-width: 0; }
.acc-title {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--ink-1);
}
.acc-row.danger .acc-title { color: var(--danger-ink); }
.acc-sub {
  font-size: 11.5px;
  color: var(--ink-3);
  margin-top: 2px;
}
.acc-flag {
  font-size: 11px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
}
.acc-flag.warn { background: var(--warning-soft); color: var(--warning-ink); }
.acc-arrow { color: var(--ink-4); transition: transform 180ms var(--ease); }
.acc-row:hover .acc-arrow { color: var(--brand); transform: translateX(2px); }
.acc-row.danger:hover .acc-arrow { color: var(--danger); }

/* 占位 */
.placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 60px 24px;
  text-align: center;
  gap: 12px;
}
.ph-ic {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  background: var(--brand-soft);
  color: var(--brand);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4px;
}
.placeholder h3 {
  font-family: 'Manrope', sans-serif;
  font-size: 17px;
  font-weight: 700;
  color: var(--ink-1);
}
.placeholder p {
  font-size: 13px;
  color: var(--ink-3);
  max-width: 380px;
  line-height: 1.7;
}

/* ─────────────────────────────────────────────
   LOGIN HISTORY
   ───────────────────────────────────────────── */
.login-card { padding: 22px 24px 18px; }
.link-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--brand);
  background: transparent;
  border: none;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--radius-xs);
  transition: var(--transition);
}
.link-btn:hover { background: var(--brand-soft); }

.login-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.login-table th {
  text-align: left;
  padding: 10px 16px;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--ink-3);
  letter-spacing: 0.06em;
  text-transform: uppercase;
  background: var(--bg-subtle);
  border-bottom: 1px solid var(--line);
}
.login-table th:first-child { border-top-left-radius: var(--radius-sm); }
.login-table th:last-child  { border-top-right-radius: var(--radius-sm); }
.login-table td {
  padding: 14px 16px;
  border-bottom: 1px solid var(--line-soft);
  color: var(--ink-2);
}
.login-table tr:last-child td { border-bottom: none; }
.login-table tr:hover td { background: rgba(0, 0, 0, 0.025); }
.login-table .mono { font-family: 'JetBrains Mono', monospace; font-size: 12.5px; color: var(--ink-1); }

.dev { display: inline-flex; align-items: center; gap: 8px; }
.dev-ic {
  width: 22px;
  height: 22px;
  border-radius: 6px;
  background: var(--bg-subtle);
  color: var(--ink-2);
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.status {
  display: inline-flex;
  align-items: center;
  padding: 3px 10px;
  font-size: 11px;
  font-weight: 700;
  border-radius: var(--radius-pill);
}
.status.current { background: var(--brand-soft); color: var(--brand-ink); }
.status.normal  { background: var(--success-soft); color: var(--success-ink); }

/* ─────────────────────────────────────────────
   Responsive
   ───────────────────────────────────────────── */
@media (max-width: 1100px) {
  .stats { grid-template-columns: repeat(2, 1fr); }
  .content { grid-template-columns: 1fr; }
}
@media (max-width: 760px) {
  .stats { grid-template-columns: 1fr; }
  .hero-grid { grid-template-columns: 1fr; text-align: center; }
  .hero-avatar { margin: 0 auto; width: 100px; height: 100px; }
  .avatar-letter { font-size: 40px; }
  .field { grid-template-columns: 1fr; gap: 6px; }
  .login-table { display: block; overflow-x: auto; }
}
</style>
