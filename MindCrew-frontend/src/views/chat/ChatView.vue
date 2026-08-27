<template>
  <div class="chat-layout">
    <!-- 移动端遮罩 · 只在 chatSidebarOpen 时显示（CSS 控制 ≤768px 才可见） -->
    <div
      v-if="chatSidebarOpen"
      class="chat-sidebar-mask"
      @click="chatSidebarOpen = false"
    ></div>

    <!-- ===== 左侧会话列表（移动端为抽屉） ===== -->
    <aside class="chat-sidebar" :class="{ 'mobile-open': chatSidebarOpen }" aria-label="对话历史">
      <div class="chat-rail-head">
        <div>
          <strong>AI 助手</strong>
          <span>知识与数据工作台</span>
        </div>
        <button class="rail-icon-btn" type="button" title="新建对话" aria-label="新建对话" @click="newConversation">
          <el-icon><Plus /></el-icon>
        </button>
      </div>

      <div class="sidebar-top">
        <button class="new-chat-btn" type="button" @click="newConversation">
          <el-icon size="15"><EditPen /></el-icon>
          <span>新建对话</span>
          <kbd>⌘ N</kbd>
        </button>
        <label class="conv-search">
          <el-icon><Search /></el-icon>
          <input v-model="conversationSearch" type="search" placeholder="搜索当前对话" aria-label="搜索当前对话" />
        </label>
      </div>

      <details class="scope-disclosure">
        <summary>
          <span class="scope-summary-title"><el-icon><FolderOpened /></el-icon>知识范围</span>
          <span class="scope-summary-value">{{ scopeSummary }}</span>
          <el-icon class="scope-chevron"><ArrowDown /></el-icon>
        </summary>
        <div class="scope-panel">
          <div class="kb-selector" v-if="collections.length">
            <div class="kb-label"><span>知识库</span><span class="kb-optional">不选则智能全库检索</span></div>
            <el-select
              v-model="selectedCollectionIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              filterable
              placeholder="全部知识库"
              clearable
              size="small"
              style="width: 100%"
            >
              <el-option v-for="c in collections" :key="c.id" :label="c.name" :value="c.id">
                <div class="scope-option"><span>{{ c.name }}</span><em>{{ c.docCount }} 文档</em></div>
              </el-option>
            </el-select>
          </div>
          <div class="kb-selector" v-if="datasources.length">
            <div class="kb-label"><span>业务数据</span><span class="kb-optional">不选则自动路由</span></div>
            <el-select
              v-model="selectedDatasourceIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              filterable
              placeholder="全部可访问数据源"
              clearable
              size="small"
              style="width: 100%"
            >
              <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id">
                <div class="scope-option"><span>{{ d.name }}</span><em v-if="d.description">{{ d.description }}</em></div>
              </el-option>
            </el-select>
          </div>
        </div>
      </details>

      <div class="conv-section-head" v-if="conversations.length">
        <span>最近对话</span>
        <div class="conv-tools">
          <button v-if="!selectMode" class="conv-tool-btn" type="button" @click="enterSelectMode">管理</button>
          <template v-else>
            <button class="conv-tool-btn" type="button" @click="toggleSelectAll">全选</button>
            <button class="conv-tool-btn danger" type="button" :disabled="!selectedConvIds.length" @click="batchDeleteConvs">删除</button>
            <button class="conv-tool-btn" type="button" @click="exitSelectMode">完成</button>
          </template>
        </div>
      </div>

      <div class="conv-list" v-loading="convLoading">
        <div
          v-for="conv in filteredConversations"
          :key="conv.id"
          class="conv-item"
          :class="{ active: !selectMode && currentConvId === conv.id, checked: selectMode && selectedConvIds.includes(conv.id) }"
          @click="selectMode ? toggleConvSelect(conv.id) : switchConversation(conv.id)"
        >
          <el-checkbox
            v-if="selectMode"
            :model-value="selectedConvIds.includes(conv.id)"
            class="conv-check"
            @click.stop
            @change="toggleConvSelect(conv.id)"
          />
          <el-icon v-else size="13" class="conv-icon" :class="{ 'voice-icon': conv.source === 'voice' }">
            <PhoneFilled v-if="conv.source === 'voice'" />
            <ChatDotRound v-else />
          </el-icon>
          <div class="conv-info">
            <div class="conv-title">
              <span>{{ formatConvTitle(conv) }}</span>
              <span v-if="conv.source === 'voice'" class="voice-badge">语音</span>
            </div>
            <div class="conv-meta">{{ formatTime(conv.lastActive) }}</div>
          </div>
          <div class="conv-btns" v-if="!selectMode">
            <button class="conv-btn" title="删除" @click.stop="deleteConversation(conv.id)">
              <el-icon size="12"><Delete /></el-icon>
            </button>
          </div>
        </div>

        <div v-if="filteredConversations.length === 0 && !convLoading" class="empty-conv">
          <div class="empty-icon">
            <el-icon size="28" color="#334155"><ChatLineSquare /></el-icon>
          </div>
          <p>{{ conversationSearch ? '没有匹配的对话' : '暂无对话记录' }}</p>
          <p>{{ conversationSearch ? '换个关键词试试' : '从一个问题开始' }}</p>
        </div>
      </div>
    </aside>

    <!-- ===== 主聊天区域 ===== -->
    <div class="chat-main" :class="{ 'is-welcome': showWelcome }">

      <header class="chat-workspace-bar">
        <button class="chat-conv-btn" @click="chatSidebarOpen = true" aria-label="会话列表">
          <el-icon size="17"><ChatDotRound /></el-icon>
        </button>
        <span class="assistant-mark" aria-hidden="true"><BrandLogo :size="22" color="#24252b" accent-color="#f1c84b" /></span>
        <div class="chat-workspace-context">
          <strong>{{ currentConversationTitle }}</strong>
          <span>{{ scopeSummary }} · 回答可追溯</span>
        </div>
        <div class="chat-workspace-actions">
          <button
            class="workspace-action"
            type="button"
            :class="{ 'is-active': callDialogVisible }"
            :title="callDialogTitle"
            @click="openCallDialog"
          >
            <el-icon><PhoneFilled /></el-icon><span>语音</span>
          </button>
          <button class="workspace-action workspace-new" type="button" @click="newConversation">
            <el-icon><Plus /></el-icon><span>新对话</span>
          </button>
        </div>
      </header>

      <!-- 移动端会话抽屉按钮 · 仅 ≤768px 显示（mobile.css 控制） -->

      <transition name="fade">
        <div v-if="showWelcome" class="welcome-screen">
          <div class="welcome-mark"><BrandLogo :size="34" color="#24252b" accent-color="#f1c84b" /></div>
          <h1 class="welcome-hint">今天想解决什么问题？</h1>
          <p class="welcome-copy">我会结合企业知识库、业务数据和实时网络信息，给出可追溯的答案。</p>
          <div class="welcome-prompts" aria-label="示例问题">
            <button v-for="item in quickQuestions.slice(0, 3)" :key="item.text" type="button" @click="useQuickQuestion(item.text)">
              <span>{{ item.text }}</span><el-icon><ArrowRight /></el-icon>
            </button>
          </div>
        </div>
      </transition>

      <!-- 消息区 -->
      <div v-show="!showWelcome" class="messages-area" ref="messagesAreaRef" @scroll="onMessagesScroll">
        <button
          v-show="showJumpToBottom"
          class="jump-to-bottom"
          type="button"
          aria-label="跳到底部"
          @click="jumpToBottom"
        >
          <el-icon><ArrowDown /></el-icon>
          跳到底部
        </button>
        <div class="messages-inner">
          <div
            v-for="msg in messages"
            :key="msg.id ?? msg.tempId"
            class="message-row"
            :class="msg.role"
          >
            <!-- 用户消息 -->
            <template v-if="msg.role === 'user'">
              <div class="msg-spacer"></div>
              <div class="user-bubble">
                <!-- 图片附件（用户上传的） -->
                <div v-if="getMsgImages(msg).length" class="user-img-row">
                  <button
                    v-for="(img, i) in getMsgImages(msg)" :key="i"
                    type="button"
                    class="user-img-thumb"
                    @click="lightboxUrl = (img.url || img.previewUrl) || null"
                  >
                    <img :src="img.url || img.previewUrl" alt="" />
                  </button>
                </div>
                <!-- 附件（用户上传的文档） -->
                <div v-if="getMsgFiles(msg).length" class="user-file-row">
                  <div v-for="(f, i) in getMsgFiles(msg)" :key="i" class="user-file-chip" :title="f.name">
                    <el-icon size="14"><Document /></el-icon>
                    <span class="user-file-name">{{ f.name }}</span>
                    <span v-if="f.size" class="user-file-size">{{ formatBytes(f.size) }}</span>
                  </div>
                </div>
                <div v-if="msg.content && msg.content !== '[图片]' && msg.content !== '[附件]'" class="bubble-text">{{ msg.content }}</div>
              </div>
              <el-avatar :size="30" :src="userStore.userInfo?.avatar" class="msg-avatar user-av">
                {{ (userStore.userInfo?.nickname || 'U').charAt(0).toUpperCase() }}
              </el-avatar>
            </template>

            <!-- AI 消息 -->
            <template v-else>
              <div class="ai-avatar-wrap">
                <div class="ai-av">
                  <img src="/zycoo-mark.png" alt="ZYCOO" class="ai-logo-img" />
                </div>
              </div>
              <div class="ai-bubble">
                <!-- ✓ 基于人工校正 角标 · 点击打开参考来源 -->
                <div
                  v-if="msg.fromGoldenPair"
                  class="golden-badge"
                  :class="{ clickable: msg.sources && msg.sources.length }"
                  :title="`匹配问题：${msg.matchedQuestion || ''} · 相似度 ${((msg.goldenScore || 0) * 100).toFixed(0)}%${msg.sources && msg.sources.length ? ' · 点击查看来源' : ''}`"
                  @click="msg.sources && msg.sources.length && openSourcesDrawer(msg.sources)"
                >
                  <el-icon size="13"><CircleCheckFilled /></el-icon>
                  <span>已审核标准答案</span>
                  <span v-if="msg.goldenScore" class="golden-score">{{ (msg.goldenScore * 100).toFixed(0) }}%</span>
                  <span v-if="msg.sources && msg.sources.length" class="golden-more">查看来源 ›</span>
                </div>

                <!-- 参考了 Golden Pair 范例（动态 few-shot）· 与"已审核标准答案"互斥 -->
                <div v-else-if="msg.referencedGoldenPair" class="golden-ref-badge" title="本次回答参考了已审核的相似标准问答，结合检索资料重新生成">
                  <el-icon size="13"><MagicStick /></el-icon>
                  <span>参考了已审核经验</span>
                </div>

                <!-- 推理链 · WorkBuddy 风格：无气泡、灰色文字、左侧时间线 -->
                <div v-if="msg.agentSteps && msg.agentSteps.length" class="agent-trace">
                  <div class="trace-header" @click="msg.showSteps = !msg.showSteps">
                    <span class="trace-title">深度思考</span>
                    <el-icon class="trace-clock"><Clock /></el-icon>
                    <span class="trace-meta">{{ msg.agentSteps.length }} 步</span>
                    <el-icon class="trace-toggle" :class="{ rotated: msg.showSteps }"><ArrowDown /></el-icon>
                  </div>
                  <transition name="fade">
                    <div v-if="msg.showSteps" class="trace-body">
                      <div v-for="(step, i) in msg.agentSteps" :key="i" class="trace-step">
                        <div class="step-marker"></div>
                        <div class="step-content">
                          <span class="step-type">{{ step.type }}</span>
                          <span class="step-text">{{ step.text }}</span>
                          <!-- 🆕 实时检索：正在阅读的文档片段（最多 5 条） -->
                          <div v-if="step.sources && step.sources.length" class="step-sources">
                            <div v-for="(src, si) in step.sources" :key="si" class="step-source">
                              <span class="src-icon"><el-icon><Document /></el-icon></span>
                              <div class="src-body">
                                <div class="src-name">{{ src.docName }}</div>
                                <div v-if="src.excerpt" class="src-excerpt">{{ src.excerpt }}</div>
                              </div>
                              <span class="src-score" v-if="src.score > 0">{{ (src.score * 100).toFixed(0) }}%</span>
                            </div>
                          </div>
                        </div>
                      </div>
                    </div>
                  </transition>
                </div>

                <!-- Markdown 渲染内容（流式期间也实时渲染，逐行边出边渲染，结束用带角标的最终 HTML） -->
                <div
                  class="bubble-content md-body"
                  :class="{ 'bubble-content-streaming': msg.isStreaming }"
                  v-html="msg.isStreaming ? renderMd(msg.content, 0) : (msg.renderedHtml || renderMd(msg.content, msg.sources?.length || 0))"
                  @click="onCiteClick($event, msg)"
                  @mouseover="onCiteHover($event, msg)"
                  @mouseout="onCiteLeave($event)"
                ></div>

                <!-- 反问澄清卡片：AI 判定问题模糊，给出多选项让用户选择 / 补充 / 跳过 -->
                <div v-if="msg.clarify" class="clarify-card">
                  <div class="clarify-opts">
                    <button
                      v-for="(opt, i) in msg.clarify.options" :key="i"
                      class="clarify-opt"
                      :class="{ chosen: msg.clarify.chosen === opt }"
                      :disabled="msg.clarify.answered"
                      @click="answerClarify(msg, opt)"
                    >{{ opt }}</button>
                  </div>
                  <div v-if="!msg.clarify.answered" class="clarify-other-row">
                    <input
                      v-model="msg.clarify.otherText"
                      class="clarify-other-input"
                      placeholder="其他（手动补充）…"
                      @compositionstart="onImeCompositionStart"
                      @compositionend="onImeCompositionEnd"
                      @keydown.enter="answerOtherOnEnter($event, msg)"
                    />
                    <button class="clarify-other-btn" @click="answerOther(msg)">提交</button>
                    <button class="clarify-skip-btn" @click="skipClarify(msg)">跳过</button>
                  </div>
                  <div v-else class="clarify-done">
                    {{ msg.clarify.chosen === '__skip__'
                       ? '已跳过澄清，直接作答'
                       : (msg.clarify.chosen ? '已选择：' + msg.clarify.chosen : '已作答') }}
                  </div>
                </div>

                <!-- 流式生成指示器：仅在 content 还没出现时显示三个点 -->
                <div
                  v-if="msg.isStreaming && !msg.content"
                  class="thinking-dots"
                >
                  <span class="dot"></span>
                  <span class="dot"></span>
                  <span class="dot"></span>
                </div>

                <!-- NL2SQL · 数据库查询结果：图表 + 表格 -->
                <template v-if="msg.dbResults && msg.dbResults.length">
                  <DbResultCard v-for="(dbr, di) in msg.dbResults" :key="'db' + di" :result="dbr" />
                </template>

                <!-- 来源引用 · 点击右侧抽屉展开（不再下拉撑长页面） -->
                <div v-if="msg.sources && msg.sources.length && !msg.clarify" class="sources-panel">
                  <button class="sources-toggle" @click="openSourcesDrawer(msg.sources)">
                    <el-icon size="12"><Document /></el-icon>
                    <span>参考来源 {{ msg.sources.length }} 条</span>
                    <el-icon class="toggle-icon"><ArrowRight /></el-icon>
                  </button>
                  <transition name="fade">
                    <div v-if="false && msg.showSources" class="sources-list">
                      <div v-for="(src, i) in msg.sources" :key="i" class="source-card" :class="`media-${src.mediaType || 'document'}`">
                        <!-- Golden Pair 类型：人工校正过的标准答案 · 特殊样式 -->
                        <template v-if="src.type === 'golden_pair'">
                          <div class="src-header golden">
                            <span class="src-idx">[{{ src.index ?? (Number(i) + 1) }}]</span>
                            <el-icon size="13" color="#10b981"><CircleCheckFilled /></el-icon>
                            <span class="src-name golden-name">人工校正的标准答案</span>
                            <span v-if="src.hitCount" class="src-score">被命中 {{ src.hitCount }} 次</span>
                          </div>
                          <div class="src-golden-body">
                            <div v-if="src.matchedQuestion" class="golden-row">
                              <span class="golden-label">匹配问题：</span>
                              <span>{{ src.matchedQuestion }}</span>
                            </div>
                            <div class="golden-row">
                              <span class="golden-label">校正来源：</span>
                              <span>{{ src.verifiedBy || '管理员' }} · Golden Pair #{{ src.pairId }}</span>
                            </div>
                            <div class="golden-tip">
                              💡 该问题之前已有用户提问并由管理员审核校正答案，本次直接复用以保证一致性
                            </div>
                          </div>
                        </template>

                        <!-- 普通知识库 chunk -->
                        <template v-else>
                        <div class="src-header">
                          <span class="src-idx">[{{ src.index ?? (Number(i) + 1) }}]</span>
                          <span v-if="src.type === 'web'" class="src-web-badge">🌐 网络搜索</span>
                          <span class="src-media-badge" v-if="src.mediaType">{{ mediaTypeLabel(src.mediaType) }}</span>
                          <span class="src-name">{{ src.name || '未命名来源' }}</span>
                          <span v-if="src.score && !src.directRead" class="src-score">{{ (src.score * 100).toFixed(0) }}%</span>
                        </div>
                        <div class="src-excerpt" :class="{ expanded: src._expanded }">{{ src.content }}</div>
                        <button
                          v-if="(src.content || '').length > 80"
                          class="src-expand-btn"
                          @click="src._expanded = !src._expanded"
                        >
                          {{ src._expanded ? '收起' : '展开全文' }}
                        </button>
                        <div class="src-meta">
                          <span v-if="src.chapter">{{ src.chapter }}</span>
                          <!-- 仅文档类（PDF/PPT/Word 等）显示页码；音视频/图片不展示 -->
                          <span v-if="src.pageNumber && src.mediaType !== 'video' && src.mediaType !== 'audio' && src.mediaType !== 'image'">
                            · 第 {{ src.pageNumber }} 页
                          </span>
                          <span v-if="src.startMs != null" class="src-timestamp">
                            · {{ formatMediaTime(src.startMs) }}
                            <template v-if="hasValidEndMs(src)">
                              ~ {{ formatMediaTime(displayEndMs(src)) }}
                            </template>
                          </span>
                          <span v-if="src.speakerId" class="src-speaker">· {{ src.speakerId }}</span>
                        </div>

                        <!-- 操作按钮区：文档/音视频/图片都能打开原文 -->
                        <div class="src-actions">
                          <!-- 文档类「打开原文」（PDF 自动跳页） -->
                          <button
                            v-if="canOpenOriginal(src)"
                            class="src-open-btn"
                            :disabled="src._opening"
                            @click="openOriginalDoc(src)"
                          >
                            <el-icon :size="13"><Document /></el-icon>
                            <span>{{ src._opening ? '加载中...' : (src.mediaType === 'image' ? '查看图片' : '打开原文') }}</span>
                            <span v-if="src.pageNumber && (!src.mediaType || src.mediaType === 'document')" class="open-page">
                              · 第 {{ src.pageNumber }} 页
                            </span>
                          </button>
                        </div>

                        <!-- 音频/视频内嵌播放器 + 自动 seek -->
                        <div v-if="(src.mediaType === 'audio' || src.mediaType === 'video') && src.sourceObjectName" class="src-media-player">
                          <button class="src-play-btn" @click="openMediaSource(src)">
                            <el-icon :size="14"><VideoPlay /></el-icon>
                            <span>跳转到 {{ formatMediaTime(src.startMs) }} 播放</span>
                          </button>
                          <component
                            v-if="src._loaded"
                            :is="src.mediaType === 'audio' ? 'audio' : 'video'"
                            :ref="(el: any) => registerMediaPlayer(el, src)"
                            :src="src._mediaUrl"
                            controls
                            preload="metadata"
                            class="src-media-element"
                            @loadedmetadata="seekMediaTo(src)"
                          />
                        </div>
                        </template>
                      </div>
                    </div>
                  </transition>
                </div>

                <!-- 操作栏 -->
                <div v-if="!msg.isStreaming" class="msg-actions">
                  <button
                    v-if="msg.wordRequested"
                    class="word-download-btn"
                    :disabled="downloadingWordMessageId === msg.id"
                    title="将本条方案整理为 Word 文档"
                    @click="downloadAnswerWord(msg)"
                  >
                    <el-icon size="14"><Download /></el-icon>
                    <span>{{ downloadingWordMessageId === msg.id ? '正在生成 Word…' : '下载 Word' }}</span>
                  </button>
                  <button
                    class="action-btn thumb"
                    :class="{ active: msg.feedback === 1 }"
                    title="答案有用"
                    @click="submitFeedback(msg, 'up')"
                  >
                    <span class="thumb-emoji">👍</span>
                    <span class="thumb-label">有用</span>
                  </button>
                  <button
                    class="action-btn thumb"
                    :class="{ active: msg.feedback === -1, danger: msg.feedback === -1 }"
                    title="答案没用"
                    @click="submitFeedback(msg, 'down')"
                  >
                    <span class="thumb-emoji">👎</span>
                    <span class="thumb-label">没用</span>
                  </button>
                  <button class="action-btn" title="我来纠正答案" @click="openCorrectionDialog(msg)">
                    <el-icon size="13"><EditPen /></el-icon>
                  </button>
                  <button class="action-btn" title="查看检索过程" @click="showRetrievalLog(msg)">
                    <el-icon size="13"><DataAnalysis /></el-icon>
                  </button>
                  <button class="action-btn" title="复制" @click="copyContent(msg.content)">
                    <el-icon size="13"><CopyDocument /></el-icon>
                  </button>
                </div>
              </div>
            </template>
          </div>
        </div>
      </div>

      <!-- ===== 输入区域 ===== -->
      <div class="input-zone">
        <div class="composer-context-row">
          <span><el-icon><FolderOpened /></el-icon>{{ scopeSummary }}</span>
          <span class="composer-context-divider" aria-hidden="true"></span>
          <span>{{ webSearchPreferenceExplicit ? (webSearchEnabled ? '联网已开启' : '仅内部知识') : '联网自动判断' }}</span>
        </div>
        <div class="input-card"
             :class="{ 'drag-over': isDraggingImage }"
             @dragover.prevent="isDraggingImage = true"
             @dragleave.prevent="isDraggingImage = false"
             @drop.prevent="handleImageDrop">

          <button
            class="input-plus-btn"
            type="button"
            :disabled="isStreaming"
            title="添加附件"
            aria-label="添加附件"
            @click="triggerAttachmentUpload"
          ><el-icon><Plus /></el-icon></button>

          <!-- 图片预览条 -->
          <div v-if="pendingImages.length" class="img-preview-row">
            <div v-for="(img, idx) in pendingImages" :key="img.localId" class="img-thumb"
                 :class="{ uploading: img.status === 'uploading', error: img.status === 'error' }">
              <img
                v-if="img.previewUrl"
                :src="img.previewUrl"
                alt=""
                :class="{ clickable: img.status === 'done' }"
                @click="img.status === 'done' && (lightboxUrl = img.url || img.previewUrl)"
              />
              <div v-if="img.status === 'uploading'" class="thumb-overlay">
                <div class="spinner-mini"></div>
              </div>
              <div
                v-else-if="img.status === 'error'"
                class="thumb-overlay error-overlay"
                :title="(img.error ? img.error + ' · ' : '') + '点击重试'"
                @click="retryUpload(img)"
              >
                <el-icon size="16"><RefreshRight /></el-icon>
              </div>
              <button class="thumb-close" @click="removePendingImage(idx)">
                <el-icon size="12"><Close /></el-icon>
              </button>
            </div>
          </div>

          <!-- 附件预览条（文档） -->
          <div v-if="pendingFiles.length" class="file-preview-row">
            <div v-for="(f, idx) in pendingFiles" :key="f.localId" class="file-chip"
                 :class="{ uploading: f.status === 'uploading' || f.status === 'transcribing', error: f.status === 'error' }"
                 :title="f.status === 'error' ? (f.error || '上传失败') + ' · 点击重试' : (f.status === 'transcribing' ? '音视频转写中，完成后才能发送' : f.name)"
                 @click="f.status === 'error' && retryAttachment(f)">
              <el-icon class="file-chip-ic" size="15">
                <Loading v-if="f.status === 'uploading' || f.status === 'transcribing'" class="spin" />
                <RefreshRight v-else-if="f.status === 'error'" />
                <VideoCamera v-else-if="f.media" />
                <Document v-else />
              </el-icon>
              <span class="file-chip-name">{{ f.name }}</span>
              <span v-if="f.status === 'transcribing'" class="file-chip-size">转写中…</span>
              <span v-else class="file-chip-size">{{ formatBytes(f.size) }}</span>
              <button class="file-chip-close" @click.stop="removePendingFile(idx)">
                <el-icon size="12"><Close /></el-icon>
              </button>
            </div>
          </div>

          <el-input
            v-model="inputText"
            type="textarea"
            :autosize="{ minRows: 1, maxRows: 6 }"
            :placeholder="isStreaming ? '正在生成，请稍候...' : (pendingImages.length ? '为图片添加说明' : '向企业知识库提问')"
            :disabled="isStreaming"
            @keydown.enter.exact="handleEnterSend"
            @compositionstart="onImeCompositionStart"
            @compositionend="onImeCompositionEnd"
            @paste="handlePaste"
            class="chat-input"
          />
          <!-- 发送按钮（独立 absolute · v3.4 移出 toolbar） -->
          <button
            v-if="isStreaming"
            class="send-btn stop-btn active"
            title="停止生成"
            aria-label="停止生成"
            @click="stopGeneration"
          >
            <el-icon size="15"><CloseBold /></el-icon>
          </button>
          <button
            v-else
            class="send-btn"
            title="发送"
            aria-label="发送"
            :disabled="(!inputText.trim() && !pendingImages.length && !pendingFiles.length) || hasPendingUpload"
            :class="{ active: (inputText.trim() || pendingImages.length || pendingFiles.length) && !hasPendingUpload }"
            @click="handleSend"
          >
            <el-icon size="16"><Position /></el-icon>
          </button>
          <div class="input-toolbar">
            <div class="toolbar-left">
              <input ref="attachmentFileInput" type="file" :accept="ATTACHMENT_ACCEPT + ',image/*'" multiple style="display:none"
                     @change="handleAttachmentFileChange" />
              <button class="toolbar-icon-btn" :disabled="isStreaming"
                      title="上传附件（图片 / PDF / Word / Excel / PPT / TXT、音频 / 视频，AI 自动识别分析）"
                      @click="triggerAttachmentUpload">
                <el-icon size="15"><Paperclip /></el-icon><span class="tool-label">附件</span>
              </button>
              <VoiceInputButton
                @partial="onVoicePartial"
                @final="onVoiceFinal"
              />
              <!-- H5 · 语音通话图标融入输入框（桌面端用右上角浮动按钮，由 CSS 控制显隐） -->
              <button
                class="toolbar-icon-btn toolbar-call-btn"
                :class="{ 'is-active': callDialogVisible }"
                :title="selectedKbIds.length ? `在 ${selectedKbIds.length} 个知识库中通话` : '在全部知识库中通话'"
                @click="openCallDialog"
              >
                <el-icon size="16"><PhoneFilled /></el-icon>
              </button>
              <!-- 联网开关：开启后本轮允许 AI 联网检索最新信息；关闭则只用知识库 -->
              <button
                class="toolbar-icon-btn toolbar-web-btn"
                :class="{ 'is-active': webSearchEnabled }"
                :title="webSearchTitle"
                @click="toggleWebSearch"
              >
                <el-icon size="16"><Connection /></el-icon>
                <span class="web-btn-label">联网</span>
              </button>
              <!-- 深度总结开关：开启后对"总结/综述/对比"类问题走高召回+专业结构化输出（更慢更专业） -->
              <button
                class="toolbar-icon-btn toolbar-web-btn"
                :class="{ 'is-active': deepSummaryEnabled }"
                :title="deepSummaryEnabled ? '深度总结已开启 · 多文档高质量综合（更慢，点击关闭）' : '深度总结已关闭 · 普通快答（点击开启）'"
                @click="deepSummaryEnabled = !deepSummaryEnabled"
              >
                <el-icon size="16"><Document /></el-icon>
                <span class="web-btn-label">深度总结</span>
              </button>
              <span v-if="inputText.length > 600" class="char-count" :class="{ warn: inputText.length > 800 }">
                {{ inputText.length }}/800
              </span>
            </div>
            <div class="toolbar-right">
            </div>
          </div>
        </div>
        <p class="input-disclaimer">
          默认自动全库检索；图片支持 JPG/PNG/WEBP，单张 ≤ 10MB，单次最多 8 张
        </p>
      </div>
    </div>

    <!-- ===== PC 端参考来源左右分栏面板（移动端走下方 el-drawer） ===== -->
    <div
      v-if="!isMobileViewport && sourcesDrawerVisible"
      class="preview-panel sources-panel"
      :style="{ width: sourcesPanelWidth + 'px' }"
    >
      <div class="preview-resizer" @pointerdown="startSourcesResize"></div>
      <div class="preview-panel-head">
        <span class="preview-panel-title">参考来源</span>
        <button class="preview-panel-close" title="关闭来源" @click="sourcesDrawerVisible = false">
          <el-icon :size="16"><Close /></el-icon>
        </button>
      </div>
      <div class="sources-drawer-body preview-panel-body">
        <div v-if="sourcesDrawerData.length === 0" class="empty">暂无来源</div>
        <div
          v-for="(src, i) in sourcesDrawerData"
          :key="i"
          :ref="(el: any) => registerSourceCard(el, Number(i))"
          class="source-card"
          :class="[`media-${src.mediaType || 'document'}`, { 'cite-highlight': (src.index ?? Number(i) + 1) === highlightCiteIndex }]"
        >
          <!-- Golden Pair -->
          <template v-if="src.type === 'golden_pair'">
            <div class="src-header golden">
              <span class="src-idx">[{{ src.index ?? (Number(i) + 1) }}]</span>
              <el-icon size="13" color="#10b981"><CircleCheckFilled /></el-icon>
              <span class="src-name golden-name">人工校正的标准答案</span>
              <span v-if="src.hitCount" class="src-score">被命中 {{ src.hitCount }} 次</span>
            </div>
            <div class="src-golden-body">
              <div v-if="src.matchedQuestion" class="golden-row">
                <span class="golden-label">匹配问题：</span><span>{{ src.matchedQuestion }}</span>
              </div>
              <div class="golden-row">
                <span class="golden-label">校正来源：</span>
                <span>{{ src.verifiedBy || '管理员' }} · Golden Pair #{{ src.pairId }}</span>
              </div>
            </div>
          </template>
          <!-- 普通知识库 chunk -->
          <template v-else>
            <div class="src-header">
              <span class="src-idx">[{{ src.index ?? (Number(i) + 1) }}]</span>
              <span v-if="src.type === 'web'" class="src-web-badge">🌐 网络搜索</span>
              <span class="src-media-badge" v-if="src.mediaType">{{ mediaTypeLabel(src.mediaType) }}</span>
              <span class="src-name">{{ src.name || '未命名来源' }}</span>
              <span v-if="src.score && !src.directRead" class="src-score">{{ (src.score * 100).toFixed(0) }}%</span>
            </div>
            <div class="src-excerpt" :class="{ expanded: src._expanded }">{{ src.content }}</div>
            <button
              v-if="(src.content || '').length > 80"
              class="src-expand-btn"
              @click="src._expanded = !src._expanded"
            >
              {{ src._expanded ? '收起' : '展开全文' }}
            </button>
            <div class="src-meta">
              <span v-if="src.chapter">{{ src.chapter }}</span>
              <span v-if="src.pageNumber && src.mediaType !== 'video' && src.mediaType !== 'audio' && src.mediaType !== 'image'">
                · 第 {{ src.pageNumber }} 页
              </span>
              <span v-if="src.startMs != null" class="src-timestamp">
                · {{ formatMediaTime(src.startMs) }}
                <template v-if="hasValidEndMs(src)">~ {{ formatMediaTime(displayEndMs(src)) }}</template>
              </span>
            </div>
            <div class="src-actions">
              <button
                v-if="canOpenOriginal(src)"
                class="src-open-btn"
                :disabled="src._opening"
                @click="openOriginalDoc(src)"
              >
                <el-icon :size="13"><Document /></el-icon>
                <span>{{ src._opening ? '加载中...' : '查看原文' }}</span>
              </button>
            </div>

            <!-- 音频/视频内嵌播放器 + 自动 seek -->
            <div v-if="(src.mediaType === 'audio' || src.mediaType === 'video') && src.sourceObjectName" class="src-media-player">
              <button class="src-play-btn" @click="openMediaSource(src)">
                <el-icon :size="14"><VideoPlay /></el-icon>
                <span>跳转到 {{ formatMediaTime(src.startMs) }} 播放</span>
              </button>
              <component
                v-if="src._loaded"
                :is="src.mediaType === 'audio' ? 'audio' : 'video'"
                :ref="(el: any) => registerMediaPlayer(el, src)"
                :src="src._mediaUrl"
                controls
                preload="metadata"
                class="src-media-element"
                @loadedmetadata="seekMediaTo(src)"
              />
            </div>
          </template>
        </div>
      </div>
    </div>

    <!-- ===== PC 端原文左右分栏面板（#4④ · 移动端走下方 el-drawer） ===== -->
    <div
      v-if="!isMobileViewport && previewDrawerVisible"
      class="preview-panel"
      :style="{ width: previewPanelWidth + 'px' }"
    >
      <div class="preview-resizer" @pointerdown="startPreviewResize"></div>
      <div class="preview-panel-head">
        <span class="preview-panel-title" :title="previewTitle">{{ previewTitle }}</span>
        <button class="preview-panel-close" title="关闭原文" @click="previewDrawerVisible = false">
          <el-icon :size="16"><Close /></el-icon>
        </button>
      </div>
      <div class="preview-drawer-body preview-panel-body" v-loading="previewLoading">
        <img v-if="previewKind === 'image'" :src="previewUrl" class="preview-image" />
        <iframe v-else-if="previewKind === 'iframe'" :src="previewUrl" class="preview-iframe"></iframe>
        <pre v-else-if="previewKind === 'text'" class="preview-text">{{ previewText }}</pre>
        <div v-else-if="previewKind === 'office'" ref="officeContainerRef" class="preview-office"></div>
        <div v-else-if="previewKind === 'unsupported'" class="preview-unsupported">
          <el-icon :size="40" color="#94a3b8"><Document /></el-icon>
          <p>{{ previewMessage }}</p>
        </div>
      </div>
    </div>

    <!-- ===== 检索过程弹窗 ===== -->
    <el-dialog
      v-model="retrievalLogVisible"
      title="RAG 检索过程"
      width="660px"
      class="rl-dialog"
    >
      <div v-if="currentRetrievalLog" class="rl-content">
        <!-- 管道步骤 -->
        <div class="rl-pipeline">
          <div v-for="(step, i) in pipelineSteps" :key="i" class="rl-step-wrap">
            <div class="rl-step" :style="{ '--step-color': step.color }">
              <span class="step-dot"></span>
              <span>{{ step.label }}</span>
            </div>
            <div v-if="i < pipelineSteps.length - 1" class="rl-step-arrow">→</div>
          </div>
        </div>

        <!-- Query 对比 -->
        <div class="rl-block">
          <div class="rl-block-title">Query 改写</div>
          <div class="rl-query-row">
            <div class="rl-query-box">
              <div class="rl-query-label">原始</div>
              <div class="rl-query-text">{{ currentRetrievalLog.originalQuery || '-' }}</div>
            </div>
            <el-icon color="#38bdf8"><Right /></el-icon>
            <div class="rl-query-box improved">
              <div class="rl-query-label">改写后</div>
              <div class="rl-query-text">{{ currentRetrievalLog.rewrittenQuery || '-' }}</div>
            </div>
          </div>
        </div>

        <!-- 检索数字 -->
        <div class="rl-block">
          <div class="rl-block-title">多路召回 & RRF 融合</div>
          <div class="rl-metrics">
            <div class="rl-metric">
              <div class="rl-metric-val" style="color:#71717a">{{ currentRetrievalLog.vectorResults ?? 0 }}</div>
              <div class="rl-metric-lbl">向量检索</div>
            </div>
            <span style="color:#475569;font-size:20px">+</span>
            <div class="rl-metric">
              <div class="rl-metric-val" style="color:#38bdf8">{{ currentRetrievalLog.bm25Results ?? 0 }}</div>
              <div class="rl-metric-lbl">BM25 检索</div>
            </div>
            <span style="color:#475569;font-size:20px">→</span>
            <div class="rl-metric">
              <div class="rl-metric-val" style="color:#34d399">{{ currentRetrievalLog.rrfCount ?? 0 }}</div>
              <div class="rl-metric-lbl">RRF 融合</div>
            </div>
          </div>
        </div>

        <!-- 结果状态 -->
        <div class="rl-block">
          <div class="rl-block-title">重排序结果</div>
          <div class="rl-result-row">
            <span style="color:#94a3b8">
              注入 <strong style="color:#e2e8f0">{{ currentRetrievalLog.rerankTop ?? 5 }}</strong> 条上下文
            </span>
            <el-tag :type="currentRetrievalLog.isFallback ? 'warning' : 'success'" size="large" effect="light">
              {{ currentRetrievalLog.isFallback ? '⚠ 低置信度，兜底响应' : '✓ 检索命中，正常响应' }}
            </el-tag>
          </div>
        </div>
      </div>
      <div v-else class="rl-empty">暂无检索日志</div>
    </el-dialog>

    <!-- ===== 语音通话对话框 · 复用 chat 选中的 KB ===== -->
    <el-dialog
      v-model="callDialogVisible"
      :title="callDialogTitle"
      width="520px"
      :close-on-click-modal="false"
      :before-close="onCallDialogClose"
      class="voice-call-dialog"
    >
      <VoiceCallPanel
        v-if="callDialogVisible"
        :kb-ids="selectedKbIds"
        :collection-ids="selectedCollectionIds"
        :kb-scope-label="callScopeLabel"
        @close="callDialogVisible = false"
      />
    </el-dialog>

    <!-- ===== 纠正答案对话框 ===== -->
    <el-dialog
      v-model="correctionVisible"
      title="我来提供正确答案"
      width="640px"
      :close-on-click-modal="false"
      class="correction-dialog"
    >
      <div class="correction-hint">
        <el-icon size="14" color="#38bdf8"><EditPen /></el-icon>
        <span>你的修正会进入审核队列，审核通过后会自动作为该类问题的标准答案，实现"AI 越用越准"</span>
      </div>
      <el-input
        v-model="correctionText"
        type="textarea"
        :autosize="{ minRows: 8, maxRows: 18 }"
        placeholder="请输入正确答案，或在 AI 答复的基础上修改"
        maxlength="5000"
        show-word-limit
      />
      <template #footer>
        <el-button @click="correctionVisible = false">取消</el-button>
        <el-button type="primary" :loading="correctionSubmitting" @click="submitCorrection">
          提交审核
        </el-button>
      </template>
    </el-dialog>

    <!-- ===== 参考来源右侧抽屉（移动端 · PC 端走上方左右分栏） ===== -->
    <el-drawer
      v-if="isMobileViewport"
      v-model="sourcesDrawerVisible"
      title="参考来源"
      direction="rtl"
      size="92%"
      :append-to-body="true"
    >
      <div class="sources-drawer-body">
        <div v-if="sourcesDrawerData.length === 0" class="empty">暂无来源</div>
        <div
          v-for="(src, i) in sourcesDrawerData"
          :key="i"
          :ref="(el: any) => registerSourceCard(el, Number(i))"
          class="source-card"
          :class="[`media-${src.mediaType || 'document'}`, { 'cite-highlight': (src.index ?? Number(i) + 1) === highlightCiteIndex }]"
        >
          <!-- Golden Pair -->
          <template v-if="src.type === 'golden_pair'">
            <div class="src-header golden">
              <span class="src-idx">[{{ src.index ?? (Number(i) + 1) }}]</span>
              <el-icon size="13" color="#10b981"><CircleCheckFilled /></el-icon>
              <span class="src-name golden-name">人工校正的标准答案</span>
              <span v-if="src.hitCount" class="src-score">被命中 {{ src.hitCount }} 次</span>
            </div>
            <div class="src-golden-body">
              <div v-if="src.matchedQuestion" class="golden-row">
                <span class="golden-label">匹配问题：</span><span>{{ src.matchedQuestion }}</span>
              </div>
              <div class="golden-row">
                <span class="golden-label">校正来源：</span>
                <span>{{ src.verifiedBy || '管理员' }} · Golden Pair #{{ src.pairId }}</span>
              </div>
            </div>
          </template>
          <!-- 普通知识库 chunk -->
          <template v-else>
            <div class="src-header">
              <span class="src-idx">[{{ src.index ?? (Number(i) + 1) }}]</span>
              <span v-if="src.type === 'web'" class="src-web-badge">🌐 网络搜索</span>
              <span class="src-media-badge" v-if="src.mediaType">{{ mediaTypeLabel(src.mediaType) }}</span>
              <span class="src-name">{{ src.name || '未命名来源' }}</span>
              <span v-if="src.score && !src.directRead" class="src-score">{{ (src.score * 100).toFixed(0) }}%</span>
            </div>
            <div class="src-excerpt" :class="{ expanded: src._expanded }">{{ src.content }}</div>
            <button
              v-if="(src.content || '').length > 80"
              class="src-expand-btn"
              @click="src._expanded = !src._expanded"
            >
              {{ src._expanded ? '收起' : '展开全文' }}
            </button>
            <div class="src-meta">
              <span v-if="src.chapter">{{ src.chapter }}</span>
              <span v-if="src.pageNumber && src.mediaType !== 'video' && src.mediaType !== 'audio' && src.mediaType !== 'image'">
                · 第 {{ src.pageNumber }} 页
              </span>
              <span v-if="src.startMs != null" class="src-timestamp">
                · {{ formatMediaTime(src.startMs) }}
                <template v-if="hasValidEndMs(src)">~ {{ formatMediaTime(displayEndMs(src)) }}</template>
              </span>
            </div>
            <div class="src-actions">
              <button
                v-if="canOpenOriginal(src)"
                class="src-open-btn"
                :disabled="src._opening"
                @click="openOriginalDoc(src)"
              >
                <el-icon :size="13"><Document /></el-icon>
                <span>{{ src._opening ? '加载中...' : '查看原文' }}</span>
              </button>
            </div>

            <!-- 音频/视频内嵌播放器 + 自动 seek -->
            <div v-if="(src.mediaType === 'audio' || src.mediaType === 'video') && src.sourceObjectName" class="src-media-player">
              <button class="src-play-btn" @click="openMediaSource(src)">
                <el-icon :size="14"><VideoPlay /></el-icon>
                <span>跳转到 {{ formatMediaTime(src.startMs) }} 播放</span>
              </button>
              <component
                v-if="src._loaded"
                :is="src.mediaType === 'audio' ? 'audio' : 'video'"
                :ref="(el: any) => registerMediaPlayer(el, src)"
                :src="src._mediaUrl"
                controls
                preload="metadata"
                class="src-media-element"
                @loadedmetadata="seekMediaTo(src)"
              />
            </div>
          </template>
        </div>
      </div>
    </el-drawer>

    <!-- ===== 原文预览右侧抽屉（移动端 · PC 端走上方左右分栏 #4④） ===== -->
    <el-drawer
      v-if="isMobileViewport"
      v-model="previewDrawerVisible"
      :title="previewTitle"
      direction="rtl"
      size="92%"
      :append-to-body="true"
    >
      <template #header>
        <div class="preview-header">
          <span class="preview-title-text">{{ previewTitle }}</span>
        </div>
      </template>
      <div class="preview-drawer-body" v-loading="previewLoading">
        <!-- 图片 -->
        <img v-if="previewKind === 'image'" :src="previewUrl" class="preview-image" />
        <!-- PDF / 可在线预览的 -->
        <iframe v-else-if="previewKind === 'iframe'" :src="previewUrl" class="preview-iframe"></iframe>
        <!-- 文本 / markdown 内联 -->
        <pre v-else-if="previewKind === 'text'" class="preview-text">{{ previewText }}</pre>
        <!-- Office（docx / xls(x)）前端渲染 -->
        <div v-else-if="previewKind === 'office'" ref="officeContainerRef" class="preview-office"></div>
        <!-- 不支持在线预览（view-only：不提供下载入口） -->
        <div v-else-if="previewKind === 'unsupported'" class="preview-unsupported">
          <el-icon :size="40" color="#94a3b8"><Document /></el-icon>
          <p>{{ previewMessage }}</p>
        </div>
      </div>
    </el-drawer>

    <!-- 待发送图片 · 点击缩略图看大图（#8） -->
    <Teleport to="body">
      <div v-if="lightboxUrl" class="img-lightbox" @click="lightboxUrl = null">
        <img :src="lightboxUrl" class="img-lightbox-img" alt="" />
        <button class="img-lightbox-close" @click.stop="lightboxUrl = null">
          <el-icon :size="20"><Close /></el-icon>
        </button>
      </div>

      <!-- 角标悬停来源卡片（类 DeepSeek）-->
      <div
        v-if="citeCard.visible && citeCard.src"
        class="cite-card"
        :style="{ left: citeCard.x + 'px', top: citeCard.y + 'px' }"
      >
        <div class="cite-card-head">
          <span class="cite-card-kind">{{ citeCardKind(citeCard.src) }}</span>
          <span class="cite-card-name">{{ citeCard.src.name || '来源' }}</span>
        </div>
        <div v-if="citeCard.src.chapter || citeCard.src.pageNumber" class="cite-card-sub">
          <span v-if="citeCard.src.chapter">{{ citeCard.src.chapter }}</span>
          <span v-if="citeCard.src.pageNumber">· 第 {{ citeCard.src.pageNumber }} 页</span>
        </div>
        <div class="cite-card-snippet">{{ (citeCard.src.content || '').slice(0, 140) }}{{ (citeCard.src.content || '').length > 140 ? '…' : '' }}</div>
        <div class="cite-card-foot">{{ citeCardAction(citeCard.src) }}</div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
