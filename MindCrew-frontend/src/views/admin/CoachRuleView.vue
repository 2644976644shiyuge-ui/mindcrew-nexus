<template>
  <div class="cr-page">
    <header class="cr-header">
      <div>
        <h2 class="title">教练规则 <span class="title-tag">教练模式出题模板</span></h2>
        <p class="desc">配置题型配比 + 附加出题规则，知识库设置里下拉直接选用。同一知识库选不同规则，出题风格不同。</p>
      </div>
      <el-button type="primary" :icon="Plus" @click="openDialog(null)">新建教练规则</el-button>
    </header>

    <el-table :data="rows" v-loading="loading" stripe>
      <el-table-column label="规则" min-width="200">
        <template #default="{ row }">
          <div class="cr-name">{{ row.name }}</div>
          <div class="cr-desc">{{ row.description || '-' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="题型配比" min-width="220">
        <template #default="{ row }">
          <span class="muted">{{ quizSummary(row.quizConfig) }}</span>
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
    <div v-if="!loading && !rows.length" class="empty">还没有教练规则，点右上角「新建教练规则」。</div>

    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑教练规则' : '新建教练规则'" width="600px" top="8vh">
      <el-form :model="form" label-position="top">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="如 标准均衡卷" maxlength="30" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input v-model="form.description" placeholder="一句话说明用途" maxlength="60" />
        </el-form-item>
        <el-form-item label="题型配比（每种出几道，全部 0 = 默认均衡）">
          <div class="quiz-row">
            <div class="quiz-item"><span>单选</span>
              <el-input-number v-model="form.single" :min="0" :max="50" size="small" controls-position="right" style="width:104px" /></div>
            <div class="quiz-item"><span>多选</span>
              <el-input-number v-model="form.multiple" :min="0" :max="50" size="small" controls-position="right" style="width:104px" /></div>
            <div class="quiz-item"><span>判断</span>
              <el-input-number v-model="form.judge" :min="0" :max="50" size="small" controls-position="right" style="width:104px" /></div>
            <div class="quiz-item"><span>简答</span>
              <el-input-number v-model="form.short" :min="0" :max="50" size="small" controls-position="right" style="width:104px" /></div>
          </div>
        </el-form-item>
        <el-form-item label="附加规则（可选）">
          <el-input v-model="form.coachRule" type="textarea" :autosize="{ minRows: 4, maxRows: 14 }"
                    placeholder="例：优先考核风控/合规要点；题干口语化……（留空 = 不附加）" />
          <div class="form-hint">出题始终只基于知识库原文，规则只影响"怎么出"，不会让 AI 编造原文外内容。</div>
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
import { coachRuleApi, type CoachRule } from '@/api/coachRule'

const unwrap = (r: any) => r?.data ?? r
const loading = ref(false)
const rows = ref<CoachRule[]>([])

const quizSummary = (cfg?: string): string => {
  if (!cfg) return '默认均衡'
  try {
    const o = JSON.parse(cfg)
    const parts: string[] = []
    if (o.single) parts.push(`单选${o.single}`)
    if (o.multiple) parts.push(`多选${o.multiple}`)
    if (o.judge) parts.push(`判断${o.judge}`)
    if (o.short) parts.push(`简答${o.short}`)
    return parts.length ? parts.join(' / ') : '默认均衡'
  } catch { return '默认均衡' }
}

const load = async () => {
  loading.value = true
  try { rows.value = unwrap(await coachRuleApi.list()) || [] }
  catch (e: any) { ElMessage.error('加载失败：' + (e?.message || '')) }
  finally { loading.value = false }
}
onMounted(load)

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive<{
  id: number | null; name: string; description: string;
  single: number; multiple: number; judge: number; short: number; coachRule: string
}>({ id: null, name: '', description: '', single: 0, multiple: 0, judge: 0, short: 0, coachRule: '' })

const openDialog = (row: CoachRule | null) => {
  if (row) {
    form.id = row.id ?? null
    form.name = row.name || ''
    form.description = row.description || ''
    form.coachRule = row.coachRule || ''
    let cfg: any = {}
    try { cfg = row.quizConfig ? JSON.parse(row.quizConfig) : {} } catch { cfg = {} }
    form.single = Number(cfg.single) || 0
    form.multiple = Number(cfg.multiple) || 0
    form.judge = Number(cfg.judge) || 0
    form.short = Number(cfg.short) || 0
  } else {
    form.id = null; form.name = ''; form.description = ''
    form.single = 0; form.multiple = 0; form.judge = 0; form.short = 0; form.coachRule = ''
  }
  dialogVisible.value = true
}

const save = async () => {
  if (!form.name.trim()) { ElMessage.warning('请输入名称'); return }
  const total = form.single + form.multiple + form.judge + form.short
  const quizConfig = total > 0
    ? JSON.stringify({ single: form.single, multiple: form.multiple, judge: form.judge, short: form.short })
    : ''
  saving.value = true
  try {
    const payload: CoachRule = {
      name: form.name.trim(),
      description: form.description.trim(),
      quizConfig,
      coachRule: form.coachRule.trim(),
    }
    if (form.id) await coachRuleApi.update(form.id, payload)
    else await coachRuleApi.create(payload)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  } finally { saving.value = false }
}

const toggle = async (row: CoachRule, v: boolean) => {
  try { await coachRuleApi.setEnabled(row.id!, v); row.enabled = v ? 1 : 0 }
  catch (e: any) { ElMessage.error('操作失败：' + (e?.message || '')) }
}

const del = async (row: CoachRule) => {
  await ElMessageBox.confirm(`确认删除教练规则「${row.name}」？`, '删除', { type: 'warning', confirmButtonText: '确认删除' })
  try { await coachRuleApi.delete(row.id!); ElMessage.success('已删除'); await load() }
  catch (e: any) { ElMessage.error('删除失败：' + (e?.message || '')) }
}
</script>

<style scoped>
.cr-page { padding: 24px 28px; height: 100%; overflow-y: auto; }
.cr-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 16px; gap: 16px; }
.title { font-size: 20px; font-weight: 700; color: var(--ink-1); display: flex; align-items: center; gap: 10px; }
.title-tag { font-size: 12px; font-weight: 500; color: var(--brand); background: var(--brand-soft); padding: 2px 10px; border-radius: 999px; }
.desc { font-size: 13px; color: var(--ink-4); margin: 6px 0 0; }
.cr-name { font-weight: 600; color: var(--ink-1); }
.cr-desc { font-size: 12px; color: var(--ink-4); margin-top: 2px; }
.muted { color: var(--ink-4); font-size: 12px; }
.empty { text-align: center; color: var(--ink-4); font-size: 13px; padding: 50px 0; }
.form-hint { font-size: 12px; color: var(--ink-4); margin-top: 4px; }
.quiz-row { display: flex; gap: 16px; flex-wrap: wrap; width: 100%; }
.quiz-item { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--ink-2); }
</style>
