<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  dataSourceApi, dataSourceAclApi,
  type DataSource, type DsTableMeta, type DsColumn,
  type IntrospectedTable, type DsAclEntry,
} from '@/api/datasource'
import { departmentApi, positionApi, type Department, type Position } from '@/api/orgAcl'

// ───────────── 列表 ─────────────
const loading = ref(false)
const list = ref<DataSource[]>([])

const loadList = async () => {
  loading.value = true
  try {
    const res: any = await dataSourceApi.list()
    list.value = res?.data ?? res ?? []
  } catch (e: any) {
    ElMessage.error('加载失败：' + (e?.message ?? e))
  } finally {
    loading.value = false
  }
}

// ───────────── 新建 / 编辑 ─────────────
const editVisible = ref(false)
const editing = ref(false)
const testing = ref(false)
const form = reactive<DataSource>({
  name: '', dbType: 'mysql', host: '', port: 3306, dbName: '',
  username: '', password: '', jdbcParams: '', description: '',
  visibility: 'private', status: 'enabled', autoSync: 1, syncIntervalMin: 60,
})
const editId = ref<number | null>(null)

const openCreate = () => {
  editId.value = null
  Object.assign(form, {
    name: '', dbType: 'mysql', host: '', port: 3306, dbName: '',
    username: '', password: '', jdbcParams: '', description: '',
    visibility: 'private', status: 'enabled', autoSync: 1, syncIntervalMin: 60,
  })
  editVisible.value = true
}

const openEdit = (row: DataSource) => {
  editId.value = row.id ?? null
  Object.assign(form, {
    ...row,
    password: '', // 留空表示沿用旧密码
  })
  editVisible.value = true
}

const submitEdit = async () => {
  if (!form.name || !form.host || !form.dbName || !form.username) {
    ElMessage.warning('名称 / 主机 / 库名 / 用户名 必填')
    return
  }
  editing.value = true
  try {
    if (editId.value) {
      await dataSourceApi.update(editId.value, { ...form })
      ElMessage.success('已更新')
    } else {
      await dataSourceApi.create({ ...form })
      ElMessage.success('已创建')
    }
    editVisible.value = false
    await loadList()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message ?? e))
  } finally {
    editing.value = false
  }
}

// 注意：request 拦截器在 code===200 时直接返回 data（成功为字符串 'ok'），
// 业务失败时已自行 ElMessage.error 并 reject，故 catch 内不再重复弹错。
const testConn = async (fromDialog: boolean) => {
  testing.value = true
  try {
    const payload: DataSource = fromDialog
      ? { ...form, id: editId.value ?? undefined }
      : { ...form }
    await dataSourceApi.test(payload)
    ElMessage.success('连接成功')
  } catch {
    /* 拦截器已弹出失败原因 */
  } finally {
    testing.value = false
  }
}

const testRow = async (row: DataSource) => {
  testing.value = true
  try {
    await dataSourceApi.test({ ...row, id: row.id })
    ElMessage.success(`${row.name} 连接成功`)
  } catch {
    /* 拦截器已弹出失败原因 */
  } finally {
    await loadList()   // 刷新 last_test_* 状态
    testing.value = false
  }
}

const removeRow = async (row: DataSource) => {
  try {
    await ElMessageBox.confirm(`确定删除数据源「${row.name}」？`, '删除确认', { type: 'warning' })
    await dataSourceApi.delete(row.id!)
    ElMessage.success('已删除')
    await loadList()
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('删除失败：' + (e?.message ?? e))
  }
}

// ───────────── 表元数据编辑 ─────────────
interface EditableTable extends DsTableMeta {
  columns: DsColumn[]   // 由 columnsJson 解析
  expanded?: boolean
}
const tablesVisible = ref(false)
const tablesLoading = ref(false)
const tablesSaving = ref(false)
const introspecting = ref(false)
const syncing = ref(false)
const tablesDsId = ref<number | null>(null)
const tablesDsName = ref('')
const tables = ref<EditableTable[]>([])

