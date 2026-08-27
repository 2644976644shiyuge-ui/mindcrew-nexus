<template>
  <div class="org-page">
    <header class="page-header">
      <div>
        <div class="title-row">
          <h2 class="page-title">组织 & 职位</h2>
          <span class="title-tag">职位独立 KB</span>
        </div>
        <p class="page-desc">
          部门是组织结构（树形），职位是业务角色（HR 经理 / Java 工程师 ...），与系统角色（admin/auditor/user）独立。
          知识库可按职位授权可读 / 可写 / 可管理；点 <el-icon style="vertical-align:-2px"><Key /></el-icon> <strong>功能权限</strong> 可设定部门/职位的<strong>可用菜单功能</strong>（职位优先、可继承部门，admin 永远全开）。
        </p>
      </div>
    </header>

    <div class="org-main">
      <!-- 左侧 部门树 -->
      <section class="left-pane">
        <div class="pane-header">
          <h3>部门</h3>
          <button class="btn-mini" @click="openDeptDialog(null)">
            <el-icon size="13"><Plus /></el-icon> 新增
          </button>
        </div>
        <el-tree
          :data="deptTree"
          node-key="id"
          :props="{ label: 'name', children: 'children' }"
          highlight-current
          @node-click="onSelectDept"
          v-loading="loadingDept"
          class="dept-tree"
        >
          <template #default="{ node, data }">
            <div class="tree-node">
              <span>{{ data.name }}</span>
              <div class="tree-actions">
                <button class="icon-btn-sm" @click.stop="openPermDialog('department', data)" title="功能权限">
                  <el-icon size="12"><Key /></el-icon>
                </button>
                <button class="icon-btn-sm" @click.stop="openDeptDialog(data)" title="编辑">
                  <el-icon size="12"><Edit /></el-icon>
                </button>
                <button class="icon-btn-sm danger" @click.stop="delDept(data)" title="删除">
                  <el-icon size="12"><Delete /></el-icon>
                </button>
              </div>
            </div>
          </template>
        </el-tree>
      </section>

      <!-- 右侧 职位列表 -->
      <section class="right-pane">
        <div class="pane-header">
          <h3>
            {{ selectedDept ? selectedDept.name + ' · 职位' : '全部职位' }}
          </h3>
          <button class="btn-primary-mini" @click="openPosDialog(null)">
            <el-icon size="13"><Plus /></el-icon> 新增职位
          </button>
        </div>
        <div v-loading="loadingPos" class="pos-grid">
          <article v-for="p in positions" :key="p.id" class="pos-card">
            <header>
              <span class="pos-code">{{ p.code }}</span>
              <span class="pos-level">L{{ p.level }}</span>
            </header>
            <h4>{{ p.name }}</h4>
            <p>{{ p.description || '（无描述）' }}</p>
            <footer>
              <button class="icon-btn" @click="openPermDialog('position', p)" title="功能权限">
                <el-icon><Key /></el-icon>
              </button>
              <button class="icon-btn" @click="openPosDialog(p)" title="编辑">
                <el-icon><Edit /></el-icon>
              </button>
              <button class="icon-btn danger" @click="delPos(p)" title="删除">
                <el-icon><Delete /></el-icon>
              </button>
            </footer>
          </article>
          <div v-if="!loadingPos && positions.length === 0" class="empty">暂无职位</div>
        </div>
      </section>
    </div>

    <!-- 部门编辑对话框 -->
    <el-dialog v-model="deptDialogVisible" :title="deptForm.id ? '编辑部门' : '新建部门'" width="480px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="deptForm.name" placeholder="如 技术中心" maxlength="60" />
        </el-form-item>
        <el-form-item label="上级">
          <el-select v-model="deptForm.parentId" placeholder="选一级则留空" clearable style="width:100%">
            <el-option v-for="d in deptList" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="deptForm.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="deptForm.sortOrder" :min="0" :max="9999" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deptDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveDept" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 职位编辑对话框 -->
    <el-dialog v-model="posDialogVisible" :title="posForm.id ? '编辑职位' : '新建职位'" width="520px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="posForm.name" placeholder="如 Java 工程师" maxlength="60" />
        </el-form-item>
        <el-form-item label="Code" required>
          <el-input v-model="posForm.code" placeholder="英文唯一标识，如 java_dev" maxlength="40" :disabled="!!posForm.id" />
          <div style="font-size:11px;color:#94a3b8;margin-top:2px">code 创建后不可改 · 用于 ACL 关联</div>
        </el-form-item>
        <el-form-item label="所属部门">
          <el-select v-model="posForm.departmentId" placeholder="可跨部门 · 留空" clearable style="width:100%">
            <el-option v-for="d in deptList" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="职级">
          <el-input-number v-model="posForm.level" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="posForm.description" type="textarea" :rows="2" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="posDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="savePos" :loading="saving">保存</el-button>
      </template>
    </el-dialog>

    <!-- 功能权限配置对话框 · #3 -->
    <el-dialog
      v-model="permDialogVisible"
      :title="`功能权限 · ${permTarget?.name || ''}`"
      width="640px"
      :close-on-click-modal="false"
    >
      <div class="perm-head">
        <el-radio-group v-model="permMode" size="small">
          <el-radio-button :value="false">{{ permKind === 'position' ? '继承部门/默认' : '继承默认' }}</el-radio-button>
          <el-radio-button :value="true">自定义可用功能</el-radio-button>
        </el-radio-group>
        <span class="perm-hint">
          {{ permKind === 'position'
            ? '职位优先级最高；选「继承」则跟随所属部门，部门也没配则用系统默认。'
            : '部门为其下职位提供默认权限；职位可单独覆盖。选「继承」则用系统默认。' }}
        </span>
      </div>

      <div v-show="permMode" class="perm-body" v-loading="permLoading">
        <div class="perm-toolbar">
          <el-button text size="small" @click="permSelectAll">全选</el-button>
          <el-button text size="small" @click="permChecked = []">清空</el-button>
          <span class="perm-count">已选 {{ permChecked.length }} 项</span>
        </div>
        <div v-for="g in featureGroups" :key="g.group" class="perm-group">
          <div class="perm-group-title">{{ g.group }}</div>
          <el-checkbox-group v-model="permChecked" class="perm-checks">
            <el-checkbox
              v-for="f in g.items"
              :key="f.key"
              :value="f.key"
              :disabled="ALWAYS_ON.includes(f.key)"
            >
              {{ f.label }}
              <span v-if="ALWAYS_ON.includes(f.key)" class="perm-locked">· 基础</span>
            </el-checkbox>
          </el-checkbox-group>
        </div>
      </div>

      <template #footer>
        <el-button @click="permDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="permSaving" @click="savePerm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete, Key } from '@element-plus/icons-vue'
