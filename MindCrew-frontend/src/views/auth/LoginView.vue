<template>
  <div class="login-page">
    <div class="login-shell">
      <aside class="login-story" aria-label="产品介绍">
        <div class="story-brand">
          <span class="story-mark"><BrandLogo :size="24" color="#24252b" accent-color="#24252b" /></span>
          <span>{{ brandStore.systemName }}</span>
        </div>
        <div class="story-copy">
          <h2>让企业知识<br>真正参与工作</h2>
          <p>统一连接知识问答、数字员工、客户洞察与团队协作，把分散信息转化为可执行的下一步。</p>
        </div>
        <div class="story-foot">安全、可追溯的企业 AI 工作空间</div>
      </aside>

      <section class="form-pane">
        <!-- 登录视图 -->
        <template v-if="view === 'login'">
          <div class="brand-top">
            <img src="/zycoo-logo.png" alt="ZYCOO" class="brand-logo-img" />
          </div>

          <h1 class="welcome">Welcome Back</h1>
          <p class="signin-prompt">
            Sign in to continue to <a class="link" href="#">ZYCOO</a>
          </p>

          <el-form
            ref="loginFormRef"
            :model="loginForm"
            :rules="loginRules"
            size="large"
            @submit.prevent
          >
            <el-form-item prop="username">
              <el-input
                v-model="loginForm.username"
                placeholder="Username"
                :prefix-icon="User"
                clearable
              />
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                v-model="loginForm.password"
                type="password"
                placeholder="Password"
                :prefix-icon="Lock"
                show-password
                clearable
                @keyup.enter="handleLogin"
              />
            </el-form-item>

            <div class="row-between">
              <el-checkbox v-model="rememberMe">
                <span class="cb-label">Remember me</span>
              </el-checkbox>
              <a class="link" @click="openQr">Forgot password?</a>
            </div>

            <button
              type="button"
              class="cta"
              :class="{ loading }"
              :disabled="loading"
              @click="handleLogin"
            >
              <span>{{ loading ? 'Signing in…' : 'Sign In' }}</span>
            </button>
          </el-form>

          <p class="bottom-prompt">
            Don't have an account?
            <a class="link" @click="view = 'register'">Sign up</a>
          </p>
        </template>

        <!-- 注册视图 -->
        <template v-else>
          <div class="brand-top">
            <img src="/zycoo-logo.png" alt="ZYCOO" class="brand-logo-img" />
          </div>

          <h1 class="welcome">Create account</h1>
          <p class="signin-prompt">
            Join <a class="link" href="#">ZYCOO</a> with your invite code
          </p>

          <el-form
            ref="registerFormRef"
            :model="registerForm"
            :rules="registerRules"
            size="large"
            @submit.prevent
          >
            <el-form-item prop="username">
              <el-input
                v-model="registerForm.username"
                placeholder="Username (4-20 chars)"
                :prefix-icon="User"
                clearable
              />
            </el-form-item>
            <el-form-item prop="nickname">
              <el-input
                v-model="registerForm.nickname"
                placeholder="Nickname (optional)"
                :prefix-icon="Avatar"
                clearable
              />
            </el-form-item>
            <el-form-item prop="password">
              <el-input
                v-model="registerForm.password"
                type="password"
                placeholder="Password (6+ chars)"
                :prefix-icon="Lock"
                show-password
                clearable
              />
            </el-form-item>
            <el-form-item prop="confirmPassword">
              <el-input
                v-model="registerForm.confirmPassword"
                type="password"
                placeholder="Confirm password"
                :prefix-icon="Lock"
                show-password
                clearable
              />
            </el-form-item>
            <el-form-item prop="inviteCode">
              <el-input
                v-model="registerForm.inviteCode"
                placeholder="Invite code"
                :prefix-icon="Ticket"
                clearable
                @keyup.enter="handleRegister"
              >
                <template #append>
                  <el-button native-type="button" @click="openQr">Get code</el-button>
                </template>
              </el-input>
            </el-form-item>

            <button
              type="button"
              class="cta"
              :class="{ loading }"
              :disabled="loading"
              @click="handleRegister"
            >
              <span>{{ loading ? 'Creating…' : 'Sign up' }}</span>
            </button>
          </el-form>

          <p class="bottom-prompt">
            Already have an account?
            <a class="link" @click="view = 'login'">Sign in</a>
          </p>
        </template>
      </section>

      </div>

    <!-- 忘记密码 / 获取邀请码：弹二维码 -->
    <el-dialog v-model="qrVisible" title="扫码联系管理员" width="340px" align-center>
      <div class="qr-box">
        <img v-if="qrUrl" :src="qrUrl" alt="管理员二维码" class="qr-img" />
        <el-empty v-else description="管理员尚未配置二维码" :image-size="80" />
        <p class="qr-tip">扫码联系管理员获取邀请码或找回密码</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { User, Lock, Avatar, Ticket } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { userApi } from '@/api/user'