const parseColumns = (json?: string): DsColumn[] => {
  if (!json) return []
  try {
    const arr = JSON.parse(json)
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

const openTables = async (row: DataSource) => {
  tablesDsId.value = row.id ?? null
  tablesDsName.value = row.name
  tablesVisible.value = true
  tablesLoading.value = true
  try {
    const res: any = await dataSourceApi.listTables(row.id!)
    const raw: DsTableMeta[] = res?.data ?? res ?? []
    tables.value = raw.map(t => ({ ...t, columns: parseColumns(t.columnsJson), expanded: false }))
  } catch (e: any) {
    ElMessage.error('加载表元数据失败：' + (e?.message ?? e))
    tables.value = []
  } finally {
    tablesLoading.value = false
  }
}

const introspect = async () => {
  if (!tablesDsId.value) return
  introspecting.value = true
  try {
    const res: any = await dataSourceApi.introspect(tablesDsId.value)
    const fetched: IntrospectedTable[] = res?.data ?? res ?? []
    // 合并：保留已有的语义编辑，补充新表
    const existing = new Map(tables.value.map(t => [t.tableName, t]))
    const merged: EditableTable[] = fetched.map(ft => {
      const old = existing.get(ft.tableName)
      if (old) {
        // 用反查的列补齐（保留已填的业务名/描述）
        const oldCols = new Map(old.columns.map(c => [c.name, c]))
        const cols = ft.columns.map(c => {
          const oc = oldCols.get(c.name)
          return {
            name: c.name, type: c.type, comment: c.comment,
            businessName: oc?.businessName ?? c.businessName ?? c.comment ?? '',
            description: oc?.description ?? c.description ?? '',
          }
        })
        return { ...old, columns: cols }
      }
      return {
        tableName: ft.tableName,
        businessName: ft.tableComment ?? '',
        description: ft.tableComment ?? '',
        enabled: 0,           // 反查出来默认不暴露，管理员手动开
        columns: ft.columns.map(c => ({
          name: c.name, type: c.type, comment: c.comment,
          businessName: c.businessName ?? c.comment ?? '', description: '',
        })),
        expanded: false,
      }
    })
    tables.value = merged
    ElMessage.success(`反查到 ${fetched.length} 张表，请勾选要暴露给 AI 的表并补充语义`)
  } catch (e: any) {
    ElMessage.error('反查失败：' + (e?.message ?? e))
  } finally {
    introspecting.value = false
  }
}

const saveTables = async () => {
  if (!tablesDsId.value) return
  tablesSaving.value = true
  try {
    const payload: DsTableMeta[] = tables.value.map((t, i) => ({
      tableName: t.tableName,
      businessName: t.businessName,
      description: t.description,
      columnsJson: JSON.stringify(t.columns ?? []),
      enabled: t.enabled ? 1 : 0,
      sortOrder: i,
    }))
    await dataSourceApi.saveTables(tablesDsId.value, payload)
    ElMessage.success('已保存表语义')
    tablesVisible.value = false
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message ?? e))
  } finally {
    tablesSaving.value = false
  }
}

const syncNow = async () => {
  if (!tablesDsId.value) return
  syncing.value = true
  try {
    const res: any = await dataSourceApi.syncTables(tablesDsId.value)
    const r = res?.data ?? res ?? {}
    const added = r.added?.length ?? 0
    const disabled = r.disabled?.length ?? 0
    ElMessage.success(`同步完成：新增 ${added} 张(默认未启用)、失效禁用 ${disabled} 张`)
    // 重新加载表元数据以反映同步结果
    const tr: any = await dataSourceApi.listTables(tablesDsId.value)
    const raw: DsTableMeta[] = tr?.data ?? tr ?? []
    tables.value = raw.map(t => ({ ...t, columns: parseColumns(t.columnsJson), expanded: false }))
  } catch (e: any) {
    ElMessage.error('同步失败：' + (e?.message ?? e))
  } finally {
    syncing.value = false
  }
}

const enableAll  = () => { tables.value.forEach(t => { t.enabled = 1 }); ElMessage.success(`已启用全部 ${tables.value.length} 张表，记得点「保存语义」`) }
const disableAll = () => { tables.value.forEach(t => { t.enabled = 0 }) }

const enabledCount = computed(() => tables.value.filter(t => t.enabled).length)

// ───────────── 权限（部门 / 职位） ─────────────
const aclVisible = ref(false)
const aclLoading = ref(false)
const aclSaving = ref(false)
const aclDsId = ref<number | null>(null)
const aclDsName = ref('')
const aclVisibility = ref<'public' | 'scoped' | 'private'>('private')
const aclEntries = ref<DsAclEntry[]>([])
const departments = ref<Department[]>([])
const positions = ref<Position[]>([])