import { departmentApi, positionApi, type Department, type Position, type DeptNode, type FeatureDef } from '@/api/orgAcl'

const loadingDept = ref(false)
const loadingPos  = ref(false)
const saving      = ref(false)

const deptTree   = ref<DeptNode[]>([])
const deptList   = ref<Department[]>([])
const positions  = ref<Position[]>([])
const selectedDept = ref<Department | null>(null)

async function loadDepts() {
  loadingDept.value = true
  try {
    const [tree, list]: any = await Promise.all([departmentApi.tree(), departmentApi.list()])
    deptTree.value = tree?.data ?? tree ?? []
    deptList.value = list?.data ?? list ?? []
  } finally { loadingDept.value = false }
}

async function loadPositions(deptId?: number) {
  loadingPos.value = true
  try {
    const res: any = await positionApi.list(deptId)
    positions.value = res?.data ?? res ?? []
  } finally { loadingPos.value = false }
}

function onSelectDept(d: Department) {
  selectedDept.value = d
  loadPositions(d.id)
}

// ─── #3 · 功能权限配置 ───────────────────────────────
const ALWAYS_ON = ['chat', 'profile']    // 基础功能：恒选中、不可取消
const features = ref<FeatureDef[]>([])
const featureGroups = computed(() => {
  const groups: { group: string; items: FeatureDef[] }[] = []
  for (const f of features.value) {
    let g = groups.find(x => x.group === f.group)
    if (!g) { g = { group: f.group, items: [] }; groups.push(g) }
    g.items.push(f)
  }
  return groups
})

async function loadFeatures() {
  if (features.value.length) return
  const res: any = await positionApi.features()
  features.value = res?.data ?? res ?? []
}

