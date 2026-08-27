<script setup lang="ts">
/**
 * 知识库（集合）列表页 · 任务 15
 * 用户能看到所有可访问的知识库，点击进入详情管理库内文档
 */
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, FolderOpened, EditPen, Delete, Refresh, Document } from '@element-plus/icons-vue'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'
import { personaApi } from '@/api/persona'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const list = ref<KnowledgeCollection[]>([])
const loading = ref(false)
// 人格下拉（知识库可绑定专属人格）
const personas = ref<any[]>([])
const loadPersonas = async () => {
  try {
    const data: any = await personaApi.list()
    personas.value = (data?.data ?? data ?? []).filter((p: any) => p.enabled !== 0)
  } catch { /* 拿不到不致命，下拉为空即可 */ }
}
const editVisible = ref(false)
const editForm = ref<Partial<KnowledgeCollection>>({
  name: '',
  description: '',
  icon: 'FolderOpened',
  color: '#0a0a0a',
  visibility: 'public',
})
const isEdit = computed(() => editForm.value.id != null)

const ICON_CHOICES = [
  'FolderOpened', 'Files', 'Document', 'Reading',
  'Notebook', 'Collection', 'Box', 'Operation',
]
const COLOR_CHOICES = [
  '#0a0a0a', '#3D5AFE', '#0EA5E9', '#10B981',
  '#F59E0B', '#EF4444', '#EC4899', '#64748B',
]

const loadList = async () => {
  loading.value = true
  try {
    const data: any = await collectionApi.list()
    list.value = data?.data ?? data ?? []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message || ''))
  } finally { loading.value = false }
}

const openCreate = () => {
  editForm.value = {
    name: '', description: '',
    icon: 'FolderOpened', color: '#0a0a0a', visibility: 'public',
  }
  editVisible.value = true
}

const openEdit = (c: KnowledgeCollection) => {
  editForm.value = { ...c }
  editVisible.value = true
}

const save = async () => {
  if (!editForm.value.name?.trim()) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  try {
    // 清空人格 → 传 0，让后端把 persona_id 置回 null（默认）；选了则传 id
    const payload = { ...editForm.value, personaId: editForm.value.personaId || 0 }
    if (isEdit.value) {
      await collectionApi.update(editForm.value.id!, payload)
      ElMessage.success('已保存')
    } else {
      await collectionApi.create(payload)
      ElMessage.success('知识库创建成功')
    }
    editVisible.value = false
    await loadList()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.response?.data?.message || e?.message || ''))
  }
}

const removeOne = async (c: KnowledgeCollection) => {
  await ElMessageBox.confirm(
    `确认删除知识库「${c.name}」？\n库内 ${c.docCount} 个文档不会被删除，会变成「散文档」可重新归类。`,
    '提示',
    { type: 'warning' }
  )
  try {
    await collectionApi.delete(c.id)
    ElMessage.success('已删除')
    await loadList()
  } catch (e: any) {
    ElMessage.error('删除失败：' + (e?.response?.data?.message || e?.message || ''))
  }
}

const enterDetail = (c: KnowledgeCollection) => {
  router.push(`/collections/${c.id}`)
}

const canEdit = (c: KnowledgeCollection) => {
  if (userStore.isAdmin) return true
  return c.ownerUserId === userStore.userInfo?.id
}

onMounted(() => { loadList(); loadPersonas() })
</script>