const addType = ref<'position' | 'department'>('department')
const newDepartmentId = ref<number | null>(null)
const newPositionId = ref<number | null>(null)

const ensureOrgLoaded = async () => {
  if (departments.value.length === 0) {
    const dRes: any = await departmentApi.list()
    departments.value = dRes?.data ?? dRes ?? []
  }
  if (positions.value.length === 0) {
    const pRes: any = await positionApi.list()
    positions.value = pRes?.data ?? pRes ?? []
  }
}

const departmentLabel = (id?: number | null) =>
  departments.value.find(d => d.id === id)?.name ?? `部门#${id}`
const positionLabel = (id?: number | null) =>
  positions.value.find(p => p.id === id)?.name ?? `职位#${id}`
const aclSubjectLabel = (e: DsAclEntry) =>
  e.departmentId ? departmentLabel(e.departmentId) : positionLabel(e.positionId)

const openAcl = async (row: DataSource) => {
  aclDsId.value = row.id ?? null
  aclDsName.value = row.name
  aclVisibility.value = (row.visibility as any) ?? 'private'
  aclVisible.value = true
  aclLoading.value = true
  try {
    await ensureOrgLoaded()
    const res: any = await dataSourceAclApi.list(row.id!)
    const acls = res?.data ?? res ?? []
    aclEntries.value = acls.map((a: any) => ({
      positionId: a.positionId, departmentId: a.departmentId, permission: a.permission ?? 'read',
    }))
  } catch (e: any) {
    ElMessage.error('加载权限失败：' + (e?.message ?? e))
    aclEntries.value = []
  } finally {
    aclLoading.value = false
  }
}

const addAclEntry = () => {
  if (addType.value === 'position') {
    if (!newPositionId.value) { ElMessage.warning('请选择职位'); return }
    if (aclEntries.value.some(e => e.positionId === newPositionId.value)) {
      ElMessage.warning('该职位已添加'); return
    }
    aclEntries.value.push({ positionId: newPositionId.value, departmentId: null, permission: 'read' })
    newPositionId.value = null
  } else {
    if (!newDepartmentId.value) { ElMessage.warning('请选择部门'); return }
    if (aclEntries.value.some(e => e.departmentId === newDepartmentId.value)) {
      ElMessage.warning('该部门已添加'); return
    }
    aclEntries.value.push({ positionId: null, departmentId: newDepartmentId.value, permission: 'read' })
    newDepartmentId.value = null
  }
}

const removeAclEntry = (idx: number) => aclEntries.value.splice(idx, 1)

const saveAcl = async () => {
  if (!aclDsId.value) return
  aclSaving.value = true
  try {
    // 先更新 visibility（复用 update）
    const row = list.value.find(d => d.id === aclDsId.value)
    if (row && row.visibility !== aclVisibility.value) {
      await dataSourceApi.update(aclDsId.value, { ...row, password: '', visibility: aclVisibility.value })
    }
    if (aclVisibility.value === 'scoped') {
      await dataSourceAclApi.replace(aclDsId.value, aclEntries.value)
    }
    ElMessage.success('权限已保存')
    aclVisible.value = false
    await loadList()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message ?? e))
  } finally {
    aclSaving.value = false
  }
}

const visibilityText = (v?: string) =>
  v === 'public' ? '所有人' : v === 'scoped' ? '按权限' : '仅创建者'

onMounted(loadList)
</script>