import { useBrandStore } from '@/stores/brand'
import BrandLogo from '@/components/BrandLogo.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const brandStore = useBrandStore()

type View = 'login' | 'register'
const view = ref<View>('login')
const loading = ref(false)
const rememberMe = ref(false)

const loginFormRef = ref<FormInstance>()
const registerFormRef = ref<FormInstance>()

const loginForm = reactive({ username: '', password: '' })
const registerForm = reactive<{
  username: string
  nickname: string
  password: string
  confirmPassword: string
  inviteCode: string
}>({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  inviteCode: '',
})

const qrVisible = ref(false)
const qrUrl = ref<string | null>(null)
const openQr = async () => {
  qrVisible.value = true
  try {
    const res: any = await userApi.getRegisterQr()
    qrUrl.value = (res?.data ?? res)?.qrUrl ?? null
  } catch {
    qrUrl.value = null
  }
}

const loginRules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

const registerRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 4, max: 20, message: '4-20位字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, message: '至少6位', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    {
      validator: (_rule: any, value: string, callback: Function) => {
        value !== registerForm.password ? callback(new Error('两次密码不一致')) : callback()
      },
      trigger: 'blur',
    },
  ],
  inviteCode: [{ required: true, message: '请填写邀请码', trigger: 'blur' }],
}

const handleLogin = async () => {
  const valid = await loginFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userStore.login({ username: loginForm.username, password: loginForm.password })
    ElMessage.success(`登录成功，欢迎使用 ${brandStore.systemName}！`)
    const redirect = (route.query.redirect as string) || '/'
    router.push(redirect)
  } catch {
    /* handled in request.ts */
  } finally {
    loading.value = false
  }
}

