<template>
  <div class="knowledge-page page-container">
    <!-- 顶部统计卡 -->
    <div class="stat-strip">
      <div class="stat-item" v-for="s in statCards" :key="s.label">
        <div class="stat-icon-wrap" :style="{ background: s.bg, border: `1px solid ${s.border}` }">
          <el-icon size="16" :color="s.color"><component :is="s.icon" /></el-icon>
        </div>
        <div>
          <div class="stat-val" :style="{ color: s.color }">{{ s.value }}</div>
          <div class="stat-lbl">{{ s.label }}</div>
        </div>
      </div>
    </div>

    <!-- 操作栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-select v-if="SHOW_CATEGORY" v-model="filterCategory" placeholder="全部分类" clearable size="default" style="width:160px" @change="loadList">
          <el-option v-for="c in categoryOptions" :key="c.value" :label="c.label" :value="c.value" />
        </el-select>
        <el-select v-model="filterStatus" placeholder="全部状态" clearable size="default" style="width:130px" @change="loadList">
          <el-option label="处理中" value="processing" />
          <el-option label="等待重建" value="rebuild_queued" />
          <el-option label="重建中" value="rebuilding" />
          <el-option label="就绪"   value="ready" />
          <el-option label="失败"   value="failed" />
          <el-option label="重建失败" value="rebuild_failed" />
        </el-select>
        <el-button :icon="Refresh" @click="loadList" circle />
        <el-button v-if="userStore.isAdmin && SHOW_CATEGORY" text :icon="Setting" @click="openCatManager">管理分类</el-button>
      </div>
      <el-button v-if="userStore.isAdmin" type="primary" @click="openUploadDialog">
        <el-icon><Upload /></el-icon>
        上传文档
      </el-button>
    </div>

    <!-- 批量操作工具栏（选中文档时显示）· 任务 15.2 · 仅管理员 -->
    <transition name="fade">
      <div v-if="userStore.isAdmin && selectedDocIds.length > 0" class="batch-toolbar">
        <span class="batch-info">已选 <b>{{ selectedDocIds.length }}</b> 个文档</span>
        <el-button type="primary" size="small" :icon="FolderOpened" @click="openBatchMoveDialog">
          批量移入知识库
        </el-button>
        <el-button link size="small" @click="clearSelection">取消选择</el-button>
      </div>
    </transition>

    <!-- 知识库卡片网格 -->
    <div v-loading="loading" class="kb-grid">
      <div
        v-for="item in knowledgeList"
        :key="item.id"
        class="kb-card"
        :class="{ selected: isSelected(item.id) }"
        @click="openDetail(item)"
      >
        <!-- 选择框 · 批量移入用 · 仅管理员 -->
        <button v-if="userStore.isAdmin" class="kb-select-box" :class="{ checked: isSelected(item.id) }" @click.stop="toggleSelect(item.id, $event)" title="选择">
          <el-icon v-if="isSelected(item.id)" size="12" color="#fff"><Check /></el-icon>
        </button>

        <!-- 状态指示灯 -->
        <div class="kb-card-header">
          <div class="kb-file-icon">
            <el-icon size="20" :color="getFileColor(item.fileType)"><component :is="getFileIcon(item.fileType)" /></el-icon>
          </div>
          <div class="header-tags">
            <!-- 任务 15.2 · 显示所属知识库（替换原文档级可见性徽标） -->
            <span class="coll-badge" v-if="(item as any).collectionId">
              <el-icon size="11"><FolderOpened /></el-icon>
              <span>{{ collectionNameOf((item as any).collectionId) }}</span>
            </span>
            <span class="coll-badge orphan" v-else>
              <el-icon size="11"><Warning /></el-icon>
              <span>未归档</span>
            </span>
            <el-tag :type="getStatusType(item.status)" size="small" effect="light">
              {{ getStatusLabel(item.status) }}
            </el-tag>
          </div>
        </div>

        <div class="kb-card-body">
          <div class="kb-name" :title="item.name">{{ item.name }}</div>
          <div
            v-if="['failed', 'rebuild_failed'].includes(item.status) && item.errorMsg"
            class="kb-error-inline"
          >
            <div class="kb-error-row" @click.stop="showErrorDetail(item)">
              <el-icon size="12"><WarningFilled /></el-icon>
              <span class="kb-error-text">{{ item.errorMsg }}</span>
              <span class="kb-error-more">查看 ›</span>
            </div>
            <div v-if="userStore.isAdmin" class="kb-error-actions" @click.stop>
              <el-tooltip content="重新处理" placement="top">
                <button class="kb-inline-btn" @click.stop="reprocess(item.id)">
                  <el-icon><Refresh /></el-icon>
                </button>
              </el-tooltip>
              <el-tooltip content="删除" placement="top">
                <button class="kb-inline-btn danger" @click.stop="deleteItem(item.id)">
                  <el-icon><Delete /></el-icon>
                </button>
              </el-tooltip>
            </div>
          </div>
          <div v-else class="kb-desc">{{ item.description || '暂无描述' }}</div>
        </div>

        <div class="kb-card-footer">
          <div class="kb-meta">
            <span v-if="SHOW_CATEGORY" class="meta-tag" :style="{ borderColor: getCategoryColor(item.category) + '55', color: getCategoryColor(item.category) }">
              {{ getCategoryLabel(item.category) }}
              <el-icon v-if="(item as any).categoryUserSet === 1" size="10" class="lock-mark" title="用户手动锁定"><Lock /></el-icon>
              <el-icon v-else-if="item.category" size="10" class="ai-mark" title="AI 推荐"><MagicStick /></el-icon>
            </span>
            <span class="meta-chunks">
              <el-icon size="11"><Document /></el-icon>
              {{ item.chunkCount ?? '-' }} 切片
            </span>
          </div>
          <!-- AI 提取的 tags -->
          <div v-if="parseTags((item as any).tags).length" class="kb-tags-row">
            <span v-for="t in parseTags((item as any).tags).slice(0, 4)" :key="t" class="kb-tag-chip">#{{ t }}</span>
          </div>
          <div class="kb-time">{{ formatDate(item.createTime) }}</div>
        </div>

        <!-- 操作蒙层（失败状态不显示蒙层，按钮已 inline 显示）· 普通用户仅可查看详情 -->
        <div v-if="!['failed', 'rebuild_failed'].includes(item.status)" class="kb-card-overlay" @click.stop="openDetail(item)">
          <el-tooltip content="查看详情">
            <button class="overlay-btn primary" @click.stop="openDetail(item)">
              <el-icon><View /></el-icon>
              <span class="overlay-btn-label">查看</span>
            </button>
          </el-tooltip>
          <template v-if="userStore.isAdmin">
            <el-tooltip content="详情 / 可见性 / ACL 授权">
              <button class="overlay-btn" @click.stop="openDetail(item)">
                <el-icon><Setting /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip v-if="item.status === 'ready'" content="重建检索索引（应用最新切片策略）">
              <button class="overlay-btn" @click.stop="rebuildIndex(item.id)">
                <el-icon><Refresh /></el-icon>
              </button>
            </el-tooltip>
            <el-tooltip content="删除">
              <button class="overlay-btn danger" @click.stop="deleteItem(item.id)">
                <el-icon><Delete /></el-icon>
              </button>
            </el-tooltip>
          </template>
        </div>
      </div>

      <!-- 上传卡（仅管理员） -->
      <div v-if="userStore.isAdmin" class="kb-card upload-placeholder" @click="openUploadDialog">
        <el-icon size="28" color="#334155"><Plus /></el-icon>
        <span>上传新文档</span>
      </div>
    </div>

    <!-- 空状态 -->
    <div v-if="!loading && knowledgeList.length === 0" class="empty-state">
      <el-icon size="48" color="#1e293b"><FolderOpened /></el-icon>
      <p class="empty-title">知识库为空</p>
      <p class="empty-desc">上传文档后，{{ brandStore.systemName }} 将自动解析并建立向量索引</p>
      <el-button v-if="userStore.isAdmin" type="primary" @click="openUploadDialog">立即上传</el-button>
      <p v-else class="empty-desc">请联系管理员上传文档</p>
    </div>

    <!-- 分页 -->
    <div class="pagination" v-if="total > pageSize">
      <el-pagination
        v-model:current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        background
        layout="prev, pager, next"
        @current-change="loadList"
      />
    </div>

    <!-- ===== 上传弹窗 ===== -->
    <!-- ===== 上传弹窗已抽成全局组件 GlobalUploadDialog · 见 MainLayout · 此处占位避免破坏旧代码 ===== -->
    <el-dialog v-if="false" v-model="uploadDialogVisible" title="（已迁移到全局组件）" width="720px">
      <el-form ref="uploadFormRef" :model="uploadForm" :rules="uploadRules" label-width="90px">
        <!-- 拖拽上传区（支持多文件 + 文件夹） -->
        <el-form-item prop="file">
          <div
            class="drop-zone"
            :class="{ dragging: isDragging }"
            @dragover.prevent="isDragging = true"
            @dragleave.prevent="isDragging = false"
            @drop.prevent="handleDrop"
            @click="triggerFileInput"
          >
            <input ref="fileInputRef" type="file" multiple accept=".pdf,.docx,.doc,.pptx,.ppt,.xlsx,.xls,.csv,.wps,.html,.htm,.jpg,.jpeg,.png,.webp,.bmp,.gif,.mp3,.wav,.m4a,.aac,.flac,.opus,.ogg,.amr,.mp4,.mov,.mkv,.avi,.flv,.webm,.m4v,.txt,.md,.markdown" style="display:none" @change="handleFileChange" />
            <input ref="folderInputRef" type="file" multiple webkitdirectory directory style="display:none" @change="handleFileChange" />
            <el-icon size="32" color="#334155"><UploadFilled /></el-icon>
            <div class="drop-text">
              拖拽文件 / 文件夹到此，或<span class="drop-link">点击选择文件</span>
              <span class="drop-link" @click.stop="triggerFolderInput" style="margin-left:8px">选择文件夹</span>
            </div>
            <div class="drop-hint">支持 PDF / Word / PowerPoint / Excel / WPS / HTML / 图片 / <strong>音频</strong> / <strong>视频</strong> / 文本（最多 100 个，单文件 ≤ 500MB）</div>
          </div>
        </el-form-item>

        <el-form-item label="默认分类">
          <el-select v-model="uploadForm.category" placeholder="留空 · 由 AI 自动判断" clearable style="width:100%">
            <el-option v-for="c in categoryOptions" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
          <div style="font-size:11px;color:#94a3b8;margin-top:4px">
            队列中已加入的文件不受此处变更影响，新拖入的文件会沿用此分类。
          </div>
        </el-form-item>

        <el-form-item label="默认描述">
          <el-input v-model="uploadForm.description" type="textarea" :rows="2" placeholder="简要描述（可选 · 留空则后端自动生成）" />
        </el-form-item>

        <!-- 上传队列表（核心改造） -->
        <el-form-item v-if="uploadStore.queue.length > 0" label="队列">
          <div class="queue-toolbar">
            <span class="queue-stat">
              总 {{ uploadStore.stats.total }} ·
              <span style="color:#38bdf8">进行 {{ uploadStore.stats.uploading + uploadStore.stats.pending }}</span> ·
              <span style="color:#34d399">完成 {{ uploadStore.stats.done }}</span>
              <span v-if="uploadStore.stats.failed > 0" style="color:#ef4444"> · 失败 {{ uploadStore.stats.failed }}</span>
            </span>
            <el-button v-if="!uploadStore.paused" link size="small" type="warning"
                       :disabled="uploadStore.stats.pending === 0" @click="uploadStore.pauseUploads">暂停</el-button>
            <el-button v-else link size="small" type="success" @click="uploadStore.resumeUploads">继续</el-button>
            <el-button link size="small" :disabled="uploadStore.stats.done === 0" @click="uploadStore.clearDone">清除已完成</el-button>
            <el-button link size="small" :disabled="uploadStore.hasActive" @click="uploadStore.clearAll" type="danger">清空</el-button>
          </div>
          <div class="queue-list">
            <div v-for="row in uploadStore.queue" :key="row.id" class="queue-row" :class="'st-' + row.status">
              <el-icon size="16" :color="getFileColor(row.ext)" class="qr-icon"><component :is="getFileIcon(row.ext)" /></el-icon>
              <div class="qr-main">
                <div class="qr-name" :title="row.name">{{ row.name }}</div>
                <div class="qr-meta">
                  <span>{{ formatFileSize(row.size) }}</span>
                  <span class="qr-status">
                    <span v-if="row.status === 'pending'">待上传</span>
                    <span v-else-if="row.status === 'uploading'">上传中 {{ row.progress }}%</span>
                    <span v-else-if="row.status === 'processing'">服务端处理中</span>
                    <span v-else-if="row.status === 'done'" style="color:#34d399">✓ 成功</span>
                    <span v-else-if="row.status === 'failed'" style="color:#ef4444" :title="row.errorMsg">✗ {{ row.errorMsg || '失败' }}</span>
                  </span>
                </div>
                <el-progress
                  v-if="row.status === 'uploading' || row.status === 'pending'"
                  :percentage="row.progress"
                  :show-text="false"
                  :stroke-width="3"
                  color="#38bdf8"
                />
              </div>
              <div class="qr-actions">
                <el-button v-if="row.status === 'failed'" link size="small" @click="uploadStore.retry(row.id)">重试</el-button>
                <el-button
                  v-if="row.status !== 'uploading'"
                  link
                  size="small"
                  type="danger"
                  @click="uploadStore.removeItem(row.id)"
                >移除</el-button>
              </div>
            </div>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="uploadDialogVisible = false">
          {{ uploadStore.hasActive ? '后台运行' : '关闭' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== 详情弹窗 ===== -->
    <el-dialog v-model="detailVisible" title="文档详情" width="520px">
      <div v-if="currentDoc" class="detail-grid">
        <div class="detail-row span2">
          <span class="detail-key">文档名称</span>
          <span class="detail-val">{{ currentDoc.name }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-key">文件类型</span>
          <span class="detail-val">{{ currentDoc.fileType?.toUpperCase() || '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-key">文件大小</span>
          <span class="detail-val">{{ formatFileSize(currentDoc.fileSize) }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-key">切片数量</span>
          <span class="detail-val" style="color:#38bdf8;font-weight:700">{{ currentDoc.chunkCount ?? '-' }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-key">状态</span>
          <span class="detail-val">
            <el-tag :type="getStatusType(currentDoc.status)" size="small">{{ getStatusLabel(currentDoc.status) }}</el-tag>
          </span>
        </div>
        <div class="detail-row">
          <span class="detail-key">上传时间</span>
          <span class="detail-val">{{ formatDate(currentDoc.createTime) }}</span>
        </div>
        <div class="detail-row span2">
          <span class="detail-key">描述</span>
          <span class="detail-val">{{ currentDoc.description || '暂无' }}</span>
        </div>

        <!-- 任务 15.2 · 文档不再独立设置权限，权限继承自所属知识库 -->
        <div class="detail-row span2 inherit-tip">
          <span class="detail-key">
            <el-icon size="13"><Lock /></el-icon>
            <span>权限来源</span>
          </span>
          <span class="detail-val">
            <span v-if="(currentDoc as any).collectionId" style="font-size:12.5px;color:var(--ink-2)">
              ⓘ 此文档继承所属知识库
              <el-button
                link
                size="small"
                @click="goToCollection((currentDoc as any).collectionId)"
              >「{{ collectionNameOf((currentDoc as any).collectionId) }}」</el-button>
              的可见性与授权配置
            </span>
            <span v-else style="font-size:12.5px;color:#b45309">
              ⚠ 未归档散文档 · 仅创建者可见 ·
              <el-button v-if="userStore.isAdmin" link size="small" type="primary" @click="openMoveDialog(currentDoc)">
                移入知识库
              </el-button>
            </span>
          </span>
        </div>
        <div v-if="(currentDoc as any).summary" class="detail-row span2">
          <span class="detail-key">AI 摘要</span>
          <span class="detail-val" style="line-height:1.6">{{ (currentDoc as any).summary }}</span>
        </div>
        <div v-if="parseTags((currentDoc as any).tags).length" class="detail-row span2">
          <span class="detail-key">AI 标签</span>
          <span class="detail-val">
            <el-tag
              v-for="t in parseTags((currentDoc as any).tags)"
              :key="t"
              size="small"
              effect="plain"
              style="margin:2px 6px 2px 0"
            >#{{ t }}</el-tag>
          </span>
        </div>
        <div class="detail-row span2">
          <span class="detail-key">原文件</span>
          <span class="detail-val">
            <el-button size="small" type="primary" plain :icon="Download" :loading="downloading"
                       @click="downloadDoc(currentDoc!.id)">下载原文件</el-button>
          </span>
        </div>
        <div class="detail-row span2" v-if="currentDoc.status === 'ready'">
          <span class="detail-key">操作</span>
          <span class="detail-val" style="display:flex;gap:8px;flex-wrap:wrap;align-items:center">
            <el-button v-if="userStore.isAdmin && SHOW_CATEGORY" size="small" :icon="MagicStick" @click="reclassifyDoc(currentDoc.id)">
              重新分类
            </el-button>
            <el-button v-if="userStore.isAdmin" size="small" type="primary" :icon="FolderOpened" @click="openMoveDialog(currentDoc)">
              移入知识库
            </el-button>
            <span style="font-size:11px;color:var(--ink-3)">API 接入 / 权限配置 → 请到对应知识库设置</span>
          </span>
        </div>
        <div v-if="currentDoc.errorMsg" class="detail-row span2 error-block">
          <span class="detail-key" style="color:#f87171">⚠ 失败原因</span>
          <span class="detail-val error-msg">{{ currentDoc.errorMsg }}</span>
        </div>
      </div>
    </el-dialog>

    <!-- ===== 任务 15.2 · 移入知识库弹窗 ===== -->
    <el-dialog v-model="moveDialogVisible" :title="`移入知识库 · ${moveTargetDoc?.name || ''}`" width="480px">
      <el-form label-width="80px">
        <el-form-item label="目标库">
          <el-select v-model="moveTargetCollectionId" placeholder="选择目标知识库" filterable style="width:100%">
            <el-option v-for="c in allCollections" :key="c.id" :label="`${c.name} (${c.docCount} 文档)`" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="batchMoveIds.length > 1">
          <span style="font-size:12px;color:var(--ink-3)">将批量移入 {{ batchMoveIds.length }} 个文档</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moveDialogVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!moveTargetCollectionId" @click="confirmMove">确认移入</el-button>
      </template>
    </el-dialog>

    <!-- 保留：旧 API Key 弹窗（已隐藏，避免破坏旧引用）-->
    <el-dialog v-if="false" v-model="apiKeyDialogVisible" :title="`API 接入 · ${apiKeyTargetKb?.name || ''}`" width="780px" :close-on-click-modal="false" class="api-key-dialog">
      <div class="api-hint">
        每个知识库可以颁发<strong>独立的 API Key</strong>给第三方系统接入。Key 只能访问本 KB 的数据，
        按月配额计费，可随时吊销。<span class="api-hint-warn">⚠ 完整 key 仅在生成时显示一次，请妥善保管。</span>
      </div>

      <div class="api-keys-list" v-loading="apiKeysLoading">
        <article v-for="k in apiKeys" :key="k.id" class="api-key-card" :class="`status-${k.status}`">
          <header>
            <div class="ak-name">{{ k.name }}</div>
            <span class="ak-status" :class="`s-${k.status}`">{{ statusLabel(k.status) }}</span>
          </header>
          <div class="ak-prefix">
            <code>{{ k.keyPrefix }}······</code>
            <span class="ak-prefix-tip">完整 key 仅生成时可见 · 已隐藏</span>
          </div>
          <div class="ak-meta">
            <span>本月: <b>{{ k.monthUsed }}</b> / {{ k.monthlyQuota }}</span>
            <span>累计: {{ k.totalCalls }}</span>
            <span v-if="k.lastUsedAt">最近调用: {{ formatDate(k.lastUsedAt as string) }}</span>
          </div>
          <div class="ak-actions">
            <el-button size="small" :icon="DocumentCopy" @click="showCurlExample(k)">查看接入示例</el-button>
            <el-button size="small" type="danger" plain :icon="Close" :disabled="k.status !== 'active'" @click="revokeKey(k)">
              吊销
            </el-button>
          </div>
        </article>
        <div v-if="!apiKeysLoading && apiKeys.length === 0" class="empty">
          该知识库还没有 API Key · 点击下方按钮生成第一个
        </div>
      </div>

      <template #footer>
        <el-button @click="apiKeyDialogVisible = false">关闭</el-button>
        <el-button type="primary" :icon="Plus" @click="openIssueDialog">
          生成新 API Key
        </el-button>
      </template>
    </el-dialog>

    <!-- 任务 11.6 · 生成新 Key 弹窗 -->
    <el-dialog v-model="issueDialogVisible" :title="`为「${apiKeyTargetKb?.name}」生成 API Key`" width="560px" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="issueForm.name" placeholder="如 客户 X 接入 · 内部 CRM 集成" maxlength="80" />
        </el-form-item>
        <el-form-item label="月调用上限">
          <el-input-number v-model="issueForm.monthlyQuota" :min="100" :max="1000000" :step="1000" style="width:180px" />
          <span style="margin-left:8px;color:var(--ink-3);font-size:12px">超出后该 key 当月停用</span>
        </el-form-item>
        <el-form-item label="过期时间">
          <el-date-picker v-model="issueForm.expireAt" type="datetime" placeholder="留空 = 永不过期" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="issueForm.description" type="textarea" :rows="2" placeholder="给运维 / 自己看的备注 · 选填" maxlength="300" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="issueDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="issuing" @click="confirmIssue">生成 Key</el-button>
      </template>
    </el-dialog>

    <!-- 任务 11.6 · 生成结果展示（完整 key 仅此一次） -->
    <el-dialog v-model="issuedResultVisible" title="⚠ 完整 API Key（仅显示一次）" width="640px" :close-on-click-modal="false" :show-close="false">
      <div class="issued-warn">
        <el-icon :size="18"><WarningFilled /></el-icon>
        <span>请立即复制保存。关闭后此完整 key 永久不可见，DB 仅存 SHA-256 hash。</span>
      </div>
      <div class="issued-key">
        <code>{{ issuedRawKey }}</code>
        <el-button size="small" type="primary" :icon="DocumentCopy" @click="copyRawKey">复制</el-button>
      </div>
      <div class="issued-curl">
        <div class="curl-title">接入示例（curl）：</div>
        <pre>{{ buildCurlExample(issuedRawKey, apiKeyTargetKb?.id) }}</pre>
      </div>
      <template #footer>
        <el-button type="primary" @click="closeIssuedResult">我已复制保存</el-button>
      </template>
    </el-dialog>

    <!-- 接入示例查看（旧 key） -->
    <el-dialog v-model="curlExampleVisible" title="接入示例" width="640px">
      <div class="issued-warn" style="background:rgba(245,158,11,0.08);border-color:rgba(245,158,11,0.3);color:#b45309">
        <el-icon :size="14"><InfoFilled /></el-icon>
        <span>完整 key 已隐藏。示例中的 <code>YOUR_API_KEY</code> 请替换为你保存的完整 key。</span>
      </div>
      <div class="issued-curl">
        <div class="curl-title">/v3/chat · 问答</div>
        <pre>{{ buildCurlExample('YOUR_API_KEY', apiKeyTargetKb?.id) }}</pre>
        <div class="curl-title" style="margin-top:14px">/v3/search · 纯检索</div>
        <pre>{{ buildSearchExample('YOUR_API_KEY', apiKeyTargetKb?.id) }}</pre>
      </div>
    </el-dialog>

    <!-- 分类管理（管理员）-->
    <el-dialog v-if="SHOW_CATEGORY" v-model="catMgrVisible" title="管理文档分类" width="560px">
      <p class="cat-hint">分类用于文档归类与筛选。删除分类不影响已用该分类的文档。</p>

      <!-- 新增 -->
      <div class="cat-add">
        <el-color-picker v-model="newCat.color" size="default" />
        <el-input v-model="newCat.name" placeholder="分类名称，如「合同」" style="flex:1" maxlength="20" />
        <el-input v-model="newCat.code" placeholder="代码（选填，留空自动用名称）" style="width:200px" />
        <el-button type="primary" :icon="Plus" :loading="catSaving" @click="addCategory">添加</el-button>
      </div>

      <!-- 列表 -->
      <div class="cat-list">
        <div v-for="c in rawCategories" :key="c.id" class="cat-row">
          <el-color-picker v-model="c.color" size="small" @change="saveCategory(c)" />
          <el-input v-model="c.name" size="small" style="flex:1" @blur="saveCategory(c)" />
          <code class="cat-code">{{ c.code }}</code>
          <el-button text type="danger" :icon="Delete" @click="deleteCategory(c)" />
        </div>
        <div v-if="!rawCategories.length" class="cat-empty">暂无分类，先在上方添加一个。</div>
      </div>

      <template #footer>
        <el-button type="primary" @click="catMgrVisible = false">完成</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { useUploadStore } from '@/stores/upload'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  Refresh, Upload, Delete, Plus, Document, FolderOpened, Warning,
  UploadFilled, Close, MagicStick, WarningFilled, Setting, Check,
  Link, DocumentCopy, InfoFilled, Download,
  View, Lock, Hide, Tickets, User, OfficeBuilding, CircleCheckFilled
} from '@element-plus/icons-vue'
import { knowledgeApi, type KnowledgeBase } from '@/api/knowledge'
import { kbCategoryApi, type KbCategory } from '@/api/kbCategory'
import { useUserStore } from '@/stores/user'
import { positionApi, departmentApi, kbAclApi, type Position, type Department } from '@/api/orgAcl'
import { apiKeyApi, type ApiKey } from '@/api/apiKey'
import { collectionApi, type KnowledgeCollection } from '@/api/collection'
import { useBrandStore } from '@/stores/brand'

const brandStore = useBrandStore()

// ── 状态 ──
// 任务 15.2 · 所有可用知识库（移入操作用）
const allCollections = ref<KnowledgeCollection[]>([])
const collectionMap = computed<Record<number, string>>(() => {
  const m: Record<number, string> = {}
  allCollections.value.forEach(c => { m[c.id] = c.name })
  return m
})
const collectionNameOf = (id?: number) => id ? (collectionMap.value[id] || `知识库 #${id}`) : '-'

// 移入弹窗
const moveDialogVisible = ref(false)
const moveTargetDoc = ref<KnowledgeBase | null>(null)
const moveTargetCollectionId = ref<number | null>(null)
const batchMoveIds = ref<number[]>([])
const router = useRouter()

const openMoveDialog = (doc: KnowledgeBase) => {
  moveTargetDoc.value = doc
  batchMoveIds.value = [doc.id]
  moveTargetCollectionId.value = null
  moveDialogVisible.value = true
}
const openBatchMoveDialog = () => {
  if (!selectedDocIds.value.length) return
  moveTargetDoc.value = null
  batchMoveIds.value = [...selectedDocIds.value]
  moveTargetCollectionId.value = null
  moveDialogVisible.value = true
}
const confirmMove = async () => {
  if (!moveTargetCollectionId.value || !batchMoveIds.value.length) return
  try {
    await collectionApi.assign(moveTargetCollectionId.value, batchMoveIds.value)
    ElMessage.success(`已移入「${collectionNameOf(moveTargetCollectionId.value)}」`)
    moveDialogVisible.value = false
    selectedDocIds.value = []
    await Promise.all([loadList(), loadCollections()])
  } catch (e: any) {
    ElMessage.error('移入失败：' + (e?.response?.data?.message || e?.message || ''))
  }
}

const loadCollections = async () => {
  try {
    const res: any = await collectionApi.list()
    allCollections.value = res?.data ?? res ?? []
  } catch { /* ignore */ }
}

const goToCollection = (id: number) => { router.push(`/collections/${id}`) }

// 批量选择
const selectedDocIds = ref<number[]>([])
const isSelected = (id: number) => selectedDocIds.value.includes(id)
const toggleSelect = (id: number, e?: Event) => {
  e?.stopPropagation()
  const i = selectedDocIds.value.indexOf(id)
  if (i >= 0) selectedDocIds.value.splice(i, 1)
  else selectedDocIds.value.push(id)
}
const clearSelection = () => { selectedDocIds.value = [] }

// 从「所有文档」页打开上传弹窗 · 清空目标库 = 散文档模式
const openUploadDialog = () => {
  uploadStore.targetCollectionId = null
  uploadStore.dialogVisible = true
}

const loading     = ref(false)
const knowledgeList = ref<KnowledgeBase[]>([])
const total       = ref(0)
const currentPage = ref(1)
const pageSize    = ref(24)
// 文档分类功能暂时下线（置 true 即可恢复全部分类相关 UI）
const SHOW_CATEGORY = false
const filterCategory = ref('')
const filterStatus   = ref('')

// ⭐ 全局上传队列 store（任务 1.10 改造）
const uploadStore = useUploadStore()
// 任一文件成功后刷新知识库列表
let unregisterUploadCb: (() => void) | null = null

// 上传弹窗的 v-model 直接指向 store 的 dialogVisible（用 computed proxy）
// 这样 dock 点「打开完整队列」时，KnowledgeView 弹窗能同步弹出
const uploadDialogVisible = computed({
  get: () => uploadStore.dialogVisible,
  set: (v: boolean) => { uploadStore.dialogVisible = v },
})
const detailVisible       = ref(false)
const isDragging   = ref(false)
const fileInputRef = ref<HTMLInputElement>()
const folderInputRef = ref<HTMLInputElement>()
const uploadFormRef = ref<FormInstance>()
const currentDoc   = ref<KnowledgeBase | null>(null)

const uploadForm = ref({ category: '', description: '' })
const uploadRules: FormRules = {}   // category 改为可空，留空时由 LLM 自动分类

// 分类选项 · 动态从后端加载，失败时降级到内置兜底
const categoryOptions = ref<{ label: string; value: string; color?: string }[]>([
  { label: '技术', value: 'tech' },
  { label: '产品', value: 'product' },
  { label: '法务', value: 'legal' },
  { label: '财务', value: 'finance' },
  { label: '培训', value: 'training' },
  { label: '人事', value: 'hr' },
  { label: '客户', value: 'customer' },
  { label: '其他', value: 'other' },
])

// ───────── 分类管理（管理员）─────────
const userStore = useUserStore()
const rawCategories = ref<KbCategory[]>([])
const catMgrVisible = ref(false)
const catSaving = ref(false)
const newCat = reactive<{ name: string; code: string; color: string }>({ name: '', code: '', color: '#6366f1' })

const openCatManager = () => {
  newCat.name = ''; newCat.code = ''; newCat.color = '#6366f1'
  catMgrVisible.value = true
}

const addCategory = async () => {
  const name = newCat.name.trim()
  const code = newCat.code.trim() || name   // code 留空用名称兜底
  if (!name) { ElMessage.warning('请输入分类名称'); return }
  catSaving.value = true
  try {
    await kbCategoryApi.create({ name, code, color: newCat.color, sortOrder: rawCategories.value.length })
    ElMessage.success('已添加')
    newCat.name = ''; newCat.code = ''
    await loadCategoryDict()
  } catch (e: any) {
    ElMessage.error('添加失败：' + (e?.message || ''))
  } finally { catSaving.value = false }
}

const saveCategory = async (c: KbCategory) => {
  if (!c.id) return
  try {
    await kbCategoryApi.update(c.id, { name: c.name, code: c.code, color: c.color, sortOrder: c.sortOrder })
    ElMessage.success('已保存')
    await loadCategoryDict()
  } catch (e: any) {
    ElMessage.error('保存失败：' + (e?.message || ''))
  }
}

const deleteCategory = async (c: KbCategory) => {
  if (!c.id) return
  await ElMessageBox.confirm(
    `删除分类「${c.name}」？\n已用此分类的文档不受影响（仍保留该分类标签），只是新建/筛选时不再出现。`,
    '删除分类', { type: 'warning', confirmButtonText: '确认删除' }
  )
  try {
    await kbCategoryApi.delete(c.id)
    ElMessage.success('已删除')
    await loadCategoryDict()
  } catch (e: any) {
    ElMessage.error('删除失败：' + (e?.message || ''))
  }
}

// 全量统计（后端聚合，与列表同一 ACL 作用域）· 不再用当前页估算
const docStats = ref({ total: 0, ready: 0, processing: 0, failed: 0, totalChunks: 0 })

const statCards = computed(() => {
  const s = docStats.value
  return [
    { label: '文档总数', value: s.total,       icon: 'FolderOpened', color: '#38bdf8', bg: 'rgba(56,189,248,0.1)', border: 'rgba(56,189,248,0.2)' },
    { label: '已就绪',   value: s.ready,       icon: 'CircleCheck',  color: '#34d399', bg: 'rgba(52,211,153,0.1)', border: 'rgba(52,211,153,0.2)' },
    { label: '处理中',   value: s.processing,  icon: 'Loading',      color: '#fbbf24', bg: 'rgba(251,191,36,0.1)', border: 'rgba(251,191,36,0.2)' },
    { label: '总切片数', value: s.totalChunks, icon: 'Document',     color: '#71717a', bg: 'rgba(129,140,248,0.1)', border: 'rgba(129,140,248,0.2)' },
  ]
})

const loadStats = async () => {
  try {
    const res: any = await knowledgeApi.stats()
    const d = res?.data ?? res ?? {}
    docStats.value = {
      total:       Number(d.total ?? 0),
      ready:       Number(d.ready ?? 0),
      processing:  Number(d.processing ?? 0),
      failed:      Number(d.failed ?? 0),
      totalChunks: Number(d.totalChunks ?? 0),
    }
  } catch { /* 统计失败不影响列表 */ }
}

const loadList = async () => {
  loading.value = true
  try {
    const res = await knowledgeApi.list({
      current:  currentPage.value,
      size:     pageSize.value,
      category: filterCategory.value || undefined,
      status:   filterStatus.value || undefined,
    })
    knowledgeList.value = res.records || res || []
    total.value = res.total ?? knowledgeList.value.length
    loadStats()   // 顶部卡片用全量统计，与列表分页解耦
  } finally {
    loading.value = false
  }
}

// 加载分类字典（失败保留内置兜底）· 抽成函数，分类管理后可刷新
const loadCategoryDict = async () => {
  try {
    const res: any = await kbCategoryApi.list()
    const arr = res?.data ?? res ?? []
    if (Array.isArray(arr)) {
      rawCategories.value = arr
      if (arr.length > 0) {
        categoryOptions.value = arr.map((c: any) => ({ label: c.name, value: c.code, color: c.color }))
      }
    }
  } catch { /* keep fallback */ }
}

onMounted(async () => {
  await loadCategoryDict()
  loadList()
  loadCollections()    // 任务 15.2 · 加载知识库列表用于「移入」操作
  // 注册上传完成回调 · 单个文件成功后立刻刷新知识库列表
  unregisterUploadCb = uploadStore.onItemDone(() => loadList())
})

onBeforeUnmount(() => {
  // 离开页面解绑 · 队列本身在 store 里继续跑，dock 接管展示
  unregisterUploadCb?.()
})

const openDetail = (item: KnowledgeBase) => {
  currentDoc.value = item
  detailVisible.value = true
  // 任务 7 · 详情打开时加载 ACL
  loadAclForCurrentDoc()
}

// 下载原文件（OSS 预签名直链 / 本地代理）
const downloading = ref(false)
const downloadDoc = async (id: number) => {
  downloading.value = true
  try {
    const res: any = await knowledgeApi.downloadUrl(id)
    let url = res?.data ?? res
    if (!url) { ElMessage.error('原文件不存在或已清理'); return }
    // 本地代理下载(proxy-fetch)需鉴权：新开页不带 Authorization 头 → 用 ?token= 透传（JWT 过滤器支持）
    if (typeof url === 'string' && url.includes('/proxy-fetch')) {
      const token = localStorage.getItem('token') || ''
      url += (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token)
    }
    window.open(url, '_blank')
  } catch (e: any) {
    ElMessage.error('获取下载链接失败：' + (e?.message || ''))
  } finally { downloading.value = false }
}

// ─────────────────────────────────────────────
// 任务 7 · 可见性 + ACL 配置（支持 职位级 + 部门级）
// ─────────────────────────────────────────────
interface AclEntry {
  positionId?: number | null
  departmentId?: number | null
  permission: string
}

const aclLoading = ref(false)
const aclEntries = ref<AclEntry[]>([])
const allPositions = ref<Position[]>([])
const allDepartments = ref<Department[]>([])
const newAclPositionId   = ref<number | null>(null)
const newAclDepartmentId = ref<number | null>(null)
const newAclPermission   = ref('read')
const addSubjectType     = ref<'position' | 'department'>('position')

const positionLabel = (id: number) => {
  const p = allPositions.value.find(x => x.id === id)
  return p ? `${p.name} (${p.code})` : `#${id}`
}
const departmentLabel = (id: number) => {
  const d = allDepartments.value.find(x => x.id === id)
  return d ? d.name : `#${id}`
}
const aclSubjectLabel = (e: AclEntry) =>
  e.departmentId ? departmentLabel(e.departmentId) : positionLabel(e.positionId as number)

const availableAclPositions = computed(() => {
  const granted = new Set(aclEntries.value.filter(e => e.positionId).map(e => e.positionId))
  return allPositions.value.filter(p => !granted.has(p.id))
})
const availableAclDepartments = computed(() => {
  const granted = new Set(aclEntries.value.filter(e => e.departmentId).map(e => e.departmentId))
  return allDepartments.value.filter(d => !granted.has(d.id))
})

const loadAclForCurrentDoc = async () => {
  if (!currentDoc.value) return
  // 先确保 职位 + 部门 字典都已加载
  if (allPositions.value.length === 0 || allDepartments.value.length === 0) {
    try {
      const [pRes, dRes]: any = await Promise.all([positionApi.list(), departmentApi.list()])
      allPositions.value   = pRes?.data ?? pRes ?? []
      allDepartments.value = dRes?.data ?? dRes ?? []
    } catch { /* 字典加载失败不阻塞 */ }
  }
  if (((currentDoc.value as any).visibility || 'public') !== 'scoped') {
    aclEntries.value = []
    return
  }
  aclLoading.value = true
  try {
    const res: any = await kbAclApi.list(currentDoc.value.id)
    const list = res?.data ?? res ?? []
    aclEntries.value = list.map((a: any) => ({
      positionId:   a.positionId   ?? null,
      departmentId: a.departmentId ?? null,
      permission:   a.permission,
    }))
  } catch (e: any) {
    ElMessage.error('加载 ACL 失败：' + (e?.message || ''))
    aclEntries.value = []
  } finally {
    aclLoading.value = false
  }
}

const changeVisibility = async (id: number, v: string) => {
  try {
    await knowledgeApi.updateVisibility(id, v)
    if (currentDoc.value) (currentDoc.value as any).visibility = v
    ElMessage.success(`可见性已切换为 ${v}`)
    if (v === 'scoped') await loadAclForCurrentDoc()
    loadList()
  } catch (e: any) {
    ElMessage.error('切换失败：' + (e?.message || ''))
  }
}

const persistAcl = async () => {
  if (!currentDoc.value) return
  try {
    // 转成后端期望格式 (AclEntry[])
    const payload = aclEntries.value.map(e => ({
      positionId:   e.positionId   ?? null,
      departmentId: e.departmentId ?? null,
      permission:   e.permission as 'read' | 'write' | 'admin',
    }))
    await kbAclApi.replace(currentDoc.value.id, payload)
  } catch (e: any) {
    ElMessage.error('保存授权失败：' + (e?.message || ''))
  }
}

const addAclEntry = async () => {
  if (addSubjectType.value === 'position') {
    if (!newAclPositionId.value) return
    aclEntries.value.push({
      positionId:   newAclPositionId.value,
      departmentId: null,
      permission:   newAclPermission.value,
    })
    newAclPositionId.value = null
  } else {
    if (!newAclDepartmentId.value) return
    aclEntries.value.push({
      positionId:   null,
      departmentId: newAclDepartmentId.value,
      permission:   newAclPermission.value,
    })
    newAclDepartmentId.value = null
  }
  newAclPermission.value = 'read'
  await persistAcl()
  ElMessage.success('已添加授权')
}

const removeAclEntryAt = async (idx: number) => {
  aclEntries.value.splice(idx, 1)
  await persistAcl()
  ElMessage.success('已撤销授权')
}

const onAclEntryChange = persistAcl

// 任务 7 · 可见性徽标 · 用 Element Plus 图标代替 emoji
const visibilityOptions = [
  { value: 'public',  title: '公开',    desc: '所有登录用户可读',     icon: 'View' },
  { value: 'scoped',  title: '按职位',  desc: '通过 ACL 控制访问',    icon: 'Lock' },
  { value: 'private', title: '私有',    desc: '仅创建者可见',         icon: 'Hide' },
]
const visibilityLabel = (v?: string) => {
  const x = v || 'public'
  return ({ public: '公开', scoped: '按职位', private: '私有' } as any)[x] || x
}
const visibilityTip = (v?: string) => {
  const x = v || 'public'
  return ({
    public: '所有登录用户可读',
    scoped: '按职位 ACL 控制访问',
    private: '仅创建者可见',
  } as any)[x] || ''
}
const visibilityIcon = (v?: string) => {
  const x = v || 'public'
  return ({ public: 'View', scoped: 'Lock', private: 'Hide' } as any)[x] || 'View'
}

// ─────────────────────────────────────────────
// 任务 11.6 · KB 专属 API Key 管理
// ─────────────────────────────────────────────
const apiKeyDialogVisible = ref(false)
const apiKeyTargetKb = ref<KnowledgeBase | null>(null)
const apiKeysLoading = ref(false)
const apiKeys = ref<ApiKey[]>([])

const openApiAccessDialog = async (kb: KnowledgeBase) => {
  apiKeyTargetKb.value = kb
  apiKeyDialogVisible.value = true
  await loadApiKeysForKb(kb.id)
}

const loadApiKeysForKb = async (kbId: number) => {
  apiKeysLoading.value = true
  try {
    const res: any = await apiKeyApi.byKb(kbId)
    apiKeys.value = res?.data ?? res ?? []
  } catch (e: any) {
    ElMessage.error('加载 API Keys 失败：' + (e?.message || ''))
    apiKeys.value = []
  } finally {
    apiKeysLoading.value = false
  }
}

const statusLabel = (s: string) =>
  ({ active: '✓ 启用中', revoked: '✕ 已吊销', expired: '⏰ 已过期' } as any)[s] || s

const revokeKey = async (k: ApiKey) => {
  try {
    await ElMessageBox.confirm(`确认吊销 API Key「${k.name}」？该 key 立即失效，无法恢复。`, '警告', { type: 'warning' })
    await apiKeyApi.revoke(k.id)
    ElMessage.success('已吊销')
    await loadApiKeysForKb(apiKeyTargetKb.value!.id)
  } catch (e: any) {
    if (e !== 'cancel') ElMessage.error('吊销失败：' + (e?.message || ''))
  }
}

// 生成 key 弹窗
const issueDialogVisible = ref(false)
const issuing = ref(false)
const issueForm = reactive<{
  name: string; monthlyQuota: number; expireAt: string | null; description: string
}>({ name: '', monthlyQuota: 10000, expireAt: null, description: '' })

const openIssueDialog = () => {
  issueForm.name = ''
  issueForm.monthlyQuota = 10000
  issueForm.expireAt = null
  issueForm.description = ''
  issueDialogVisible.value = true
}

const issuedResultVisible = ref(false)
const issuedRawKey = ref('')

const confirmIssue = async () => {
  if (!issueForm.name.trim()) {
    ElMessage.warning('请填名称')
    return
  }
  if (!apiKeyTargetKb.value) return
  issuing.value = true
  try {
    const res: any = await apiKeyApi.issue({
      name: issueForm.name.trim(),
      allowedKbIds: [apiKeyTargetKb.value.id],   // 11.6 · 单 KB 绑定
      monthlyQuota: issueForm.monthlyQuota,
      expireAt: issueForm.expireAt || undefined,
      description: issueForm.description || undefined,
    })
    const data = res?.data ?? res
    issuedRawKey.value = data.rawKey
    issueDialogVisible.value = false
    issuedResultVisible.value = true
    await loadApiKeysForKb(apiKeyTargetKb.value.id)
  } catch (e: any) {
    ElMessage.error('生成失败：' + (e?.message || ''))
  } finally {
    issuing.value = false
  }
}

const copyRawKey = async () => {
  try {
    await navigator.clipboard.writeText(issuedRawKey.value)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

const closeIssuedResult = async () => {
  try {
    await ElMessageBox.confirm('确认已经复制并妥善保存完整 key？关闭后无法再查看完整 key。', '再次确认', {
      type: 'warning',
      confirmButtonText: '已保存，关闭',
      cancelButtonText: '让我再看看',
    })
    issuedResultVisible.value = false
    issuedRawKey.value = ''
  } catch { /* cancel */ }
}

// 查看接入示例（旧 key · 不显完整 key）
const curlExampleVisible = ref(false)
const showCurlExample = (_k: ApiKey) => {
  curlExampleVisible.value = true
}

// 生成 curl 示例代码
const buildCurlExample = (key: string, kbId?: number) => {
  const host = window.location.origin
  return `curl -X POST ${host}/api/v3/chat \\
  -H "Authorization: Bearer ${key}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "question": "试用期工资多少？",
    "kbId": ${kbId ?? '<KB_ID>'}
  }'`
}

const buildSearchExample = (key: string, kbId?: number) => {
  const host = window.location.origin
  return `curl -X POST ${host}/api/v3/search \\
  -H "Authorization: Bearer ${key}" \\
  -H "Content-Type: application/json" \\
  -d '{
    "query": "试用期",
    "kbId": ${kbId ?? '<KB_ID>'},
    "topK": 5
  }'`
}

// 点失败原因 → 直接弹完整文本（绕过 overlay 蒙层，无需 hover）
const showErrorDetail = (item: KnowledgeBase) => {
  ElMessageBox.alert(item.errorMsg || '未知错误', `❌ ${item.name} · 处理失败`, {
    type: 'error',
    confirmButtonText: '我知道了',
    customClass: 'error-detail-box',
    dangerouslyUseHTMLString: false,
  })
}

const deleteItem = async (id: number) => {
  await ElMessageBox.confirm('确认删除该知识库？此操作不可恢复。', '警告', { type: 'warning' })
  await knowledgeApi.delete(id)
  ElMessage.success('已删除')
  loadList()
}

const reprocess = async (id: number) => {
  await knowledgeApi.reprocess(id)
  ElMessage.success('已重新提交处理')
  loadList()
}

const rebuildIndex = async (id: number) => {
  await ElMessageBox.confirm(
    '确认重建该文档的检索索引？处理期间该文档会暂时不可检索，完成后将使用最新的父子切片策略。',
    '重建检索索引',
    { type: 'warning', confirmButtonText: '开始重建', cancelButtonText: '取消' },
  )
  await knowledgeApi.reprocess(id)
  ElMessage.success('已提交索引重建')
  loadList()
}

// ── 文件上传 ──
const triggerFileInput = () => fileInputRef.value?.click()
// ─── 文件 / 文件夹采集 ──────────────────────────────────
const triggerFolderInput = () => folderInputRef.value?.click()

const handleFileChange = (e: Event) => {
  const files = Array.from((e.target as HTMLInputElement).files || [])
  if (files.length) addFilesToQueue(files)
  // 重置 input 让同名文件能再次选
  ;(e.target as HTMLInputElement).value = ''
}

const handleDrop = async (e: DragEvent) => {
  isDragging.value = false
  const items = e.dataTransfer?.items
  if (items && items.length && (items[0] as any).webkitGetAsEntry) {
    // 支持文件夹拖拽
    const all: File[] = []
    const promises: Promise<void>[] = []
    for (let i = 0; i < items.length; i++) {
      const entry = (items[i] as any).webkitGetAsEntry?.()
      if (entry) promises.push(traverseEntry(entry, all))
    }
    await Promise.all(promises)
    if (all.length) addFilesToQueue(all)
  } else {
    // 老浏览器 fallback：只取 files
    const files = Array.from(e.dataTransfer?.files || [])
    if (files.length) addFilesToQueue(files)
  }
}

/** 递归遍历 DataTransferItem entry，把所有文件平铺到 out */
function traverseEntry(entry: any, out: File[]): Promise<void> {
  return new Promise((resolve) => {
    if (entry.isFile) {
      entry.file((f: File) => {
        // 文件夹里的文件可能带 fullPath，重命名加上目录前缀避免重名假冲突
        if (entry.fullPath && entry.fullPath !== '/' + f.name) {
          try {
            Object.defineProperty(f, 'name', { value: entry.fullPath.replace(/^\//, '').replace(/\//g, '_') })
          } catch (_) { /* 老浏览器忽略 */ }
        }
        out.push(f)
        resolve()
      }, () => resolve())
    } else if (entry.isDirectory) {
      const reader = entry.createReader()
      const all: Promise<void>[] = []
      const readBatch = () => {
        reader.readEntries((entries: any[]) => {
          if (!entries.length) {
            Promise.all(all).then(() => resolve())
            return
          }
          entries.forEach(e => all.push(traverseEntry(e, out)))
          readBatch()   // readEntries 一次最多 100，递归读完
        }, () => resolve())
      }
      readBatch()
    } else {
      resolve()
    }
  })
}

/** 真正入队 · 调 store 做校验 */
function addFilesToQueue(files: File[]) {
  if (files.length > 100) {
    ElMessage.warning(`一次最多 100 个文件，本次取前 100 个（共 ${files.length}）`)
    files = files.slice(0, 100)
  }
  const { added, rejected } = uploadStore.addFiles(
    files,
    uploadForm.value.category,
    uploadForm.value.description
  )
  if (added.length > 0) {
    ElMessage.success(`已加入队列 ${added.length} 个文件`)
  }
  if (rejected.length > 0) {
    // 失败摘要：太多就只展示前 3 条
    const sample = rejected.slice(0, 3).map(r => `${r.name}：${r.reason}`).join('\n')
    const more = rejected.length > 3 ? `\n…（共 ${rejected.length} 个跳过）` : ''
    ElMessageBox.alert(sample + more, '部分文件未加入队列', { type: 'warning', confirmButtonText: '知道了' })
  }
}

const onDialogClose = () => {
  // 后台运行：不清队列，靠 dock 继续展示。只重置表单。
  uploadForm.value = { category: '', description: '' }
}

// ── 工具函数 ──
const getStatusType = (s: string) => {
  const m: Record<string, string> = {
    ready: 'success', processing: 'warning', uploading: 'info', failed: 'danger',
    rebuild_queued: 'warning', rebuilding: 'warning', rebuild_failed: 'danger'
  }
  return (m[s] || 'info') as any
}
const getStatusLabel = (s: string) => {
  const m: Record<string, string> = {
    ready: '就绪', processing: '处理中', uploading: '上传中', failed: '失败',
    rebuild_queued: '等待重建', rebuilding: '重建中', rebuild_failed: '重建失败'
  }
  return m[s] || s
}
const getCategoryLabel = (v: string) => categoryOptions.value.find(c => c.value === v)?.label || v || '-'
const getCategoryColor = (v: string) => categoryOptions.value.find(c => c.value === v)?.color || '#64748B'

// JSON tags 解析
const parseTags = (raw: any): string[] => {
  if (!raw) return []
  if (Array.isArray(raw)) return raw
  try {
    const arr = typeof raw === 'string' ? JSON.parse(raw) : raw
    return Array.isArray(arr) ? arr : []
  } catch { return [] }
}

// 修改文档分类（锁定为用户手动）
const changeDocCategory = async (kbId: number, code: string) => {
  try {
    await kbCategoryApi.setDocumentCategory(kbId, code)
    ElMessage.success('分类已更新（已锁定，AI 不会再覆盖）')
    if (currentDoc.value) {
      currentDoc.value.category = code
      ;(currentDoc.value as any).categoryUserSet = 1
    }
    loadList()
  } catch (e: any) {
    ElMessage.error('修改失败：' + (e?.message || ''))
  }
}

// 重新分类（仅管理员）
const reclassifyDoc = async (kbId: number) => {
  try {
    await kbCategoryApi.reclassify(kbId)
    ElMessage.success('已重新提交分类，请稍后刷新查看')
    detailVisible.value = false
    setTimeout(loadList, 1500)
  } catch (e: any) {
    ElMessage.error('重分类失败：' + (e?.message || ''))
  }
}
const getFileExt = (name: string) => name.split('.').pop()?.toLowerCase() || ''
const getFileIcon = (ext: string) => {
  if (['pdf'].includes(ext))        return 'Document'
  if (['docx','doc'].includes(ext)) return 'Edit'
  if (['md','markdown'].includes(ext)) return 'Memo'
  return 'Document'
}
const getFileColor = (ext: string) => {
  if (['pdf'].includes(ext))        return '#f87171'
  if (['docx','doc'].includes(ext)) return '#38bdf8'
  if (['md','markdown'].includes(ext)) return '#71717a'
  return '#94a3b8'
}
const formatFileSize = (bytes: number) => {
  if (!bytes) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024*1024) return `${(bytes/1024).toFixed(1)} KB`
  return `${(bytes/1024/1024).toFixed(1)} MB`
}
const formatDate = (d: string) => {
  if (!d) return '-'
  return new Date(d).toLocaleDateString('zh-CN', { year:'numeric', month:'short', day:'numeric' })
}
</script>

<style scoped>
.knowledge-page { display: flex; flex-direction: column; gap: 16px; overflow-y: auto; }

/* 统计条 */
.stat-strip {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}
.stat-item {
  flex: 1;
  min-width: 140px;
  display: flex;
  align-items: center;
  gap: 12px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 14px 16px;
}
.stat-icon-wrap {
  width: 36px; height: 36px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.stat-val { font-size: 22px; font-weight: 800; font-family: 'JetBrains Mono', monospace; line-height: 1; }
.stat-lbl { font-size: 11px; color: #64748b; margin-top: 3px; }

/* 操作栏 */
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px 16px;
}
.toolbar-left { display: flex; align-items: center; gap: 10px; }

/* 批量操作工具栏 · 任务 15.2 */
.batch-toolbar {
  display: flex; align-items: center; gap: 12px;
  margin: 0 0 12px;
  padding: 10px 16px;
  background: linear-gradient(135deg, rgba(0,0,0,0.10), rgba(59,130,246,0.08));
  border: 1px solid rgba(0,0,0,0.28);
  border-radius: 10px;
}
.batch-info { font-size: 13px; color: var(--ink-1); }
.batch-info b { color: #0a0a0a; font-weight: 700; }

/* 卡片左上角勾选框 */
.kb-select-box {
  position: absolute;
  top: 8px; left: 8px;
  z-index: 2;
  width: 20px; height: 20px;
  background: rgba(255,255,255,0.95);
  border: 1.5px solid rgba(148,163,184,0.7);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s;
  display: flex; align-items: center; justify-content: center;
  padding: 0;
  opacity: 0;
}
.kb-card:hover .kb-select-box { opacity: 1; }
.kb-select-box.checked {
  opacity: 1;
  background: #0071E3;
  border-color: #0071E3;
}
.kb-select-box.checked:hover { background: #0A84FF; }
.kb-card.selected {
  border-color: #0a0a0a !important;
  box-shadow: 0 0 0 2px rgba(0,0,0,0.16);
}

/* 所属库徽标 */
.coll-badge {
  display: inline-flex; align-items: center; gap: 3px;
  padding: 2px 8px; border-radius: 999px;
  background: rgba(0,0,0,0.10); color: #0a0a0a;
  font-size: 10.5px; font-weight: 600;
  max-width: 130px;
}
.coll-badge span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.coll-badge.orphan { background: rgba(245,158,11,0.12); color: #b45309; }

.fade-enter-active, .fade-leave-active { transition: opacity 0.2s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }

/* 卡片网格 */
.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 12px;
}

.kb-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 16px;
  cursor: pointer;
  transition: var(--transition);
  position: relative;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-height: 160px;
}
.kb-card:hover {
  border-color: rgba(56,189,248,0.3);
  box-shadow: 0 4px 20px rgba(0,0,0,0.3);
  transform: translateY(-1px);
}
.kb-card:hover .kb-card-overlay { opacity: 1; }

.kb-card-header { display: flex; align-items: center; justify-content: space-between; }
.header-tags { display: flex; align-items: center; gap: 6px; }

/* 任务 7 · 可见性徽标 */
.vis-badge {
  font-size: 10.5px;
  font-weight: 700;
  padding: 2px 8px;
  border-radius: 999px;
  font-family: 'JetBrains Mono', monospace;
  cursor: help;
  letter-spacing: 0.02em;
}
.vis-badge.vis-public  { background: rgba(52, 211, 153, 0.12); color: #047857; border: 1px solid rgba(52, 211, 153, 0.3); }
.vis-badge.vis-scoped  { background: rgba(0, 0, 0, 0.12); color: #0a0a0a; border: 1px solid rgba(0, 0, 0, 0.3); }
.vis-badge.vis-private { background: rgba(245, 158, 11, 0.12); color: #b45309; border: 1px solid rgba(245, 158, 11, 0.3); }

/* 任务 7 · 详情对话框 ACL 配置 */
.acl-block { background: rgba(0, 0, 0, 0.04); border: 1px solid rgba(0, 0, 0, 0.18) !important; }
.acl-perms { background: rgba(0, 0, 0, 0.06); border: 1px solid rgba(0, 0, 0, 0.22) !important; }
.acl-row {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 0;
}
.acl-row + .acl-row { border-top: 1px dashed rgba(0, 0, 0, 0.15); }
.acl-pos { flex: 1; font-size: 12.5px; color: var(--ink-1, #0f172a); font-weight: 600; }
.acl-del {
  width: 22px; height: 22px;
  border-radius: 4px;
  background: transparent; border: 1px solid transparent;
  color: var(--ink-3, #94a3b8); cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
}
.acl-del:hover { background: rgba(239, 68, 68, 0.1); border-color: #ef4444; color: #ef4444; }
.acl-add-row {
  display: flex; align-items: center; gap: 6px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed rgba(0, 0, 0, 0.2);
}
.acl-tip { margin-top: 8px; font-size: 11px; color: var(--ink-3, #94a3b8); line-height: 1.55; }

/* 任务 7 补强 · 部门/职位 subject 类型徽标 */
.acl-subject-tag {
  font-size: 10.5px;
  font-weight: 700;
  padding: 1px 7px;
  border-radius: 4px;
  flex-shrink: 0;
  letter-spacing: 0.02em;
}
.acl-subject-tag.pos {
  background: rgba(56, 189, 248, 0.12);
  color: #0284c7;
  border: 1px solid rgba(56, 189, 248, 0.3);
}
.acl-subject-tag.dept {
  background: rgba(0, 0, 0, 0.12);
  color: #0a0a0a;
  border: 1px solid rgba(0, 0, 0, 0.3);
}

/* 添加授权区 · Tab + form */
.acl-add-block {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed rgba(0, 0, 0, 0.2);
}
.acl-tabs { margin-bottom: 8px; }
.kb-file-icon {
  width: 36px; height: 36px;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: 9px;
  display: flex; align-items: center; justify-content: center;
}

.kb-card-body { flex: 1; }
.kb-name {
  font-size: 14px; font-weight: 600; color: #e2e8f0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  margin-bottom: 4px;
}
.kb-desc { font-size: 12px; color: #64748b; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }

.kb-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
.kb-meta { display: flex; align-items: center; gap: 10px; }
.meta-tag {
  font-size: 11px; color: #64748b;
  background: var(--bg-elevated);
  border-radius: 10px;
  padding: 2px 8px;
  border: 1px solid var(--border);
}
.meta-chunks { display: flex; align-items: center; gap: 4px; font-size: 11px; color: #475569; }
.kb-time { font-size: 11px; color: #334155; }
.lock-mark, .ai-mark { margin-left: 4px; font-size: 10px; }
.kb-tags-row {
  display: flex; flex-wrap: wrap; gap: 4px;
  margin-top: 6px;
}
.kb-tag-chip {
  font-size: 10.5px;
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(56, 189, 248, 0.08);
  color: #38bdf8;
  border: 1px solid rgba(56, 189, 248, 0.18);
  font-family: 'JetBrains Mono', monospace;
}
.kb-error-inline {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
  padding: 8px 10px;
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.22);
  border-radius: 8px;
}
.kb-error-row {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  cursor: pointer;
  transition: opacity 0.15s;
}
.kb-error-row:hover { opacity: 0.85; }
.kb-error-row .el-icon { color: #f87171; margin-top: 1px; flex-shrink: 0; }
.kb-error-text {
  flex: 1;
  font-size: 11.5px;
  color: #fca5a5;
  line-height: 1.4;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  word-break: break-all;
}
.kb-error-more {
  font-size: 10.5px;
  color: #f87171;
  font-weight: 600;
  flex-shrink: 0;
  white-space: nowrap;
}
.kb-error-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
  padding-top: 4px;
  border-top: 1px dashed rgba(248, 113, 113, 0.2);
}
.kb-inline-btn {
  width: 26px;
  height: 26px;
  border-radius: 6px;
  border: 1px solid rgba(248, 113, 113, 0.3);
  background: rgba(248, 113, 113, 0.05);
  color: #fca5a5;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.15s;
}
.kb-inline-btn:hover { background: rgba(248, 113, 113, 0.18); color: #fff; }
.kb-inline-btn.danger:hover { background: #ef4444; border-color: #ef4444; }

.error-block {
  background: rgba(248, 113, 113, 0.06);
  border: 1px solid rgba(248, 113, 113, 0.18);
  border-radius: 8px;
  padding: 10px 12px;
}
.error-msg {
  color: #f87171;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.55;
  word-break: break-all;
  white-space: pre-wrap;
}

.kb-card-overlay {
  position: absolute;
  inset: 0;
  background: rgba(13,17,23,0.85);
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  opacity: 0;
  transition: opacity 0.2s;
  border-radius: var(--radius);
  cursor: pointer;          /* 整个 overlay 区也可点（→ openDetail）*/
}
.overlay-btn {
  height: 38px;
  min-width: 38px;
  padding: 0 12px;
  border-radius: var(--radius-sm);
  border: 1px solid var(--border);
  background: var(--bg-elevated);
  display: inline-flex; align-items: center; justify-content: center;
  gap: 6px;
  cursor: pointer; color: #cbd5e1;
  font-size: 12.5px; font-weight: 600;
  transition: var(--transition);
}
.overlay-btn:hover { border-color: var(--primary); color: var(--primary); background: rgba(56, 189, 248, 0.06); }
.overlay-btn.primary {
  background: rgba(56, 189, 248, 0.12);
  border-color: rgba(56, 189, 248, 0.4);
  color: #38bdf8;
}
.overlay-btn.primary:hover {
  background: rgba(56, 189, 248, 0.2);
  border-color: #38bdf8;
}
.overlay-btn.danger {
  width: 38px;       /* 删除按钮保持小方形，只显图标 */
  padding: 0;
}
.overlay-btn.danger:hover { border-color: #f87171; color: #f87171; background: rgba(248,113,113,0.1); }
.overlay-btn-label { white-space: nowrap; }

/* 上传占位卡 */
.upload-placeholder {
  border-style: dashed;
  background: transparent;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: #334155;
  font-size: 13px;
}
.upload-placeholder:hover { border-color: rgba(56,189,248,0.4); color: var(--primary); }

/* 空状态 */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 60px;
  text-align: center;
}
.empty-title { font-size: 18px; font-weight: 600; color: #475569; }
.empty-desc { font-size: 13px; color: #334155; max-width: 300px; line-height: 1.6; }

.pagination { display: flex; justify-content: center; padding: 8px 0; }

/* 上传弹窗 */
.drop-zone {
  width: 100%;
  min-height: 130px;
  border: 2px dashed var(--border);
  border-radius: var(--radius);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  cursor: pointer;
  transition: var(--transition);
  position: relative;
  background: var(--bg-elevated);
}
.drop-zone:hover, .drop-zone.dragging { border-color: var(--primary); background: rgba(56,189,248,0.05); }
.drop-zone.has-file { border-style: solid; border-color: rgba(52,211,153,0.4); background: rgba(52,211,153,0.04); }
.drop-text { font-size: 13px; color: #64748b; }
.drop-link { color: var(--primary); cursor: pointer; }
.drop-hint { font-size: 11px; color: #475569; }
.file-selected-name { font-size: 13px; color: #e2e8f0; font-weight: 500; }
.file-selected-size { font-size: 11px; color: #64748b; }
.file-clear {
  position: absolute; top: 8px; right: 8px;
  background: var(--bg-elevated); border: 1px solid var(--border);
  border-radius: 50%; width: 22px; height: 22px;
  display: flex; align-items: center; justify-content: center;
  cursor: pointer; color: #64748b;
  transition: var(--transition);
}
.file-clear:hover { color: #f87171; border-color: #f87171; }

.upload-progress-wrap {
  width: 100%;
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 12px;
}
.progress-info { display: flex; justify-content: space-between; font-size: 12px; color: #64748b; margin-bottom: 8px; }
.progress-pct { color: var(--primary); font-weight: 700; }

/* ── 队列表（1.10 改造） ────────────────────────────── */
.queue-toolbar {
  display: flex; align-items: center; gap: 12px;
  width: 100%; padding: 0 4px 6px;
  font-size: 12px;
}
.queue-stat { color: #94a3b8; flex: 1; }
.queue-list {
  width: 100%;
  max-height: 260px;
  overflow-y: auto;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--bg-elevated);
}
.queue-row {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 12px;
  border-bottom: 1px solid rgba(148,163,184,0.08);
  transition: background 0.15s;
}
.queue-row:last-child { border-bottom: none; }
.queue-row:hover { background: rgba(56,189,248,0.04); }
.queue-row.st-done { opacity: 0.7; }
.queue-row.st-failed { background: rgba(239,68,68,0.04); }
.qr-icon { flex-shrink: 0; }
.qr-main { flex: 1; min-width: 0; }
.qr-name {
  font-size: 13px; color: #e2e8f0; font-weight: 500;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.qr-meta {
  font-size: 11px; color: #64748b;
  display: flex; gap: 12px; margin-top: 2px;
}
.qr-status { color: #94a3b8; }
.qr-actions { flex-shrink: 0; }

/* 详情弹窗 */
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.detail-row { display: flex; flex-direction: column; gap: 4px; padding: 10px 12px; background: var(--bg-elevated); border: 1px solid var(--border); border-radius: var(--radius-sm); }
.detail-row.span2 { grid-column: span 2; }
.detail-key {
  font-size: 11px;
  color: var(--ink-3);
  text-transform: uppercase;
  letter-spacing: 0.5px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 5px;
}
.detail-key .el-icon { color: var(--ink-4); }

/* 分类锁定 / AI 推荐 提示 */
.cat-lock-tip, .cat-ai-tip {
  margin-left: 8px;
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 3px;
}
.cat-lock-tip { color: var(--ink-3); }
.cat-ai-tip   { color: var(--brand); }

/* 可见性卡片选择器 */
.vis-block .detail-val { display: block; }
.vis-cards {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 4px;
}
.vis-card {
  position: relative;
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 8px;
  text-align: left;
  cursor: pointer;
  transition: all 0.15s;
  font-family: inherit;
}
.vis-card:hover {
  border-color: var(--line-strong);
  background: var(--bg-hover);
}
.vis-card.active {
  border-color: var(--brand);
  background: var(--brand-soft);
  box-shadow: 0 0 0 3px var(--brand-glow);
}
.vis-card .vis-icon { color: var(--ink-3); flex-shrink: 0; margin-top: 1px; }
.vis-card.active .vis-icon { color: var(--brand); }
.vis-card-body { flex: 1; min-width: 0; }
.vis-card-title { font-size: 13px; font-weight: 700; color: var(--ink-1); line-height: 1.2; }
.vis-card-desc { font-size: 11.5px; color: var(--ink-3); margin-top: 2px; line-height: 1.4; }
.vis-check {
  position: absolute;
  top: 6px; right: 8px;
  color: var(--brand);
}
.detail-val { font-size: 13.5px; color: #e2e8f0; }

/* ═══════════════════════════════════════════
 * 任务 11.6 · KB 专属 API Key 弹窗
 * ═══════════════════════════════════════════ */
.api-hint {
  padding: 12px 14px;
  margin-bottom: 16px;
  background: rgba(56, 189, 248, 0.08);
  border: 1px solid rgba(56, 189, 248, 0.25);
  border-radius: 8px;
  font-size: 12.5px;
  color: var(--ink-2);
  line-height: 1.6;
}
.api-hint-warn { color: #f59e0b; font-weight: 600; }
.api-keys-list { display: flex; flex-direction: column; gap: 10px; max-height: 360px; overflow-y: auto; }
.api-key-card {
  background: var(--bg-surface);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 14px 16px;
}
.api-key-card.status-revoked,
.api-key-card.status-expired { opacity: 0.6; }
.api-key-card header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.ak-name { font-size: 14px; font-weight: 700; color: var(--ink-1); }
.ak-status { font-size: 11px; font-weight: 700; padding: 2px 8px; border-radius: 999px; }
.ak-status.s-active  { background: rgba(52,211,153,0.15); color: #047857; }
.ak-status.s-revoked { background: rgba(248,113,113,0.15); color: #b91c1c; }
.ak-status.s-expired { background: rgba(148,163,184,0.15); color: #64748b; }
.ak-prefix { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.ak-prefix code {
  font-family: 'JetBrains Mono', monospace;
  background: var(--bg-elevated); padding: 4px 10px;
  border-radius: 6px; font-size: 12.5px;
  color: var(--ink-1);
}
.ak-prefix-tip { font-size: 11px; color: var(--ink-4); }
.ak-meta { display: flex; gap: 16px; font-size: 12px; color: var(--ink-3); margin-bottom: 10px; }
.ak-meta b { color: var(--ink-1); }
.ak-actions { display: flex; gap: 8px; }
.api-keys-list .empty { padding: 40px 20px; text-align: center; color: var(--ink-3); }

.issued-warn {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 14px;
  background: rgba(248, 113, 113, 0.08);
  border: 1px solid rgba(248, 113, 113, 0.3);
  border-radius: 8px;
  color: #b91c1c;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 14px;
}
.issued-key {
  display: flex; align-items: center; gap: 10px;
  padding: 12px 14px;
  background: #1f2937;
  border-radius: 8px;
  margin-bottom: 14px;
}
.issued-key code {
  flex: 1;
  font-family: 'JetBrains Mono', monospace;
  color: #34d399;
  font-size: 13px;
  word-break: break-all;
}
.issued-curl { margin-top: 4px; }
.curl-title { font-size: 12px; font-weight: 700; color: var(--ink-2); margin-bottom: 6px; }
.issued-curl pre {
  background: #0f172a;
  color: #cbd5e1;
  padding: 12px 14px;
  border-radius: 8px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
}
.issued-curl pre code { color: #fbbf24; }

/* 分类管理 */
.cat-hint { font-size: 12px; color: var(--ink-4); margin: 0 0 14px; }
.cat-add { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.cat-list { display: flex; flex-direction: column; gap: 8px; max-height: 360px; overflow-y: auto; }
.cat-row { display: flex; align-items: center; gap: 10px; }
.cat-row .cat-code { font-size: 11px; color: var(--ink-4); background: var(--bg-subtle); padding: 2px 8px; border-radius: 4px; min-width: 80px; text-align: center; }
.cat-empty { text-align: center; color: var(--ink-4); font-size: 13px; padding: 20px; }
</style>