<template>
  <div class="ds-view">
    <div class="ds-header">
      <div>
        <h2>数据源配置</h2>
        <p class="sub">配置外部业务数据库（如 CRM），授权后即可在「智能问答」中用自然语言查询分析。</p>
      </div>
      <el-button type="primary" :icon="'Plus'" @click="openCreate">新建数据源</el-button>
    </div>

    <el-table :data="list" v-loading="loading" class="ds-table" border>
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="连接" min-width="220">
        <template #default="{ row }">
          <code>{{ row.dbType }}://{{ row.host }}:{{ row.port }}/{{ row.dbName }}</code>
        </template>
      </el-table-column>
      <el-table-column prop="username" label="账号" width="110" />
      <el-table-column label="可见性" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.visibility === 'public' ? 'success' : row.visibility === 'scoped' ? 'warning' : 'info'">
            {{ visibilityText(row.visibility) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'enabled' ? 'success' : 'danger'">
            {{ row.status === 'enabled' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="连接测试" width="130">
        <template #default="{ row }">
          <el-tag v-if="row.lastTestStatus === 'ok'" size="small" type="success">正常</el-tag>
          <el-tooltip v-else-if="row.lastTestStatus === 'fail'" :content="row.lastTestError || '失败'">
            <el-tag size="small" type="danger">失败</el-tag>
          </el-tooltip>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="320" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :loading="testing" @click="testRow(row)">测试</el-button>
          <el-button link type="primary" @click="openTables(row)">表/字段</el-button>
          <el-button link type="primary" @click="openAcl(row)">权限</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeRow(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建 / 编辑 -->
    <el-dialog v-model="editVisible" :title="editId ? '编辑数据源' : '新建数据源'" width="560px">
      <el-form :model="form" label-width="92px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如：CRM 客户库" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.dbType" disabled>
            <el-option label="MySQL" value="mysql" />
          </el-select>
        </el-form-item>
        <el-form-item label="主机" required>
          <el-input v-model="form.host" placeholder="127.0.0.1" />
        </el-form-item>
        <el-form-item label="端口">
          <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" />
        </el-form-item>
        <el-form-item label="库名" required>
          <el-input v-model="form.dbName" placeholder="crm" />
        </el-form-item>
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" placeholder="建议使用只读账号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password
                    :placeholder="editId ? '留空表示不修改' : '数据库密码'" />
        </el-form-item>
        <el-form-item label="JDBC 参数">
          <el-input v-model="form.jdbcParams" placeholder="useSSL=false&serverTimezone=UTC（留空用默认）" />
        </el-form-item>
        <el-form-item label="业务说明">
          <el-input v-model="form.description" type="textarea" :rows="2"
                    placeholder="给 AI 的库级说明，如：客户、订单、跟进记录" />
        </el-form-item>
        <el-form-item label="可见性">
          <el-select v-model="form.visibility">
            <el-option label="仅创建者" value="private" />
            <el-option label="按权限（部门/职位）" value="scoped" />
            <el-option label="所有人" value="public" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-switch v-model="form.status" active-value="enabled" inactive-value="disabled"
                     active-text="启用" inactive-text="停用" />
        </el-form-item>
        <el-form-item label="自动同步表结构">
          <el-switch v-model="form.autoSync" :active-value="1" :inactive-value="0"
                     active-text="开" inactive-text="关" />
          <span class="muted" style="margin-left:12px">每隔一段时间自动发现新表 / 禁用已删除的表(新表默认不启用,需手动开)</span>
        </el-form-item>
        <el-form-item label="同步周期" v-if="form.autoSync">
          <el-input-number v-model="form.syncIntervalMin" :min="0" :step="10" :max="10080" />
          <span class="muted" style="margin-left:12px">分钟 · 0 = 仅手动同步</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :loading="testing" @click="testConn(true)">测试连接</el-button>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 表 / 字段语义 -->
    <el-dialog v-model="tablesVisible" :title="`表/字段语义 · ${tablesDsName}`" width="860px" top="6vh">
      <div class="tbl-toolbar">
        <el-button :loading="introspecting" @click="introspect">从数据库反查表结构</el-button>
        <el-button :loading="syncing" type="primary" plain @click="syncNow">立即同步</el-button>
        <el-button v-if="tables.length" type="success" plain @click="enableAll">一键全部启用</el-button>
        <el-button v-if="tables.length" plain @click="disableAll">全部禁用</el-button>
        <span class="muted">已暴露 {{ enabledCount }} / {{ tables.length }} 张表给 AI</span>
      </div>
      <div v-loading="tablesLoading" class="tbl-list">
        <el-empty v-if="!tables.length" description="还没有表元数据，点上方按钮从数据库反查" />
        <div v-for="(t, ti) in tables" :key="t.tableName" class="tbl-card">
          <div class="tbl-row">
            <el-switch v-model="t.enabled" :active-value="1" :inactive-value="0" />
            <strong class="tbl-name">{{ t.tableName }}</strong>
            <el-input v-model="t.businessName" size="small" placeholder="业务名（如：客户表）" style="width:160px" />
            <el-input v-model="t.description" size="small" placeholder="表说明（给 AI）" class="flex1" />
            <el-button link type="primary" size="small" @click="t.expanded = !t.expanded">
              {{ t.expanded ? '收起字段' : `字段(${t.columns.length})` }}
            </el-button>
          </div>
          <div v-if="t.expanded" class="col-list">
            <div v-for="(c, ci) in t.columns" :key="ci" class="col-row">
              <code class="col-name">{{ c.name }}</code>
              <span class="col-type">{{ c.type }}</span>
              <el-input v-model="c.businessName" size="small" placeholder="字段业务名" style="width:150px" />
              <el-input v-model="c.description" size="small" placeholder="字段说明（给 AI）" class="flex1" />
            </div>
          </div>
        </div>
      </div>
      <template #footer>
        <el-button @click="tablesVisible = false">取消</el-button>
        <el-button type="primary" :loading="tablesSaving" @click="saveTables">保存语义</el-button>
      </template>
    </el-dialog>

    <!-- 权限 -->
    <el-dialog v-model="aclVisible" :title="`访问权限 · ${aclDsName}`" width="600px">
      <el-form label-width="92px" v-loading="aclLoading">
        <el-form-item label="可见性">
          <el-radio-group v-model="aclVisibility">
            <el-radio value="private">仅创建者</el-radio>
            <el-radio value="scoped">按部门/职位</el-radio>
            <el-radio value="public">所有人</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="aclVisibility === 'scoped'">
          <el-divider>授权对象</el-divider>
          <div class="acl-add">
            <el-radio-group v-model="addType" size="small">
              <el-radio-button value="department">部门</el-radio-button>
              <el-radio-button value="position">职位</el-radio-button>
            </el-radio-group>
            <el-select v-if="addType === 'department'" v-model="newDepartmentId"
                       placeholder="选择部门" filterable clearable style="width:200px">
              <el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id!" />
            </el-select>
            <el-select v-else v-model="newPositionId"
                       placeholder="选择职位" filterable clearable style="width:200px">
              <el-option v-for="p in positions" :key="p.id" :label="p.name" :value="p.id!" />
            </el-select>
            <el-button type="primary" plain @click="addAclEntry">添加</el-button>
          </div>
          <div class="acl-list">
            <div v-for="(e, idx) in aclEntries" :key="idx" class="acl-row">
              <el-tag size="small" :type="e.departmentId ? 'warning' : 'info'">
                {{ e.departmentId ? '部门' : '职位' }}
              </el-tag>
              <span class="acl-name">{{ aclSubjectLabel(e) }}</span>
              <span class="muted">可查询</span>
              <el-button link type="danger" size="small" @click="removeAclEntry(idx)">移除</el-button>
            </div>
            <el-empty v-if="!aclEntries.length" description="未授权任何部门/职位" :image-size="60" />
          </div>
          <p class="acl-tip">部门授权对其所有子部门生效；系统管理员始终可访问全部数据源。</p>
        </template>
        <p v-else-if="aclVisibility === 'public'" class="acl-tip">所有登录用户都能用自然语言查询该数据源。</p>
        <p v-else class="acl-tip">仅创建者本人可访问。</p>
      </el-form>
      <template #footer>
        <el-button @click="aclVisible = false">取消</el-button>
        <el-button type="primary" :loading="aclSaving" @click="saveAcl">保存权限</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.ds-view { padding: 20px; }
.ds-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
.ds-header h2 { margin: 0; }
.sub { color: var(--el-text-color-secondary); font-size: 13px; margin: 4px 0 0; }
.ds-table { width: 100%; }
.muted { color: var(--el-text-color-secondary); font-size: 12px; }
.tbl-toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 12px; }
.tbl-list { max-height: 60vh; overflow: auto; }
.tbl-card { border: 1px solid var(--el-border-color-lighter); border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; }
.tbl-row { display: flex; align-items: center; gap: 10px; }
.tbl-name { min-width: 140px; font-family: monospace; }
.flex1 { flex: 1; }
.col-list { margin-top: 10px; padding-top: 10px; border-top: 1px dashed var(--el-border-color-lighter); display: flex; flex-direction: column; gap: 6px; }
.col-row { display: flex; align-items: center; gap: 8px; }
.col-name { min-width: 140px; color: var(--el-color-primary); }
.col-type { min-width: 70px; font-size: 12px; color: var(--el-text-color-secondary); }
.acl-add { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.acl-list { display: flex; flex-direction: column; gap: 8px; }
.acl-row { display: flex; align-items: center; gap: 10px; }
.acl-name { font-weight: 600; }
.acl-tip { color: var(--el-text-color-secondary); font-size: 12px; margin-top: 12px; }
</style>
