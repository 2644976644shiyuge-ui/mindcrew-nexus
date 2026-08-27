<template>
  <div class="sp-page">
    <header class="sp-header">
      <div>
        <h2 class="title">技能包 <span class="title-tag">提问时可选</span></h2>
        <p class="desc">配置后，销售在智能问答输入框上方就能选「技能」提问--同一问题，选不同技能出不同结果。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openDialog(null)">新建技能包</el-button>
    </header>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column label="技能" min-width="200">
        <template #default="{ row }">
          <div class="sp-name">{{ row.name }}</div>
          <div class="sp-desc">{{ row.description || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="绑定知识库" min-width="200">
        <template #default="{ row }">
          <span v-if="!parseIds(row.collectionIds).length" class="muted">全部知识库</span>
          <el-tag v-for="cid in parseIds(row.collectionIds)" :key="cid" size="small" effect="plain" style="margin:2px 4px 2px 0">
            {{ collName(cid) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center">
        <template #default="{ row }">
          <el-switch :model-value="row.enabled === 1" @change="(v: any) => toggle(row, !!v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="del(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="!loading && !rows.length" class="empty">还没有技能包，点右上角「新建技能包」。</div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑技能包' : '新建技能包'" width="640px" top="6vh">
      <el-form :model="form" label-width="92px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 产品匹配顾问" maxlength="20" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.description" placeholder="一句话说明这个技能干啥" maxlength="60" />
        </el-form-item>
        <el-form-item label="技能指令" required>
          <div class="instr-wrap">
            <div class="instr-tools">
              <el-button link type="primary" size="small" @click="insertTemplate">插入结构化模板</el-button>
            </div>
            <el-input v-model="form.instruction" type="textarea" :autosize="{ minRows: 10, maxRows: 22 }"
                      placeholder="点「插入结构化模板」一键填入骨架，再把【】里的内容替换成你的业务即可。" />
            <div class="form-hint">
              建议按六块写：<b>角色 / 输入 / 步骤 / 输出格式 / 硬约束 / 示例</b>。其中「示例(few-shot)」填 1-2 个真实问答，AI 的输出格式会稳定很多。
            </div>
          </div>
        </el-form-item>
        <el-form-item label="绑定知识库">
          <el-select v-model="form.collIds" multiple filterable clearable placeholder="留空 = 全部知识库" style="width:100%">
            <el-option v-for="c in collections" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <div class="form-hint">选了就只在这些库检索（如"产品匹配"只查产品库）；留空则全库。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { skillPackApi, type SkillPack } from '@/api/skillPack'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'

const unwrap = (r: any) => r?.data ?? r
const loading = ref(false)
const rows = ref<SkillPack[]>([])
const collections = ref<KnowledgeCollection[]>([])
const collMap = ref<Record<number, string>>({})
const collName = (id: number) => collMap.value[id] || `#${id}`

const parseIds = (s?: string): number[] => {
  if (!s) return []
  try { const a = JSON.parse(s); return Array.isArray(a) ? a : [] } catch { return [] }
}

const load = async () => {
  loading.value = true
  try { rows.value = unwrap(await skillPackApi.list()) || [] }
  catch (e: any) { ElMessage.error('加载失败：' + (e?.message || '')) }
  finally { loading.value = false }
}
const loadColls = async () => {
  try {
    const list: KnowledgeCollection[] = unwrap(await collectionApi.list()) || []
    collections.value = list
    collMap.value = Object.fromEntries(list.map(c => [c.id, c.name]))
  } catch { /* 静默 */ }
}
onMounted(() => { load(); loadColls() })

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive<{ id: number | null; name: string; description: string; instruction: string; collIds: number[] }>(
  { id: null, name: '', description: '', instruction: '', collIds: [] }
)

// 结构化技能指令骨架：六块（角色/输入/步骤/输出格式/硬约束/示例）
const INSTRUCTION_TEMPLATE = `# 角色
你是【角色定位，如：助贷产品匹配顾问】。

# 输入
用户会提供【关键信息，如：公积金 / 个税 / 有无房 / 网贷 / 征信】。

# 步骤
1. 提取关键信息
2. 仅在知识库资料中检索可用内容
3. 给出结论（选 2-3 个最优）

# 输出格式（严格遵守）
【逐项说明每部分输出什么，例：
① 产品名
② 匹配理由（对应客户哪条资质）
③ 报单话术（可直接发给客户的一段话）】

# 硬约束
- 只用知识库里确有的内容；缺资料就直说"暂无匹配"，绝不编造额度/利率等数字
- 不确定的字段标注"需核实"
- 不输出与本次任务无关的内容

# 示例（建议填 1-2 个，输出会更稳定）
输入：【一个典型输入】
输出：【对应的理想输出】`

const insertTemplate = async () => {
  if (form.instruction.trim()) {
    try {
      await ElMessageBox.confirm('当前已有内容，插入模板会覆盖它，确定吗？', '插入结构化模板', {
        type: 'warning', confirmButtonText: '覆盖', cancelButtonText: '取消',
      })
    } catch { return }
  }
  form.instruction = INSTRUCTION_TEMPLATE
}

const openDialog = (row: SkillPack | null) => {
  if (row) {
    form.id = row.id ?? null
    form.name = row.name || ''
    form.description = row.description || ''
    form.instruction = row.instruction || ''
    form.collIds = parseIds(row.collectionIds)
  } else {
    form.id = null; form.name = ''; form.description = ''; form.instruction = ''; form.collIds = []
  }
  dialogVisible.value = true
}

const save = async () => {
  if (!form.name.trim()) { ElMessage.warning('请输入名称'); return }
  if (!form.instruction.trim()) { ElMessage.warning('请输入技能指令'); return }
  saving.value = true
  try {
    const payload: SkillPack = {
      name: form.name.trim(),
      description: form.description.trim(),
      instruction: form.instruction.trim(),
      collectionIds: form.collIds.length ? JSON.stringify(form.collIds) : '',
    }
    if (form.id) await skillPackApi.update(form.id, payload)
    else await skillPackApi.create(payload)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { saving.value = false }
}

const toggle = async (row: SkillPack, v: boolean) => {
  try { await skillPackApi.setEnabled(row.id!, v); row.enabled = v ? 1 : 0 }
  catch (e: any) { ElMessage.error('操作失败：' + (e?.message || '')) }
}

const del = async (row: SkillPack) => {
  await ElMessageBox.confirm(`确认删除技能包「${row.name}」？`, '删除', { type: 'warning', confirmButtonText: '确认删除' })
  try { await skillPackApi.delete(row.id!); ElMessage.success('已删除'); await load() }
  catch (e: any) { ElMessage.error('删除失败：' + (e?.message || '')) }
}
</script>

<style scoped>
.sp-page { padding: 24px 28px; height: 100%; overflow-y: auto; }
.sp-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; gap: 16px; }
.title { font-size: 20px; font-weight: 700; color: var(--ink-1); display: flex; align-items: center; gap: 10px; }
.title-tag { font-size: 12px; font-weight: 500; color: var(--brand); background: var(--brand-soft); padding: 2px 10px; border-radius: 999px; }
.desc { font-size: 13px; color: var(--ink-4); margin: 6px 0 0; }
.sp-name { font-weight: 600; color: var(--ink-1); }
.sp-desc { font-size: 12px; color: var(--ink-4); margin-top: 2px; }
.muted { color: var(--ink-4); font-size: 12px; }
.empty { text-align: center; color: var(--ink-4); font-size: 13px; padding: 50px 0; }
.form-hint { font-size: 12px; color: var(--ink-4); margin-top: 4px; }
.instr-wrap { width: 100%; }
.instr-tools { display: flex; justify-content: flex-end; margin-bottom: 2px; }
</style>