// keep-alive 需要组件 name 才能匹配 include · 切菜单回来流式继续不丢
defineOptions({ name: 'ChatView' })
import { ref, reactive, onMounted, onActivated, onBeforeUnmount, nextTick, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, ChatDotRound, ChatLineSquare, MagicStick, ArrowDown, Clock,
         Document, DataAnalysis, CopyDocument,
         Position, FolderOpened, Right, VideoPlay, PhoneFilled, Download,
         EditPen, CircleCheckFilled, Close, CloseBold, RefreshRight, Link, ArrowRight, Paperclip, VideoCamera, Connection, Coin, Search } from '@element-plus/icons-vue'
import { marked } from 'marked'
// docx-preview / xlsx 体积较大，按需 dynamic import（见 openOriginalDoc），不进主包
import { chatApi, type Conversation, type Message } from '@/api/chat'
import { feedbackApi } from '@/api/feedback'
import { knowledgeApi } from '@/api/knowledge'
import { collectionApi } from '@/api/collection'
import { dataSourceApi, type DataSourceBrief } from '@/api/datasource'
import { useUserStore } from '@/stores/user'
import VoiceCallPanel from '@/components/VoiceCallPanel.vue'
import VoiceInputButton from '@/components/VoiceInputButton.vue'
import BrandLogo from '@/components/BrandLogo.vue'
import DbResultCard from '@/components/DbResultCard.vue'
import { useBrandStore } from '@/stores/brand'