const handleRegister = async () => {
  if (!registerForm.inviteCode.trim()) {
    registerFormRef.value?.validateField('inviteCode').catch(() => {})
    await ElMessageBox.alert(
      '必须填写邀请码才能注册，请联系管理员获取邀请码。',
      '无法注册',
      { confirmButtonText: '我知道了', type: 'warning' }
    ).catch(() => {})
    return
  }
  const valid = await registerFormRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await userApi.register({
      username: registerForm.username,
      password: registerForm.password,
      nickname: registerForm.nickname || undefined,
      inviteCode: registerForm.inviteCode.trim(),
    })
    ElMessage.success('注册成功，请登录')
    loginForm.username = registerForm.username
    loginForm.password = registerForm.password
    view.value = 'login'
  } catch {
    /* 邀请码无效/已用尽等后端错误已由 request.ts 拦截器统一弹出提示 */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ─────────────────────────────────────────────
   Page · 整页浅灰底，居中卡片
   ───────────────────────────────────────────── */
.login-page {
  position: fixed;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #F5F7FB;
  overflow: hidden;
  font-family: 'Inter', -apple-system, 'SF Pro Text', 'Helvetica Neue', 'PingFang SC', sans-serif;
}

.login-shell {
  width: min(520px, calc(100vw - 32px));
  background: #FFFFFF;
  border-radius: 20px;
  overflow: hidden;
  box-shadow:
    0 30px 80px rgba(15, 23, 42, 0.10),
    0 6px 20px rgba(15, 23, 42, 0.04),
    0 0 0 1px rgba(15, 23, 42, 0.04);
}

/* ─────────────────────────────────────────────
   左半 · 表单区
   ───────────────────────────────────────────── */
.form-pane {
  padding: 56px 56px 48px;
  display: flex;
  flex-direction: column;
  background: #FFFFFF;
  overflow-y: auto;
}

.brand-top {
  display: flex;
  justify-content: center;
  margin-bottom: 36px;
}
.brand-logo-img {
  display: block;
  width: 124px;
  height: auto;
  object-fit: contain;
}

.welcome {
  font-size: 34px;
  font-weight: 700;
  color: #0F172A;
  margin: 0 0 10px;
  letter-spacing: -0.02em;
  line-height: 1.15;
}

.signin-prompt {
  font-size: 14px;
  color: #64748B;
  margin: 0 0 32px;
  line-height: 1.5;
}
.signin-prompt .link {
  color: #3B82F6;
  font-weight: 500;
  text-decoration: none;
  cursor: pointer;
}
.signin-prompt .link:hover { text-decoration: underline; }

/* ── Element Plus 表单样式覆盖 ── */
.form-pane :deep(.el-form-item) { margin-bottom: 16px; }
.form-pane :deep(.el-input__wrapper) {
  background: #FFFFFF !important;
  border-radius: 10px !important;
  box-shadow: 0 0 0 1px #E2E8F0 !important;
  height: 50px;
  padding: 0 14px !important;
  transition: box-shadow 160ms ease;
}
.form-pane :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px #CBD5E1 !important;
}
.form-pane :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 1.5px #3B82F6 !important;
}
.form-pane :deep(.el-input__inner) {
  font-size: 14.5px !important;
  color: #0F172A !important;
  height: 50px;
}
.form-pane :deep(.el-input__inner::placeholder) { color: #94A3B8 !important; }
.form-pane :deep(.el-input__prefix-inner .el-icon) {
  color: #94A3B8 !important;
  font-size: 16px;
}

/* Remember me + Forgot password 行 */
.row-between {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 4px 0 22px;
}
.cb-label { color: #475569; font-size: 13px; }
.form-pane :deep(.el-checkbox__input.is-checked .el-checkbox__inner) {
  background-color: #3B82F6 !important;
  border-color: #3B82F6 !important;
}
.form-pane :deep(.el-checkbox__label) { padding-left: 6px; }

.link {
  color: #3B82F6;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  text-decoration: none;
  transition: opacity 160ms ease;
}
.link:hover { opacity: 0.78; }

/* 主按钮 · 纯蓝实心 */
.cta {
  width: 100%;
  height: 50px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 22px;
  border: none;
  border-radius: 10px;
  background: #3B82F6;
  color: #FFFFFF;
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.01em;
  cursor: pointer;
  transition: background 160ms ease, transform 100ms ease;
}
.cta:hover:not(:disabled) { background: #2563EB; }
.cta:active:not(:disabled) { transform: scale(0.99); }
.cta:disabled { opacity: 0.7; cursor: not-allowed; }

/* 底部 Sign up 提示 */
.bottom-prompt {
  margin-top: 24px;
  text-align: center;
  font-size: 13px;
  color: #64748B;
}
.bottom-prompt .link { margin-left: 4px; }

/* ─────────────────────────────────────────────
   右半 · 3D ZYCOO 视觉
   ───────────────────────────────────────────── */
.image-pane {
  position: relative;
  background: #FFFFF0;
  overflow: hidden;
}
.hero-3d {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  object-position: center;
  filter: saturate(1.6);
}

/* 二维码弹窗 */
.qr-box { display: flex; flex-direction: column; align-items: center; gap: 12px; }
.qr-img { width: 220px; height: 220px; object-fit: contain; border-radius: 8px; background: #fff; padding: 6px; }
.qr-tip { font-size: 13px; color: #94A3B8; }

/* ─────────────────────────────────────────────
   响应式 · ≤880px 隐藏右半图
   ───────────────────────────────────────────── */
@media (max-width: 880px) {
  .login-shell {
    grid-template-columns: 1fr;
    height: auto;
    max-height: calc(100vh - 32px);
    width: calc(100vw - 32px);
  }
  .image-pane { display: none; }
  .form-pane { padding: 40px 28px 32px; }
  .welcome { font-size: 28px; }
}
</style>
