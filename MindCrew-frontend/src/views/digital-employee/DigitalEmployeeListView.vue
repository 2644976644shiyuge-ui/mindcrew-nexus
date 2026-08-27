<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MagicStick, Search, View } from '@element-plus/icons-vue'
import { digitalEmployeeApi, type DigitalEmployeeCard } from '@/api/digitalEmployee'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const loading = ref(false)
const keyword = ref('')
const list = ref<DigitalEmployeeCard[]>([])

const isAdmin = computed(() => userStore.isAdmin)

async function load() {
  loading.value = true
  try {
    list.value = await digitalEmployeeApi.listMine(keyword.value || undefined)
    if (route.query.capability === 'ppt') {
      const pptEmployee = list.value.find(item => item.primaryScenario === 'ppt_authoring')
      if (pptEmployee) {
        await router.replace({
          name: 'DigitalEmployeeChat',
          params: { id: pptEmployee.id },
        })
      } else {
        ElMessage.warning('当前账号暂无可用的 PPT 数字员工')
      }
    }
  } finally {
    loading.value = false
  }
}

function openChat(item: DigitalEmployeeCard) {
  router.push({ name: 'DigitalEmployeeChat', params: { id: item.id } })
}

function goAdmin() {
  router.push({ name: 'DigitalEmployeeAdmin' })
}

function goCreate() {
  router.push({ name: 'DigitalEmployeeCreate' })
}

onMounted(load)
</script>

<template>
  <div class="de-page">
    <header class="de-header">
      <div>
        <h1>选择数字员工</h1>
        <p class="sub">已授权给你的智能同事，点击进入对话</p>
      </div>
      <div class="actions">
        <el-button @click="$router.push('/digital-employees')">
          <el-icon><View /></el-icon>&nbsp;3D 办公室
        </el-button>
        <el-button v-if="isAdmin" @click="goAdmin">控制台</el-button>
        <el-button v-if="isAdmin" type="primary" @click="goCreate">创建数字员工</el-button>
      </div>
    </header>

    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索数字员工…"
        clearable
        :prefix-icon="Search"
        @keyup.enter="load"
        @clear="load"
      />
      <el-button type="primary" plain @click="load">搜索</el-button>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />
    <el-empty v-else-if="!list.length" description="暂无授权的数字员工" />

    <div v-else class="card-grid">
      <article
        v-for="item in list"
        :key="item.id"
        class="de-card"
        @click="openChat(item)"
      >
        <div class="ws-scene">
          <span class="ws-orb"><el-icon :size="24"><MagicStick /></el-icon></span>
          <span class="ws-word">{{ item.primaryScenarioLabel }}</span>
        </div>
        <div class="card-top">
          <div class="avatar">{{ item.avatar || 'AI' }}</div>
          <div class="meta">
            <div class="name-row">
              <span class="name">{{ item.name }}</span>
              <span class="status" :class="{ on: item.status === 'published' }">
                <i />{{ item.runtimeLabel || '运行中' }}
              </span>
            </div>
            <div class="tag">企业数字员工 · {{ item.primaryScenarioLabel }}</div>
          </div>
        </div>
        <p class="summary">{{ item.summary || '暂无简介' }}</p>
        <div class="stats">
          <span>{{ item.sessionCount ?? 0 }} sessions</span>
          <span>{{ item.tokenDisplay ?? '-' }}</span>
          <span v-if="item.primaryScenario === 'ppt_authoring'" class="capability-action">生成 PPT →</span>
          <span v-else>{{ item.activeDisplay ?? '-' }}</span>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.de-page {
  min-height: calc(100vh - 80px);
  padding: 24px 32px 48px;
  background: transparent;   /* 透出全局呼吸光斑背景 */
  color: #0A0A0A;
}
.de-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 20px;
  gap: 16px;
  flex-wrap: wrap;
}
.de-header h1 {
  margin: 0;
  font-size: 22px;
  font-weight: 600;
}
.sub {
  margin: 6px 0 0;
  color: var(--ink-2);
  font-size: 13px;
}
.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}
.toolbar {
  display: flex;
  gap: 12px;
  max-width: 480px;
  margin-bottom: 28px;
}
.card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
}
.de-card {
  background: rgba(255,255,255,.85);
  border: 1px solid #E8E8ED;
  border-radius: 18px;
  padding: 16px;
  cursor: pointer;
  transition: border-color 0.2s, transform 0.2s cubic-bezier(.22,1,.36,1), box-shadow 0.2s;
  box-shadow: 0 1px 3px rgba(0,0,0,.04);
}
.de-card:hover {
  border-color: rgba(0,113,227,.4);
  transform: translateY(-3px);
  box-shadow: 0 10px 30px rgba(0,113,227,.10);
}
.card-top {
  display: flex;
  gap: 12px;
}
.avatar {
  width: 44px;
  height: 44px;
  border-radius: 10px;
  background: #F4F4F5;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
}
.name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.name {
  font-weight: 600;
  font-size: 15px;
}
.status {
  font-size: 11px;
  color: #71717A;
  display: flex;
  align-items: center;
  gap: 4px;
}
.status i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #D4D4D8;
}
.status.on i {
  background: #16A34A;
}
.tag {
  font-size: 12px;
  color: #71717A;
  margin-top: 4px;
}
.summary {
  font-size: 13px;
  color: #52525B;
  margin: 12px 0;
  line-height: 1.45;
  min-height: 2.9em;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.stats {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #0A0A0A;
  font-variant-numeric: tabular-nums;
}
/* 伪 3D 工位场景（参考图精神 · 每 agent 一个专属工位） */
.ws-scene {
  height: 84px;
  margin-bottom: 12px;
  border-radius: 10px;
  background: linear-gradient(180deg, #FAFAFA 0%, #F4F4F5 100%);
  border: 0.5px solid #E4E4E7;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  padding: 6px 12px 4px;
  overflow: hidden;
}
.ws-scene svg {
  display: block;
  max-height: 74px;
}
</style>
