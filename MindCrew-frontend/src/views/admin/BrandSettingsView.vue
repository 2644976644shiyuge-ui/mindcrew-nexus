<template>
  <div class="brand-settings-page page-container">
    <div class="page-header">
      <div>
        <h2 class="page-title">品牌设置</h2>
        <p class="page-desc">将系统名称和 Logo 配置为公司品牌，登录页、侧边栏与问答助手会同步展示。</p>
      </div>
      <el-button type="primary" :loading="saving" @click="saveSettings">保存设置</el-button>
    </div>

    <div v-loading="loading" class="settings-grid">
      <el-card class="settings-card" shadow="never">
        <template #header>
          <div class="card-title">基础信息</div>
        </template>

        <el-form label-position="top">
          <el-form-item label="系统名称 / 公司名称">
            <el-input
              v-model="form.systemName"
              maxlength="40"
              show-word-limit
              placeholder="请输入公司名称"
            />
          </el-form-item>

          <el-form-item label="系统 Logo">
            <div class="logo-row">
              <div class="logo-preview">
                <img v-if="form.logoUrl" :src="form.logoUrl" alt="Logo 预览" />
                <BrandLogo v-else :size="44" />
              </div>
              <div class="logo-actions">
                <el-upload
                  :show-file-list="false"
                  :http-request="uploadLogo"
                  accept="image/*"
                >
                  <el-button :loading="uploading">上传 Logo</el-button>
                </el-upload>
                <el-button text type="danger" :disabled="!form.logoUrl" @click="form.logoUrl = null">
                  使用默认 Logo
                </el-button>
                <div class="upload-tip">建议上传正方形 PNG/SVG/JPG，文件不超过 2MB。</div>
              </div>
            </div>
          </el-form-item>
        </el-form>
      </el-card>

      <el-card class="settings-card preview-card" shadow="never">
        <template #header>
          <div class="card-title">展示预览</div>
        </template>

        <div class="preview-sidebar">
          <div class="preview-logo">
            <img v-if="form.logoUrl" :src="form.logoUrl" alt="" />
            <BrandLogo v-else :size="24" />
            <span>{{ previewName }}</span>
          </div>
          <div class="preview-nav active">智能问答</div>
          <div class="preview-nav">知识库</div>
        </div>

        <div class="preview-login">
          <div class="preview-brand">
            <img v-if="form.logoUrl" :src="form.logoUrl" alt="" />
            <BrandLogo v-else :size="28" />
            <span>{{ previewName }}</span>
          </div>
          <div class="preview-title">{{ previewName }} 智能问答</div>
          <div class="preview-sub">企业知识随取随用</div>
        </div>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { brandApi } from '@/api/brand'
import { useBrandStore } from '@/stores/brand'
import BrandLogo from '@/components/BrandLogo.vue'

const brandStore = useBrandStore()
const loading = ref(false)
const saving = ref(false)
const uploading = ref(false)

const form = reactive<{
  systemName: string
  logoUrl: string | null
}>({
  systemName: 'ZYCOO Nexus',
  logoUrl: null,
})

const previewName = computed(() => form.systemName.trim() || '公司名称')

const loadSettings = async () => {
  loading.value = true
  try {
    const data = await brandApi.getSettings()
    form.systemName = data.systemName || 'ZYCOO Nexus'
    form.logoUrl = data.logoUrl || null
  } finally {
    loading.value = false
  }
}

const saveSettings = async () => {
  if (!form.systemName.trim()) {
    ElMessage.warning('请填写系统名称')
    return
  }
  saving.value = true
  try {
    const data = await brandApi.updateSettings({
      systemName: form.systemName.trim(),
      logoUrl: form.logoUrl,
    })
    form.systemName = data.systemName
    form.logoUrl = data.logoUrl
    brandStore.applyBrand(data)
    ElMessage.success('品牌设置已保存')
  } finally {
    saving.value = false
  }
}

const uploadLogo = async (opt: any) => {
  uploading.value = true
  try {
    const url = await brandApi.uploadLogo(opt.file)
    form.logoUrl = url
    ElMessage.success('Logo 上传成功，保存后生效')
  } finally {
    uploading.value = false
  }
}

onMounted(loadSettings)
</script>

<style scoped>
.brand-settings-page {
  padding: 24px;
}
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}
.page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  color: var(--ink-1);
}
.page-desc {
  margin: 8px 0 0;
  color: var(--ink-3);
  font-size: 13px;
}
.settings-grid {
  display: grid;
  grid-template-columns: minmax(360px, 1fr) minmax(360px, 1fr);
  gap: 18px;
}
.settings-card {
  border: 1px solid var(--line);
  border-radius: 14px;
}
.card-title {
  font-weight: 700;
  color: var(--ink-1);
}
.logo-row {
  display: flex;
  gap: 18px;
  align-items: center;
}
.logo-preview {
  width: 76px;
  height: 76px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 1px solid var(--line);
  border-radius: 16px;
  background: var(--bg-card);
}
.logo-preview img {
  width: 54px;
  height: 54px;
  object-fit: contain;
}
.logo-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
}
.upload-tip {
  font-size: 12px;
  color: var(--ink-4);
}
.preview-card :deep(.el-card__body) {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 18px;
}
.preview-sidebar {
  min-height: 230px;
  padding: 16px;
  border-radius: 16px;
  background: var(--sidebar-bg);
  border: 1px solid var(--line);
}
.preview-logo,
.preview-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 800;
  color: var(--ink-1);
}
.preview-logo img,
.preview-brand img {
  width: 28px;
  height: 28px;
  object-fit: contain;
  border-radius: 8px;
}
.preview-nav {
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  color: var(--ink-3);
  font-size: 13px;
}
.preview-nav.active {
  color: var(--brand);
  background: var(--brand-soft);
  font-weight: 700;
}
.preview-login {
  min-height: 230px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
  padding: 24px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(0, 0, 0, 0.08), rgba(167, 139, 250, 0.12));
  border: 1px solid var(--line);
}
.preview-title {
  font-size: 24px;
  font-weight: 800;
  color: var(--ink-1);
}
.preview-sub {
  color: var(--ink-3);
}
@media (max-width: 900px) {
  .settings-grid,
  .preview-card :deep(.el-card__body) {
    grid-template-columns: 1fr;
  }
}
</style>