const permDialogVisible = ref(false)
const permLoading = ref(false)
const permSaving = ref(false)
const permKind = ref<'department' | 'position'>('position')
const permTarget = ref<Department | Position | null>(null)
const permMode = ref(false)               // false=继承，true=自定义
const permChecked = ref<string[]>([])

async function openPermDialog(kind: 'department' | 'position', target: Department | Position) {
  permKind.value = kind
  permTarget.value = target
  permDialogVisible.value = true
  permLoading.value = true
  try {
    await loadFeatures()
    const raw = (target as any).permissions as string | null | undefined
    if (raw == null || raw === '') {
      permMode.value = false              // 继承
      permChecked.value = [...ALWAYS_ON]
    } else {
      permMode.value = true               // 自定义
      let arr: string[] = []
      try { arr = JSON.parse(raw) } catch { arr = [] }
      permChecked.value = Array.from(new Set([...ALWAYS_ON, ...arr]))
    }
  } finally { permLoading.value = false }
}

function permSelectAll() {
  permChecked.value = features.value.map(f => f.key)
}

async function savePerm() {
  if (!permTarget.value) return
  permSaving.value = true
  try {
    const id = (permTarget.value as any).id as number
    // 继承 → 传 null 清空；自定义 → 传选中（含基础项，后端也会兜底并入）
    const payload = permMode.value
      ? Array.from(new Set([...ALWAYS_ON, ...permChecked.value]))
      : null
    if (permKind.value === 'department') {
      await departmentApi.updatePermissions(id, payload)
    } else {
      await positionApi.updatePermissions(id, payload)
    }
    ElMessage.success('功能权限已更新')
    permDialogVisible.value = false
    // 刷新本地数据，保证再次打开看到最新
    await loadDepts()
    await loadPositions(selectedDept.value?.id)
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { permSaving.value = false }
}

onMounted(async () => {
  await loadDepts()
  await loadPositions()
})

// ── 部门 CRUD ──
const deptDialogVisible = ref(false)
const deptForm = reactive<Partial<Department>>({})

function openDeptDialog(d: Department | null) {
  if (d) {
    Object.assign(deptForm, d)
  } else {
    Object.keys(deptForm).forEach(k => (deptForm as any)[k] = undefined)
    deptForm.sortOrder = 100
    deptForm.enabled = 1
  }
  deptDialogVisible.value = true
}

async function saveDept() {
  if (!deptForm.name?.trim()) return ElMessage.warning('请填部门名')
  saving.value = true
  try {
    if (deptForm.id) await departmentApi.update(deptForm.id, deptForm)
    else             await departmentApi.create(deptForm)
    ElMessage.success('已保存')
    deptDialogVisible.value = false
    await loadDepts()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { saving.value = false }
}

async function delDept(d: Department) {
  try {
    await ElMessageBox.confirm(`确认删除部门「${d.name}」？`, '警告', { type: 'warning' })
    await departmentApi.delete(d.id)
    ElMessage.success('已删除')
    await loadDepts()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '删除失败')
  }
}

// ── 职位 CRUD ──
const posDialogVisible = ref(false)
const posForm = reactive<Partial<Position>>({})

function openPosDialog(p: Position | null) {
  if (p) {
    Object.assign(posForm, p)
  } else {
    Object.keys(posForm).forEach(k => (posForm as any)[k] = undefined)
    posForm.level = 1
    posForm.sortOrder = 100
    posForm.enabled = 1
    if (selectedDept.value) posForm.departmentId = selectedDept.value.id
  }
  posDialogVisible.value = true
}

async function savePos() {
  if (!posForm.name?.trim()) return ElMessage.warning('请填职位名')
  if (!posForm.code?.trim()) return ElMessage.warning('请填 code')
  saving.value = true
  try {
    if (posForm.id) await positionApi.update(posForm.id, posForm)
    else            await positionApi.create(posForm)
    ElMessage.success('已保存')
    posDialogVisible.value = false
    await loadPositions(selectedDept.value?.id)
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { saving.value = false }
}

async function delPos(p: Position) {
  try {
    await ElMessageBox.confirm(`确认删除职位「${p.name}」？已绑定此职位的用户 / KB 授权将失效。`, '警告', { type: 'warning' })
    await positionApi.delete(p.id)
    ElMessage.success('已删除')
    await loadPositions(selectedDept.value?.id)
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error(e?.message || '删除失败')
  }
}
</script>

<style scoped>
.org-page { padding: 28px 32px 48px; height: 100%; overflow-y: auto; background: var(--bg-page); }
.page-header { margin-bottom: 24px; }
.title-row { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.page-title { font-size: 24px; font-weight: 700; color: var(--ink-1); }
.title-tag { font-size: 11px; font-weight: 600; padding: 3px 10px; border-radius: 999px;
  background: linear-gradient(135deg, #e4e4e7, #d4d4d8); color: #0a0a0a; }
.page-desc { font-size: 13.5px; color: var(--ink-3); max-width: 820px; line-height: 1.65; }

.org-main { display: grid; grid-template-columns: 280px 1fr; gap: 20px; }
.left-pane, .right-pane { background: var(--bg-surface); border: 1px solid var(--line);
  border-radius: 12px; padding: 16px; }
.pane-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.pane-header h3 { font-size: 14px; font-weight: 700; color: var(--ink-1); }
.btn-mini, .btn-primary-mini {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 5px 10px; font-size: 12px; border-radius: 6px;
  border: 1px solid var(--line); background: var(--bg-elevated);
  color: var(--ink-2); cursor: pointer;
}
.btn-mini:hover { background: var(--bg-hover); }
.btn-primary-mini { background: var(--brand, #3D5AFE); color: #fff; border-color: var(--brand, #3D5AFE); }

.dept-tree { font-size: 13px; }
.tree-node { display: flex; justify-content: space-between; align-items: center; flex: 1; gap: 8px; padding-right: 4px; }
.tree-actions { display: none; gap: 2px; }
.tree-node:hover .tree-actions { display: flex; }
.icon-btn-sm {
  width: 22px; height: 22px; border-radius: 4px;
  background: transparent; border: none; color: var(--ink-3); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
}
.icon-btn-sm:hover { background: var(--bg-hover); color: var(--ink-1); }
.icon-btn-sm.danger:hover { background: rgba(239,68,68,.1); color: #ef4444; }

.pos-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 14px; min-height: 200px; }
.pos-card {
  background: var(--bg-elevated); border: 1px solid var(--line);
  border-radius: 10px; padding: 14px 16px;
  display: flex; flex-direction: column; gap: 8px;
  transition: box-shadow 0.15s;
}
.pos-card:hover { box-shadow: var(--shadow-md); }
.pos-card header { display: flex; justify-content: space-between; align-items: center; }
.pos-code { font-family: 'JetBrains Mono', monospace; font-size: 11px; color: var(--ink-4);
  background: var(--bg-subtle); padding: 1px 7px; border-radius: 4px; }
.pos-level { font-size: 11px; font-weight: 700; color: #6366f1; }
.pos-card h4 { font-size: 14.5px; font-weight: 700; color: var(--ink-1); }
.pos-card p { font-size: 12px; color: var(--ink-3); line-height: 1.55; min-height: 36px; }
.pos-card footer { display: flex; gap: 4px; justify-content: flex-end; padding-top: 6px; border-top: 1px dashed var(--line); }
.icon-btn {
  width: 28px; height: 28px; border-radius: 6px;
  background: transparent; border: 1px solid transparent; color: var(--ink-3);
  display: inline-flex; align-items: center; justify-content: center; cursor: pointer;
  transition: all 0.15s;
}
.icon-btn:hover { background: var(--bg-hover); border-color: var(--line); color: var(--ink-1); }
.icon-btn.danger:hover { background: rgba(239,68,68,.1); border-color: #ef4444; color: #ef4444; }

.empty { grid-column: 1 / -1; padding: 60px 20px; text-align: center; color: var(--ink-4); font-size: 13px; }

/* ── #3 功能权限配置弹窗 ── */
.perm-head { display: flex; flex-direction: column; gap: 8px; margin-bottom: 14px; }
.perm-hint { font-size: 12px; color: var(--ink-3); line-height: 1.5; }
.perm-body { border-top: 1px solid var(--line); padding-top: 12px; }
.perm-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.perm-count { margin-left: auto; font-size: 12px; color: var(--ink-3); }
.perm-group { margin-bottom: 14px; }
.perm-group-title { font-size: 12px; font-weight: 700; color: var(--ink-2); margin-bottom: 8px; }
.perm-checks { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px 10px; }
.perm-locked { font-size: 11px; color: var(--ink-4); }
</style>