<template>
  <div class="coll-page">
    <header class="page-header">
      <div class="title-block">
        <h2 class="page-title">我的知识库</h2>
        <p class="page-desc">把同主题的文档归到一个知识库 · 问答时可指定范围检索</p>
      </div>
      <div class="header-actions">
        <el-button :icon="Refresh" @click="loadList">刷新</el-button>
        <el-button type="primary" :icon="Plus" @click="openCreate">新建知识库</el-button>
      </div>
    </header>

    <div v-loading="loading" class="coll-grid">
      <article
        v-for="c in list"
        :key="c.id"
        class="coll-card"
        :style="{ '--accent': c.color || '#0a0a0a' }"
        @click="enterDetail(c)"
      >
        <div class="card-icon" :style="{ background: c.color + '18', color: c.color }">
          <el-icon size="24"><component :is="c.icon || 'FolderOpened'" /></el-icon>
        </div>
        <div class="card-body">
          <div class="card-name">{{ c.name }}</div>
          <div class="card-desc" :title="c.description">{{ c.description || '暂无描述' }}</div>
          <div class="card-stats">
            <span class="stat">
              <el-icon size="11"><Document /></el-icon>
              {{ c.docCount }} 文档
            </span>
            <span class="stat">{{ c.totalChunks }} 切片</span>
            <span class="stat vis" :class="'vis-' + c.visibility">
              {{ c.visibility === 'public' ? '公开' : c.visibility === 'scoped' ? '部门可见' : '仅自己' }}
            </span>
          </div>
        </div>
        <div v-if="canEdit(c)" class="card-actions" @click.stop>
          <button class="act-btn" title="编辑" @click="openEdit(c)">
            <el-icon size="13"><EditPen /></el-icon>
          </button>
          <button v-if="!c.isSystem" class="act-btn danger" title="删除" @click="removeOne(c)">
            <el-icon size="13"><Delete /></el-icon>
          </button>
        </div>
      </article>

      <!-- 新建占位卡 -->
      <button class="create-card" @click="openCreate">
        <el-icon size="28"><Plus /></el-icon>
        <span>新建知识库</span>
        <small>把相关文档归到一起，方便检索和授权</small>
      </button>
    </div>

    <!-- 创建/编辑弹窗 -->
    <el-dialog
      v-model="editVisible"
      :title="isEdit ? '编辑知识库' : '新建知识库'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" placeholder="如：HR 制度库 / 产品手册库" maxlength="40" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input
            v-model="editForm.description"
            type="textarea"
            :rows="2"
            placeholder="这个知识库主要放什么内容（可选）"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="图标">
          <div class="icon-picker">
            <button
              v-for="ic in ICON_CHOICES"
              :key="ic"
              class="icon-cell"
              :class="{ active: editForm.icon === ic }"
              @click.prevent="editForm.icon = ic"
            >
              <el-icon size="18"><component :is="ic" /></el-icon>
            </button>
          </div>
        </el-form-item>
        <el-form-item label="主题色">
          <div class="color-picker">
            <button
              v-for="cl in COLOR_CHOICES"
              :key="cl"
              class="color-cell"
              :class="{ active: editForm.color === cl }"
              :style="{ background: cl }"
              @click.prevent="editForm.color = cl"
            />
          </div>
        </el-form-item>
        <el-form-item label="可见性">
          <el-radio-group v-model="editForm.visibility">
            <el-radio value="public">公开（所有人可读）</el-radio>
            <el-radio value="scoped">部门可见（按 ACL）</el-radio>
            <el-radio value="private">仅自己</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="专属人格">
          <el-select
            v-model="editForm.personaId"
            clearable
            placeholder="（默认）跟随全局默认人格"
            style="width: 100%"
          >
            <el-option
              v-for="p in personas"
              :key="p.id"
              :label="p.name + (p.isDefault ? '（全局默认）' : '')"
              :value="p.id"
            />
          </el-select>
          <div class="form-hint">单选此知识库对话时生效；多选知识库时回退全局默认人格。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="save">{{ isEdit ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.coll-page { padding: 28px 32px; height: 100%; overflow-y: auto; background: transparent; }
.page-header { display: flex; justify-content: space-between; align-items: flex-end; margin-bottom: 22px; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 22px; font-weight: 700; color: var(--ink-1); margin-bottom: 4px; }
.page-desc { font-size: 13px; color: var(--ink-3); }
.header-actions { display: flex; gap: 10px; }

.coll-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 16px;
}
.coll-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 18px 18px 16px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 14px;
  cursor: pointer;
  transition: all 0.18s ease;
  overflow: hidden;
}
.coll-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 3px;
  background: var(--accent);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform 0.2s ease;
}
.coll-card:hover {
  border-color: var(--accent);
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(15,23,42,0.08);
}
.coll-card:hover::before { transform: scaleX(1); }
.card-icon {
  width: 48px; height: 48px;
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 12px;
}
.card-body { flex: 1; }
.card-name { font-size: 16px; font-weight: 700; color: var(--ink-1); margin-bottom: 4px; }
.card-desc {
  font-size: 12px; color: var(--ink-3);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 32px;
  margin-bottom: 10px;
}
.card-stats { display: flex; gap: 10px; flex-wrap: wrap; font-size: 11px; color: var(--ink-3); }
.stat { display: inline-flex; align-items: center; gap: 4px; }
.vis { padding: 1px 8px; border-radius: 999px; font-weight: 600; }
.vis-public { background: rgba(16,185,129,0.12); color: #047857; }
.vis-scoped { background: rgba(245,158,11,0.12); color: #b45309; }
.vis-private { background: rgba(100,116,139,0.12); color: #475569; }

.card-actions {
  position: absolute; top: 12px; right: 12px;
  display: flex; gap: 4px;
  opacity: 0; transition: opacity 0.15s;
}
.coll-card:hover .card-actions { opacity: 1; }
.act-btn {
  width: 26px; height: 26px;
  display: inline-flex; align-items: center; justify-content: center;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 6px;
  cursor: pointer;
  color: var(--ink-2);
}
.act-btn:hover { background: rgba(0,0,0,0.08); color: #0a0a0a; border-color: #0a0a0a; }
.act-btn.danger:hover { background: rgba(239,68,68,0.08); color: #ef4444; border-color: #ef4444; }

.create-card {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 6px;
  padding: 32px 18px;
  background: rgba(0,0,0,0.04);
  border: 2px dashed rgba(0,0,0,0.3);
  border-radius: 14px;
  color: #0a0a0a;
  cursor: pointer;
  min-height: 168px;
  font-weight: 600;
}
.create-card:hover { background: rgba(0,0,0,0.08); border-color: #0a0a0a; }
.create-card small { color: var(--ink-3); font-weight: 400; font-size: 11px; text-align: center; }

/* 弹窗内 */
.icon-picker, .color-picker { display: flex; flex-wrap: wrap; gap: 6px; }
.icon-cell {
  width: 36px; height: 36px;
  display: inline-flex; align-items: center; justify-content: center;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
  cursor: pointer;
  color: var(--ink-2);
}
.icon-cell:hover, .icon-cell.active { border-color: #0a0a0a; color: #0a0a0a; background: rgba(0,0,0,0.06); }
.color-cell {
  width: 28px; height: 28px;
  border: 2px solid transparent;
  border-radius: 50%;
  cursor: pointer;
  transition: transform 0.15s;
}
.color-cell:hover { transform: scale(1.1); }
.color-cell.active { border-color: var(--ink-1); transform: scale(1.1); box-shadow: 0 0 0 2px #fff inset; }
</style>