// 配置 marked
marked.setOptions({ breaks: true, gfm: true })

const route     = useRoute()
const router    = useRouter()
const userStore = useUserStore()
const brandStore = useBrandStore()

// ── 状态 ──
// 移动端会话抽屉开关（≤768px 才生效，桌面端不可见也不影响布局）
const chatSidebarOpen  = ref(false)
const conversations    = ref<Conversation[]>([])
const convLoading      = ref(false)
const currentConvId    = ref<number | null>(null)
const messages         = ref<any[]>([])
const messagesAreaRef  = ref<HTMLElement>()
const inputText        = ref('')
const downloadingWordMessageId = ref<number | null>(null)

// 输入法确认候选词也会触发 Enter。额外保留短暂保护窗，
// 兼容部分浏览器在 compositionend 后补发一次 keydown 的行为。
let imeComposing = false
let suppressEnterUntil = 0
const onImeCompositionStart = () => {
  imeComposing = true
}
const onImeCompositionEnd = () => {
  imeComposing = false
  suppressEnterUntil = Date.now() + 120
}
const shouldIgnoreImeEnter = (event: KeyboardEvent) =>
  imeComposing || event.isComposing || event.keyCode === 229 || Date.now() < suppressEnterUntil

const isWordExportIntent = (text?: string): boolean => {
  const normalized = (text || '').replace(/\s+/g, '').toLowerCase()
  if (!/(word|docx|微软文档)/i.test(normalized)) return false
  return /(生成|整理|输出|导出|制作|形成|写成|转成|转换|汇总|下载)/.test(normalized)
}

// ── 联网偏好：默认“自动判断”，用户手动开/关后才作为明确偏好持久化 ──
// 未设置时请求不传 webSearch，后端可对“最新/市场/竞品”等时效性问题自动联网；
// 用户点过开关后显式传 true/false，始终尊重用户选择。
const storedWebSearchPreference = localStorage.getItem('chat_web_search')
const webSearchEnabled = ref(storedWebSearchPreference === '1')
const webSearchPreferenceExplicit = ref(storedWebSearchPreference !== null)
const webSearchHint = computed(() => {
  if (!webSearchPreferenceExplicit.value) return '自动 · 市场/最新问题按需联网'
  return webSearchEnabled.value ? '已开启 · AI 可检索网络' : '已关闭 · 仅用知识库'
})
const webSearchTitle = computed(() => {
  if (!webSearchPreferenceExplicit.value) return '联网自动判断 · 市场/最新问题按需联网（点击强制开启）'
  return webSearchEnabled.value
    ? '联网已开启 · AI 可检索最新网络信息（点击关闭）'
    : '联网已关闭 · 仅用知识库（点击开启）'
})
const toggleWebSearch = () => {
  webSearchPreferenceExplicit.value = true
  webSearchEnabled.value = !webSearchEnabled.value
  localStorage.setItem('chat_web_search', webSearchEnabled.value ? '1' : '0')
}
// 深度总结开关:开启则强制走高召回+专业结构化总结(可控,适合多文档综合;更慢)
const deepSummaryEnabled = ref(localStorage.getItem('chat_deep_summary') === '1')
watch(deepSummaryEnabled, (v) => localStorage.setItem('chat_deep_summary', v ? '1' : '0'))

// ── 图片输入 · 任务 10 ──
interface PendingImage {
  localId: number
  file: File
  previewUrl: string         // 本地 blob URL，仅用于预览
  status: 'uploading' | 'done' | 'error'
  objectName?: string        // 上传成功后的 OSS objectName
  url?: string               // 后端返回的可访问 URL
  error?: string
}
const pendingImages = ref<PendingImage[]>([])
const isDraggingImage = ref(false)

// ─── 附件（文档）上传 ───
interface PendingFile {
  localId: number
  file: File
  name: string
  size: number
  // transcribing：音视频上传后异步转写中，转写完成（done）才可发送
  status: 'uploading' | 'transcribing' | 'done' | 'error'
  objectName?: string
  media?: boolean
  error?: string
}
const pendingFiles = ref<PendingFile[]>([])
const attachmentFileInput = ref<HTMLInputElement>()
// 后端 ALLOWED_ATTACHMENT_EXTS 的镜像（DocumentExtractor 支持集减去图片类）
const DOC_ACCEPT = '.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx,.csv,.wps,.html,.htm,.txt,.md,.markdown'
// 音视频：上传后异步转写，AI 据转写内容回答
const MEDIA_ACCEPT = '.mp3,.wav,.m4a,.aac,.flac,.opus,.ogg,.amr,.mp4,.mov,.mkv,.avi,.flv,.webm,.m4v'
const ATTACHMENT_ACCEPT = DOC_ACCEPT + ',' + MEDIA_ACCEPT
const MEDIA_EXTS = MEDIA_ACCEPT.split(',').map(s => s.replace('.', ''))
const MAX_ATTACHMENT_BYTES = 200 * 1024 * 1024
const MAX_MEDIA_BYTES = 300 * 1024 * 1024
const isMediaFile = (f: File): boolean =>
  MEDIA_EXTS.includes((f.name.split('.').pop() || '').toLowerCase())

const hasPendingUpload = computed(() =>
  pendingImages.value.some(i => i.status === 'uploading') ||
  pendingFiles.value.some(f => f.status === 'uploading' || f.status === 'transcribing'))

const formatBytes = (n: number): string => {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}


const handleImageDrop = (e: DragEvent) => {
  isDraggingImage.value = false
  const files = e.dataTransfer?.files
  if (!files) return
  const all = Array.from(files)
  const imgs = all.filter(f => f.type.startsWith('image/'))
  const docs = all.filter(f => !f.type.startsWith('image/'))
  if (imgs.length) addImages(imgs)
  if (docs.length) addFiles(docs)   // 非图片文件作为附件处理
}

const handlePaste = (e: ClipboardEvent) => {
  const items = e.clipboardData?.items
  if (!items) return
  const imgs: File[] = []
  for (const item of items) {
    if (item.type.startsWith('image/')) {
      const f = item.getAsFile()
      if (f) imgs.push(f)
    }
  }
  if (imgs.length) {
    e.preventDefault()
    addImages(imgs)
  }
}

const addImages = (files: File[]) => {
  const remaining = 8 - pendingImages.value.length
  if (remaining <= 0) {
    ElMessage.warning('单次最多 8 张图片')
    return
  }
  const accepted = files.slice(0, remaining)
  if (files.length > remaining) ElMessage.warning(`已选 ${pendingImages.value.length} 张，本次只接受前 ${remaining} 张`)

  for (const f of accepted) {
    if (f.size > 10 * 1024 * 1024) {
      ElMessage.error(`${f.name} 超过 10MB，已跳过`)
      continue
    }
    const pi: PendingImage = {
      localId: Date.now() + Math.random(),
      file: f,
      previewUrl: URL.createObjectURL(f),
      status: 'uploading',
    }
    pendingImages.value.push(pi)
    uploadImage(pi)
  }
}

const uploadImage = async (pi: PendingImage) => {
  // ⚠️ 必须改「数组里的响应式代理元素」，不能改入参 pi（push 前的原始对象）：
  //    直接改 pi.status 不会触发 Vue 响应式，会导致一直转圈 + hasPendingUpload 卡在 true 无法发送。
  //    每次用 localId 重新定位，顺便兼容上传途中用户已删图的情况。
  const target = () => pendingImages.value.find(i => i.localId === pi.localId)
  const fd = new FormData()
  fd.append('file', pi.file)
  try {
    const res = await fetch('/api/v2/chat/upload-image', {
      method: 'POST',
      body: fd,
      headers: { Authorization: 'Bearer ' + (localStorage.getItem('token') || '') },
    })
    if (!res.ok) {
      const text = await res.text()
      throw new Error(`HTTP ${res.status} · ${text.slice(0, 200)}`)
    }
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '上传失败')
    const t = target()
    if (t) {
      t.status = 'done'
      t.objectName = json.data.objectName
      t.url = json.data.url
    }
  } catch (e: any) {
    const t = target()
    if (t) {
      t.status = 'error'
      t.error  = e?.message || '上传失败'
    }
    ElMessage.error(`图片上传失败：${e?.message || '上传失败'}`)
  }
}

const removePendingImage = (idx: number) => {
  const img = pendingImages.value[idx]
  if (img?.previewUrl) URL.revokeObjectURL(img.previewUrl)
  pendingImages.value.splice(idx, 1)
}

// 上传失败 → 点击重试（#8）
const retryUpload = (img: PendingImage) => {
  const t = pendingImages.value.find(i => i.localId === img.localId)
  if (!t || t.status === 'uploading') return
  t.status = 'uploading'
  t.error = undefined
  uploadImage(t)
}

// ─── 附件（文档）上传逻辑 ───
const triggerAttachmentUpload = () => attachmentFileInput.value?.click()

const handleAttachmentFileChange = (e: Event) => {
  const files = (e.target as HTMLInputElement).files
  if (files) {
    // 附件按钮统一入口：图片走多模态图片通道，其余走附件解析通道
    const all = Array.from(files)
    const imgs = all.filter(f => f.type.startsWith('image/'))
    const docs = all.filter(f => !f.type.startsWith('image/'))
    if (imgs.length) addImages(imgs)
    if (docs.length) addFiles(docs)
  }
  if (attachmentFileInput.value) attachmentFileInput.value.value = ''
}

const isAcceptedAttachment = (f: File): boolean => {
  const ext = '.' + (f.name.split('.').pop() || '').toLowerCase()
  return ATTACHMENT_ACCEPT.split(',').includes(ext)
}

const addFiles = (files: File[]) => {
  const remaining = 5 - pendingFiles.value.length
  if (remaining <= 0) { ElMessage.warning('单次最多 5 个附件'); return }
  const accepted = files.slice(0, remaining)
  if (files.length > remaining) ElMessage.warning(`已选 ${pendingFiles.value.length} 个，本次只接受前 ${remaining} 个`)

  for (const f of accepted) {
    if (!isAcceptedAttachment(f)) {
      ElMessage.error(`${f.name} 格式不支持，已跳过`)
      continue
    }
    const media = isMediaFile(f)
    const maxBytes = media ? MAX_MEDIA_BYTES : MAX_ATTACHMENT_BYTES
    if (f.size > maxBytes) {
      ElMessage.error(`${f.name} 超过 ${maxBytes / 1024 / 1024}MB，已跳过`)
      continue
    }
    const pf: PendingFile = {
      localId: Date.now() + Math.random(),
      file: f,
      name: f.name,
      size: f.size,
      status: 'uploading',
      media,
    }
    pendingFiles.value.push(pf)
    uploadAttachment(pf)
  }
}

const uploadAttachment = async (pf: PendingFile) => {
  // 与 uploadImage 同理：必须改数组里的响应式代理元素，按 localId 重新定位
  const target = () => pendingFiles.value.find(f => f.localId === pf.localId)
  const fd = new FormData()
  fd.append('file', pf.file)
  try {
    const res = await fetch('/api/v2/chat/upload-attachment', {
      method: 'POST',
      body: fd,
      headers: { Authorization: 'Bearer ' + (localStorage.getItem('token') || '') },
    })
    if (!res.ok) {
      const text = await res.text()
      throw new Error(`HTTP ${res.status} · ${text.slice(0, 200)}`)
    }
    const json = await res.json()
    if (json.code !== 200) throw new Error(json.message || '上传失败')
    const t = target()
    if (t) {
      t.objectName = json.data.objectName
      if (json.data.media) {
        // 音视频：进入转写中，轮询直到 ready
        t.status = 'transcribing'
        pollTranscription(pf.localId, json.data.objectName)
      } else {
        t.status = 'done'
      }
    }
  } catch (e: any) {
    const t = target()
    if (t) {
      t.status = 'error'
      t.error = e?.message || '上传失败'
    }
    ElMessage.error(`附件上传失败：${e?.message || '上传失败'}`)
  }
}

// 轮询音视频转写状态（最多约 10 分钟）
const pollTranscription = async (localId: number, objectName: string) => {
  const target = () => pendingFiles.value.find(f => f.localId === localId)
  const deadline = Date.now() + 10 * 60 * 1000
  while (Date.now() < deadline) {
    await new Promise(r => setTimeout(r, 3000))
    const t = target()
    if (!t || t.status !== 'transcribing') return   // 已被移除或状态变更
    try {
      const res = await fetch(`/api/v2/chat/attachment-status?objectName=${encodeURIComponent(objectName)}`, {
        headers: { Authorization: 'Bearer ' + (localStorage.getItem('token') || '') },
      })
      const json = await res.json()
      const status = json?.data?.status
      if (status === 'ready') {
        const tt = target(); if (tt) tt.status = 'done'
        return
      }
      if (status === 'failed') {
        const tt = target()
        if (tt) { tt.status = 'error'; tt.error = json?.data?.errorMsg || '转写失败' }
        ElMessage.error(`「${t.name}」转写失败`)
        return
      }
    } catch { /* 网络抖动忽略，继续轮询 */ }
  }
  const t = target()
  if (t && t.status === 'transcribing') { t.status = 'error'; t.error = '转写超时' }
}

const removePendingFile = (idx: number) => {
  pendingFiles.value.splice(idx, 1)
}

const retryAttachment = (f: PendingFile) => {
  const t = pendingFiles.value.find(i => i.localId === f.localId)
  if (!t || t.status === 'uploading') return
  t.status = 'uploading'
  t.error = undefined
  uploadAttachment(t)
}

// 缩略图点击看大图（#8）· 待发送图片预览
const lightboxUrl = ref<string | null>(null)

/** 从消息里抽出图片附件（兼容新消息的 .images 和历史消息从 sources 解析） */
const getMsgImages = (msg: any): { url?: string; previewUrl?: string }[] => {
  if (msg.images && msg.images.length) return msg.images
  if (!msg.sources) return []
  const arr = typeof msg.sources === 'string' ? safeJsonParse(msg.sources) : msg.sources
  if (!Array.isArray(arr)) return []
  return arr.filter((s: any) => s.type === 'user_image' && (s.url || s.objectName))
            .map((s: any) => ({ url: s.url }))
}

/** 从消息里抽出附件（兼容新消息的 .files 和历史消息从 sources 解析 user_attachment） */
const getMsgFiles = (msg: any): { name: string; size?: number }[] => {
  if (msg.files && msg.files.length) return msg.files
  if (!msg.sources) return []
  const arr = typeof msg.sources === 'string' ? safeJsonParse(msg.sources) : msg.sources
  if (!Array.isArray(arr)) return []
  return arr.filter((s: any) => s.type === 'user_attachment' && s.name)
            .map((s: any) => ({ name: s.name, size: s.sizeBytes }))
}

const safeJsonParse = (s: string): any => {
  try { return JSON.parse(s) } catch { return null }
}
const isStreaming      = ref(false)
let currentSse: EventSource | null = null   // 当前流式连接（打断用）
let streamingMsg: any = null                // 当前正在生成的消息对象
const knowledgeBases   = ref<{ id: number; name: string }[]>([])
const selectedKbIds    = ref<number[]>([])
// 任务 15：知识库（集合）选择 · 替代旧的「按文档多选」
const collections = ref<{ id: number; name: string; docCount: number; color?: string }[]>([])
// 数据源选择器（NL2SQL 范围）：选中后只查这些库；不选=全部可访问库由后端/模型路由
const datasources = ref<DataSourceBrief[]>([])
const selectedDatasourceIds = ref<number[]>([])
const selectedCollectionIds = ref<number[]>([])
// 技能包（方案A）：改为管理员后台设置全局生效，用户问答页不再选择/加载
const retrievalLogVisible = ref(false)
const currentRetrievalLog = ref<any>(null)
let scrollScheduled = false
let hasActivatedOnce = false
let consumingHomeQuestion = false

const showWelcome = computed(() => !currentConvId.value && messages.value.length === 0)
const conversationSearch = ref('')

const filteredConversations = computed(() => {
  const query = conversationSearch.value.trim().toLowerCase()
  if (!query) return conversations.value
  return conversations.value.filter(conv => formatConvTitle(conv).toLowerCase().includes(query))
})

const scopeSummary = computed(() => {
  const knowledgeCount = selectedCollectionIds.value.length
  const dataCount = selectedDatasourceIds.value.length
  if (!knowledgeCount && !dataCount) return '全部知识库'
  const parts: string[] = []
  if (knowledgeCount) parts.push(`${knowledgeCount} 个知识库`)
  if (dataCount) parts.push(`${dataCount} 个数据源`)
  return parts.join(' · ')
})

const currentConversationTitle = computed(() => {
  if (!currentConvId.value) return '新对话'
  const conversation = conversations.value.find(item => item.id === currentConvId.value)
  return conversation ? formatConvTitle(conversation) : '当前对话'
})

const quickQuestions = [
  { text: '总结本周重点工作并给出行动建议' },
  { text: '对比两款产品的核心差异与适用场景' },
  { text: '分析目标市场的主要竞品与机会' },
  { text: '从资料中提取关键数据与风险点' },
]

const useQuickQuestion = (question: string) => {
  inputText.value = question
}

const pipelineSteps = [
  { label: 'Query 改写', color: '#38bdf8' },
  { label: '多路召回',   color: '#71717a' },
  { label: 'RRF 融合',  color: '#34d399' },
  { label: 'Cross重排', color: '#fbbf24' },
  { label: 'LLM 生成',  color: '#f87171' },
]

// ── 生命周期 ──
const consumeHomeQuestion = async () => {
  const q = route.query.q
  if (consumingHomeQuestion || typeof q !== 'string' || !q.trim()) return

  consumingHomeQuestion = true
  try {
    if (route.query.web === '1' || route.query.web === '0') {
      webSearchPreferenceExplicit.value = true
      webSearchEnabled.value = route.query.web === '1'
      localStorage.setItem('chat_web_search', webSearchEnabled.value ? '1' : '0')
    }

    const cids = route.query.collections
    if (typeof cids === 'string' && cids) {
      const valid = new Set(collections.value.map(c => c.id))
      selectedCollectionIds.value = cids.split(',')
        .map(Number).filter(n => !Number.isNaN(n) && valid.has(n))
    }

    // ChatView 会被 keep-alive 缓存；清掉 query 避免刷新或下次激活时重复发送。
    await router.replace({ path: '/chat' })
    if (isStreaming.value) {
      // 上一轮仍在生成时不丢失首页带来的问题，放入输入框等用户发送。
      inputText.value = q.trim()
      ElMessage.info('已将首页问题带入输入框，当前回答完成后可继续发送')
      return
    }
    await sendMessage(q.trim())
  } finally {
    consumingHomeQuestion = false
  }
}

onMounted(async () => {
  await Promise.all([loadConversations(), loadKnowledgeBases(), loadCollections(), loadDatasources()])
  const idParam = route.params['id']
  if (idParam) { switchConversation(Number(idParam)); return }
  await consumeHomeQuestion()
})

// ChatView 被 keep-alive 缓存；从知识库管理页切回时需重新拉取，否则下拉框会停留在首次进入时的旧数据。
onActivated(async () => {
  if (!hasActivatedOnce) {
    hasActivatedOnce = true
    return
  }
  await loadCollections()
  await consumeHomeQuestion()
})

// ── 知识库 ──
const loadKnowledgeBases = async () => {
  try {
    const res = await knowledgeApi.list({ size: 50, status: 'ready' })
    knowledgeBases.value = (res.records || []).map((kb: any) => ({ id: kb.id, name: kb.name }))
  } catch { /* ignore */ }
}
// 任务 15：加载知识库（集合）
const loadCollections = async () => {
  try {
    const res: any = await collectionApi.list()
    const arr = res?.data ?? res ?? []
    collections.value = arr.map((c: any) => ({
      id: c.id, name: c.name, docCount: c.docCount ?? 0, color: c.color,
    }))
    const accessibleIds = new Set(collections.value.map(c => c.id))
    selectedCollectionIds.value = selectedCollectionIds.value.filter(id => accessibleIds.has(id))
  } catch { /* ignore */ }
}

