<template>
  <div class="register-page">
    <div class="bg-decoration">
      <div class="circle circle-1"></div>
      <div class="circle circle-2"></div>
    </div>

    <div class="register-card">
      <div class="card-header">
        <div class="logo">
          <div class="logo-icon">
            <BrandLogo :size="24" />
          </div>
          <span class="logo-text">{{ brandStore.systemName }}</span>
        </div>
        <h2>创建账号</h2>
        <p>加入 {{ brandStore.systemName }}，构建您的专属智能知识库</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        size="large"
        label-position="top"
        @submit.prevent
      >
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="登录名" prop="username">
              <el-input v-model="form.username" placeholder="3-20个字符" :prefix-icon="User" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="昵称" prop="nickname">
              <el-input v-model="form.nickname" placeholder="显示名称" :prefix-icon="UserFilled" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="6-20个字符"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="再次输入密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>

        <el-form-item label="邀请码" prop="inviteCode">
          <el-input v-model="form.inviteCode" placeholder="请输入邀请码" :prefix-icon="Ticket">
            <template #append>
              <el-button native-type="button" @click="openQr">获取邀请码</el-button>
            </template>
          </el-input>
        </el-form-item>
        <div class="org-tip">
          <el-icon size="11"><InfoFilled /></el-icon>
          注册需邀请码 · 没有邀请码？点「获取邀请码」扫码联系管理员
        </div>

        <el-button
          class="register-btn"
          type="primary"
          native-type="button"
          :loading="loading"
          @click="handleRegister"
        >
          {{ loading ? '注册中...' : '立即注册' }}
        </el-button>
      </el-form>

      <div class="card-footer">
        已有账号？
        <router-link to="/login" class="login-link">立即登录</router-link>
      </div>
    </div>

    <!-- 获取邀请码：弹窗显示管理员上传的二维码 -->
    <el-dialog v-model="qrVisible" title="获取邀请码" width="340px" align-center>
      <div class="qr-box">
        <img v-if="qrUrl" :src="qrUrl" alt="邀请二维码" class="qr-img" />
        <el-empty v-else description="管理员尚未配置二维码" :image-size="80" />
        <p class="qr-tip">扫码联系管理员获取邀请码</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { User, UserFilled, Lock, InfoFilled, Ticket } from '@element-plus/icons-vue'
import { userApi } from '@/api/user'
import { useBrandStore } from '@/stores/brand'
import BrandLogo from '@/components/BrandLogo.vue'

const router = useRouter()
const brandStore = useBrandStore()
const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive<{
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

// 获取邀请码：弹窗显示管理员上传的二维码
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

const validateConfirmPwd = (_rule: any, value: string, callback: any) => {
  if (value !== form.password) {
    callback(new Error('两次密码不一致'))
  } else {
    callback()
  }
}

const rules: FormRules = {
  username: [
    { required: true, message: '请输入登录名', trigger: 'blur' },
    { min: 3, max: 20, message: '登录名长度为3-20个字符', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为6-20个字符', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: validateConfirmPwd, trigger: 'blur' }
  ],
  inviteCode: [
    { required: true, message: '请填写邀请码', trigger: 'blur' }
  ]
}

const handleRegister = async () => {
  // 邀请码必填：未填（含纯空格）→ 弹框强提示并拦截，绝不提交、绝不跳转
  if (!form.inviteCode.trim()) {
    formRef.value?.validateField('inviteCode').catch(() => {})
    await ElMessageBox.alert('必须填写邀请码才能注册，请联系管理员获取邀请码。', '无法注册', {
      confirmButtonText: '我知道了',
      type: 'warning',
    }).catch(() => {})
    return
  }
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userApi.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname || undefined,
      inviteCode: form.inviteCode.trim(),
    })
    ElMessage.success('注册成功！请登录')
    router.push('/login')
  } catch {
    // 错误已在 request.ts 中处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #0f172a 0%, #1e1b4b 50%, #0f172a 100%);
  position: relative;
  overflow: hidden;
}

.bg-decoration { position: absolute; inset: 0; pointer-events: none; }
.circle { position: absolute; border-radius: 50%; }
.circle-1 { width: 500px; height: 500px; top: -150px; right: -100px; background: radial-gradient(circle, rgba(37,99,235,0.1), transparent); }
.circle-2 { width: 350px; height: 350px; bottom: -100px; left: -80px; background: radial-gradient(circle, rgba(0,0,0,0.1), transparent); }

.register-card {
  width: 520px;
  max-width: calc(100vw - 48px);
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 20px;
  padding: 40px;
  backdrop-filter: blur(20px);
  box-shadow: 0 25px 60px rgba(0, 0, 0, 0.5);
}

.card-header { text-align: center; margin-bottom: 32px; }

.logo {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
}

.logo-icon {
  width: 44px;
  height: 44px;
  background: linear-gradient(135deg, #2563eb, #0a0a0a);
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.logo-text {
  font-size: 22px;
  font-weight: 800;
  color: #fff;
  letter-spacing: 1px;
}

.card-header h2 {
  font-size: 22px;
  font-weight: 700;
  color: #f1f5f9;
  margin-bottom: 6px;
}

.card-header p { color: #64748b; font-size: 14px; }

:deep(.el-form-item__label) { color: #94a3b8 !important; font-size: 13px; }
:deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.06) !important;
  border: 1px solid rgba(255, 255, 255, 0.1) !important;
  box-shadow: none !important;
}
:deep(.el-input__inner) { color: #e2e8f0 !important; }

.register-btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  background: linear-gradient(135deg, #2563eb, #0a0a0a) !important;
  border: none !important;
  border-radius: 10px !important;
  margin-top: 8px;
}

/* 任务 7 · 部门/职位提示 */
.org-tip {
  display: flex;
  align-items: center;
  gap: 5px;
  margin: -6px 0 14px;
  font-size: 11.5px;
  color: #94a3b8;
  line-height: 1.5;
}

.card-footer {
  text-align: center;
  margin-top: 20px;
  color: #64748b;
  font-size: 14px;
}

.login-link {
  color: #60a5fa;
  text-decoration: none;
  font-weight: 500;
  margin-left: 4px;
}

.qr-box { display: flex; flex-direction: column; align-items: center; gap: 12px; }
.qr-img { width: 220px; height: 220px; object-fit: contain; border-radius: 8px; background: #fff; padding: 6px; }
.qr-tip { font-size: 13px; color: var(--ink-3, #94a3b8); }
</style>
