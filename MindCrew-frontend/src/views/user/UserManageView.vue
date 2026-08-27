<template>
  <div class="user-manage-page">
    <!-- 搜索栏 -->
    <el-card class="search-card">
      <div class="filter-bar">
        <el-input
          v-model="keyword"
          placeholder="搜索登录名、昵称"
          :prefix-icon="Search"
          clearable
          style="width: 240px"
          @change="reloadFromFirstPage"
        />
        <el-select v-model="roleFilter" placeholder="全部角色" clearable style="width: 130px" @change="reloadFromFirstPage">
          <el-option label="管理员" value="admin" />
          <el-option label="普通用户" value="user" />
        </el-select>
        <el-select v-model="sourceFilter" placeholder="全部来源" clearable style="width: 140px" @change="reloadFromFirstPage">
          <el-option label="外部注册" value="register" />
          <el-option label="管理员创建" value="admin" />
        </el-select>
        <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 120px" @change="reloadFromFirstPage">
          <el-option label="启用" :value="1" />
          <el-option label="禁用" :value="0" />
        </el-select>
        <el-button type="primary" :icon="Refresh" @click="loadUsers">刷新</el-button>
        <el-button type="primary" :icon="Plus" plain @click="openCreateDialog">添加用户</el-button>
        <el-button :icon="Ticket" plain @click="openInviteDialog">邀请码管理</el-button>
        <div class="filter-spacer"></div>
        <el-button type="success" :icon="Histogram" @click="overviewVisible = true">全员花费总览</el-button>
        <el-statistic title="用户总数" :value="total" />
      </div>
    </el-card>

    <!-- 用户列表 -->
    <el-card class="table-card">
      <el-table
        :data="users"
        v-loading="loading"
        stripe
        row-key="id"
        style="width: 100%"
      >
        <el-table-column label="用户" min-width="180">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="36" :src="row.avatar">
                {{ row.nickname?.charAt(0) }}
              </el-avatar>
              <div class="user-cell-info">
                <div class="cell-name">{{ row.nickname || row.username }}</div>
                <div class="cell-username">@{{ row.username }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="phone" label="手机号" width="140">
          <template #default="{ row }">
            {{ row.phone || '-' }}
          </template>
        </el-table-column>

        <el-table-column label="角色" width="100">
          <template #default="{ row }">
            <el-tag :type="getRoleType(row.role)">{{ getRoleLabel(row.role) }}</el-tag>
          </template>
        </el-table-column>

        <el-table-column label="来源" width="110">
          <template #default="{ row }">
            <el-tag :type="isRegister(row) ? 'warning' : 'info'" effect="plain">
              {{ isRegister(row) ? '外部注册' : '管理员创建' }}
            </el-tag>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-switch
              :model-value="row.status === 1"
              :disabled="row.role === 'admin'"
              @change="(val: boolean) => toggleStatus(row, val)"
            />
          </template>
        </el-table-column>

        <el-table-column prop="lastLogin" label="最后登录" width="160">
          <template #default="{ row }">
            {{ row.lastLogin ? formatDate(row.lastLogin) : '未登录' }}
          </template>
        </el-table-column>

        <el-table-column prop="createTime" label="注册时间" width="160">
          <template #default="{ row }">
            {{ formatDate(row.createTime) }}
          </template>
        </el-table-column>

        <el-table-column label="账号到期" width="180">
          <template #default="{ row }">
            <button class="expire-cell" :class="{ expired: isExpired(row) }" @click="openExpireDialog(row)">
              <el-icon size="12"><Clock /></el-icon>
              <span v-if="row.expireTime">{{ formatDate(row.expireTime) }}{{ isExpired(row) ? ' · 已到期' : '' }}</span>
              <span v-else>永久</span>
            </button>
          </template>
        </el-table-column>

        <!-- 任务 7 · 部门 / 职位 列 -->
        <el-table-column label="部门 / 职位" width="200">
          <template #default="{ row }">
            <div class="org-col">
              <div class="org-line">
                <el-icon size="11" color="#0a0a0a"><OfficeBuilding /></el-icon>
                <span>{{ deptNameOf(row.departmentId) }}</span>
              </div>
              <div class="org-line">
                <el-icon size="11" color="#0EA5E9"><UserFilled /></el-icon>
                <span>{{ positionNameOf(row.positionId) }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <!-- 任务 13.6 · 本月用量列 -->
        <el-table-column label="本月用量" width="170">
          <template #default="{ row }">
            <button class="usage-cell" @click="$router.push(`/user-usage/${row.id}`)">
              <span class="cost">¥{{ formatMoney(usageMap[row.id]?.costCny) }}</span>
              <span class="chats">{{ usageMap[row.id]?.chatCount ?? 0 }} 对话</span>
              <el-icon size="11" color="#94a3b8"><ArrowRight /></el-icon>
            </button>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <div class="action-icons">
              <el-tooltip content="调整角色" placement="top">
                <el-button size="small" type="primary" circle :icon="Avatar" @click="openRoleDialog(row)" />
              </el-tooltip>
              <el-tooltip content="分配职位" placement="top">
                <el-button size="small" type="warning" circle :icon="OfficeBuilding" @click="openOrgDialog(row)" />
              </el-tooltip>
              <el-tooltip content="知识库配置" placement="top">
                <el-button size="small" type="success" circle :icon="Collection" @click="openKbDialog(row)" />
              </el-tooltip>
              <el-tooltip content="重置密码" placement="top">
                <el-button size="small" circle :icon="Key" @click="openResetPwd(row)" />
              </el-tooltip>
              <el-tooltip content="用量详情" placement="top">
                <el-button size="small" type="info" circle :icon="DataLine" @click="$router.push(`/user-usage/${row.id}`)" />
              </el-tooltip>
              <el-tooltip v-if="row.role !== 'admin'" content="注销" placement="top">
                <el-button size="small" type="danger" circle :icon="Delete" @click="deleteUser(row)" />
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="current"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @change="loadUsers"
        />
      </div>
    </el-card>

    <!-- 调整角色弹窗 -->
    <el-dialog v-model="roleDialogVisible" title="调整用户角色" width="400px">
      <el-form :model="roleForm" label-width="80px">
        <el-form-item label="用户">
          <span>{{ roleForm.username }}</span>
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="roleForm.role" style="width:100%">
            <el-option label="管理员" value="admin" />
            <el-option label="普通用户" value="user" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="roleDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveRole">保存</el-button>
      </template>
    </el-dialog>

    <!-- 添加用户弹窗 -->
    <el-dialog v-model="createDialogVisible" title="添加用户" width="460px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="createForm.username" placeholder="登录名 · 只能字母/数字/符号，禁中文" clearable />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input v-model="createForm.password" type="password" show-password placeholder="6-20 个字符" clearable />
        </el-form-item>
        <el-form-item label="昵称" prop="nickname">
          <el-input v-model="createForm.nickname" placeholder="显示名" clearable />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="createForm.role" style="width:100%">
            <el-option label="普通用户" value="user" />
            <el-option label="管理员" value="admin" />
          </el-select>
        </el-form-item>
        <el-form-item label="部门" prop="departmentId">
          <el-select v-model="createForm.departmentId" placeholder="请选择部门" clearable style="width:100%">
            <el-option v-for="d in allDepts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="职位" prop="positionId">
          <el-select v-model="createForm.positionId" placeholder="请选择职位" clearable style="width:100%">
            <el-option v-for="p in allPositions" :key="p.id" :label="`${p.name} (${p.code})`" :value="p.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 全员花费总览弹窗 -->
    <UsageOverviewDialog v-model="overviewVisible" :users="users" />

    <!-- 任务 7 · 分配部门/职位弹窗 -->
    <el-dialog v-model="orgDialogVisible" title="分配部门 / 职位" width="480px">
      <div class="org-hint">
        部门 + 职位决定该用户能访问哪些"职位独立"的知识库。系统角色（管理员/普通用户）独立管理。
      </div>
      <el-form :model="orgForm" label-width="80px">
        <el-form-item label="用户">
          <span>{{ orgForm.username }}</span>
        </el-form-item>
        <el-form-item label="部门">
          <el-select v-model="orgForm.departmentId" placeholder="留空 = 未分配" clearable style="width:100%">
            <el-option v-for="d in allDepts" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="职位">
          <el-select v-model="orgForm.positionId" placeholder="留空 = 仅能看 public KB" clearable style="width:100%">
            <el-option v-for="p in allPositions" :key="p.id" :label="`${p.name} (${p.code})`" :value="p.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="orgDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveOrg">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码弹窗（管理员直接设新密码） -->
    <el-dialog v-model="resetPwdVisible" title="重置密码" width="420px">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="用户">
          <span>{{ resetForm.username }}</span>
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="resetForm.newPassword"
            type="password"
            show-password
            placeholder="6-20 个字符"
            clearable
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetPwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="resetting" @click="submitResetPwd">确认重置</el-button>
      </template>
    </el-dialog>

    <!-- 知识库配置弹窗（按用户直接授权） -->
    <el-dialog v-model="kbDialogVisible" title="知识库配置" width="520px">
      <div class="org-hint">
        {{ kbForm.mode === 'override'
          ? '覆盖模式：该用户只能访问下面勾选的知识库，忽略其部门/职位授权（公开库 / 本人创建的仍可见）。'
          : '继承模式：在该用户「部门/职位」授权之外，额外再加下面勾选的知识库（并集，不会减少原有权限）。' }}
      </div>
      <el-form label-width="80px">
        <el-form-item label="用户">
          <span>{{ kbForm.username }}</span>
        </el-form-item>
        <el-form-item label="授权模式">
          <el-radio-group v-model="kbForm.mode">
            <el-radio value="inherit">继承部门/职位 + 附加</el-radio>
            <el-radio value="override">仅按这里设置（覆盖）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="知识库">
          <el-select
            v-model="kbForm.collectionIds"
            multiple
            filterable
            clearable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择该用户可访问的知识库"
            style="width:100%"
            :loading="kbLoading"
          >
            <el-option v-for="c in allCollections" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="kbDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="kbSaving" @click="saveUserKb">保存</el-button>
      </template>
    </el-dialog>

    <!-- 设置账号到期时间 -->
    <el-dialog v-model="expireDialogVisible" title="设置账号到期时间" width="420px">
      <el-form label-width="90px">
        <el-form-item label="用户">
          <span>{{ expireForm.username }}</span>
        </el-form-item>
        <el-form-item label="到期时间">
          <el-date-picker
            v-model="expireForm.expireTime"
            type="datetime"
            placeholder="留空 = 永久有效"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width:100%"
          />
        </el-form-item>
        <div class="org-hint">到期后该账号将无法登录。清空则永久有效。外部注册默认 2 天。</div>
      </el-form>
      <template #footer>
        <el-button @click="expireDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="expireSaving" @click="saveExpire">保存</el-button>
      </template>
    </el-dialog>

    <!-- 邀请码管理 + 注册二维码 -->
    <el-dialog v-model="inviteDialogVisible" title="邀请码管理" width="900px">
      <div class="invite-top">
        <div class="invite-gen">
          <div class="invite-gen-row">
            <span>生成数量</span>
            <el-input-number v-model="genForm.count" :min="1" :max="100" size="small" />
            <span>每个可用次数</span>
            <el-input-number v-model="genForm.maxUses" :min="0" size="small" />
            <span class="hint-inline">0 = 不限</span>
          </div>
          <div class="invite-gen-row">
            <span>过期时间</span>
            <el-date-picker v-model="genForm.expireTime" type="datetime" size="small"
              placeholder="留空 = 不过期" value-format="YYYY-MM-DD HH:mm:ss" style="width:200px" />
            <el-input v-model="genForm.remark" size="small" placeholder="备注（可选）" style="width:160px" />
            <el-button type="primary" size="small" :loading="generating" @click="doGenerate">生成邀请码</el-button>
          </div>
        </div>
        <div class="invite-qr">
          <div class="invite-qr-title">注册页二维码</div>
          <img v-if="qrUrl" :src="qrUrl" class="invite-qr-img" />
          <div v-else class="invite-qr-empty">未上传</div>
          <el-upload :show-file-list="false" :http-request="uploadQr" accept="image/*">
            <el-button size="small" :icon="Upload">上传/更换二维码</el-button>
          </el-upload>
        </div>
      </div>

      <el-table :data="inviteCodes" size="small" max-height="320" style="width:100%; margin-top:8px">
        <el-table-column label="邀请码" min-width="180">
          <template #default="{ row }">
            <div class="invite-code-cell">
              <code>{{ row.code }}</code>
              <el-button size="small" text :icon="CopyDocument" title="复制邀请码" @click="copyInviteCode(row.code)" />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="使用情况" width="110">
          <template #default="{ row }">{{ row.usedCount }} / {{ row.maxUses ?? '∞' }}</template>
        </el-table-column>
        <el-table-column label="过期时间" width="160">
          <template #default="{ row }">{{ row.expireTime ? formatDate(row.expireTime) : '不过期' }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="100">
          <template #default="{ row }">{{ row.remark || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled === 1" @change="(v: boolean) => toggleInvite(row, v)" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button size="small" type="danger" circle :icon="Delete" @click="deleteInvite(row)" />
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Search, Refresh, OfficeBuilding, UserFilled, ArrowRight, Histogram, Plus,
         Avatar, DataLine, Delete, Key, Collection, Ticket, Clock, Upload, CopyDocument } from '@element-plus/icons-vue'
import { userApi, inviteApi, type UserInfo, type InviteCode } from '@/api/user'
import { departmentApi, positionApi, type Department, type Position } from '@/api/orgAcl'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'
import { usageApi } from '@/api/usage'
import UsageOverviewDialog from '@/components/UsageOverviewDialog.vue'

const overviewVisible = ref(false)

const loading = ref(false)
const users = ref<UserInfo[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const roleFilter = ref('')
const sourceFilter = ref('')
const statusFilter = ref<number | ''>('')

const reloadFromFirstPage = () => { current.value = 1; loadUsers() }

const isRegister = (row: UserInfo) => row.source === 'register'
const isExpired = (row: UserInfo) =>
  !!row.expireTime && new Date(row.expireTime).getTime() < Date.now()

const roleDialogVisible = ref(false)
const roleForm = reactive({ id: 0, username: '', role: '' })

// 添加用户
const createDialogVisible = ref(false)
const creating = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive<{
  username: string; password: string; nickname: string;
  role: string; departmentId: number | null; positionId: number | null
}>({ username: '', password: '', nickname: '', role: 'user', departmentId: null, positionId: null })
const createRules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度为 3-20 个字符', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_.@-]+$/, message: '只能含字母、数字及 _ . @ - 符号（禁中文/空格）', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 20, message: '密码长度为 6-20 个字符', trigger: 'blur' },
  ],
  nickname:     [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  role:         [{ required: true, message: '请选择角色', trigger: 'change' }],
  departmentId: [{ required: true, message: '请选择部门', trigger: 'change' }],
  positionId:   [{ required: true, message: '请选择职位', trigger: 'change' }],
}

const openCreateDialog = () => {
  createForm.username = ''
  createForm.password = ''
  createForm.nickname = ''
  createForm.role = 'user'
  createForm.departmentId = null
  createForm.positionId = null
  createDialogVisible.value = true
}

const submitCreate = async () => {
  if (!createFormRef.value) return
  await createFormRef.value.validate(async (ok) => {
    if (!ok) return
    creating.value = true
    try {
      await userApi.createUser({
        username: createForm.username.trim(),
        password: createForm.password,
        nickname: createForm.nickname.trim() || undefined,
        role: createForm.role,
        departmentId: createForm.departmentId,
        positionId: createForm.positionId,
      })
      ElMessage.success('用户创建成功')
      createDialogVisible.value = false
      await loadUsers()
    } catch (e: any) {
      ElMessage.error('创建失败：' + (e?.message || ''))
    } finally {
      creating.value = false
    }
  })
}

// 任务 7 · 部门 / 职位
const allDepts = ref<Department[]>([])
const allPositions = ref<Position[]>([])
const orgDialogVisible = ref(false)
const orgForm = reactive<{ id: number; username: string; departmentId: number | null; positionId: number | null }>(
  { id: 0, username: '', departmentId: null, positionId: null }
)

const deptNameOf = (id?: number | null) => {
  if (!id) return '-'
  return allDepts.value.find(d => d.id === id)?.name || '-'
}
const positionNameOf = (id?: number | null) => {
  if (!id) return '-'
  return allPositions.value.find(p => p.id === id)?.name || '-'
}

const loadOrgData = async () => {
  try {
    const [dRes, pRes]: any = await Promise.all([departmentApi.list(), positionApi.list()])
    allDepts.value = dRes?.data ?? dRes ?? []
    allPositions.value = pRes?.data ?? pRes ?? []
  } catch (e: any) {
    ElMessage.warning('部门/职位字典加载失败：' + (e?.message || ''))
  }
}

const openOrgDialog = (row: UserInfo) => {
  orgForm.id = row.id
  orgForm.username = row.username
  orgForm.departmentId = row.departmentId ?? null
  orgForm.positionId = row.positionId ?? null
  orgDialogVisible.value = true
}

const saveOrg = async () => {
  try {
    await userApi.updateUserOrg(orgForm.id, orgForm.departmentId, orgForm.positionId)
    ElMessage.success('已保存 · 该用户的知识库访问范围已重新计算')
    orgDialogVisible.value = false
    await loadUsers()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  }
}

const loadUsers = async () => {
  loading.value = true
  try {
    const result = await userApi.listUsers({
      current: current.value,
      size: pageSize.value,
      keyword: keyword.value || undefined,
      role: roleFilter.value || undefined,
      source: sourceFilter.value || undefined,
      status: statusFilter.value === '' ? undefined : statusFilter.value,
    })
    users.value = result.records
    total.value = result.total
    // 任务 13.6 · 拉每个用户的本月用量（并发）
    loadUsageBatch(users.value.map(u => u.id))
  } finally {
    loading.value = false
  }
}

// 任务 13.6 · 用量缓存（按 userId 索引）
const usageMap = ref<Record<number, { chatCount: number; costCny: number }>>({})

const loadUsageBatch = async (userIds: number[]) => {
  // 并发拉，失败不阻塞列表
  const now = new Date()
  const firstDay = new Date(now.getFullYear(), now.getMonth(), 1).toISOString().slice(0, 10)
  const today    = now.toISOString().slice(0, 10)
  for (const id of userIds) {
    usageApi.userSummary(id, firstDay, today)
      .then((res: any) => {
        const d = res?.data ?? res
        usageMap.value[id] = {
          chatCount: d?.chatCount ?? 0,
          costCny:   Number(d?.costCny ?? 0),
        }
      })
      .catch(() => { /* 字典缺/无数据时静默 · 显示 0 */ })
  }
}

const formatMoney = (n?: number) => (n == null ? '0.0000' : Number(n).toFixed(4))

onMounted(async () => {
  await loadOrgData()
  await loadUsers()
})

const getRoleType = (role: string) => {
  return role === 'admin' ? 'danger' : ''
}

const getRoleLabel = (role: string) => {
  const map: Record<string, string> = { admin: '管理员', user: '用户' }
  return map[role] || role
}

const formatDate = (dateStr: string) => {
  return new Date(dateStr).toLocaleDateString('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit'
  })
}