const loadDatasources = async () => {
  try {
    const res: any = await dataSourceApi.accessible()
    const arr = res?.data ?? res ?? []
    datasources.value = arr.map((d: any) => ({ id: d.id, name: d.name, description: d.description }))
  } catch { /* 无数据源权限或功能未启用 → 静默，不显示选择器 */ }
}

// ── 会话管理 ──
const loadConversations = async () => {
  convLoading.value = true
  try {
    const res = await chatApi.listConversations({ size: 50 })
    conversations.value = res.records || []
  } finally {
    convLoading.value = false
  }
}

const switchConversation = async (convId: number) => {
  currentConvId.value = convId
  chatSidebarOpen.value = false   // 移动端：选完会话后自动收抽屉

  // 从会话中恢复「当时选的知识库（集合）范围」并回显（任务 3）。
  // 会话 kbIds 字段现存的是集合 id；只保留仍存在的集合，旧对话残留的文档 id 会被过滤掉 → 视为全部知识库。
  const conv = conversations.value.find(c => c.id === convId)
  const savedScope = parseKbIds(conv?.kbIds)
  const validIds = new Set(collections.value.map(c => c.id))
  selectedCollectionIds.value = savedScope.filter(id => validIds.has(id))
  selectedKbIds.value = []

  const res = await chatApi.getHistory(convId, { size: 100 })
  let previousUserContent = ''
  messages.value = (res.records || []).map((m: Message) => {
    const wordRequested = m.role === 'assistant' && isWordExportIntent(previousUserContent)
    if (m.role === 'user') previousUserContent = m.content || ''
    const parsedSources = m.sources ? (typeof m.sources === 'string' ? JSON.parse(m.sources) : m.sources) : null
    // 反问澄清消息：sources 里带 type=clarify，回看时重建为已作答的选项卡片
    const clarifySrc = Array.isArray(parsedSources)
      ? parsedSources.find((s: any) => s && s.type === 'clarify') : null
    const clarify = clarifySrc
      ? { question: clarifySrc.question || m.content, options: clarifySrc.options || [], answered: true }
      : null
    // NL2SQL：从 sources 重建数据库查询结果（图表/表格），并从引用源里剔除
    const dbResults = Array.isArray(parsedSources)
      ? parsedSources.filter((s: any) => s && s.type === 'db_result') : []
    const citations = Array.isArray(parsedSources)
      ? parsedSources.filter((s: any) => !s || s.type !== 'db_result') : parsedSources
    return {
      ...m,
      sources: citations,
      dbResults,
      clarify,
      retrievalLog: m.retrievalLog ? (typeof m.retrievalLog === 'string' ? JSON.parse(m.retrievalLog) : m.retrievalLog) : null,
      renderedHtml: m.role === 'assistant' ? renderMd(m.content, (Array.isArray(citations) ? citations.length : 0)) : '',
      showSources: false,
      showSteps: false,
      isStreaming: false,
      wordRequested,
    }
  })
  await scrollToBottom()
}

const parseKbIds = (kbIdsStr?: string): number[] => {
  if (!kbIdsStr) return []
  try {
    return JSON.parse(kbIdsStr) as number[]
  } catch {
    return []
  }
}

const newConversation = () => {
  currentConvId.value = null
  messages.value = []
  inputText.value = ''
  selectedKbIds.value = []
  selectedCollectionIds.value = []
  chatSidebarOpen.value = false   // 移动端：新建后自动收抽屉
}

const deleteConversation = async (convId: number) => {
  await ElMessageBox.confirm('确认删除该对话？', '提示', { type: 'warning' })
  await chatApi.deleteConversation(convId)
  if (currentConvId.value === convId) newConversation()
  await loadConversations()
  ElMessage.success('已删除')
}

// ── 多选批量删除 ──
const selectMode = ref(false)
const selectedConvIds = ref<number[]>([])
const enterSelectMode = () => { selectMode.value = true; selectedConvIds.value = [] }
const exitSelectMode = () => { selectMode.value = false; selectedConvIds.value = [] }
const toggleConvSelect = (id: number) => {
  const i = selectedConvIds.value.indexOf(id)
  if (i >= 0) selectedConvIds.value.splice(i, 1)
  else selectedConvIds.value.push(id)
}
const toggleSelectAll = () => {
  selectedConvIds.value = selectedConvIds.value.length === conversations.value.length
    ? []
    : conversations.value.map(c => c.id)
}
const batchDeleteConvs = async () => {
  const ids = [...selectedConvIds.value]
  if (!ids.length) return
  await ElMessageBox.confirm(`确认删除选中的 ${ids.length} 个对话？此操作不可恢复。`, '批量删除',
    { type: 'warning', confirmButtonText: '确认删除', confirmButtonClass: 'el-button--danger' })
  try {
    await Promise.all(ids.map(id => chatApi.deleteConversation(id)))
    if (currentConvId.value != null && ids.includes(currentConvId.value)) newConversation()
    ElMessage.success(`已删除 ${ids.length} 个对话`)
    exitSelectMode()
    await loadConversations()
  } catch (e: any) {
    ElMessage.error('批量删除失败：' + (e?.message || ''))
    await loadConversations()
  }
}

// ── 发送 ──
const handleEnterSend = (event: KeyboardEvent) => {
  if (shouldIgnoreImeEnter(event) || event.shiftKey) return
  event.preventDefault()
  handleSend()
}
const answerOtherOnEnter = (event: KeyboardEvent, msg: any) => {
  if (shouldIgnoreImeEnter(event)) return
  event.preventDefault()
  answerOther(msg)
}
// ─── 反问澄清：用户点选项 / 输入「其他」 / 跳过 ───
// 选项与跳过都发起「第二轮」请求，并传 allowClarify=false 禁止再次反问（防死循环）
const findPrevUserQuestion = (msg: any): string => {
  const idx = messages.value.indexOf(msg)
  for (let i = idx - 1; i >= 0; i--) {
    if (messages.value[i].role === 'user') return messages.value[i].content || ''
  }
  return ''
}
const answerClarify = (msg: any, option: string) => {
  if (!msg.clarify || msg.clarify.answered || isStreaming.value) return
  msg.clarify.answered = true
  msg.clarify.chosen = option
  const orig = findPrevUserQuestion(msg)
  sendMessage(`${orig || msg.clarify.question}（补充：${option}）`, { allowClarify: false })
}
const answerOther = (msg: any) => {
  const other = (msg.clarify?.otherText || '').trim()
  if (!other) { ElMessage.warning('请输入你的补充说明'); return }
  answerClarify(msg, other)
}
const skipClarify = (msg: any) => {
  if (!msg.clarify || msg.clarify.answered || isStreaming.value) return
  msg.clarify.answered = true
  msg.clarify.chosen = '__skip__'
  const orig = findPrevUserQuestion(msg)
  sendMessage(orig || msg.clarify.question, { allowClarify: false })
}

const handleSend = () => sendMessage(inputText.value)

// 打断生成：关闭流、保留已生成内容、收尾
const stopGeneration = () => {
  if (currentSse) { try { currentSse.close() } catch { /* ignore */ } currentSse = null }
  if (streamingMsg) {
    streamingMsg.content = (streamingMsg.content || '').trimEnd()
    streamingMsg.content += (streamingMsg.content ? '\n\n' : '') + '_（已停止生成）_'
    streamingMsg.renderedHtml = renderMd(streamingMsg.content, streamingMsg.sources?.length || 0)
    streamingMsg.isStreaming = false
    streamingMsg = null
  }
  isStreaming.value = false
}

const sendMessage = async (text: string, opts?: { allowClarify?: boolean }) => {
  const content = text.trim()
  if ((!content && !pendingImages.value.length && !pendingFiles.value.length) || isStreaming.value) return
  if (hasPendingUpload.value) {
    ElMessage.warning('文件还在上传中，请稍候')
    return
  }

  // 收集已上传图片的 objectName（务实：必须是真实上传成功的，不带半成品）
  const uploadedImages = pendingImages.value.filter(i => i.status === 'done' && i.objectName)
  const imageObjectNames = uploadedImages.map(i => i.objectName!)
  const userImageSources = uploadedImages.map(i => ({
    type: 'user_image',
    objectName: i.objectName,
    url: i.url,
  }))
  // 收集已上传附件（done）
  const uploadedFiles = pendingFiles.value.filter(f => f.status === 'done' && f.objectName)
  const attachmentPayload = uploadedFiles.map(f => ({ objectName: f.objectName!, name: f.name }))
  const userFileSources = uploadedFiles.map(f => ({
    type: 'user_attachment',
    objectName: f.objectName,
    name: f.name,
    sizeBytes: f.size,
  }))
  // 提交后清空待发图片 / 附件
  const sentImages = [...pendingImages.value]
  const sentFiles = uploadedFiles.map(f => ({ name: f.name, size: f.size }))
  pendingImages.value = []
  pendingFiles.value = []

  inputText.value = ''
  isStreaming.value = true

  const mergedUserSources = [...userImageSources, ...userFileSources]
  // 用户消息（图片/附件挂在 sources，附件另存 files 供即时展示）
  messages.value.push({
    tempId: Date.now(),
    role: 'user',
    content: content || (sentImages.length ? '[图片]' : (sentFiles.length ? '[附件]' : '')),
    isStreaming: false,
    images: sentImages,
    files: sentFiles,
    sources: mergedUserSources.length ? mergedUserSources : null,
  } as any)

  const aiMsg = reactive({
    tempId: Date.now() + 1,
    id: undefined as number | undefined,   // 后端 done 事件回传后填入，供👍/👎/纠正使用
    role: 'assistant',
    content: '',
    renderedHtml: '',
    sources: null as any,
    showSources: false,
    agentSteps: [] as { type: string; text: string; sources?: Array<{ docName: string; excerpt: string; score: number }> }[],
    showSteps: false,
    isStreaming: true,
    feedback: 0,
    retrievalLog: null as any,
    fromGoldenPair: false,
    goldenPairId: null as any,
    goldenScore: null as any,
    matchedQuestion: null as any,
    referencedGoldenPair: false,
    goldenRefCount: 0,
    clarify: null as null | { question: string; options: string[]; answered?: boolean; chosen?: string; otherText?: string },
    dbResults: [] as any[],   // NL2SQL 数据库查询结果（图表/表格）
    wordRequested: isWordExportIntent(content),
  })
  messages.value.push(aiMsg)
  streamingMsg = aiMsg
  await scrollToBottom()

  let pendingTokenBuffer = ''
  let flushTimer: number | null = null

  const flushPendingTokens = () => {
    if (!pendingTokenBuffer) return
    aiMsg.content += pendingTokenBuffer
    pendingTokenBuffer = ''
    scheduleScrollToBottom()
  }

  const scheduleTokenFlush = () => {
    if (flushTimer !== null) return
    flushTimer = window.setTimeout(() => {
      flushTimer = null
      flushPendingTokens()
    }, 40)
  }

  // SSE 连接
  const params = new URLSearchParams({ message: content })
  if (currentConvId.value) params.append('conversationId', String(currentConvId.value))
  // 任务 15：检索范围统一用「知识库（集合）」id。
  // 不再发旧版「按文档多选」的 kbIds -- 旧对话可能残留成百上千个文档 id，
  // 拼进 GET URL 会超长被网关打回（表现为"生成失败，请重试"）。
  if (selectedCollectionIds.value.length) {
    params.append('collectionIds', selectedCollectionIds.value.join(','))
  }
  if (imageObjectNames.length) {
    params.append('imageObjectNames', imageObjectNames.join(','))
  }
  if (attachmentPayload.length) {
    params.append('attachments', JSON.stringify(attachmentPayload))
  }
  // 只有用户明确开/关时才传；默认不传，由后端按“最新/市场/竞品”等意图自动判断。
  if (webSearchPreferenceExplicit.value) {
    params.append('webSearch', String(webSearchEnabled.value))
  }
  // NL2SQL 数据源范围：选了才传，缩小到选中库；不传=后端用全部可访问库
  if (selectedDatasourceIds.value.length) {
    params.append('datasourceIds', selectedDatasourceIds.value.join(','))
  }
  // 深度总结开关：开启时显式传 true，后端强制走高质量总结模式
  if (deepSummaryEnabled.value) params.append('deepSummary', 'true')
  // 反问澄清：用户已选过选项/点了跳过的「第二轮」请求传 false，禁止再次反问，防止死循环
  if (opts?.allowClarify === false) params.append('allowClarify', 'false')
  const token = localStorage.getItem('token') || ''
  params.append('token', token)

  const sse = new EventSource(`/api/v2/chat/stream?${params}`)
  currentSse = sse

  sse.addEventListener('token', (e) => {
    const d = JSON.parse(e.data)
    pendingTokenBuffer += d.content ?? ''
    scheduleTokenFlush()
  })

  // 自纠错可能生成一份完整的新答案。收到替换事件时必须丢弃尚未刷新的旧 token，
  // 否则定时器会在新答案后再次拼上旧草稿，造成内容重复或前后矛盾。
  sse.addEventListener('answer_replace', (e) => {
    const d = JSON.parse(e.data)
    const replacement = typeof d.content === 'string'
      ? d.content
      : (typeof d.answer === 'string' ? d.answer : null)
    if (replacement === null) return
    if (flushTimer !== null) {
      window.clearTimeout(flushTimer)
      flushTimer = null
    }
    pendingTokenBuffer = ''
    aiMsg.content = replacement
    aiMsg.renderedHtml = renderMd(aiMsg.content, aiMsg.sources?.length || 0)
    scheduleScrollToBottom()
  })

  // 图片分析进度 · 任务 10
  sse.addEventListener('image-analysis', (e) => {
    const d = JSON.parse(e.data)
    if (d.status === 'start') {
      aiMsg.agentSteps.push({ type: 'image', text: `开始分析 ${d.imageCount} 张图片…` })
    } else if (d.status === 'done') {
      aiMsg.agentSteps.push({ type: 'image', text: `图片分析完成 · ${d.imageCount} 张 · 用时 ${d.elapsedMs}ms` })
    } else if (d.status === 'error') {
      aiMsg.agentSteps.push({ type: 'image', text: `图片 #${d.imageIndex} 识别失败：${d.message}` })
    }
  })

  // 附件解析进度
  sse.addEventListener('attachment-analysis', (e) => {
    const d = JSON.parse(e.data)
    if (d.status === 'start') {
      aiMsg.agentSteps.push({ type: 'attachment', text: `开始解析 ${d.count} 个附件…` })
    } else if (d.status === 'done') {
      aiMsg.agentSteps.push({ type: 'attachment', text: `附件解析完成 · ${d.count} 个 · 提取 ${d.chars} 字` })
    }
  })

  // Golden Pair 命中事件 · 标记为"基于人工校正"
  sse.addEventListener('golden-hit', (e) => {
    const d = JSON.parse(e.data)
    aiMsg.fromGoldenPair = true
    aiMsg.goldenPairId   = d.pairId
    aiMsg.goldenScore    = d.score
    aiMsg.matchedQuestion = d.matchedQuestion
  })

  // 反问澄清事件：AI 判定问题模糊，回了一组选项让用户选择（或跳过）
  sse.addEventListener('clarify', (e) => {
    const d = JSON.parse(e.data)
    aiMsg.clarify = {
      question: d.question || '',
      options: Array.isArray(d.options) ? d.options : [],
      answered: false,
    }
    // 反问问题本身作为内容展示（token 流不会再来）
    if (!aiMsg.content) aiMsg.content = d.question || ''
  })

  // NL2SQL：数据库查询结果事件（图表 + 表格 · 流式期间即时渲染）
  sse.addEventListener('db_query', (e) => {
    const d = JSON.parse(e.data)
    const results = Array.isArray(d.results) ? d.results : []
    const ok = results.filter((r: any) => r && r.status === 'ok')
    if (ok.length) {
      aiMsg.dbResults = ok
      ok.forEach((r: any) => aiMsg.agentSteps.push({
        type: 'db_query', text: `查询数据源「${r.datasourceName}」：${r.rowCount} 行`,
      }))
    }
    results.filter((r: any) => r && r.status !== 'ok').forEach((r: any) => {
      const reason = r.error || '查询未返回有效数据'
      aiMsg.agentSteps.push({
        type: 'db_query_error',
        text: `外部数据源查询未完成：${reason}`,
      })
      ElMessage.warning(`外部数据源查询未完成：${reason}`)
    })
  })

  // Agent 推理链事件（v2 Agent 事件格式）
  const agentEvents = ['intent', 'rewrite', 'retrieval', 'rerank', 'reflection']
  agentEvents.forEach(evt => {
    sse.addEventListener(evt, (e) => {
      const d = JSON.parse(e.data)
      let text = ''
      if (evt === 'intent') text = `意图：${d.intentType}（置信度 ${(d.confidence * 100).toFixed(0)}%）`
      else if (evt === 'rewrite') text = d.fromCache ? `已改写（缓存）` : `改写：${d.rewritten}`
      else if (evt === 'retrieval') text = `召回 ${d.totalCount} 条切片`
      else if (evt === 'rerank') text = `重排序 Top-${d.topK}，压缩至 ${d.compressed} 条`
      else if (evt === 'reflection') text = `自纠错第${d.round}轮：${d.passed ? '通过' : '未通过'}（置信度 ${(d.confidence * 100).toFixed(0)}%）`
      else text = d.text || d.content || JSON.stringify(d)
      aiMsg.agentSteps.push({ type: evt, text })
      // 滚动到底，让用户看到实时新增的思考步骤
      scrollToBottom()
    })
  })

  // 🆕 实时检索结果：把 AI 正在读的文档 chunk 实时展示
  sse.addEventListener('retrieval_sources', (e) => {
    try {
      const d = JSON.parse(e.data)
      const sources = Array.isArray(d.sources) ? d.sources : []
      if (!sources.length) return
      aiMsg.agentSteps.push({
        type: 'reading',
        text: `正在阅读 ${sources.length} 个文档片段`,
        sources: sources.map((s: any) => ({
          docName: s.docName || '未知文档',
          excerpt: s.excerpt || '',
          score: typeof s.score === 'number' ? s.score : 0
        }))
      })
      scrollToBottom()
    } catch {}
  })

  sse.addEventListener('done', (e) => {
    const d = JSON.parse(e.data)
    if (flushTimer !== null) {
      window.clearTimeout(flushTimer)
      flushTimer = null
    }
    flushPendingTokens()
    // done.answer 是后端持久化的最终版本，优先于过程中收到的 token/替换事件。
    if (typeof d.answer === 'string') aiMsg.content = d.answer
    // ⭐ 接收后端真实 messageId，让👍/👎/纠正立即可用（不用等刷新）
    console.info('[chat] done event payload =', d)   // 排查：看 messageId 是否真到了
    if (d.messageId) aiMsg.id = d.messageId
    else console.warn('[chat] ⚠ done 事件没回 messageId，请重启后端')
    // NL2SQL：把 type=db_result 从引用源里拆出来单独渲染图表/表格，剩下的才是引用卡片
    const allSources = Array.isArray(d.sources) ? d.sources : []
    const dbFromSources = allSources.filter((s: any) => s && s.type === 'db_result')
    if (dbFromSources.length) aiMsg.dbResults = dbFromSources
    const citations = allSources.filter((s: any) => !s || s.type !== 'db_result')
    aiMsg.sources      = citations.length ? citations : null
    aiMsg.retrievalLog = d.retrievalLog || null
    aiMsg.renderedHtml = renderMd(aiMsg.content, aiMsg.sources?.length || 0)
    aiMsg.isStreaming  = false
    // done 里也可能带 fromGoldenPair（双保险）
    if (d.fromGoldenPair) {
      aiMsg.fromGoldenPair = true
      aiMsg.goldenPairId   = d.pairId
      aiMsg.goldenScore    = d.score
    }
    // 动态 few-shot：本次回答参考了 Golden Pair 范例
    if (d.referencedGoldenPair) {
      aiMsg.referencedGoldenPair = true
      aiMsg.goldenRefCount = d.goldenRefCount || 0
    }
    isStreaming.value  = false
    if (d.conversationId) currentConvId.value = d.conversationId
    sse.close()
    currentSse = null; streamingMsg = null
    loadConversations()
    scheduleScrollToBottom()
  })

  sse.addEventListener('error', () => {
    if (flushTimer !== null) {
      window.clearTimeout(flushTimer)
      flushTimer = null
    }
    flushPendingTokens()
    aiMsg.content    = aiMsg.content || '生成失败，请重试。'
    aiMsg.renderedHtml = renderMd(aiMsg.content, aiMsg.sources?.length || 0)
    aiMsg.isStreaming = false
    isStreaming.value = false
    sse.close()
  })

  sse.onerror = () => {
    if (flushTimer !== null) {
      window.clearTimeout(flushTimer)
      flushTimer = null
    }
    flushPendingTokens()
    aiMsg.renderedHtml = renderMd(aiMsg.content, aiMsg.sources?.length || 0)
    aiMsg.isStreaming = false
    isStreaming.value = false
    sse.close()
  }
}

// ── 工具函数 ──
const renderMd = (content: string, sourceCount = 0) => {
  if (!content) return ''
  // 模型有时把角标包进反引号 `[2][4]` → marked 渲染成 <code>，导致角标不可交互。
  // 渲染前先脱掉"仅含角标"的反引号外壳（不动真正的代码）。
  const unwrapped = content.replace(/`(\s*(?:\[\d{1,3}\]\s*){1,8})`/g, '$1')
  return injectCitations(marked.parse(unwrapped) as string, sourceCount)
}

/**
 * #4② 正文溯源角标：把回答里的 [1]、[2][3] 等引用标记转成可点击角标。
 * 跳过 <pre>/<code> 内的内容，避免误伤代码里的方括号数字。
 * 点击行为由 onCiteClick 事件委托处理（打开来源抽屉并高亮对应条目）。
 */
const injectCitations = (html: string, sourceCount = 0): string => {
  const parts = html.split(/(<pre[\s\S]*?<\/pre>|<code[\s\S]*?<\/code>)/gi)
  return parts.map((seg, idx) => {
    if (idx % 2 === 1) return seg   // 奇数段是 code/pre 原样保留
    return seg.replace(/\[(\d{1,3})\]/g, (_m, n) => {
      const num = Number(n)
      // 只有「真实存在的来源序号(1..来源数)」才渲染成可点角标；
      // 其余（如文档正文里的"第82条"被模型写成 [82]）保留为普通文本，不做假角标。
      if (num >= 1 && num <= sourceCount) {
        return `<sup class="cite-badge" data-cite="${num}" title="悬停看来源 · 点击查看原文">${num}</sup>`
      }
      return _m
    })
  }).join('')
}

// 取第 n 条来源（index 优先匹配，回退按数组下标）
const findSource = (sources: any[], n: number) =>
  sources.find(s => (s.index ?? -1) === n) || sources[n - 1] || null

// 正文角标点击 · 直接在右侧分栏打开原文：
//   音视频     → 弹出「参考来源」面板并定位到该来源，内嵌播放器自动加载并跳转播放
//   文档/图片 → openOriginalDoc（右侧原文分栏）
//   网页       → 打开原网页
//   无原件     → openSourcesDrawer（右侧来源列表并高亮）
const onCiteClick = (e: MouseEvent, msg: any) => {
  const target = e.target as HTMLElement
  if (!target || !target.classList.contains('cite-badge')) return
  const n = Number(target.dataset.cite)
  if (!n || !msg?.sources?.length) return
  const src = findSource(msg.sources, n)
  if (!src) { openSourcesDrawer(msg.sources, n); return }
  hideCiteCard()
  const media = src.mediaType === 'audio' || src.mediaType === 'video'
  const webUrl = citeWebUrl(src)
  if (media) {
    // 音视频没有「原文」可在原文分栏渲染 → 直接弹出参考来源面板定位到该条，
    // 并加载内嵌播放器（_loaded 后 @loadedmetadata 会自动 seek 到时间点播放）
    openSourcesDrawer(msg.sources, n)
    openMediaSource(src)
  } else if (src.type === 'web' && webUrl) {
    window.open(webUrl, '_blank')          // 网页来源 → 打开原网页
  } else if (src.sourceObjectName) {
    openOriginalDoc(src)                    // 文档/图片 → 右侧分栏打开原文
  } else {
    openSourcesDrawer(msg.sources, n)       // 无原件 → 高亮来源列表
  }
}

// 取网页来源的 url
const citeWebUrl = (src: any): string => {
  if (!src) return ''
  if (src.url) return src.url
  if (typeof src.ref === 'string' && /^https?:\/\//.test(src.ref)) return src.ref
  return ''
}
// 卡片底部提示：点击会发生什么
const citeCardAction = (src: any): string => {
  if (!src) return ''
  if (src.mediaType === 'audio' || src.mediaType === 'video') return '点击角标播放'
  if (src.type === 'web' && citeWebUrl(src)) return '点击角标打开网页'
  if (src.sourceObjectName) return '点击角标查看原文'
  return '点击角标查看来源'
}

// 角标悬停卡片（类 DeepSeek）：显示来源名/类型/摘要
const citeCard = reactive<{ visible: boolean; x: number; y: number; src: any | null }>(
  { visible: false, x: 0, y: 0, src: null }
)
let citeHideTimer: any = null
const onCiteHover = (e: MouseEvent, msg: any) => {
  const target = e.target as HTMLElement
  if (!target || !target.classList.contains('cite-badge')) return
  const n = Number(target.dataset.cite)
  if (!n || !msg?.sources?.length) return
  const src = findSource(msg.sources, n)
  if (!src) return
  if (citeHideTimer) { clearTimeout(citeHideTimer); citeHideTimer = null }
  const r = target.getBoundingClientRect()
  citeCard.x = Math.min(r.left, window.innerWidth - 360)
  citeCard.y = r.bottom + 8
  citeCard.src = src
  citeCard.visible = true
}
const onCiteLeave = (e: MouseEvent) => {
  const target = e.target as HTMLElement
  if (!target || !target.classList.contains('cite-badge')) return
  citeHideTimer = setTimeout(hideCiteCard, 150)
}
const hideCiteCard = () => { citeCard.visible = false; citeCard.src = null }

const citeCardKind = (src: any) => {
  const m = src?.mediaType
  if (m === 'audio') return '🎧 音频'
  if (m === 'video') return '🎬 视频'
  if (m === 'image') return '🖼 图片'
  if (src?.pairId) return '✅ 经验库'
  return '📄 文档'
}

const scrollToBottom = async () => {
  await nextTick()
  if (messagesAreaRef.value) {
    messagesAreaRef.value.scrollTop = messagesAreaRef.value.scrollHeight
  }
}

/**
 * 是否"已贴近底部"（距离底部 ≤ 80px）。
 * 用于判断新内容到来时是否自动滚：如果用户在底部 → 自动跟随；
 * 如果用户向上翻了 → 不自动滚，露出"跳到底部"按钮，让用户自己点。
 */
const isNearBottom = () => {
  const el = messagesAreaRef.value
  if (!el) return true
  const distance = el.scrollHeight - el.scrollTop - el.clientHeight
  return distance < 80
}

const userScrolledUp = ref(false)        // 用户是否在向上翻（脱离底部）
const showJumpToBottom = ref(false)       // 是否显示"跳到底部"按钮
const onMessagesScroll = () => {
  const near = isNearBottom()
  // "在底部" → 用户没翻上去；否则视为翻了
  userScrolledUp.value = !near
  showJumpToBottom.value = !near
}
const jumpToBottom = async () => {
  await scrollToBottom()
  userScrolledUp.value = false
  showJumpToBottom.value = false
}

const scheduleScrollToBottom = () => {
  if (scrollScheduled) return
  // 用户向上翻了 → 不自动滚，避免打断阅读
  if (userScrolledUp.value) return
  scrollScheduled = true
  requestAnimationFrame(async () => {
    await scrollToBottom()
    scrollScheduled = false
  })
}

const copyContent = async (content: string) => {
  await navigator.clipboard.writeText(content)
  ElMessage.success('已复制')
}

const ensureAssistantMessageId = async (msg: any): Promise<number | null> => {
  if (msg.id) return msg.id
  if (!currentConvId.value) return null
  try {
    const hist: any = await chatApi.getHistory(currentConvId.value, { current: 1, size: 100 })
    const records: any[] = hist?.records || hist?.data?.records || []
    const matched = [...records].reverse().find((record: any) =>
      record.role === 'assistant'
      && record.content
      && record.content.slice(0, 80) === (msg.content || '').slice(0, 80)
    )
    if (matched?.id) {
      msg.id = matched.id
      return matched.id
    }
  } catch {
    // 调用方统一提示
  }
  return null
}

const filenameFromDisposition = (value?: string): string => {
  if (!value) return '方案文档.docx'
  const utf8 = value.match(/filename\*=UTF-8''([^;]+)/i)
  if (utf8?.[1]) {
    try { return decodeURIComponent(utf8[1]) } catch { /* 使用普通 filename */ }
  }
  const plain = value.match(/filename="?([^";]+)"?/i)
  return plain?.[1] || '方案文档.docx'
}

const downloadAnswerWord = async (msg: any) => {
  const messageId = await ensureAssistantMessageId(msg)
  if (!messageId) {
    ElMessage.warning('回答仍在保存，请稍后再试')
    return
  }
  downloadingWordMessageId.value = messageId
  try {
    const response = await chatApi.exportWord(messageId)
    const blob = response.data instanceof Blob
      ? response.data
      : new Blob([response.data], {
          type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        })
    const signature = new Uint8Array(await blob.slice(0, 4).arrayBuffer())
    if (signature[0] !== 0x50 || signature[1] !== 0x4b) {
      throw new Error('服务器未返回有效的 Word 文件')
    }
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filenameFromDisposition(response.headers?.['content-disposition'])
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
    ElMessage.success('Word 已生成并开始下载')
  } catch (error: any) {
    ElMessage.error(error?.message || 'Word 生成失败，请稍后重试')
  } finally {
    downloadingWordMessageId.value = null
  }
}

const submitFeedback = async (msg: any, rating: 'up' | 'down') => {
  // 兜底：msg.id 为空（旧后端 jar 没回 messageId）时，现查会话最新 assistant 消息
  if (!msg.id && currentConvId.value) {
    try {
      const hist: any = await chatApi.getHistory(currentConvId.value, { current: 1, size: 50 })
      const records: any[] = hist?.records || hist?.data?.records || []
      // 找内容与当前消息匹配的最新 assistant 行
      const matched = [...records].reverse().find((r: any) =>
        r.role === 'assistant' && r.content && r.content.slice(0, 30) === msg.content.slice(0, 30)
      )
      if (matched?.id) {
        msg.id = matched.id
        console.info('[feedback] 兜底查到 messageId=', matched.id)
      }
    } catch (e) {
      console.warn('[feedback] 兜底查询 history 失败', e)
    }
  }
  if (!msg.id) {
    console.warn('[feedback] msg.id 仍为空 · msg=', JSON.parse(JSON.stringify(msg)))
    ElMessage.warning('消息还在保存中，请稍候 1 秒后再试（如反复出现请重启后端）')
    return
  }
  try {
    await feedbackApi.submit({ messageId: msg.id, rating })
    msg.feedback = rating === 'up' ? 1 : -1
    ElMessage.success(rating === 'up' ? '感谢反馈！' : '已记录，可点 ✎ 提供正确答案帮我们改进')
  } catch (e: any) {
    ElMessage.error('提交失败：' + (e?.message || ''))
  }
}

// 纠正对话框 · 用户可输入正确答案，提交后进入审核队列
// ─── 语音通话对话框 ───
const callDialogVisible = ref(false)
const callDialogTitle = computed(() =>
  selectedKbIds.value.length
    ? `语音通话 · 在 ${selectedKbIds.value.length} 个知识库内回答`
    : '语音通话 · 全库检索'
)
const callScopeLabel = computed(() => {
  if (!selectedKbIds.value.length) return '全部知识库'
  const names = selectedKbIds.value
    .map(id => knowledgeBases.value.find(k => k.id === id)?.name)
    .filter(Boolean) as string[]
  if (names.length === 0) return `${selectedKbIds.value.length} 个知识库`
  return names.length > 2 ? `${names.slice(0, 2).join('、')} 等 ${names.length} 个` : names.join('、')
})
function openCallDialog() {
  callDialogVisible.value = true
}

// ─── 浮动通话按钮拖动 ───
const callBtnDragging = ref(false)
const callBtnPos = ref<{ left: number; top: number } | null>(null)
const callBtnStyle = computed(() => callBtnPos.value
  ? { position: 'fixed' as const, left: callBtnPos.value.left + 'px', top: callBtnPos.value.top + 'px', right: 'auto', bottom: 'auto' }
  : {})
let callDrag: { startX: number; startY: number; originLeft: number; originTop: number; moved: boolean } | null = null

function startCallBtnDrag(e: MouseEvent) {
  const rect = (e.currentTarget as HTMLElement).getBoundingClientRect()
  callDrag = { startX: e.clientX, startY: e.clientY, originLeft: rect.left, originTop: rect.top, moved: false }
  window.addEventListener('mousemove', onCallBtnDragMove)
  window.addEventListener('mouseup', onCallBtnDragEnd)
}
function onCallBtnDragMove(e: MouseEvent) {
  if (!callDrag) return
  const dx = e.clientX - callDrag.startX
  const dy = e.clientY - callDrag.startY
  if (!callDrag.moved && Math.abs(dx) + Math.abs(dy) > 4) {
    callDrag.moved = true
    callBtnDragging.value = true
  }
  if (callDrag.moved) {
    const size = 56
    const left = Math.max(8, Math.min(window.innerWidth - size - 8, callDrag.originLeft + dx))
    const top = Math.max(8, Math.min(window.innerHeight - size - 8, callDrag.originTop + dy))
    callBtnPos.value = { left, top }
  }
}
function onCallBtnDragEnd() {
  window.removeEventListener('mousemove', onCallBtnDragMove)
  window.removeEventListener('mouseup', onCallBtnDragEnd)
  setTimeout(() => { callBtnDragging.value = false }, 0)   // 让本次 click 能据此忽略
  callDrag = null
}
function onCallBtnClick() {
  if (callBtnDragging.value) return   // 刚拖完的 click 不触发打开
  openCallDialog()
}
function onCallDialogClose(done: () => void) {
  // VoiceCallPanel 内部会通过 onBeforeUnmount 清理
  done()
}

// ─── 语音转文字 · 持续追加到输入框 ───
let voicePartialBaseline = ''      // 录音开始时 inputText 的内容
let voicePartialActive = false
function onVoicePartial(text: string) {
  if (!voicePartialActive) {
    voicePartialBaseline = inputText.value
    voicePartialActive = true
  }
  // 在原文本后追加识别中文本（用空格分隔）
  inputText.value = voicePartialBaseline
    ? voicePartialBaseline + (voicePartialBaseline.endsWith(' ') ? '' : ' ') + text
    : text
}
function onVoiceFinal(text: string) {
  if (voicePartialActive) {
    inputText.value = voicePartialBaseline
      ? voicePartialBaseline + (voicePartialBaseline.endsWith(' ') ? '' : ' ') + text
      : text
    voicePartialActive = false
    voicePartialBaseline = ''
  }
}

const correctionVisible = ref(false)
const correctionTarget  = ref<any>(null)
const correctionText    = ref('')
const correctionSubmitting = ref(false)

const openCorrectionDialog = async (msg: any) => {
  // 兜底：msg.id 缺失时查 history
  if (!msg.id && currentConvId.value) {
    try {
      const hist: any = await chatApi.getHistory(currentConvId.value, { current: 1, size: 50 })
      const records: any[] = hist?.records || hist?.data?.records || []
      const matched = [...records].reverse().find((r: any) =>
        r.role === 'assistant' && r.content && r.content.slice(0, 30) === msg.content.slice(0, 30)
      )
      if (matched?.id) msg.id = matched.id
    } catch (_) {}
  }
  if (!msg.id) {
    ElMessage.warning('消息尚未保存，请稍候 1 秒后再试')
    return
  }
  correctionTarget.value = msg
  correctionText.value = msg.content || ''
  correctionVisible.value = true
}

const submitCorrection = async () => {
  if (!correctionTarget.value || !correctionText.value.trim()) {
    ElMessage.warning('请输入正确答案')
    return
  }
  correctionSubmitting.value = true
  try {
    await feedbackApi.submit({
      messageId: correctionTarget.value.id,
      rating: 'down',
      correctionText: correctionText.value.trim(),
    })
    correctionTarget.value.feedback = -1
    correctionTarget.value.hasCorrection = true
    ElMessage.success('已提交，等待审核员收录后将自动改进')
    correctionVisible.value = false
    correctionTarget.value = null
    correctionText.value = ''
  } catch (e: any) {
    ElMessage.error('提交失败：' + (e?.message || ''))
  } finally {
    correctionSubmitting.value = false
  }
}

const showRetrievalLog = (msg: any) => {
  currentRetrievalLog.value = msg.retrievalLog
  retrievalLogVisible.value = true
}

const formatTime = (t: string) => {
  if (!t) return ''
  const d = new Date(t)
  const now = new Date()
  const diff = (now.getTime() - d.getTime()) / 1000
  if (diff < 60)   return '刚刚'
  if (diff < 3600) return `${Math.floor(diff / 60)}分钟前`
  if (diff < 86400) return `${Math.floor(diff / 3600)}小时前`
  return d.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

const formatConvTitle = (conv: { title?: string; source?: string }) => {
  return conv.title || '未命名对话'
}

// ─────────────────────────────────────────────
// 时间戳级溯源辅助函数
// ─────────────────────────────────────────────
const mediaTypeLabel = (type: string) => {
  const map: Record<string, string> = {
    audio: '音频',
    video: '视频',
    image: '图片',
    pdf: 'PDF',
    pptx: 'PPT',
    xlsx: 'Excel',
    document: '文档',
    text: '文本'
  }
  return map[type] || type
}

const formatMediaTime = (ms?: number | null) => {
  if (ms == null) return ''
  const sec = Math.floor(ms / 1000)
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = sec % 60
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

/** 缓存 objectName → 预签名 URL，避免重复请求 */
/**
 * 性能优化 · OSS 预签名 URL 缓存
 *   - 内存 Map（当前会话）+ localStorage（跨刷新/标签页持久化）
 *   - OSS URL 有效期通常 7 天，缓存 6 天兜底（留 1 天 safety margin）
 *   - key = sourceObjectName · value = { url, expireAt }
 */
const MEDIA_URL_CACHE_KEY = 'mindcrew_media_url_cache_v1'
const MEDIA_URL_TTL_MS = 6 * 24 * 3600 * 1000   // 6 天

interface CachedMediaUrl { url: string; expireAt: number }

const mediaUrlCache = (() => {
  // 加载持久化数据
  const loaded = new Map<string, CachedMediaUrl>()
  try {
    const raw = localStorage.getItem(MEDIA_URL_CACHE_KEY)
    if (raw) {
      const obj: Record<string, CachedMediaUrl> = JSON.parse(raw)
      const now = Date.now()
      for (const [k, v] of Object.entries(obj)) {
        if (v && v.expireAt > now) loaded.set(k, v)
      }
    }
  } catch {}

  const persist = (() => {
    let timer: any = null
    return () => {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        try {
          const obj: Record<string, CachedMediaUrl> = {}
          loaded.forEach((v, k) => { obj[k] = v })
          localStorage.setItem(MEDIA_URL_CACHE_KEY, JSON.stringify(obj))
        } catch {}
      }, 500)
    }
  })()

  return {
    get(key: string): string | undefined {
      const v = loaded.get(key)
      if (!v) return undefined
      if (v.expireAt < Date.now()) { loaded.delete(key); persist(); return undefined }
      return v.url
    },
    set(key: string, url: string) {
      loaded.set(key, { url, expireAt: Date.now() + MEDIA_URL_TTL_MS })
      persist()
    },
  }
})()
const mediaPlayerRefs = new Map<string, HTMLMediaElement>()

/**
 * 文档/图片带 sourceObjectName 才能「查看原文」。
 * 音频/视频不显示「查看原文」--它们用卡片内嵌播放器（跳转到 X 播放），
 * 走 iframe 预览反而会触发浏览器下载。
 */