const toggleStatus = async (row: UserInfo, val: boolean) => {
  try {
    await userApi.updateUserStatus(row.id, val ? 1 : 0)
    row.status = val ? 1 : 0
    ElMessage.success(`已${val ? '启用' : '禁用'}用户 ${row.username}`)
  } catch {}
}

// 重置密码（管理员直接设新密码）
const resetPwdVisible = ref(false)
const resetting = ref(false)
const resetForm = reactive({ id: 0, username: '', newPassword: '' })

const openResetPwd = (row: UserInfo) => {
  resetForm.id = row.id
  resetForm.username = row.username
  resetForm.newPassword = ''
  resetPwdVisible.value = true
}

const submitResetPwd = async () => {
  const pwd = resetForm.newPassword
  if (!pwd || pwd.length < 6 || pwd.length > 20) {
    ElMessage.warning('新密码需 6-20 个字符')
    return
  }
  resetting.value = true
  try {
    await userApi.adminResetPassword(resetForm.id, pwd)
    ElMessage.success(`已重置 ${resetForm.username} 的密码`)
    resetPwdVisible.value = false
  } catch (e: any) {
    ElMessage.error('重置失败：' + (e?.message || ''))
  } finally {
    resetting.value = false
  }
}

// 知识库配置（按用户直接授权）
const kbDialogVisible = ref(false)
const kbLoading = ref(false)
const kbSaving = ref(false)
const allCollections = ref<KnowledgeCollection[]>([])
const kbForm = reactive<{ id: number; username: string; mode: string; collectionIds: number[] }>(
  { id: 0, username: '', mode: 'inherit', collectionIds: [] }
)

const openKbDialog = async (row: UserInfo) => {
  kbForm.id = row.id
  kbForm.username = row.username
  kbForm.mode = 'inherit'
  kbForm.collectionIds = []
  kbDialogVisible.value = true
  kbLoading.value = true
  try {
    if (!allCollections.value.length) {
      const c: any = await collectionApi.list()
      allCollections.value = c?.data ?? c ?? []
    }
    const res: any = await userApi.getUserCollections(row.id)
    const d = res?.data ?? res ?? {}
    kbForm.mode = d.mode === 'override' ? 'override' : 'inherit'
    kbForm.collectionIds = (d.collectionIds ?? []) as number[]
  } catch (e: any) {
    ElMessage.warning('加载知识库授权失败：' + (e?.message || ''))
  } finally {
    kbLoading.value = false
  }
}

const saveUserKb = async () => {
  if (kbForm.mode === 'override' && !kbForm.collectionIds.length) {
    try {
      await ElMessageBox.confirm(
        '覆盖模式下未选任何知识库，该用户将只能看到公开库 / 本人创建的内容。确认保存？',
        '提示', { type: 'warning' }
      )
    } catch { return }
  }
  kbSaving.value = true
  try {
    await userApi.setUserCollections(kbForm.id, kbForm.mode, kbForm.collectionIds)
    ElMessage.success('已保存 · 该用户的知识库访问范围已更新')
    kbDialogVisible.value = false
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally {
    kbSaving.value = false
  }
}

const deleteUser = async (row: UserInfo) => {
  await ElMessageBox.confirm(
    `确认注销用户「${row.username}」？\n该账号将无法登录，且从列表移除（逻辑删除，可在数据库恢复）。`,
    '注销用户', { type: 'warning', confirmButtonText: '确认注销', confirmButtonClass: 'el-button--danger' }
  )
  try {
    await userApi.deleteUser(row.id)
    ElMessage.success('已注销')
    loadUsers()
  } catch (e: any) {
    ElMessage.error('注销失败：' + (e?.message || ''))
  }
}

const openRoleDialog = (row: UserInfo) => {
  roleForm.id = row.id
  roleForm.username = row.username
  roleForm.role = row.role
  roleDialogVisible.value = true
}

const saveRole = async () => {
  try {
    await userApi.updateUserRole(roleForm.id, roleForm.role)
    ElMessage.success('角色更新成功')
    roleDialogVisible.value = false
    loadUsers()
  } catch {}
}

// ── 账号到期时间 ──
const expireDialogVisible = ref(false)
const expireSaving = ref(false)
const expireForm = reactive<{ id: number; username: string; expireTime: string | null }>(
  { id: 0, username: '', expireTime: null }
)
const openExpireDialog = (row: UserInfo) => {
  expireForm.id = row.id
  expireForm.username = row.username
  expireForm.expireTime = row.expireTime ?? null
  expireDialogVisible.value = true
}
const saveExpire = async () => {
  expireSaving.value = true
  try {
    await userApi.updateUserExpireTime(expireForm.id, expireForm.expireTime || null)
    ElMessage.success('已更新账号到期时间')
    expireDialogVisible.value = false
    await loadUsers()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally {
    expireSaving.value = false
  }
}

// ── 邀请码管理 + 注册二维码 ──
const inviteDialogVisible = ref(false)
const inviteCodes = ref<InviteCode[]>([])
const generating = ref(false)
const qrUrl = ref<string | null>(null)
const genForm = reactive<{ count: number; maxUses: number; expireTime: string | null; remark: string }>(
  { count: 1, maxUses: 1, expireTime: null, remark: '' }
)

const openInviteDialog = async () => {
  inviteDialogVisible.value = true
  await Promise.all([loadInviteCodes(), loadRegisterSettings()])
}
const loadInviteCodes = async () => {
  try {
    const res: any = await inviteApi.list()
    inviteCodes.value = res?.data ?? res ?? []
  } catch { /* ignore */ }
}
const loadRegisterSettings = async () => {
  try {
    const res: any = await inviteApi.getSettings()
    qrUrl.value = (res?.data ?? res)?.qrUrl ?? null
  } catch { /* ignore */ }
}
const doGenerate = async () => {
  generating.value = true
  try {
    await inviteApi.generate({
      count: genForm.count,
      maxUses: genForm.maxUses > 0 ? genForm.maxUses : null,
      expireTime: genForm.expireTime || null,
      remark: genForm.remark || undefined,
    })
    ElMessage.success(`已生成 ${genForm.count} 个邀请码`)
    await loadInviteCodes()
  } catch (e: any) {
    ElMessage.error('生成失败：' + (e?.message || ''))
  } finally {
    generating.value = false
  }
}
const copyInviteCode = async (code: string) => {
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success('已复制邀请码')
  } catch {
    ElMessage.warning('复制失败，请手动选择复制')
  }
}
const toggleInvite = async (row: InviteCode, enabled: boolean) => {
  try {
    await inviteApi.setEnabled(row.id, enabled)
    row.enabled = enabled ? 1 : 0
  } catch (e: any) {
    ElMessage.error('操作失败：' + (e?.message || ''))
  }
}
const deleteInvite = async (row: InviteCode) => {
  await ElMessageBox.confirm(`确认删除邀请码「${row.code}」？`, '提示', { type: 'warning' })
  try {
    await inviteApi.delete(row.id)
    ElMessage.success('已删除')
    await loadInviteCodes()
  } catch (e: any) {
    ElMessage.error('删除失败：' + (e?.message || ''))
  }
}
const uploadQr = async (opt: any) => {
  try {
    const url: any = await inviteApi.uploadQr(opt.file)
    qrUrl.value = (url?.data ?? url) ?? null
    ElMessage.success('二维码已更新')
  } catch (e: any) {
    ElMessage.error('上传失败：' + (e?.message || ''))
  }
}
</script>

<style scoped>
.user-manage-page { display: flex; flex-direction: column; gap: 16px; height: 100%; overflow-y: auto; padding: 24px; }
.search-card, .table-card { border-radius: 12px; }

/* 操作列：小圆形图标按钮排成一排 */
.action-icons { display: flex; align-items: center; gap: 6px; flex-wrap: nowrap; }
.action-icons .el-button { margin: 0; }
.action-icons .el-button.is-circle { width: 28px; height: 28px; }

.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cell-name { font-weight: 600; font-size: 14px; color: #e2e8f0; }
.cell-username { font-size: 12px; color: #64748b; }

.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* 任务 7 · 部门/职位列 */
.org-col { display: flex; flex-direction: column; gap: 4px; }
.org-line { display: flex; align-items: center; gap: 6px; font-size: 12.5px; color: var(--ink-2, #475569); }
/* 任务 13.6 · 本月用量列 */
.usage-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  background: var(--bg-elevated);
  border: 1px solid var(--line);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
  font-size: 12px;
}
.usage-cell:hover { background: var(--brand-soft); border-color: var(--brand-soft-2); }
.usage-cell .cost { color: #b45309; font-weight: 700; font-family: 'JetBrains Mono', monospace; }
.usage-cell .chats { color: var(--ink-3); font-size: 11px; }

.org-hint {
  padding: 10px 12px;
  margin-bottom: 14px;
  background: rgba(0, 0, 0, 0.08);
  border: 1px solid rgba(0, 0, 0, 0.2);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--ink-2, #475569);
  line-height: 1.55;
}

/* 筛选 + 工具栏 */
.filter-bar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.filter-spacer { flex: 1; }

/* 账号到期单元格 */
.expire-cell {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 4px 10px; border-radius: 6px; cursor: pointer; font-size: 12px;
  background: var(--bg-elevated); border: 1px solid var(--line); color: var(--ink-2, #475569);
  transition: all 0.15s;
}
.expire-cell:hover { border-color: var(--brand-soft-2); background: var(--brand-soft); }
.expire-cell.expired { color: #dc2626; border-color: rgba(220,38,38,0.4); background: rgba(220,38,38,0.06); }

/* 邀请码弹窗 */
.invite-code-cell { display: flex; align-items: center; gap: 6px; }
.invite-code-cell code { font-family: 'JetBrains Mono', monospace; }
.invite-top { display: flex; gap: 18px; align-items: flex-start; }
.invite-gen { flex: 1; display: flex; flex-direction: column; gap: 10px; }
.invite-gen-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; color: var(--ink-2, #475569); }
.hint-inline { font-size: 11px; color: var(--ink-4, #94a3b8); }
.invite-qr { width: 150px; text-align: center; display: flex; flex-direction: column; gap: 8px; align-items: center; }
.invite-qr-title { font-size: 12px; color: var(--ink-3, #94a3b8); }
.invite-qr-img { width: 120px; height: 120px; object-fit: contain; border: 1px solid var(--line); border-radius: 8px; background: #fff; }
.invite-qr-empty {
  width: 120px; height: 120px; display: flex; align-items: center; justify-content: center;
  border: 1px dashed var(--line); border-radius: 8px; color: var(--ink-4, #94a3b8); font-size: 12px;
}
</style>