function canOpenOriginal(src: any): boolean {
  if (!src?.sourceObjectName) return false
  if (src.mediaType === 'audio' || src.mediaType === 'video') return false
  // 兜底：旧数据可能没有 mediaType，按扩展名再挡一次音视频，避免 iframe 触发下载
  const name = String(src.name || src.sourceObjectName).toLowerCase()
  if (/\.(mp3|m4a|wav|aac|ogg|flac|opus|amr|mp4|mov|avi|mkv|webm|m4v|wmv|flv)(\?|#|$)/i.test(name)) return false
  return true
}

/**
 * 打开原始文档/图片
 *   PDF → 浏览器新 tab 打开 + #page=N 自动跳页
 *   DOCX/PPTX/Excel → 浏览器会下载（无内置预览）
 *   图片 → 新 tab 打开
 */
// ─── 来源 / 原文预览 抽屉（替代下拉展开 + 新 tab） ───────────
const sourcesDrawerVisible = ref(false)
const sourcesDrawerData = ref<any[]>([])
// #4② 正文角标点击时高亮的来源序号（1-based），null=不高亮
const highlightCiteIndex = ref<number | null>(null)
const sourceCardRefs = ref<Record<number, HTMLElement>>({})
const registerSourceCard = (el: any, idx: number) => {
  if (el) sourceCardRefs.value[idx] = el as HTMLElement
}
const openSourcesDrawer = (sources: any[], highlightIndex?: number) => {
  sourcesDrawerData.value = sources || []
  sourcesDrawerVisible.value = true
  highlightCiteIndex.value = highlightIndex ?? null
  if (highlightIndex != null) {
    // 等抽屉渲染完，滚动到对应来源卡片
    nextTick(() => {
      const targetPos = sourcesDrawerData.value.findIndex(
        (s, i) => (s.index ?? i + 1) === highlightIndex
      )
      const el = targetPos >= 0 ? sourceCardRefs.value[targetPos] : undefined
      el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    })
  }
}

const previewDrawerVisible = ref(false)
const previewLoading = ref(false)
const previewTitle = ref('')
const previewUrl = ref('')
const previewText = ref('')
const previewMessage = ref('')
const previewKind = ref<'image' | 'iframe' | 'text' | 'office' | 'unsupported' | ''>('')
// Office 文档（docx/xls(x)）前端渲染的挂载容器
const officeContainerRef = ref<HTMLElement>()
// 被引用切片的原文文本：docx/excel/文本预览渲染后据此定位并高亮被引段落
const pendingLocateContent = ref('')

// ─── #4④ 原文预览：PC 左右分栏 / 移动端抽屉 ───
const isMobileViewport = ref(false)
let previewMql: MediaQueryList | null = null
const onPreviewMqlChange = (e: MediaQueryListEvent) => { isMobileViewport.value = e.matches }
// 右侧面板宽度（px，可拖拽分隔条调整）
const previewPanelWidth = ref(560)
const sourcesPanelWidth = ref(460)
// 通用：右侧面板向左拖 → 变宽
const makePanelResizer = (widthRef: { value: number }, minW = 360) => (e: PointerEvent) => {
  e.preventDefault()
  const startX = e.clientX
  const startW = widthRef.value
  const onMove = (ev: PointerEvent) => {
    const next = startW + (startX - ev.clientX)
    const max = Math.min(window.innerWidth * 0.7, 1100)
    widthRef.value = Math.max(minW, Math.min(max, next))
  }
  const onUp = () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    document.body.style.userSelect = ''
  }
  document.body.style.userSelect = 'none'
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}
const startPreviewResize = makePanelResizer(previewPanelWidth)
const startSourcesResize = makePanelResizer(sourcesPanelWidth)
// 全局单实例播放：任何 audio/video 开始播放时，暂停页面上其它所有媒体
// （media 的 play 事件不冒泡，必须用捕获阶段监听）
const enforceSinglePlayback = (e: Event) => {
  const playing = e.target as HTMLMediaElement
  if (!(playing instanceof HTMLMediaElement)) return
  document.querySelectorAll<HTMLMediaElement>('audio, video').forEach(el => {
    if (el !== playing && !el.paused) { try { el.pause() } catch { /* ignore */ } }
  })
}

onMounted(() => {
  previewMql = window.matchMedia('(max-width: 768px)')
  isMobileViewport.value = previewMql.matches
  previewMql.addEventListener('change', onPreviewMqlChange)
  document.addEventListener('play', enforceSinglePlayback, true)
})
onBeforeUnmount(() => {
  previewMql?.removeEventListener('change', onPreviewMqlChange)
  document.removeEventListener('play', enforceSinglePlayback, true)
})

// 预览用的 blob URL · 关闭抽屉时释放，避免内存泄露
let currentBlobUrl: string | null = null
const releaseBlobUrl = () => {
  if (currentBlobUrl) { URL.revokeObjectURL(currentBlobUrl); currentBlobUrl = null }
}
watch(previewDrawerVisible, (v) => { if (!v) { releaseBlobUrl(); pauseAllMedia() } })
// 关闭「来源/原文」抽屉时停掉音视频播放（#4③）+ 清理角标高亮状态（#4②）
watch(sourcesDrawerVisible, (v) => {
  if (!v) {
    pauseAllMedia()
    highlightCiteIndex.value = null
    sourceCardRefs.value = {}
  }
})

async function openOriginalDoc(src: any) {
  if (!src.sourceObjectName) {
    ElMessage.warning('该来源缺少原文件信息，无法打开')
    return
  }
  src._opening = true
  previewTitle.value = src.name || '原文预览'
  previewUrl.value = ''
  previewText.value = ''
  previewMessage.value = ''
  previewKind.value = ''
  // 记录被引切片原文，供 docx/excel/文本渲染完成后定位高亮（PDF 走 #page、音视频走 seek，不在此列）
  pendingLocateContent.value = src.content || src.chapter || ''
  previewDrawerVisible.value = true
  previewLoading.value = true
  releaseBlobUrl()
  try {
    const lowerName = String(src.name || src.sourceObjectName).toLowerCase()
    const isPdf = lowerName.endsWith('.pdf') || (src.sourceObjectName || '').toLowerCase().includes('.pdf')
    const isDocx  = /\.docx(\?|#|$)/i.test(lowerName)            // .docx → docx-preview 渲染（.doc 旧二进制不支持）
    const isExcel = /\.xlsx?(\?|#|$)/i.test(lowerName)           // .xls/.xlsx → SheetJS 渲染
    const isPpt   = /\.pptx?(\?|#|$)/i.test(lowerName)           // ppt 暂不支持，仍走下载
    const isImage  = /\.(png|jpe?g|webp|gif|bmp|svg)(\?|#|$)/i.test(lowerName)
    const isText   = /\.(md|markdown|txt|csv|log|json|xml|html?)(\?|#|$)/i.test(lowerName)

    // 「新窗口打开」按钮用 OSS 直连 URL（带签名）
    let directUrl = mediaUrlCache.get(src.sourceObjectName)
    if (!directUrl) {
      try {
        const res: any = await chatApi.fetchMediaUrl(src.sourceObjectName, src.knowledgeBaseId)
        directUrl = (res?.data?.url) || res?.url
        if (directUrl) mediaUrlCache.set(src.sourceObjectName, directUrl)
      } catch { /* 拿不到不致命 */ }
    }
    if (directUrl && isPdf && src.pageNumber && src.pageNumber > 0) {
      directUrl = directUrl + '#page=' + src.pageNumber
    }
    previewUrl.value = directUrl || ''

    // PPT 暂无可靠的前端渲染方案，仍走下载
    if (isPpt) {
      previewKind.value = 'unsupported'
      previewMessage.value = '暂不支持 PPT 在线预览'
      return
    }

    // ⭐ 同源代理 fetch · 带鉴权 · 转 blob 喂给 iframe/img
    //   这样能完全绕开 OSS CORS / 401 问题
    const token = localStorage.getItem('token') || ''
    const proxyUrl = `/api/v2/kb/proxy-fetch?objectName=${encodeURIComponent(src.sourceObjectName)}&kbId=${encodeURIComponent(src.knowledgeBaseId)}`
    let blob: Blob
    try {
      const ctrl = new AbortController()
      const timer = setTimeout(() => ctrl.abort(), 15000)   // 15s 超时
      const r = await fetch(proxyUrl, {
        signal: ctrl.signal,
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
      clearTimeout(timer)
      if (!r.ok) throw new Error('代理 HTTP ' + r.status)
      blob = await r.blob()
    } catch (err: any) {
      console.warn('[preview] 代理 fetch 失败', err?.message)
      previewKind.value = 'unsupported'
      previewMessage.value = '加载失败（' + (err?.message || '未知错误') + '）· 请稍后重试'
      return
    }

    // 文本类：直接读 text · 用 markdown / 纯文本展示
    if (isText) {
      previewText.value = await blob.text()
      previewKind.value = 'text'
      await nextTick()
      locateCitedPassage()
      return
    }

    // Word（.docx）：docx-preview 在浏览器端渲染成 HTML
    if (isDocx) {
      previewKind.value = 'office'
      await nextTick()
      if (officeContainerRef.value) {
        officeContainerRef.value.innerHTML = ''
        try {
          const { renderAsync: renderDocx } = await import('docx-preview')
          await renderDocx(blob, officeContainerRef.value, undefined, {
            className: 'docx', inWrapper: true, ignoreWidth: false, ignoreHeight: false,
          })
        } catch (err: any) {
          previewKind.value = 'unsupported'
          previewMessage.value = 'Word 渲染失败（' + (err?.message || '可能是旧版 .doc 格式') + '）'
        }
        locateCitedPassage()
      }
      return
    }

    // Excel（.xls/.xlsx）：SheetJS 解析成 HTML 表格，多 sheet 依次展示
    if (isExcel) {
      try {
        const XLSX = await import('xlsx')
        const buf = await blob.arrayBuffer()
        const wb = XLSX.read(buf, { type: 'array' })
        previewKind.value = 'office'
        await nextTick()
        if (officeContainerRef.value) {
          officeContainerRef.value.innerHTML = wb.SheetNames.map((n) => {
            const ws = wb.Sheets[n]
            if (!ws) return ''
            return `<h4 class="xlsx-sheet-title">${n}</h4>` + XLSX.utils.sheet_to_html(ws)
          }).join('')
          locateCitedPassage()
        }
      } catch (err: any) {
        previewKind.value = 'unsupported'
        previewMessage.value = 'Excel 渲染失败（' + (err?.message || '') + '）'
      }
      return
    }

    // 图片 / PDF / 其他：转 blob URL 喂给 img / iframe
    currentBlobUrl = URL.createObjectURL(blob)
    let viewUrl = currentBlobUrl
    if (isPdf && src.pageNumber && src.pageNumber > 0) {
      viewUrl = viewUrl + '#page=' + src.pageNumber
    }
    if (isImage) {
      previewKind.value = 'image'
      previewUrl.value = viewUrl   // image src 用 blob URL
    } else {
      previewKind.value = 'iframe'
      previewUrl.value = viewUrl
    }
  } catch (e: any) {
    previewKind.value = 'unsupported'
    previewMessage.value = '加载失败：' + (e?.message || '')
  } finally {
    previewLoading.value = false
    src._opening = false
  }
}

// 在已渲染的 docx/excel/文本预览里，定位并高亮「被引用的那段原文」，滚动到可视区中央。
// 用切片原文（pendingLocateContent）的前若干字作锚点；中文无空格，原样匹配即可，
// 英文/markdown 用去空白兜底（退化为高亮所在块）。PDF/音视频不走这里（各自有 #page / seek）。
function locateCitedPassage() {
  const raw = pendingLocateContent.value
  if (!raw) return

  // 文本预览：单文本节点，可精确高亮子串
  if (previewKind.value === 'text') {
    const pre = visiblePreviewText()
    const node = pre?.firstChild
    if (!pre || !node || node.nodeType !== Node.TEXT_NODE) return
    const text = node.nodeValue || ''
    const needle = raw.trim().replace(/\r?\n/g, ' ').slice(0, 24)
    const idx = needle.length >= 4 ? text.indexOf(needle) : -1
    if (idx < 0) return
    try {
      const range = document.createRange()
      range.setStart(node, idx)
      range.setEnd(node, idx + needle.length)
      const mark = document.createElement('mark')
      mark.className = 'cite-locate'
      range.surroundContents(mark)
      flashLocate(mark)
    } catch { flashLocate(pre) }
    return
  }

  // Office（docx/excel）：渲染成富文本，按块定位--找到含锚点的文本节点，高亮其所在块元素
  if (previewKind.value === 'office') {
    const container = officeContainerRef.value
    if (!container) return
    const needle = raw.replace(/\s+/g, '').slice(0, 18)
    if (needle.length < 6) return
    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT)
    let node: Node | null
    while ((node = walker.nextNode())) {
      const t = (node.nodeValue || '').replace(/\s+/g, '')
      if (t && t.includes(needle)) {
        flashLocate((node.parentElement || container) as HTMLElement)
        return
      }
    }
  }
}

// 取当前可见的文本预览 <pre>（PC 分栏 / 移动抽屉只会渲染其一）
function visiblePreviewText(): HTMLElement | null {
  const els = Array.from(document.querySelectorAll<HTMLElement>('.preview-text'))
  return els.find(e => e.offsetParent !== null) || els[0] || null
}

// 加高亮类 + 平滑滚动到可视区中央
function flashLocate(el: HTMLElement) {
  el.classList.add('cite-locate')
  el.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

async function openMediaSource(src: any) {
  if (!src.sourceObjectName) return
  // 已加载就直接 seek
  if (src._loaded) {
    seekMediaTo(src)
    return
  }
  // 拉预签名 URL
  let url = mediaUrlCache.get(src.sourceObjectName)
  if (!url) {
    try {
      const res: any = await chatApi.fetchMediaUrl(src.sourceObjectName, src.knowledgeBaseId)
      url = (res?.data?.url) || res?.url
      if (url) mediaUrlCache.set(src.sourceObjectName, url)
    } catch (e) {
      ElMessage.error('获取媒体文件 URL 失败')
      return
    }
  }
  if (!url) return
  src._mediaUrl = url
  src._loaded = true
}

function registerMediaPlayer(el: any, src: any) {
  if (el && src.sourceObjectName) {
    mediaPlayerRefs.set(src.sourceObjectName + '#' + src.startMs, el)
  }
}

/** 暂停所有已注册的音视频播放器（关闭抽屉时调用，避免关掉后还在后台播放） */
function pauseAllMedia() {
  mediaPlayerRefs.forEach((el) => { try { el?.pause?.() } catch { /* ignore */ } })
}

function seekMediaTo(src: any) {
  if (src.startMs == null) return
  const key = src.sourceObjectName + '#' + src.startMs
  const el = mediaPlayerRefs.get(key)
  if (el) {
    try {
      // 元数据加载后顺便记下真实时长，用于修正历史脏数据导致的 endMs 离谱（如视频 4s 但 endMs 31000）
      if (typeof el.duration === 'number' && isFinite(el.duration) && el.duration > 0) {
        src._duration = el.duration
      }
      // 播放前先暂停页面上其它所有媒体，保证同时只有一个在响
      document.querySelectorAll<HTMLMediaElement>('audio, video').forEach(m => {
        if (m !== el && !m.paused) { try { m.pause() } catch { /* ignore */ } }
      })
      el.currentTime = src.startMs / 1000
      el.play().catch(() => { /* 浏览器可能拦截自动播放，用户点 play 即可 */ })
    } catch (e) {
      console.warn('seek 失败', e)
    }
  }
}

/**
 * 显示用的 endMs：若已知 video duration 且 endMs 超出实际时长，截到视频结尾
 */
function displayEndMs(src: any): number {
  const raw = src.endMs ?? src.startMs ?? 0
  if (src._duration && raw > src._duration * 1000) {
    return Math.round(src._duration * 1000)
  }
  return raw
}

/**
 * 判断 endMs 是否有效（合理范围内才显示 ~ endMs）
 *  - 没有 endMs / endMs <= startMs → 只显示 startMs 单点
 *  - 已有真实 duration → 一律展示（displayEndMs 自动截断到 duration）
 */
function hasValidEndMs(src: any): boolean {
  if (src.endMs == null || src.endMs <= (src.startMs ?? 0)) return false
  return true
}

/** 展开/收起 sources 列表 · 首次展开时触发 metadata 预探 */
function toggleSources(msg: any) {
  msg.showSources = !msg.showSources
  if (msg.showSources && msg.sources) {
    probeMediaDurations(msg.sources)
  }
}

/**
 * 用户展开 sources 时批量预探 video/audio 真实时长
 * 同一 sourceObjectName 共用 metadata · 不影响业务播放
 */
async function probeMediaDurations(sources: any[]) {
  if (!sources?.length) return
  const seen = new Set<string>()
  for (const src of sources) {
    if (!src.sourceObjectName) continue
    if (src.mediaType !== 'video' && src.mediaType !== 'audio') continue
    if (src._durationProbed) continue
    if (seen.has(src.sourceObjectName)) continue
    seen.add(src.sourceObjectName)
    probeOneMedia(src, sources)
  }
}

async function probeOneMedia(src: any, allSources: any[]) {
  try {
    let url = mediaUrlCache.get(src.sourceObjectName)
    if (!url) {
      const res: any = await chatApi.fetchMediaUrl(src.sourceObjectName, src.knowledgeBaseId)
      url = (res?.data?.url) || res?.url
      if (url) mediaUrlCache.set(src.sourceObjectName, url)
    }
    if (!url) return
    const probe = document.createElement(src.mediaType === 'audio' ? 'audio' : 'video') as HTMLMediaElement
    probe.preload = 'metadata'
    probe.src = url
    probe.onloadedmetadata = () => {
      if (isFinite(probe.duration) && probe.duration > 0) {
        // 同一 sourceObjectName 的所有 src 共享 duration
        for (const s of allSources) {
          if (s.sourceObjectName === src.sourceObjectName) {
            s._duration = probe.duration
            s._durationProbed = true
          }
        }
      }
      probe.src = ''   // 释放
    }
    probe.onerror = () => { probe.src = '' }
  } catch (e) {
    console.warn('media probe 失败', e)
  }
}
</script>

<style scoped>
.chat-layout {
  display: flex;
  height: 100%;
  overflow: hidden;
  background: var(--bg-base);
}

/* ─── 左侧会话列表 ─── */
.chat-sidebar {
  width: 230px;
  flex-shrink: 0;
  background: var(--bg-surface);
  border-right: 1px solid var(--border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.sidebar-top { padding: 12px; flex-shrink: 0; }
.new-chat-btn {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 9px 0;
  background: rgba(56,189,248,0.08);
  border: 1px dashed rgba(56,189,248,0.3);
  border-radius: var(--radius-sm);
  color: var(--primary);
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition: var(--transition);
}
.new-chat-btn:hover { background: rgba(56,189,248,0.14); border-style: solid; }

.kb-selector {
  padding: 0 12px 10px;
  flex-shrink: 0;
}
.kb-label {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: #475569;
  font-weight: 600;
  letter-spacing: 0.5px;
  text-transform: uppercase;
  margin-bottom: 6px;
}
.kb-optional {
  margin-left: auto;
  font-size: 10px;
  color: #64748b;
  letter-spacing: 0;
  text-transform: none;
}
.kb-hint {
  margin-top: 6px;
  font-size: 11px;
  line-height: 1.5;
  color: #64748b;
}

.conv-list {
  flex: 1;
  overflow-y: auto;
  padding: 4px 6px;
}

.conv-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: var(--transition);
  position: relative;
}
.conv-item:hover { background: var(--bg-hover); }
.conv-item:hover .conv-btns { opacity: 1; }
.conv-item.active { background: rgba(56,189,248,0.08); }
.conv-item.active .conv-icon { color: var(--primary); }

.conv-icon { color: #334155; margin-top: 2px; flex-shrink: 0; }

.conv-info { flex: 1; min-width: 0; }
.conv-title {
  font-size: 12.5px;
  font-weight: 500;
  color: #94a3b8;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.conv-item.active .conv-title { color: #cbd5e1; }
.conv-meta { font-size: 11px; color: #334155; margin-top: 2px; }

.voice-icon { color: #22c55e !important; }
.voice-badge {
  display: inline-block;
  font-size: 9px;
  font-weight: 700;
  padding: 1px 6px;
  border-radius: 999px;
  background: rgba(34, 197, 94, 0.15);
  color: #22c55e;
  margin-left: 6px;
  vertical-align: middle;
  flex-shrink: 0;
}

.conv-btns { opacity: 0; display: flex; gap: 2px; flex-shrink: 0; }
.conv-btn {
  background: none;
  border: none;
  cursor: pointer;
  color: #475569;
  padding: 3px;
  border-radius: 4px;
  display: flex;
  align-items: center;
  transition: var(--transition);
}
.conv-btn:hover { color: #f87171; background: rgba(248,113,113,0.1); }

/* 多选删除工具栏 */
.conv-tools { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
.conv-tool-btn {
  background: none; border: none; cursor: pointer;
  font-size: 12px; color: var(--primary); padding: 2px 4px; border-radius: 4px;
}
.conv-tool-btn:hover:not(:disabled) { background: var(--bg-hover); }
.conv-tool-btn.danger { color: #ef4444; }
.conv-tool-btn:disabled { color: var(--ink-4); cursor: not-allowed; }
.conv-item.checked { background: rgba(56,189,248,0.1); }
.conv-check { margin-right: 2px; }

.empty-conv {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 40px 16px;
  text-align: center;
}
.empty-icon { margin-bottom: 8px; }
.empty-conv p { font-size: 12px; color: #334155; line-height: 1.6; }

/* ─── 主聊天区 ─── */
.chat-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
}

/* ── 浮动语音通话按钮 · 右上角悬浮 ── */
.floating-call-btn {
  position: absolute;
  top: 16px;
  right: 20px;
  z-index: 30;
  width: 56px;
  height: 56px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #10b981, #059669);
  color: #fff;
  border: none;
  border-radius: 50%;
  cursor: grab;
  box-shadow: 0 6px 18px rgba(16, 185, 129, 0.32);
  transition: transform 0.15s ease, box-shadow 0.18s ease;
  touch-action: none;
  user-select: none;
}
.floating-call-btn:hover {
  box-shadow: 0 8px 22px rgba(16, 185, 129, 0.45);
  transform: translateY(-1px) scale(1.04);
}
.floating-call-btn.is-dragging {
  cursor: grabbing;
  transition: none;
  box-shadow: 0 10px 26px rgba(16, 185, 129, 0.5);
}
.floating-call-btn.is-active {
  background: linear-gradient(135deg, #059669, #047857);
}
.floating-call-btn .call-badge-dot {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  border-radius: 9px;
  background: #f59e0b;
  color: #fff;
  border: 2px solid var(--bg-surface);
}

/* ── 通话对话框 ── */
:deep(.voice-call-dialog .el-dialog__body) {
  padding: 8px 16px 18px;
}

/* 欢迎屏 */
.welcome-screen {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 24px;
  gap: 20px;
  background: var(--bg-base);
}
/* 欢迎屏 logo · 新图标自带配色，去掉深紫色背景框 */
.welcome-logo {
  width: 80px; height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  /* 仅给个淡淡的光晕，不抢图本身视觉 */
  filter: drop-shadow(0 6px 24px rgba(0, 0, 0, 0.22));
}
.welcome-logo :deep(img) { width: 100%; height: 100%; object-fit: contain; }
.welcome-title { font-size: 26px; font-weight: 700; color: #e2e8f0; }
.welcome-desc { font-size: 14px; color: #64748b; text-align: center; max-width: 380px; }
.quick-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  max-width: 560px;
  width: 100%;
}
.quick-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 16px;
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius);
  cursor: pointer;
  transition: var(--transition);
  font-size: 13px;
  color: #94a3b8;
  text-align: left;
}
.quick-item:hover { border-color: var(--primary); background: rgba(56,189,248,0.06); color: #e2e8f0; }
.welcome-tips {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: #334155;
  background: rgba(255,255,255,0.02);
  border: 1px solid var(--border);
  border-radius: 20px;
  padding: 6px 14px;
}

/* 消息区 */
.messages-area {
  flex: 1;
  overflow-y: auto;
  background: var(--bg-base);
  position: relative;
}
.messages-inner {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px 18px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

/* 跳到底部按钮：用户向上翻页时显示 */
.jump-to-bottom {
  position: absolute;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  font-size: 13px;
  color: #1f2937;
  background: #ffffff;
  border: 1px solid #d8dde6;
  border-radius: 18px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: background 0.15s, box-shadow 0.15s, transform 0.15s;
  z-index: 10;
}
.jump-to-bottom:hover {
  background: #f3f4f6;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.12);
  transform: translateX(-50%) translateY(-1px);
}

/* ===== 移动端聊天布局重写（≤768px）=====
   欢迎屏改为「顶部对齐 + 自身可滚 + 紧凑间距」，
   解决居中导致偏下、内容超出可视区滚不动的问题。
   配合 mobile.css 锁死的 body（整页不滚），这里让欢迎屏/消息区各自内部滚。 */
@media (max-width: 768px) {
  .welcome-screen {
    justify-content: flex-start !important;
    overflow-y: auto !important;
    -webkit-overflow-scrolling: touch;
    padding: 20px 16px 16px !important;
    gap: 14px !important;
  }
  .welcome-logo { width: 60px !important; height: 60px !important; }
  .welcome-title { font-size: 21px !important; }
  .welcome-desc { font-size: 12.5px !important; }
  .quick-grid {
    grid-template-columns: 1fr !important;
    gap: 8px !important;
    max-width: 100% !important;
  }
  .quick-item { padding: 12px 14px !important; }
  .messages-inner { padding: 12px 10px !important; }
}

.message-row {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  margin-bottom: 28px;
}
.message-row.user {
  margin-top: 8px;
  margin-bottom: 8px;
}
.message-row.assistant {
  margin-bottom: 32px;
}

/* 用户消息 */
.message-row.user {
  flex-direction: row;
  margin-top: 10px;
  margin-bottom: 18px;
}
.msg-spacer { flex: 1; }
.msg-avatar { flex-shrink: 0; margin-top: 2px; }
.user-av { background: linear-gradient(135deg, #1e40af, #4f46e5) !important; font-size: 12px; }

.user-bubble {
  max-width: 70%;
}
.user-bubble .bubble-text {
  background: #f1f5f9;
  color: #1f2937;
  border-radius: 22px;
  padding: 10px 16px;
  font-size: 14.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  transition: background 0.15s;
}
.user-bubble .bubble-text:hover {
  background: #e9eef5;
}

/* AI 消息 */
.ai-avatar-wrap { flex-shrink: 0; margin-top: 4px; }
.ai-av {
  width: 30px; height: 30px;
  background: linear-gradient(135deg, #1e3a5f, #0e2640);
  border: 1px solid rgba(56,189,248,0.2);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ai-bubble {
  flex: 1;
  max-width: calc(100% - 44px);
  background: transparent;
  border: 0;
  border-radius: 0;
  padding: 0;
  position: relative;
}
.bubble-content {
  line-height: 1.75;
  color: #cbd5e1;
  word-break: break-word;
}
/* 流式期间已按 Markdown 渲染成 HTML，块级元素自带换行，不能再用 pre-wrap（否则标签间换行会产生多余空行） */
.bubble-content-streaming {
  white-space: normal;
}

/* 推理链 · WorkBuddy 风格：无气泡、灰色文字、左侧时间线、充足间距 */
.agent-trace {
  margin-bottom: 18px;
  padding: 0;
  border: 0;
  background: transparent;
  border-radius: 0;
  overflow: visible;
}
.trace-header {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0 0 8px 0;
  cursor: pointer;
  font-size: 12.5px;
  color: #6E6E73;
  font-weight: 400;
  user-select: none;
}
.trace-header:hover { color: #4B4B53; }
.trace-title {
  font-size: 12.5px;
  color: #6E6E73;
  font-weight: 400;
  letter-spacing: 0.02em;
}
.trace-clock { color: #AEAEB2; font-size: 12px; }
.trace-meta {
  font-size: 12px;
  color: #AEAEB2;
  margin-left: 2px;
}
.trace-toggle {
  margin-left: auto;
  transition: transform 0.2s;
  color: #AEAEB2;
  font-size: 12px;
}
.trace-toggle.rotated { transform: rotate(180deg); }
.trace-body {
  padding: 4px 0 4px 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
  border-left: 2px solid #e5e7eb;
  padding-left: 16px;
  margin-left: 6px;
}
.trace-step {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  position: relative;
}
.step-marker {
  position: absolute;
  left: -23px;
  top: 6px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #fff;
  border: 2px solid #cbd5e1;
}
.step-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}
.step-type {
  font-family: 'JetBrains Mono', 'SF Mono', Consolas, monospace;
  font-size: 11px;
  color: #94a3b8;
  font-weight: 400;
  letter-spacing: 0;
  background: transparent;
  border: 0;
  padding: 0;
  text-transform: none;
}
.step-text {
  font-size: 13px;
  color: #6b7280;
  line-height: 1.65;
  font-weight: 400;
  word-break: break-word;
}

/* 🆕 实时检索：正在阅读的文档片段卡片（最多 5 条） */
.step-sources {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.step-source {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 8px 10px;
  background: rgba(255, 255, 255, .55);
  border: 1px solid rgba(0, 0, 0, .06);
  border-radius: 8px;
  font-size: 12px;
  transition: background 180ms;
}
.step-source:hover { background: rgba(255, 255, 255, .85); }
.src-icon {
  width: 22px; height: 22px;
  border-radius: 6px;
  display: inline-flex; align-items: center; justify-content: center;
  background: rgba(88, 86, 214, .12);
  color: #5856D6;
  font-size: 11px;
  flex-shrink: 0;
  margin-top: 1px;
}
.src-body { flex: 1; min-width: 0; }
.src-name {
  font-size: 12.5px;
  font-weight: 600;
  color: #374151;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.4;
}
.src-excerpt {
  margin-top: 2px;
  font-size: 11.5px;
  color: #6b7280;
  line-height: 1.5;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.src-score {
  font-family: 'JetBrains Mono', monospace;
  font-size: 10.5px;
  font-weight: 600;
  color: #22c55e;
  background: rgba(34, 197, 94, .1);
  padding: 2px 6px;
  border-radius: 10px;
  flex-shrink: 0;
  align-self: center;
}

/* 流式光标 */
.cursor-blink {
  display: inline-block;
  width: 2px;
  height: 1em;
  background: var(--primary);
  margin-left: 2px;
  vertical-align: text-bottom;
  animation: blink 0.8s step-end infinite;
}
@keyframes blink { 0%, 100% { opacity: 1; } 50% { opacity: 0; } }

/* 流式生成初始三个点（content 还没出现时） */
.thinking-dots {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 0;
  margin-top: 4px;
}
.thinking-dots .dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #94a3b8;
  animation: dot-fade 1.2s infinite ease-in-out both;
}
.thinking-dots .dot:nth-child(1) { animation-delay: -0.32s; }
.thinking-dots .dot:nth-child(2) { animation-delay: -0.16s; }
.thinking-text { font-weight: 500; letter-spacing: 0.02em; }
@keyframes dot-fade {
  0%, 80%, 100% { opacity: 0.35; }
  40%           { opacity: 1; }
}

/* 来源 */
/* ── 反问澄清卡片 ── */
.clarify-card {
  margin-top: 12px;
  padding: 12px 14px;
  border: 1px solid var(--el-border-color, #e4e7ed);
  border-radius: 10px;
  background: var(--el-fill-color-light, #f5f7fa);
}
.clarify-opts { display: flex; flex-wrap: wrap; gap: 8px; }
.clarify-opt {
  padding: 7px 14px;
  font-size: 13px;
  border: 1px solid var(--el-color-primary-light-5, #a0cfff);
  border-radius: 999px;
  background: var(--el-bg-color, #fff);
  color: var(--el-color-primary, #409eff);
  cursor: pointer;
  transition: all 0.15s;
}
.clarify-opt:hover:not(:disabled) {
  background: var(--el-color-primary, #409eff);
  color: #fff;
}
.clarify-opt.chosen {
  background: var(--el-color-primary, #409eff);
  color: #fff;
  border-color: var(--el-color-primary, #409eff);
}
.clarify-opt:disabled { opacity: 0.6; cursor: default; }
.clarify-other-row { display: flex; gap: 8px; margin-top: 10px; align-items: center; }
.clarify-other-input {
  flex: 1;
  min-width: 0;
  padding: 6px 12px;
  font-size: 13px;
  border: 1px solid var(--el-border-color, #dcdfe6);
  border-radius: 8px;
  background: var(--el-bg-color, #fff);
  color: var(--el-text-color-primary, #303133);
  outline: none;
}
.clarify-other-input:focus { border-color: var(--el-color-primary, #409eff); }
.clarify-other-btn, .clarify-skip-btn {
  padding: 6px 14px;
  font-size: 13px;
  border-radius: 8px;
  cursor: pointer;
  border: 1px solid transparent;
  white-space: nowrap;
}
.clarify-other-btn {
  background: var(--el-color-primary, #409eff);
  color: #fff;
}
.clarify-skip-btn {
  background: transparent;
  color: var(--el-text-color-secondary, #909399);
  border-color: var(--el-border-color, #dcdfe6);
}
.clarify-skip-btn:hover { color: var(--el-text-color-primary, #303133); }
.clarify-done {
  margin-top: 8px;
  font-size: 12.5px;
  color: var(--el-text-color-secondary, #909399);
}

.sources-panel { margin-top: 12px; }
.sources-toggle {
  display: flex;
  align-items: center;
  gap: 6px;
  background: none;
  border: none;
  cursor: pointer;
  font-size: 12px;
  color: #64748b;
  padding: 5px 8px;
  border-radius: 5px;
  transition: var(--transition);
}
.sources-toggle:hover { background: var(--bg-hover); color: var(--primary); }
.sources-list { display: flex; flex-direction: column; gap: 8px; margin-top: 8px; }
.source-card {
  background: var(--bg-elevated);
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  padding: 10px 12px;
  transition: box-shadow 0.3s ease, border-color 0.3s ease;
}
/* #4② 正文角标点击后高亮对应来源卡片 */
.source-card.cite-highlight {
  border-color: var(--primary);
  box-shadow: 0 0 0 2px var(--primary-dim);
  animation: citePulse 1.4s ease;
}
@keyframes citePulse {
  0% { box-shadow: 0 0 0 0 var(--primary-dim); }
  40% { box-shadow: 0 0 0 4px var(--primary-dim); }
  100% { box-shadow: 0 0 0 2px var(--primary-dim); }
}

/* #4② 正文里的可点击溯源角标（注入到 v-html，需 :deep） */
.md-body :deep(.cite-badge) {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  margin: 0 1px;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  vertical-align: super;
  color: var(--primary);
  background: var(--primary-dim);
  border-radius: 4px;
  cursor: pointer;
  user-select: none;
  transition: background 0.15s, color 0.15s;
}
.md-body :deep(.cite-badge:hover) {
  color: #fff;
  background: var(--primary);
}
.src-header { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.src-idx {
  font-size: 11px; font-weight: 700; font-family: monospace;
  color: var(--primary); background: var(--primary-dim);
  border-radius: 4px; padding: 1px 5px; flex-shrink: 0;
}
.src-web-badge {
  font-size: 10.5px; font-weight: 700; flex-shrink: 0;
  color: #2563eb; background: rgba(37, 99, 235, 0.12);
  border: 1px solid rgba(37, 99, 235, 0.3);
  border-radius: 4px; padding: 1px 6px; white-space: nowrap;
}
.src-name { font-size: 12px; font-weight: 600; color: #94a3b8; flex: 1; }
.src-score { font-size: 11px; color: #34d399; font-weight: 600; }
.src-excerpt {
  font-size: 12px; color: #64748b; line-height: 1.65;
  display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: pre-wrap; word-break: break-word;
}
/* 展开后取消行数限制 */
.src-excerpt.expanded {
  display: block;
  -webkit-line-clamp: unset;
  overflow: visible;
}
.src-expand-btn {
  display: inline-block;
  margin-top: 4px;
  padding: 0;
  font-size: 11.5px;
  font-weight: 500;
  color: #38bdf8;
  background: none;
  border: none;
  cursor: pointer;
  transition: color 0.15s;
}
.src-expand-btn:hover { color: #0284c7; text-decoration: underline; }

/* 「打开原文」按钮 */
.src-actions {
  display: flex; gap: 8px; flex-wrap: wrap;
  margin-top: 8px;
}
.src-open-btn {
  display: inline-flex; align-items: center; gap: 5px;
  padding: 5px 12px;
  background: rgba(0, 0, 0, 0.08);
  border: 1px solid rgba(0, 0, 0, 0.25);
  color: #0a0a0a;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s ease;
}
.src-open-btn:hover:not(:disabled) {
  background: rgba(0, 0, 0, 0.15);
  border-color: rgba(0, 0, 0, 0.45);
  transform: translateY(-1px);
}
.src-open-btn:disabled { opacity: .6; cursor: wait; }
.src-open-btn .open-page { color: #0a0a0a; font-weight: 600; }

.src-meta {
  font-size: 11px; color: #475569; margin-top: 4px;
  display: flex; flex-wrap: wrap; align-items: center; gap: 2px 4px;
}
.src-media-badge {
  font-size: 10px; font-weight: 600; padding: 1px 6px;
  border-radius: 999px; color: #fff;
  background: linear-gradient(135deg, #6366f1, #4f46e5);
  flex-shrink: 0;
}
.source-card.media-audio .src-media-badge { background: linear-gradient(135deg, #f59e0b, #d97706); }
.source-card.media-video .src-media-badge { background: linear-gradient(135deg, #ef4444, #b91c1c); }

/* Golden Pair 来源 · 人工校正答案的特殊样式 */
.src-header.golden { color: #047857; }
.golden-name {
  color: #047857 !important; font-weight: 700 !important;
}
.src-golden-body {
  padding: 10px 12px; margin-top: 6px;
  background: linear-gradient(135deg, rgba(16,185,129,0.06), rgba(16,185,129,0.02));
  border: 1px solid rgba(16,185,129,0.2);
  border-radius: 8px;
  font-size: 12.5px;
}
.golden-row { display: flex; gap: 6px; margin-bottom: 4px; line-height: 1.6; }
.golden-label { color: var(--ink-3); flex-shrink: 0; }
.golden-tip {
  margin-top: 8px; padding-top: 8px;
  border-top: 1px dashed rgba(16,185,129,0.3);
  font-size: 11.5px; color: var(--ink-3); line-height: 1.55;
}
.source-card.media-image .src-media-badge { background: linear-gradient(135deg, #10b981, #059669); }

.src-timestamp {
  font-family: 'JetBrains Mono', monospace;
  color: var(--primary);
  font-weight: 600;
}
.src-speaker { color: #94a3b8; }

/* 媒体播放器（音视频） */
.src-media-player {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--border);
}
.src-play-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: linear-gradient(135deg, rgba(56, 189, 248, 0.18), rgba(0, 0, 0, 0.12));
  border: 1px solid rgba(56, 189, 248, 0.32);
  color: #38bdf8;
  font-size: 12px;
  font-weight: 600;
  padding: 6px 12px;
  border-radius: 999px;
  cursor: pointer;
  transition: all 0.2s;
}
.src-play-btn:hover {
  background: linear-gradient(135deg, rgba(56, 189, 248, 0.28), rgba(0, 0, 0, 0.2));
  transform: translateY(-1px);
}
.src-media-element {
  display: block;
  width: 100%;
  margin-top: 8px;
  border-radius: 8px;
  background: #17181c;
}
audio.src-media-element { height: 40px; background: transparent; }
video.src-media-element { max-height: 320px; }

/* 操作栏 */
/* ── 图片输入 · 任务 10 ── */
.input-card { position: relative; transition: border-color 0.15s; }
.input-card.drag-over {
  border-color: var(--primary, #38bdf8);
  background: rgba(56, 189, 248, 0.04);
}
.img-preview-row {
  display: flex;
  gap: 8px;
  padding: 8px 10px 4px;
  flex-wrap: wrap;
}

/* 附件预览条（输入区待发送） */
.file-preview-row {
  display: flex;
  gap: 8px;
  padding: 8px 10px 4px;
  flex-wrap: wrap;
}
.file-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 220px;
  padding: 6px 8px 6px 10px;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--bg-elevated);
  font-size: 12.5px;
  color: var(--ink-1);
}
.file-chip.uploading { opacity: 0.7; }
.file-chip.error { border-color: #f87171; cursor: pointer; }
.file-chip-ic { color: var(--primary); flex-shrink: 0; }
.file-chip.error .file-chip-ic { color: #ef4444; }
.file-chip-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.file-chip-size { color: var(--ink-3); font-size: 11px; flex-shrink: 0; }
.file-chip-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: transparent;
  color: var(--ink-3);
  cursor: pointer;
  padding: 2px;
  border-radius: 4px;
  flex-shrink: 0;
}
.file-chip-close:hover { background: var(--bg-hover); color: var(--ink-1); }
.file-chip .spin { animation: spin 1s linear infinite; }
.img-thumb {
  position: relative;
  width: 64px;
  height: 64px;
  border-radius: 8px;
  overflow: hidden;
  border: 1px solid var(--border, #334155);
  background: var(--bg-elevated, #1e293b);
}
.img-thumb img { width: 100%; height: 100%; object-fit: cover; }
.img-thumb img.clickable { cursor: zoom-in; }
.img-thumb.uploading { opacity: 0.7; }
.img-thumb.error { border-color: #ef4444; }
.thumb-overlay {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  background: rgba(15, 23, 42, 0.55);
  color: #fff;
}
.thumb-overlay.error-overlay { background: rgba(239, 68, 68, 0.7); cursor: pointer; }
.thumb-overlay.error-overlay:hover { background: rgba(239, 68, 68, 0.85); }

/* 待发送图片放大预览（#8） */
.img-lightbox {
  position: fixed; inset: 0; z-index: 3000;
  display: flex; align-items: center; justify-content: center;
  background: rgba(0, 0, 0, 0.78);
  cursor: zoom-out;
}
.img-lightbox-img {
  max-width: 90vw; max-height: 90vh;
  object-fit: contain;
  border-radius: 6px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.5);
}
.img-lightbox-close {
  position: fixed; top: 24px; right: 28px;
  width: 40px; height: 40px; border-radius: 50%;
  border: none; cursor: pointer;
  background: rgba(255, 255, 255, 0.15);
  color: #fff;
  display: flex; align-items: center; justify-content: center;
}
.img-lightbox-close:hover { background: rgba(255, 255, 255, 0.28); }

/* 角标悬停来源卡片 */
.cite-card {
  position: fixed;
  z-index: 4000;
  width: 340px;
  max-width: 86vw;
  background: var(--bg-surface, #fff);
  border: 1px solid var(--line, #e2e8f0);
  border-radius: 10px;
  box-shadow: 0 10px 30px rgba(15,23,42,0.18);
  padding: 12px 14px;
  pointer-events: none;            /* 纯展示，不抢鼠标 */
  animation: citeCardIn 120ms ease;
}
@keyframes citeCardIn { from { opacity: 0; transform: translateY(-4px); } to { opacity: 1; transform: none; } }
.cite-card-head { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.cite-card-kind { font-size: 11px; color: var(--brand, #0a0a0a); background: var(--brand-soft, #f1edff); padding: 1px 7px; border-radius: 999px; white-space: nowrap; }
.cite-card-name { font-size: 13px; font-weight: 600; color: var(--ink-1, #1e293b); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.cite-card-sub { font-size: 11.5px; color: var(--ink-4, #94a3b8); margin-bottom: 6px; }
.cite-card-snippet { font-size: 12.5px; color: var(--ink-2, #475569); line-height: 1.55; max-height: 84px; overflow: hidden; }
.cite-card-foot { margin-top: 8px; font-size: 11px; color: var(--ink-4, #94a3b8); border-top: 1px solid var(--line-soft, #eef2f7); padding-top: 6px; }
.spinner-mini {
  width: 18px; height: 18px;
  border: 2px solid rgba(255,255,255,0.35);
  border-top-color: #38bdf8;
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.thumb-close {
  position: absolute;
  top: 2px; right: 2px;
  width: 18px; height: 18px;
  border-radius: 50%;
  background: rgba(15, 23, 42, 0.7);
  border: none;
  color: #fff;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
}
.thumb-close:hover { background: #ef4444; }
.toolbar-icon-btn {
  width: 30px; height: 30px;
  border-radius: 8px;
  background: none;
  border: none;
  color: var(--ink-3, #94a3b8);
  cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center;
  transition: var(--transition);
}
.toolbar-icon-btn:hover:not(:disabled) {
  background: var(--bg-hover);
  color: var(--primary, #38bdf8);
}
.toolbar-icon-btn:disabled { opacity: 0.4; cursor: not-allowed; }
/* H5 通话按钮：桌面端隐藏（用右上角浮动按钮），移动端由 mobile.css 显示 · 电话用绿色 */
.toolbar-call-btn { display: none; color: #10b981; }
.toolbar-call-btn:hover:not(:disabled) { color: #059669; }
.toolbar-call-btn.is-active { color: #059669; background: rgba(16, 185, 129, 0.12); }

/* 联网开关按钮：带文字，开启时蓝色高亮 */
.toolbar-web-btn {
  width: auto;
  gap: 4px;
  padding: 0 8px;
  font-size: 12.5px;
  border: 1px solid var(--line, #e2e8f0);
}
.toolbar-web-btn .web-btn-label { line-height: 1; }
.toolbar-web-btn.is-active {
  color: var(--primary, #2563eb);
  background: rgba(37, 99, 235, 0.1);
  border-color: rgba(37, 99, 235, 0.35);
}

.user-img-row {
  display: flex; gap: 6px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
/* 用户消息里的附件 chip */
.user-file-row {
  display: flex; gap: 6px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.user-file-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 240px;
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--bg-surface, #ffffff);
  border: 1px solid var(--line, #e2e8f0);
  font-size: 12.5px;
  color: var(--ink-1, #1e293b);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.06);
}
.user-file-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-file-size { opacity: 0.7; font-size: 11px; flex-shrink: 0; color: var(--ink-3, #64748b); }
.user-img-thumb {
  display: block;
  width: 100px; height: 100px;
  border-radius: 8px;
  overflow: hidden;
  cursor: zoom-in;
  padding: 0;
  border: none;
  background: none;
}
.user-img-thumb img { width: 100%; height: 100%; object-fit: cover; }

.golden-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  margin-bottom: 10px;
  background: linear-gradient(135deg, rgba(52, 211, 153, 0.18), rgba(34, 197, 94, 0.10));
  border: 1px solid rgba(52, 211, 153, 0.35);
  border-radius: 999px;
  color: #34d399;
  font-size: 11.5px;
  font-weight: 600;
  cursor: help;
}
.golden-badge .el-icon { color: #34d399; }
.golden-badge.clickable { cursor: pointer; transition: background 0.15s, border-color 0.15s; }
.golden-badge.clickable:hover {
  background: linear-gradient(135deg, rgba(52, 211, 153, 0.28), rgba(34, 197, 94, 0.16));
  border-color: rgba(52, 211, 153, 0.5);
}
.golden-more { margin-left: 4px; font-weight: 600; opacity: 0.85; }
.golden-score {
  margin-left: 4px;
  font-family: 'JetBrains Mono', monospace;
  color: #6ee7b7;
  font-size: 10.5px;
}
/* 参考了 Golden Pair 范例（动态 few-shot）· 蓝色，区别于绿色的"已审核标准答案" */
.golden-ref-badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  margin-bottom: 10px;
  background: linear-gradient(135deg, rgba(0, 0, 0, 0.16), rgba(59, 130, 246, 0.08));
  border: 1px solid rgba(0, 0, 0, 0.32);
  border-radius: 999px;
  color: #71717a;
  font-size: 11.5px;
  font-weight: 600;
  cursor: help;
}
.golden-ref-badge .el-icon { color: #71717a; }
.correction-dialog .correction-hint {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 14px;
  margin-bottom: 14px;
  background: rgba(56, 189, 248, 0.08);
  border: 1px solid rgba(56, 189, 248, 0.2);
  border-radius: 8px;
  font-size: 12.5px;
  color: #94a3b8;
  line-height: 1.55;
}
.msg-actions { display: flex; gap: 4px; margin-top: 10px; }
.word-download-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 12px;
  margin-right: 6px;
  border: 1px solid rgba(37, 99, 235, 0.32);
  border-radius: 7px;
  background: rgba(37, 99, 235, 0.08);
  color: #2563eb;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.word-download-btn:hover:not(:disabled) {
  background: rgba(37, 99, 235, 0.14);
  border-color: rgba(37, 99, 235, 0.48);
}
.word-download-btn:disabled {
  cursor: wait;
  opacity: 0.65;
}
.action-btn {
  display: flex;
  align-items: center;
  background: none;
  border: none;
  cursor: pointer;
  color: #475569;
  padding: 4px 7px;
  border-radius: 5px;
  transition: var(--transition);
  font-size: 12px;
}
.action-btn:hover { background: var(--bg-hover); color: #94a3b8; }
.action-btn.active { color: var(--primary); }
.action-btn.danger { color: #f87171; }

/* 点赞 / 点踩按钮 · 强调可识别性 */
.action-btn.thumb {
  gap: 4px;
  padding: 4px 9px;
  border: 1px solid transparent;
}
.action-btn.thumb .thumb-emoji {
  font-size: 14px;
  line-height: 1;
  filter: grayscale(100%) opacity(0.6);
  transition: filter 0.15s;
}
.action-btn.thumb .thumb-label {
  font-size: 11.5px;
  font-weight: 500;
}
.action-btn.thumb:hover {
  background: rgba(56, 189, 248, 0.08);
  border-color: rgba(56, 189, 248, 0.25);
}
.action-btn.thumb:hover .thumb-emoji {
  filter: none;
}
.action-btn.thumb.active {
  background: rgba(56, 189, 248, 0.12);
  border-color: rgba(56, 189, 248, 0.4);
  color: #0284c7;
}
.action-btn.thumb.active .thumb-emoji {
  filter: none;
}
.action-btn.thumb.danger {
  background: rgba(239, 68, 68, 0.10);
  border-color: rgba(239, 68, 68, 0.35);
  color: #dc2626;
}
.action-btn.thumb.danger .thumb-emoji {
  filter: none;
}

/* 输入区 */
.input-zone {
  padding: 12px 20px 16px;
  max-width: 800px;
  width: 100%;
  margin: 0 auto;
  align-self: center;
  flex-shrink: 0;
}
.input-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 12px 14px 10px;
  transition: border-color 0.2s;
}
.input-card:focus-within { border-color: rgba(56,189,248,0.4); }

:deep(.chat-input .el-textarea__inner) {
  background: transparent !important;
  border: none !important;
  box-shadow: none !important;
  padding: 0 !important;
  min-height: 24px !important;
  color: #0f172a !important;        /* 亮色主题下用深色字，原来的 #e2e8f0 是暗色主题残留 */
  font-size: 14px;
  line-height: 1.65;
}
/* placeholder 常规可见，不再灰得快看不清 */
:deep(.chat-input .el-textarea__inner::placeholder) {
  color: #94a3b8 !important;
  opacity: 1 !important;
}

.input-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}
/* ── 来源 / 原文 右侧抽屉 ─────────────────────── */
.sources-drawer-body { padding: 4px 2px 20px; display: flex; flex-direction: column; gap: 10px; }
.sources-drawer-body .source-card {
  background: var(--bg-surface); border: 1px solid var(--border); border-radius: 10px;
  padding: 12px 14px;
}
.sources-drawer-body .empty { text-align: center; color: var(--ink-3); padding: 40px; }

/* ── #4④ PC 端原文左右分栏面板 ── */
.preview-panel {
  position: relative;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
  min-width: 360px;
  background: var(--bg-surface);
  border-left: 1px solid var(--border);
}
.preview-resizer {
  position: absolute;
  left: -3px; top: 0; bottom: 0;
  width: 6px;
  cursor: col-resize;
  z-index: 6;
  touch-action: none;
}
.preview-resizer:hover { background: var(--primary-dim); }
.preview-panel-head {
  display: flex; align-items: center; justify-content: space-between;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--border);
  flex-shrink: 0;
}
.preview-panel-title {
  font-size: 14px; font-weight: 600; color: var(--ink-1);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.preview-panel-close {
  display: flex; align-items: center; justify-content: center;
  background: none; border: none; cursor: pointer;
  color: var(--ink-3); padding: 4px; border-radius: 6px; flex-shrink: 0;
}
.preview-panel-close:hover { background: var(--bg-hover); color: var(--ink-1); }
.preview-panel .preview-panel-body { flex: 1; min-height: 0; height: auto; overflow: auto; }

.preview-header { display: flex; align-items: center; justify-content: space-between; flex: 1; gap: 12px; }
.preview-title-text { font-size: 14px; font-weight: 600; color: var(--ink-1); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.preview-drawer-body { height: 100%; display: flex; align-items: stretch; justify-content: center; padding: 8px; }
.preview-iframe { width: 100%; height: 100%; min-height: 600px; border: none; background: #fff; border-radius: 6px; }
.preview-image { max-width: 100%; max-height: 100%; object-fit: contain; }
.preview-text {
  width: 100%; max-height: 100%; overflow: auto;
  padding: 16px 18px; background: #f8fafc; border-radius: 6px;
  font-family: 'JetBrains Mono', monospace; font-size: 12.5px;
  white-space: pre-wrap; word-break: break-word; line-height: 1.6;
  color: var(--ink-1);
}
.preview-unsupported {
  margin: auto; text-align: center; color: var(--ink-3);
  display: flex; flex-direction: column; align-items: center; gap: 14px;
}
.preview-unsupported p { font-size: 13px; }

/* Office（docx / xls(x)）前端渲染容器 · 内容经 innerHTML 注入，子元素样式用 :deep */
.preview-office {
  width: 100%; height: 100%; overflow: auto;
  background: #fff; border-radius: 6px; padding: 12px 16px;
}
/* 引用定位高亮：JS 动态插入的元素拿不到 scoped 属性，用 :deep 命中 docx/excel/文本里的命中段 */
:deep(.cite-locate) {
  background: #fde68a !important;
  color: #92400e !important;
  border-radius: 3px;
  scroll-margin: 80px;
  animation: citeLocatePulse 1.6s ease-out 1;
}
@keyframes citeLocatePulse {
  0%   { background: #fbbf24; box-shadow: 0 0 0 6px rgba(251,191,36,0.35); }
  100% { background: #fde68a; box-shadow: 0 0 0 0 rgba(251,191,36,0); }
}
/* docx-preview 自带文档样式，这里只兜底字体/换行 */
.preview-office :deep(.docx-wrapper) { background: #f1f5f9; padding: 12px 0; }
.preview-office :deep(.docx-wrapper > section.docx) { box-shadow: 0 1px 6px rgba(0,0,0,.12); margin-bottom: 12px; }
/* Excel sheet 标题 + 表格边框 */
.preview-office :deep(.xlsx-sheet-title) {
  margin: 14px 0 6px; font-size: 13px; font-weight: 600; color: var(--ink-1);
  border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px;
}
.preview-office :deep(table) {
  border-collapse: collapse; width: 100%; margin-bottom: 18px; font-size: 12.5px;
}
.preview-office :deep(td), .preview-office :deep(th) {
  border: 1px solid #e2e8f0; padding: 4px 8px; white-space: nowrap;
}

.char-count { font-size: 11px; color: #334155; font-family: monospace; }
.char-count.warn { color: #f87171; }

.send-btn {
  width: 34px; height: 34px;
  border-radius: 9px;
  border: none;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  background: var(--bg-elevated);
  color: #475569;
  transition: var(--transition);
}
.send-btn.active { background: var(--primary); color: #0d1117; }
.send-btn:disabled { cursor: not-allowed; opacity: 0.4; }
.send-btn.stop-btn { background: #ef4444; color: #fff; }
.send-btn.stop-btn:hover { background: #dc2626; }
.send-btn.active:hover { background: #7dd3fc; }

.sending-dots { display: flex; gap: 3px; align-items: center; }
.sending-dots span {
  width: 4px; height: 4px;
  border-radius: 50%;
  background: #0d1117;
  animation: dots 1.2s infinite;
}
.sending-dots span:nth-child(2) { animation-delay: 0.2s; }
.sending-dots span:nth-child(3) { animation-delay: 0.4s; }
@keyframes dots { 0%, 100% { opacity: 0.3; } 50% { opacity: 1; } }

.input-disclaimer { font-size: 11px; color: #334155; text-align: center; margin-top: 8px; }

/* ─── 检索日志弹窗 ─── */
:deep(.rl-dialog .el-dialog__body) { padding: 0 22px 22px; }

.rl-pipeline {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 20px;
  padding: 12px 14px;
  background: var(--bg-elevated);
  border-radius: var(--radius-sm);
}
.rl-step-wrap { display: flex; align-items: center; gap: 4px; }
.rl-step {
  display: flex; align-items: center; gap: 6px;
  font-size: 12px; font-weight: 600;
  color: var(--step-color);
  background: color-mix(in srgb, var(--step-color) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--step-color) 25%, transparent);
  border-radius: 20px;
  padding: 4px 10px;
}
.step-dot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; flex-shrink: 0; }
.rl-step-arrow { color: #334155; font-size: 12px; }

.rl-block { margin-bottom: 18px; }
.rl-block-title { font-size: 12px; font-weight: 700; color: #64748b; letter-spacing: 0.5px; text-transform: uppercase; margin-bottom: 10px; }
.rl-query-row { display: flex; align-items: center; gap: 10px; }
.rl-query-box { flex: 1; background: var(--bg-elevated); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 10px 12px; }
.rl-query-box.improved { border-color: rgba(56,189,248,0.2); }
.rl-query-label { font-size: 10px; font-weight: 700; color: #475569; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 4px; }
.rl-query-text { font-size: 13px; color: #cbd5e1; line-height: 1.5; }
.rl-metrics { display: flex; align-items: center; gap: 14px; }
.rl-metric { text-align: center; }
.rl-metric-val { font-size: 28px; font-weight: 800; font-family: 'JetBrains Mono', monospace; line-height: 1; }
.rl-metric-lbl { font-size: 11px; color: #64748b; margin-top: 4px; }
.rl-result-row { display: flex; align-items: center; justify-content: space-between; }
.rl-empty { text-align: center; color: #475569; padding: 32px; font-size: 14px; }

/* ============================================================
   ChatGPT 极简风格覆盖层（2026-08-13 · 用户要求）
   - 白底 + 浅灰边框 + 深色文字 · 大量留白
   - 用户气泡浅灰底 · AI 消息无底色
   ============================================================ */
.welcome-screen { background: rgba(255,255,255,.45) !important; backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); }
.chat-main { background: rgba(255,255,255,.45) !important; backdrop-filter: blur(10px); -webkit-backdrop-filter: blur(10px); }
.welcome-title { color: #0f172a !important; }
.gradient-text {
  background: none !important;
  -webkit-text-fill-color: #0f172a !important;
  color: #0f172a !important;
}
.welcome-desc { color: #64748b !important; }
.welcome-logo { width: 96px; height: auto; filter: none !important; }
.welcome-logo-img { width: 100%; height: auto; object-fit: contain; }
.quick-item {
  background: rgba(255,255,255,.85) !important;
  border: 1px solid #e8e8ed !important;
  border-radius: 14px !important;
  color: #334155 !important;
  box-shadow: 0 1px 3px rgba(0,0,0,.03) !important;
  transition: all .2s cubic-bezier(.22,1,.36,1) !important;
}
.quick-item:hover {
  border-color: rgba(0,113,227,.4) !important;
  background: #ffffff !important;
  color: #0f172a !important;
  transform: translateY(-2px);
  box-shadow: 0 8px 24px rgba(0,113,227,.10) !important;
}
.welcome-tips { color: #94a3b8 !important; background: rgba(255,255,255,.6) !important; border-color: #e8e8ed !important; }

.messages-area { background: transparent !important; }
.messages-inner { max-width: 1400px !important; padding: 16px 16px !important; }

/* 用户消息 · 浅灰气泡 */
.user-av { background: #f1f5f9 !important; color: #475569 !important; }
.user-bubble .bubble-text {
  background: #F2F2F7 !important;
  color: #1f2937 !important;
  border-radius: 22px !important;
  padding: 10px 16px !important;
  font-size: 14.5px !important;
  line-height: 1.6 !important;
}

/* AI 消息 · 无底色 + ZYCOO 头像 */
.ai-av { background: transparent !important; box-shadow: none !important; border: 0 !important; }
.ai-avatar-wrap { display: none !important; }
.ai-bubble {
  background: transparent !important;
  border: 0 !important;
  padding: 0 !important;
}
.bubble-content {
  line-height: 1.85 !important;
  color: #1f2937 !important;
  font-size: 15px !important;
}
.bubble-content p { margin: 0 0 14px 0 !important; }
.bubble-content ul, .bubble-content ol { margin: 8px 0 16px 0 !important; padding-left: 24px !important; }
.bubble-content li { margin: 6px 0 !important; line-height: 1.75; }
.bubble-content h1, .bubble-content h2, .bubble-content h3 { margin: 24px 0 12px 0 !important; font-weight: 600; }
.ai-logo-img { width: 20px; height: 20px; object-fit: contain; }

/* 输入区 · 浅灰圆角胶囊 */
.input-zone { background: transparent !important; }
.input-card {
  background: #F2F2F7 !important;
  border: 1px solid #E8E8ED !important;
  border-radius: 22px !important;
  box-shadow: 0 4px 24px rgba(0,0,0,.04) !important;
  transition: border-color .2s, box-shadow .2s, transform .2s cubic-bezier(.22,1,.36,1) !important;
}
.input-card:focus-within { border-color: rgba(0,113,227,.5) !important; box-shadow: 0 0 0 4px rgba(0,113,227,.12), 0 8px 32px rgba(0,0,0,.06) !important; transform: translateY(-2px); }
:deep(.chat-input .el-textarea__inner) {
  background: transparent !important;
  font-size: 14px !important;
  color: #0f172a !important;
}
:deep(.chat-input .el-textarea__inner::placeholder) { color: #94a3b8 !important; }
.send-btn {
  background: #0071E3 !important;
  color: #ffffff !important;
  border-radius: 50% !important;
  box-shadow: 0 4px 14px rgba(0,113,227,.35) !important;
  transition: filter .2s, transform .2s !important;
}
.send-btn:hover:not(:disabled) { filter: brightness(1.1); transform: scale(1.05); }
.send-btn:disabled { opacity: 0.4 !important; box-shadow: none !important; }
.input-disclaimer { color: #94a3b8 !important; }

/* ============================================================
   侧栏极简化（2026-08-13 · 用户要求 删紫 + 模仿钉钉白底简洁）
   ============================================================ */
.chat-sidebar {
  background: #ffffff !important;
  border-right: 1px solid #e2e8f0 !important;
}
.chat-sidebar-mask { background: rgba(15,23,42,0.4) !important; }

.new-chat-btn {
  background: #ffffff !important;
  background-image: none !important;
  border: 1px solid #e2e8f0 !important;
  color: #0f172a !important;
  border-radius: 10px !important;
}
.new-chat-btn:hover {
  background: #f8fafc !important;
  background-image: none !important;
  border-color: #cbd5e1 !important;
  color: #0f172a !important;
}
.new-chat-btn span { color: inherit !important; }

.conv-tool-btn {
  background: transparent !important;
  color: #475569 !important;
  background-image: none !important;
}
.conv-tool-btn:hover { background: #f1f5f9 !important; color: #0f172a !important; background-image: none !important; }
.conv-tool-btn.danger { color: #ef4444 !important; }

.conv-item { background: transparent !important; }
.conv-item:hover { background: #f8fafc !important; }
.conv-item.active { background: #f1f5f9 !important; border-left-color: #0f172a !important; }
.conv-item.active .conv-icon { color: #0f172a !important; }
.conv-item.checked { background: #eff6ff !important; }
.conv-icon { color: #94a3b8 !important; }
.conv-title { color: #0f172a !important; }
.conv-meta { color: #94a3b8 !important; }
.voice-icon { color: #0f172a !important; }
.voice-badge {
  background: #f1f5f9 !important;
  color: #475569 !important;
  background-image: none !important;
}

.kb-label { color: #475569 !important; }
.kb-label :deep(.el-icon) { color: #94a3b8 !important; }
.kb-optional { color: #94a3b8 !important; background: transparent !important; }
.kb-selector { background: #ffffff !important; }
.kb-hint { color: #94a3b8 !important; }
:deep(.kb-selector .el-select .el-input__wrapper) {
  background: #ffffff !important;
  box-shadow: 0 0 0 1px #e2e8f0 !important;
  border-radius: 8px !important;
}
:deep(.kb-selector .el-select .el-input__wrapper:hover) { box-shadow: 0 0 0 1px #cbd5e1 !important; }

.empty-conv { color: #94a3b8 !important; }
.empty-conv :deep(.el-icon) { color: #cbd5e1 !important; }
.empty-conv p { color: #94a3b8 !important; }

/* 侧栏底部用户区域 */
.sidebar-user,
.chat-sidebar :deep(.sidebar-user) {
  background: #ffffff !important;
  border-top: 1px solid #e2e8f0 !important;
  color: #0f172a !important;
  background-image: none !important;
}

/* ============================================================
   ChatGPT 极简风 v3（2026-08-13 · 用户要求 大改动）
   - 欢迎屏只剩"随时可以开始。"小字
   - 侧栏默认隐藏（☰ 按钮打开抽屉）
   - 输入框 ChatGPT 圆角 26px 风格
   ============================================================ */

/* 侧栏恢复显示（2026-08-13 回退 v3 的默认隐藏 · 保留 v2 白底极简风格） */
.chat-sidebar { display: flex !important; }

/* 欢迎屏 · 在 .chat-main 内居中（与输入框贴近·微微高于输入框） */
.chat-main { position: relative !important; }
.welcome-screen {
  position: absolute !important;
  top: 40% !important;
  left: 50% !important;
  transform: translate(-50%, -50%) !important;
  background: transparent !important;
  display: flex !important;
  align-items: center !important;
  justify-content: center !important;
  padding: 24px !important;
  width: auto !important;
  min-height: auto !important;
  z-index: 5;
  pointer-events: none;
}
.welcome-hint {
  font-size: 22px !important;
  font-weight: 400 !important;
  color: #0f172a !important;
  letter-spacing: -0.01em;
  margin: 0 !important;
  text-align: center;
  pointer-events: auto;
}

/* 消息区 · 极简 */
.messages-area { background: rgba(255,255,255,.45) !important; }
.messages-inner { max-width: 1400px !important; padding: 20px 16px !important; }

/* 隐藏消息气泡（彻底无气泡） */
.user-av { background: #f1f5f9 !important; color: #475569 !important; }
.user-bubble .bubble-text {
  background: transparent !important;
  color: #1f2937 !important;
  border-radius: 0 !important;
  padding: 0 !important;
}
.ai-av { background: transparent !important; box-shadow: none !important; border: 0 !important; }
.ai-avatar-wrap { display: none !important; }
.ai-bubble { background: transparent !important; border: 0 !important; padding: 0 !important; }

/* 输入区 · ChatGPT 极简 v3.3（2026-08-13）· + 按钮集成 + 移到红色方框位置 */
.input-zone {
  background: transparent !important;
  position: absolute !important;
  top: 50% !important;
  left: 50% !important;
  transform: translate(-50%, -50%) !important;
  width: min(640px, calc(100% - 48px)) !important;
  z-index: 50 !important;
  /* v6 ChatGPT 方案（2026-08-14）：第一个问题问完后输入框下移到底部 · 平滑过渡 */
  transition: top 380ms cubic-bezier(0.4, 0, 0.2, 1),
              bottom 380ms cubic-bezier(0.4, 0, 0.2, 1),
              transform 380ms cubic-bezier(0.4, 0, 0.2, 1) !important;
}

/* v6：当 messages-area 可见时（!showWelcome，有对话内容），输入框下移到底部，不遮挡对话 */
.chat-main:has(.messages-area:not([style*="display: none"])) .input-zone {
  top: auto !important;
  bottom: 24px !important;
  transform: translateX(-50%) !important;
}
.input-card {
  background: #ffffff !important;
  border: 0.5px solid #d1d5db !important;
  border-radius: 26px !important;
  box-shadow: 0 2px 12px rgba(15,23,42,0.06) !important;
  padding: 10px 48px 10px 50px !important;  /* 左右各留 40-50px 给 + 和 ↑ 按钮 */
  position: relative !important;
}
.input-card:focus-within { border-color: #9ca3af !important; box-shadow: 0 2px 18px rgba(15,23,42,0.08) !important; }

/* + 按钮 · input-card 最左 · + 号完美居中 */
.input-plus-btn {
  position: absolute !important;
  left: 8px !important;
  top: 50% !important;
  transform: translateY(-50%) !important;
  width: 36px; height: 36px;
  border-radius: 50% !important;
  border: 0.5px solid #d1d5db !important;
  background: #ffffff !important;
  color: #475569 !important;
  font-size: 22px !important;
  font-weight: 300 !important;
  line-height: 1 !important;
  cursor: pointer;
  display: flex !important; align-items: center !important; justify-content: center !important;
  padding: 0 !important;
  padding-bottom: 5px !important;  /* + 号基线微调（增大到 5px 让 + 上移居中） */
  z-index: 5;
}
.input-plus-btn:hover { background: #f8fafc !important; }
.input-plus-btn:disabled { opacity: 0.4; cursor: not-allowed; }

/* popover 菜单样式 */
.input-pop-menu { display: flex; flex-direction: column; gap: 2px; padding: 4px 0; }
.pop-menu-item {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 12px;
  border: none; background: transparent;
  cursor: pointer; text-align: left;
  border-radius: 8px;
  color: #0f172a;
  font-size: 13px;
  width: 100%;
}
.pop-menu-item:hover { background: #f1f5f9; }
.pop-menu-item.is-active { background: rgba(56,189,248,0.08); color: #0369a1; }
.pop-menu-item:disabled { opacity: 0.4; cursor: not-allowed; }
.pop-menu-text { display: flex; flex-direction: column; gap: 2px; }
.pop-menu-hint { font-size: 11px; color: #94a3b8; }
.pop-menu-voice { padding: 0 4px; }

/* 隐藏底部工具栏 + disclaimer */
.input-toolbar { display: none !important; }
.input-disclaimer { display: none !important; }

/* 发送按钮 · 在 input-card 内部右对齐（不嵌出） */
.input-card .send-btn {
  position: absolute !important;
  right: 8px !important;
  top: 50% !important;
  transform: translateY(-50%) !important;
  background: #9ca3af !important;  /* 浅灰（参考图） */
  color: #ffffff !important;
  border-radius: 50% !important;
  width: 34px; height: 34px;
  margin: 0 !important;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 2px 6px rgba(15,23,42,0.08) !important;
}
.input-card .send-btn.active { background: #6b7280 !important; }  /* 有内容时深一点 */
.input-card .send-btn:disabled { background: #d1d5db !important; opacity: 1 !important; }

:deep(.chat-input .el-textarea__inner) {
  background: transparent !important;
  font-size: 14.5px !important;
  color: #0f172a !important;
  line-height: 1.5 !important;
  min-height: 24px !important;
  border: none !important;
  padding: 6px 4px !important;
}
:deep(.chat-input .el-textarea__inner::placeholder) { color: #9ca3af !important; }
/* 去掉 el-input 外框（input-card 已有外框） */
:deep(.chat-input .el-textarea) { box-shadow: none !important; }

/* 浮动通话按钮 · 极简化 */
.floating-call-btn {
  top: auto !important;
  bottom: 90px !important;
  right: 24px !important;
  width: 48px !important;
  height: 48px !important;
  background: #ffffff !important;
  border: 1px solid rgba(37, 38, 43, .10) !important;
  color: #475569 !important;
  box-shadow: 0 8px 24px rgba(38, 35, 28, .10) !important;
  background-image: none !important;
}
.floating-call-btn.is-active { background: #10b981 !important; color: #fff !important; }
</style>

<style src="./chat-codex.css"></style>
