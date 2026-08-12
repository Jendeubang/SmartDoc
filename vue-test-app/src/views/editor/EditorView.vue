<template>
  <!-- ================= 模式 A：全局沉浸式 AI 对话模式 (对标飞书 aily) ================= -->
  <div v-if="isChatMode" class="feishu-chat-layout">
    <!-- 左侧会话历史导航 -->
    <aside class="chat-sidebar">
      <div class="sidebar-top">
        <div class="brand-back" @click="$router.push('/dashboard')">
          <el-icon><ArrowLeft /></el-icon> 返回工作台
        </div>
        <el-button class="new-chat-btn" icon="Plus" plain @click="startNewChat">开启新话题</el-button>

        <div class="history-group">
          <div class="group-title">历史会话</div>
          <div class="history-item active">
            <el-icon><ChatLineRound /></el-icon>
            <span class="text">{{ chatTitle }}</span>
          </div>
        </div>
      </div>
      <div class="sidebar-bottom">
        <div class="user-profile">
          <el-avatar :size="30" style="background-color: #3370ff; font-weight: bold; color: white;">
            {{ currentUserName.charAt(0).toUpperCase() }}
          </el-avatar>
          <span class="username">{{ currentUserName }}</span>
        </div>
      </div>
    </aside>

    <!-- 主聊天区域 -->
    <main class="chat-main">
      <header class="chat-header">
        <div class="header-inner">
          <span class="title">{{ chatTitle }}</span>
          <div class="header-actions">
            <el-button icon="Share" link>分享</el-button>
            <el-button icon="MoreFilled" link></el-button>
          </div>
        </div>
      </header>

      <!-- 聊天内容滚动区 -->
      <div class="chat-scroll-area" ref="globalChatRef">
        <div class="chat-content-container">
          <div v-for="(msg, i) in chatHistory" :key="i" :class="['message-row', msg.role]">
            <div class="avatar-col" v-if="msg.role === 'ai'">
              <el-avatar :size="36" src="https://api.dicebear.com/7.x/avataaars/svg?seed=AIAssistant" class="ai-avatar" />
            </div>
            <div class="message-content-col">
              <div v-if="msg.role === 'user'" class="user-bubble preserve-format">{{ msg.text }}</div>
              <div v-else class="ai-structured-card">
                <div class="card-body preserve-format">{{ msg.text }}</div>
                <div v-if="msg.sources?.length" class="message-sources">
                  <div class="sources-heading">参考来源 · {{ msg.sources.length }}</div>
                  <button v-for="(source, sourceIndex) in msg.sources" :key="`${source.documentId}-${sourceIndex}`" type="button" class="source-card" @click="openSourceDocument(source)">
                    <span>{{ sourceIndex + 1 }}</span><div><strong>{{ source.title }}</strong><small>{{ source.segmentTitle || '相关片段' }}<template v-if="source.score"> · 匹配度 {{ source.score }}%</template></small><p v-if="source.excerpt">{{ source.excerpt }}</p></div>
                  </button>
                </div>

                <div v-if="msg.actionResult" class="agent-action-box" style="margin-top: 16px;">
                  <el-divider border-style="dashed" style="margin: 12px 0;" />
                  <div style="font-size: 13px; color: #8f959e; margin-bottom: 10px; display: flex; align-items: center; gap: 6px;">
                    <el-icon><Check /></el-icon> 任务执行成功
                  </div>

                  <div v-if="msg.actionResult.type === 'document-write'" class="doc-write-card"
                       style="display: flex; align-items: center; gap: 12px; background: #f4f9f4; padding: 12px 16px; border-radius: 8px;">
                    <el-icon size="28" color="#35a853"><Document /></el-icon>
                    <div>
                      <div style="font-weight: 600; font-size: 14px; color: #1f2329;">已写入当前文档</div>
                      <div style="font-size: 12px; color: #6b7280; margin-top: 4px;">{{ msg.actionResult.changeLog || 'AI 内容已应用，尚未保存' }}</div>
                    </div>
                  </div>

                  <div v-else class="file-card" @click="handleDownloadAgentFile(msg.actionResult)"
                       style="display: flex; align-items: center; gap: 12px; background: #f4f5f7; padding: 12px 16px; border-radius: 8px; cursor: pointer; transition: background 0.2s;">
                    <el-icon size="28" color="#3370ff"><Document /></el-icon>
                    <div>
                      <div style="font-weight: 600; font-size: 14px; color: #1f2329;">
                        {{ msg.actionResult.fileName || '生成的文档' }}
                      </div>
                      <div style="font-size: 12px; color: #8f959e; margin-top: 4px;">点击查看或下载</div>
                    </div>
                  </div>
                </div>

              </div>
            </div>
          </div>

          <!-- 思考中状态 -->
          <div v-if="isAiThinking" class="message-row ai">
            <div class="avatar-col"><el-avatar :size="36" src="https://api.dicebear.com/7.x/avataaars/svg?seed=AIAssistant" /></div>
            <div class="message-content-col">
              <div class="ai-structured-card thinking">
                <el-icon class="is-loading"><Loading /></el-icon> {{ editorProgressStep || '正在执行：搜索知识库并生成分析...' }}
              </div>
            </div>
          </div>
        </div>
      </div>

      <!-- 底部悬浮输入区 (支持 RAG 切换) -->
      <div class="chat-input-container">
        <div class="chat-context-row">
          <span class="chat-context-label"><el-icon><FolderOpened /></el-icon> 文档上下文</span>
          <el-select v-model="selectedChatDocumentIds" class="chat-document-select" multiple collapse-tags collapse-tags-tooltip clearable filterable :loading="chatDocumentsLoading" placeholder="不选择时检索全部知识库">
            <el-option v-for="doc in chatDocuments" :key="doc.id" :label="doc.title" :value="doc.id" />
          </el-select>
        </div>
        <div class="chat-context-hint">{{ selectedChatDocumentIds.length ? `已选择 ${selectedChatDocumentIds.length} 份文档，AI 将优先依据这些文档回答` : '未选择文档时，AI 将根据问题检索你的全部知识库' }}</div>
        <div class="input-wrapper-inner" :class="{ 'is-focused': isInputFocused, 'rag-active': isRagMode }">
          <!-- 模式切换：气泡/书本图标 -->
          <el-tooltip :content="isRagMode ? '已开启：基于已索引文档回答' : '点击后：基于已索引文档回答'" placement="top">
            <button class="rag-mode-toggle" :class="{ active: isRagMode }" type="button" @click="isRagMode = !isRagMode">
              <el-icon><Collection /></el-icon>
              <span>基于文档回答</span>
            </button>
          </el-tooltip>

          <button class="chat-attach-btn" type="button" aria-label="上传文档" title="上传文档" :disabled="chatUploadLoading" @click="openChatFilePicker">
            <el-icon><Plus /></el-icon>
          </button>
          <input ref="chatFileInputRef" class="chat-file-input" type="file" accept=".pdf,.doc,.docx,.txt,.md,.ppt,.pptx,.xls,.xlsx" @change="onChatFileSelected" />
          <input
              v-model="userMsg"
              class="chat-input"
              placeholder="问问我关于工作的任何指令..."
              @keyup.enter="handleSend"
              @focus="isInputFocused = true"
              @blur="isInputFocused = false"
          />
          <div class="input-right">
            <el-icon class="mic-icon"><Microphone /></el-icon>
            <div class="send-btn-circle" :class="{ 'active': userMsg.length > 0 }" @click="handleSend">
              <el-icon size="20"><Top /></el-icon>
            </div>
          </div>
        </div>
        <div class="ai-hint">AI 可能生成错误内容，仅供参考</div>
      </div>
    </main>
  </div>

  <!-- ================= 模式 B：传统的文档阅读模式 (格式保护 + 悬浮球 + 优化侧边栏) ================= -->
  <div v-else class="editor-page">
    <header class="toolbar">
      <div class="left">
        <el-button icon="ArrowLeft" link @click="$router.push('/dashboard')"></el-button>
        <el-divider direction="vertical" />
        <span class="doc-title">{{ docName }}</span>
        <span class="save-status">{{ saveStatusText }}</span>
        <el-button circle icon="Clock" size="small" style="margin-left:10px;" @click="openHistoryDrawer"></el-button>
      </div>
      <div class="header-right">
        <el-button v-if="!isSourcePreview" size="small" round icon="ChatDotRound" @click="openReviewDrawer">审阅</el-button>
        <el-button v-if="!isSourcePreview" type="primary" size="small" round icon="Check" @click="handleManualSave" :loading="isSaving">手动保存</el-button>
        <el-button size="small" round icon="Download" @click="handleDownload">{{ isSourcePreview ? '下载原文件' : '下载最新版' }}</el-button>
        <el-button type="primary" size="small" round icon="Share" @click="shareDialogVisible = true">协作</el-button>
        <el-avatar :size="30" style="background-color: #3370ff; font-weight: bold; color: white; margin-left:10px;">{{ currentUserName.charAt(0).toUpperCase() }}</el-avatar>
      </div>
    </header>

    <!-- 分享协作弹窗 -->
    <el-dialog v-model="shareDialogVisible" title="🔗 分享文档与协作" width="520px" destroy-on-close @open="fetchShareCollaborators">
      <div class="share-body">
        <div class="share-section">
          <div class="share-label">文档链接</div>
          <div class="link-row">
            <el-input :model-value="shareLink" readonly size="small">
              <template #append>
                <el-button @click="copyShareLink">复制链接</el-button>
              </template>
            </el-input>
          </div>
          <div class="link-hint">拥有链接的用户需被授权后才能访问</div>
        </div>

        <el-divider />

        <div class="share-section">
          <div class="share-label">授权协作者</div>
          <div class="grant-row">
            <el-input v-model="collaboratorQuery" size="small" clearable placeholder="输入用户名或用户ID" @keyup.enter="handleGrantCollab" />
            <el-select v-model="collaboratorRole" size="small" style="width: 88px;">
              <el-option label="可编辑" value="editor" />
              <el-option label="只读" value="viewer" />
            </el-select>
            <el-select v-model="collaboratorExpiry" size="small" style="width: 104px;">
              <el-option label="7 天" :value="168" />
              <el-option label="30 天" :value="720" />
              <el-option label="永久" :value="0" />
            </el-select>
            <el-button type="primary" size="small" :loading="grantLoading" @click="handleGrantCollab">授权</el-button>
          </div>
          <div class="granted-list" v-if="shareGrantedUsers.length > 0">
            <div class="granted-item" v-for="u in shareGrantedUsers" :key="u.userId">
              <span class="granted-name">{{ u.userName || u.userId }}</span>
              <el-tag size="small" :type="u.expired ? 'danger' : 'info'" effect="plain">{{ u.expired ? '已过期' : u.role }}</el-tag>
              <small v-if="u.expiresAt" class="grant-expiry">至 {{ new Date(u.expiresAt).toLocaleDateString() }}</small>
              <el-button v-if="isDocOwner" size="small" type="danger" plain circle @click="handleRevokeCollab(u.userId)">
                <el-icon><Close /></el-icon>
              </el-button>
            </div>
          </div>
          <div class="no-granted" v-else>暂无授权用户</div>
        </div>

        <el-divider />

        <div class="share-section">
          <div class="share-label">实时协作</div>
          <p class="collab-hint">授权后，协作者打开文档链接即可加入实时协作编辑</p>
          <p class="collab-hint" style="margin-top: 6px;">协作者在编辑器左侧底部点击「加入协作」即可同步编辑</p>
        </div>
      </div>
    </el-dialog>

    <div class="workspace">
      <aside class="left-sidebar">
        <div class="sidebar-section" v-if="textStats">
          <div class="section-title"><el-icon><DataLine /></el-icon> 文本分析</div>
          <div class="stats-grid">
            <div class="stat-item"><span class="num">{{ textStats.totalCharacters || 0 }}</span><span class="desc">字数</span></div>
            <div class="stat-item"><span class="num">{{ textStats.lines || 0 }}</span><span class="desc">段落</span></div>
            <div class="stat-item"><span class="num">{{ textStats.chineseCharacters || 0 }}</span><span class="desc">中文字符</span></div>
            <div class="stat-item"><span class="num">{{ textStats.punctuations || 0 }}</span><span class="desc">标点</span></div>
          </div>
        </div>

        <div class="sidebar-section" v-if="aiSummary">
          <div class="section-title"><el-icon><Document /></el-icon> ✨ 智能摘要</div>
          <div class="summary-text preserve-format">{{ aiSummary }}</div>
          <div class="keywords-area" v-loading="keywordsLoading">
            <el-tag
                v-for="(tag, index) in keywords"
                :key="index"
                class="keyword-tag"
                size="small"
                round
                effect="light"
                :type="['info', 'success', 'warning', 'danger', 'info'][index % 5]"
            ># {{ tag }}</el-tag>
          </div>
        </div>

        <div class="sidebar-section" style="margin-top: auto;"> <CollaboratePanel :documentId="docId" :userId="currentUserId" :userName="currentUserName" />
        </div>
      </aside>

      <main class="editor-main" v-loading="docLoading" @scroll="handleScroll">
        <div class="paper-container" :class="{ 'image-paper-container': isSourcePreview }">
          <div v-if="isSourcePreview" class="image-viewer">
            <div class="image-viewer-toolbar">
              <span><el-icon><Picture /></el-icon> {{ isPdfDocument ? 'PDF 预览' : '图片预览' }}</span>
              <div v-if="isImageDocument" class="image-viewer-actions">
                <el-button circle size="small" title="缩小" @click="zoomImage(-0.1)"><el-icon><Minus /></el-icon></el-button>
                <span class="image-zoom-value">{{ Math.round(imageZoom * 100) }}%</span>
                <el-button circle size="small" title="放大" @click="zoomImage(0.1)"><el-icon><Plus /></el-icon></el-button>
                <el-button size="small" plain @click="fitImage">适应窗口</el-button>
              </div>
            </div>
            <div class="image-canvas">
              <img v-if="isImageDocument && imagePreviewUrl" :src="imagePreviewUrl" :alt="docName" :style="{ transform: `scale(${imageZoom})` }" />
              <iframe v-else-if="isPdfDocument && imagePreviewUrl" class="pdf-frame" :src="imagePreviewUrl" :title="docName"></iframe>
              <el-empty v-else description="预览加载失败，请下载原文件查看" />
            </div>
          </div>
          <div v-else class="paper preserve-format" :contenteditable="canEditDocument ? 'true' : 'false'" :class="{ 'read-only-paper': !canEditDocument }" v-html="docContent" @mouseup="handleTextSelection(); acquireParagraphLock()" @focusin="acquireParagraphLock" @keyup="acquireParagraphLock" @input="handleInput"></div>
        </div>

        <transition name="el-zoom-in-center">
          <div v-if="showAiBall" class="ai-float-ball" :style="ballStyle">
            <div class="ball-inner">
              <div class="menu-opt" @click.stop="askAiWithContext('润色')"><el-icon><MagicStick /></el-icon> 润色</div>
              <el-divider direction="vertical" />
              <div class="menu-opt" @click.stop="askAiWithContext('纠错')"><el-icon><Aim /></el-icon> 纠错</div>
              <div class="ball-arrow"></div>
            </div>
          </div>
        </transition>
      </main>

      <aside class="ai-sidebar expanded-ai">
        <div class="ai-sidebar-top">
          <div class="ai-header">
            <span style="display: flex; align-items: center; gap: 6px;">
              <el-icon color="#409EFF" size="18"><MagicStick /></el-icon> SmartDoc 灵感助理
            </span>
            <el-select v-model="currentModel" size="small" placeholder="切换模型" style="width: 130px;" @change="handleModelChange">
              <el-option v-for="m in modelOptions" :key="m.code" :label="m.name" :value="m.code" />
            </el-select>
          </div>
        </div>

        <div class="chat-area" ref="sidebarChatRef">
          <div v-for="(msg, i) in chatHistory" :key="i" :class="['chat-bubble', msg.role]">
            <div class="preserve-format">{{ msg.text }}</div>
            <div v-if="msg.sources?.length" class="message-sources compact">
              <div class="sources-heading">参考来源 · {{ msg.sources.length }}</div>
              <button v-for="(source, sourceIndex) in msg.sources" :key="`${source.documentId}-${sourceIndex}`" type="button" class="source-card" @click="openSourceDocument(source)">
                <span>{{ sourceIndex + 1 }}</span><div><strong>{{ source.title }}</strong><small>{{ source.segmentTitle || '相关片段' }}</small></div>
              </button>
            </div>

            <div v-if="msg.actionResult" class="agent-action-box" style="margin-top: 10px;">
              <el-divider border-style="dashed" style="margin: 8px 0;" />
              <div style="font-size: 12px; color: #8f959e; margin-bottom: 8px; display: flex; align-items: center; gap: 4px;">
                <el-icon><Check /></el-icon> 执行结果
              </div>
              <div v-if="msg.actionResult.type === 'document-write'" class="doc-write-card"
                   style="display: flex; align-items: center; gap: 10px; background: #f4f9f4; padding: 10px; border-radius: 6px;">
                <el-icon size="24" color="#35a853"><Document /></el-icon>
                <div>
                  <div style="font-weight: bold; font-size: 13px; color: #1f2329;">已写入当前文档</div>
                  <div style="font-size: 12px; color: #6b7280; margin-top: 2px;">{{ msg.actionResult.changeLog || 'AI 内容已应用，尚未保存' }}</div>
                </div>
              </div>
              <div v-else class="file-card" @click="handleDownloadAgentFile(msg.actionResult)"
                   style="display: flex; align-items: center; gap: 10px; background: #f4f5f7; padding: 10px; border-radius: 6px; cursor: pointer;">
                <el-icon size="24" color="#3370ff"><Document /></el-icon>
                <div>
                  <div style="font-weight: bold; font-size: 13px; color: #1f2329;">{{ msg.actionResult.fileName || '生成的文档' }}</div>
                  <div style="font-size: 12px; color: #8f959e; margin-top: 2px;">点击下载</div>
                </div>
              </div>
            </div>
          </div>
          <div v-if="isAiThinking" class="chat-bubble ai thinking">
            <el-icon class="is-loading"><Loading /></el-icon> {{ editorProgressStep || 'AI 正在为您生成...' }}
          </div>
        </div>

        <div class="input-area">
          <div class="selected-context" v-if="selectedText">已选：{{ selectedText.substring(0, 15) }}...</div>
          <div class="input-actions" style="margin-bottom: 8px; display: flex; justify-content: space-between; align-items: center;">
            <div style="display: flex; gap: 12px; align-items: center;">
              <el-switch v-model="isRagMode" active-text="全局 RAG" size="small" />
              <el-switch v-model="replaceOnWrite" active-text="替换原文" size="small" />
            </div>
            <el-button link size="small" type="primary" icon="Setting" @click="openRagSettings">知识分段</el-button>
          </div>
          <el-input v-model="userMsg" type="textarea" :rows="3" placeholder="下达任意指令，如生成PPT、润色、总结..." @keyup.enter.native="handleSend" resize="none" />
          <el-button type="primary" class="send-btn" :loading="isAiThinking" @click="handleSend" icon="Position">发送给 AI</el-button>
        </div>
      </aside>

      <el-drawer v-model="showHistory" title="文档历史版本" size="540px" direction="rtl">
        <section v-if="versionList.length > 1" class="version-compare-panel">
          <div class="review-section-title"><strong>版本差异</strong><span>逐行比较两个历史版本</span></div>
          <div class="version-compare-actions">
            <el-select v-model="diffFrom" placeholder="起始版本"><el-option v-for="ver in versionList" :key="`from-${ver.id}`" :label="`V${ver.versionNumber}`" :value="ver.versionNumber" /></el-select>
            <span>→</span>
            <el-select v-model="diffTo" placeholder="目标版本"><el-option v-for="ver in versionList" :key="`to-${ver.id}`" :label="`V${ver.versionNumber}`" :value="ver.versionNumber" /></el-select>
            <el-button type="primary" :loading="diffLoading" @click="loadVersionDiff">比较</el-button>
          </div>
          <div v-if="versionDiff" class="diff-viewer">
            <div class="diff-summary"><el-tag type="success">新增 {{ versionDiff.addedLines }} 行</el-tag><el-tag type="danger">删除 {{ versionDiff.removedLines }} 行</el-tag></div>
            <div class="diff-lines">
              <div v-for="(line, index) in versionDiff.changes" :key="index" :class="['diff-line', line.type]">
                <span>{{ line.oldLine || '' }}</span><span>{{ line.newLine || '' }}</span><code>{{ line.type === 'added' ? '+' : line.type === 'removed' ? '-' : ' ' }} {{ line.content || ' ' }}</code>
              </div>
            </div>
          </div>
        </section>
        <el-divider v-if="versionList.length > 1" />
        <el-timeline v-if="versionList.length > 0">
          <el-timeline-item v-for="ver in versionList" :key="ver.id" :timestamp="new Date(ver.createTime).toLocaleString()" placement="top">
            <el-card shadow="hover" class="version-card">
              <p>版本号：V{{ ver.versionNumber }}</p>
              <el-button size="small" type="primary" plain style="margin-top:10px" @click="handleRestore(ver.versionNumber)">恢复此版本</el-button>
            </el-card>
          </el-timeline-item>
        </el-timeline>
        <el-empty v-else description="暂无历史记录" />
      </el-drawer>

      <el-drawer v-model="reviewDrawerVisible" title="评论与建议" size="460px" direction="rtl">
        <el-tabs v-model="reviewTab">
          <el-tab-pane :label="`评论 ${comments.length ? '(' + comments.length + ')' : ''}`" name="comments">
            <div class="review-composer">
              <el-input v-model="commentContent" type="textarea" :rows="3" maxlength="4000" show-word-limit placeholder="写下评论，供协作者讨论……" />
              <el-button type="primary" :loading="reviewSubmitting" @click="submitComment">发表评论</el-button>
            </div>
            <div class="review-list" v-loading="reviewLoading">
              <article v-for="comment in comments" :key="comment.id" class="review-card">
                <header><el-avatar :size="30">{{ String(comment.userName || comment.userId || 'U').charAt(0).toUpperCase() }}</el-avatar><div><strong>{{ comment.userName || `用户 ${comment.userId}` }}</strong><small>{{ formatReviewTime(comment.createTime) }}</small></div><el-button v-if="String(comment.userId) === String(currentUserId)" text type="danger" @click="removeComment(comment)">删除</el-button></header>
                <p>{{ comment.content }}</p>
              </article>
              <el-empty v-if="!reviewLoading && !comments.length" description="还没有评论" :image-size="70" />
            </div>
          </el-tab-pane>
          <el-tab-pane :label="`修订建议 ${pendingSuggestionCount ? '(' + pendingSuggestionCount + ')' : ''}`" name="suggestions">
            <el-alert v-if="!selectedText" title="先在正文中选中要修改的文字，再打开审阅面板。" type="info" :closable="false" show-icon />
            <div v-else class="suggestion-composer">
              <label>原文</label><blockquote>{{ selectedText }}</blockquote>
              <label>建议改为</label><el-input v-model="suggestionForm.suggestedText" type="textarea" :rows="4" placeholder="输入建议的新内容" />
              <label>修改理由（可选）</label><el-input v-model="suggestionForm.reason" placeholder="例如：表述更准确" maxlength="500" />
              <el-button type="primary" :loading="reviewSubmitting" @click="submitSuggestion">提交修订建议</el-button>
            </div>
            <div class="review-list" v-loading="reviewLoading">
              <article v-for="suggestion in suggestions" :key="suggestion.id" class="review-card suggestion-card">
                <header><div><strong>用户 {{ suggestion.userId }} 的建议</strong><small>{{ formatReviewTime(suggestion.createTime) }}</small></div><el-tag :type="suggestionStatusType(suggestion.status)">{{ suggestionStatusLabel(suggestion.status) }}</el-tag></header>
                <div v-if="suggestion.originalText" class="suggestion-change"><del>{{ suggestion.originalText }}</del><ins>{{ suggestion.suggestedText }}</ins></div><p v-else>{{ suggestion.suggestedText }}</p>
                <small v-if="suggestion.reason">理由：{{ suggestion.reason }}</small>
                <div v-if="suggestion.status === 'pending' && canEditDocument" class="suggestion-actions"><el-button size="small" type="danger" plain @click="decideSuggestion(suggestion, 'rejected')">拒绝</el-button><el-button size="small" type="success" @click="decideSuggestion(suggestion, 'accepted')">接受并写入</el-button></div>
              </article>
              <el-empty v-if="!reviewLoading && !suggestions.length" description="还没有修订建议" :image-size="70" />
            </div>
          </el-tab-pane>
        </el-tabs>
      </el-drawer>
    </div>
  </div>

  <!-- RAG 高级分段管理抽屉 -->
  <el-drawer v-model="ragDrawerVisible" title="🧠 RAG 知识切片管理" size="400px">
    <!-- 重新索引操作区 -->
    <div style="margin-bottom: 20px;">
      <h4 style="margin-bottom: 10px;">重新构建向量索引</h4>
      <div style="display: flex; gap: 10px;">
        <el-select v-model="selectedStrategy" size="small" style="flex: 1;">
          <el-option v-for="(name, key) in strategyMap" :key="key" :label="name" :value="key" />
        </el-select>
        <el-button type="primary" size="small" :loading="indexing" @click="doIndexDocument">执行分段</el-button>
      </div>
    </div>

    <el-divider />

    <!-- 当前分段预览区 -->
    <h4 style="margin-bottom: 10px; display: flex; justify-content: space-between;">
      <span>当前文档分段明细</span>
      <el-tag size="small" type="success">共 {{ segments.length }} 段</el-tag>
    </h4>
    <el-collapse v-if="segments.length > 0" accordion>
      <el-collapse-item v-for="(seg, idx) in segments" :key="idx" :title="`分片 #${idx + 1}`" :name="idx">
        <div style="font-size: 12px; color: #666; white-space: pre-wrap; background: #f5f7fa; padding: 8px; border-radius: 4px;">
          {{ seg.content }}
        </div>
      </el-collapse-item>
    </el-collapse>
    <el-empty v-else description="暂未获取到分段信息" :image-size="60" />
  </el-drawer>

</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fileApi } from '../../api/file'
import { userApi } from '../../api/user'
import { docApi } from '../../api/document'
import { aiApi } from '../../api/ai'
import { agentApi } from '../../api/agent'
import { useAgentProgress } from '../../composables/useAgentProgress.js'
import request from '../../utils/request'
import CollaboratePanel from '../../collaboration/CollaboratePanel.vue'
import { ElMessage, ElMessageBox } from 'element-plus'
const route = useRoute(); const router = useRouter(); const docId = route.params.id


// ===== 状态定义 =====
const isChatMode = computed(() => docId === 'chat-mode')
const chatTitle = ref('新对话'); const isInputFocused = ref(false); const isSaving = ref(false); const saveStatusText = ref('已同步')
const docName = ref(sessionStorage.getItem('currentDocName') || '未命名文档'); const currentUserName = ref('User')
const currentUserId = ref(localStorage.getItem('userId') || '')
const docContent = ref(''); const docLoading = ref(false); const aiSummary = ref('')
const sourceFileId = ref(''); const imagePreviewUrl = ref(''); const imageZoom = ref(1)
const imageExtensions = /\.(?:avif|bmp|gif|jpe?g|png|svg|webp)$/i
const isImageDocument = computed(() => Boolean(sourceFileId.value) && imageExtensions.test(docName.value || ''))
const isPdfDocument = computed(() => Boolean(sourceFileId.value) && /\.pdf$/i.test(docName.value || ''))
const isSourcePreview = computed(() => isImageDocument.value || isPdfDocument.value)
const userMsg = ref(''); const isAiThinking = ref(false); const chatHistory = ref([]); const currentConvId = ref('')
const chatFileInputRef = ref(null); const chatUploadLoading = ref(false)
const chatDocuments = ref([]); const chatDocumentsLoading = ref(false); const selectedChatDocumentIds = ref([])
const showAiBall = ref(false); const ballStyle = reactive({ top: '0px', left: '0px' }); const selectedText = ref('')
const showHistory = ref(false); const versionList = ref([])
const diffFrom = ref(null); const diffTo = ref(null); const versionDiff = ref(null); const diffLoading = ref(false)
const reviewDrawerVisible = ref(false); const reviewTab = ref('comments'); const reviewLoading = ref(false); const reviewSubmitting = ref(false)
const comments = ref([]); const suggestions = ref([]); const commentContent = ref('')
const suggestionForm = reactive({ suggestedText: '', reason: '' })
const pendingSuggestionCount = computed(() => suggestions.value.filter(item => item.status === 'pending').length)
const keywords = ref([]); const keywordsLoading = ref(false); const isRagMode = ref(false); const replaceOnWrite = ref(true)
const { currentStep: editorProgressStep, startTracking: startProgressTracking } = useAgentProgress()
const textStats = ref(null)
const activeParagraphLock = ref('')
const activeParagraphLockedByOther = ref(false)
const paragraphLockOwner = ref('')
const documentRole = ref('viewer')
const canEditDocument = computed(() => ['owner', 'editor'].includes(documentRole.value))
let paragraphLockHeartbeat = null
// 分享协作弹窗
const shareDialogVisible = ref(false)
const shareLink = computed(() => `${window.location.origin}/editor/${docId}`)
const collaboratorQuery = ref('')
const collaboratorRole = ref('editor')
const collaboratorExpiry = ref(168)
const grantLoading = ref(false)
const shareGrantedUsers = ref([])
const isDocOwner = ref(false)

const fetchShareCollaborators = async () => {
  try {
    const res = await docApi.getCollaborators(docId)
    const all = res.data || []
    const currentUser = all.find(u => String(u.userId) === String(currentUserId.value))
    isDocOwner.value = currentUser?.role === 'owner'
    documentRole.value = currentUser?.role || 'viewer'
    const others = all.filter(u => String(u.userId) !== String(currentUserId.value))
    const enriched = await Promise.all(others.map(async (u) => {
      try {
        const userRes = await userApi.getUserInfo(u.userId)
        return { ...u, userName: userRes.data?.username || u.userId }
      } catch { return { ...u, userName: u.userId } }
    }))
    shareGrantedUsers.value = enriched
  } catch { /* 静默 */ }
}

const copyShareLink = async () => {
  try {
    await navigator.clipboard.writeText(shareLink.value)
    ElMessage.success('链接已复制，分享给协作者即可协作编辑')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

const handleGrantCollab = async () => {
  const query = collaboratorQuery.value.trim()
  if (!query) { ElMessage.warning('请输入协作者用户名或用户ID'); return }
  grantLoading.value = true
  try {
    let collaboratorUserId = query
    if (!/^\d+$/.test(query)) {
      const userRes = await userApi.getUserByUsername(query)
      collaboratorUserId = userRes?.data?.id
    }
    if (!collaboratorUserId) { ElMessage.error('没有找到该用户'); return }
    if (String(collaboratorUserId) === String(currentUserId.value)) { ElMessage.warning('不能把文档授权给自己'); return }
    await docApi.grantCollaborator(docId, collaboratorUserId, collaboratorRole.value, collaboratorExpiry.value)
    ElMessage.success('授权成功，协作者打开链接即可协作')
    collaboratorQuery.value = ''
    await fetchShareCollaborators()
  } catch (e) { ElMessage.error(e?.message || '授权失败') }
  finally { grantLoading.value = false }
}

const handleRevokeCollab = async (userId) => {
  try {
    await docApi.revokeCollaborator(docId, userId)
    ElMessage.success('已取消授权')
    await fetchShareCollaborators()
  } catch (e) { ElMessage.error(e?.message || '取消失败') }
}

// RAG 分段管理逻辑
const ragDrawerVisible = ref(false)
const strategyMap = ref({ 'AUTO': '智能自动分段' }) // 默认保底数据
const selectedStrategy = ref('AUTO')
const segments = ref([])
const indexing = ref(false)
const currentModel = ref('deepseek-chat') // 默认 DeepSeek Chat
const modelOptions = ref([])
const clearImagePreview = () => {
  if (imagePreviewUrl.value) {
    URL.revokeObjectURL(imagePreviewUrl.value)
    imagePreviewUrl.value = ''
  }
}

const loadImagePreview = async () => {
  clearImagePreview()
  if (!sourceFileId.value) return
  try {
    const imageBlob = await fileApi.preview(sourceFileId.value)
    imagePreviewUrl.value = URL.createObjectURL(imageBlob)
    imageZoom.value = 1
  } catch (error) {
    console.error('图片预览加载失败', error)
    ElMessage.error('图片预览加载失败，可下载原图查看')
  }
}

const zoomImage = (delta) => {
  imageZoom.value = Math.min(3, Math.max(0.2, Number((imageZoom.value + delta).toFixed(1))))
}

const fitImage = () => {
  imageZoom.value = 1
}

onBeforeUnmount(() => { clearImagePreview(); releaseParagraphLock() })

// 获取可用模型列表
const fetchModels = async () => {
  try {
    const res = await aiApi.getModels()
    console.log('所有模型:', JSON.stringify(res.data, null, 2))
    modelOptions.value = res.data

    // 默认用 DeepSeek Chat
    currentModel.value = 'deepseek-chat'

  } catch {
    modelOptions.value = [
      { code: 'deepseek-chat', name: 'DeepSeek Chat', available: true },
      { code: 'qwen-plus', name: '通义千问 Plus', available: true },
    ]
    currentModel.value = 'deepseek-chat'
  }
}

// ===== 1. 加载文档与初始化 AI =====
const loadDocData = async () => {
  const uid = localStorage.getItem('userId') || '1'
  currentUserId.value = uid
  userApi.getUserInfo(uid).then(r => currentUserName.value = r.data.username || 'aa').catch(()=>{})

  // 从 sessionStorage 恢复对话ID
  const savedConvId = sessionStorage.getItem('doc_conv_' + docId)
  if (savedConvId) {
    currentConvId.value = savedConvId
  }

  // 如果有历史对话ID，加载聊天记录
  if (!isChatMode.value && currentConvId.value) {
    try {
      const historyRes = await agentApi.getConversationHistory(currentConvId.value)
      const messages = historyRes.data || []
      if (messages.length > 0) {
        // 消息格式：["user:你好","assistant:你好！我是..."]
        chatHistory.value = messages.map(msg => {
          const colonIdx = msg.indexOf(':')
          if (colonIdx > 0) {
            const role = msg.substring(0, colonIdx) === 'user' ? 'user' : 'ai'
            const text = msg.substring(colonIdx + 1)
            return { role, text }
          }
          return { role: 'ai', text: msg }
        })
      }
    } catch {
      // 静默失败，不影响主流程
    }
  }

  if (isChatMode.value) {
    const task = sessionStorage.getItem('global_ai_task')
    if (task) {
      chatTitle.value = task.length > 10 ? task.substring(0, 10) + '...' : task
      userMsg.value = task; sessionStorage.removeItem('global_ai_task'); setTimeout(() => handleSend(), 500)
    } else { chatHistory.value.push({ role: 'ai', text: 'Hi！我是 SmartDoc 智能伙伴，今天需要我帮你完成什么工作？' }) }
    return
  }

  chatHistory.value.push({ role: 'ai', text: '你好！我是接入了大语言模型的助手。你可以向我提问，或选中文本进行润色。' })
  docLoading.value = true
  try {
    const docRes = await docApi.getDocDetail(docId)
    const data = docRes.data || docRes
    docName.value = data.title; aiSummary.value = data.summary; docContent.value = data.content || ''
    sourceFileId.value = data.fileId || ''
    if (isSourcePreview.value) await loadImagePreview()

    // 如果文档有内容，并行去请求“文本分析(字数统计)”接口
    if (data.content && data.content.length > 5) {
      // 获取纯文本用于分析 (剥离可能存在的 HTML 标签)
      const plainText = data.content.replace(/<[^>]+>/g, '')

      aiApi.analyzeText(plainText).then(res => {
        // 将后端的 TextAnalyzeVO 数据赋给前端变量
        textStats.value = res.data
      }).catch(e => {
        console.warn('文本分析接口调用失败', e)
        // Mock
        textStats.value = {
          totalCharacters: plainText.length,
          lines: plainText.split('\n').filter(line => line.trim() !== '').length,
          chineseCharacters: Math.floor(plainText.length * 0.8),
          punctuations: Math.floor(plainText.length * 0.15)
        }
      })
    }

    // 并行拉取关键词 (不阻塞 A4 纸显示)
    if (data.content && data.content.length > 5) {
      keywordsLoading.value = true
      aiApi.extractKeywords(data.content).then(res => {
        keywords.value = res.data.keywords.map(k => k.word)
      }).finally(() => { keywordsLoading.value = false })
    }
  } catch (e) {
    docContent.value = `<div style="text-align:center;color:red;padding:100px;">读取文档失败</div>`
  } finally { docLoading.value = false }
}

// 首次加载
onMounted(() => {
  fetchModels()
  if (isChatMode.value) {
    isRagMode.value = true
    fetchChatDocuments()
  }
  loadDocData().then(() => {
    if (!isChatMode.value) fetchShareCollaborators()
    const paperElement = document.querySelector('.paper')
    if (paperElement) {
      const content = paperElement.innerHTML
      // 带上默认模型提取关键词和摘要
      refreshKeywords(content)
      generateDocSummary(content)
    }
  })
})

// 改造关键词提取函数
const refreshKeywords = async (content) => {
  if (!content || content.length < 5) return
  keywordsLoading.value = true
  try {
    // 💡剥离 A4 纸容器中的 HTML 标签，只传纯文本给 AI，防止标签干扰
    const plainText = content.replace(/<[^>]+>/g, '').trim()

    // 调用接口时，把当前选中的模型作为第三个参数（或者根据你后端的 DTO/Query 结构传参）
    const res = await aiApi.extractKeywords(plainText, 5, currentModel.value)

    if (res?.data?.keywords) {
      keywords.value = res.data.keywords.map(k => k.word)
    } else if (Array.isArray(res?.data)) {
      keywords.value = res.data
    }
  } catch (e) {
    console.error('关键词提取失败，使用 Mock 兜底', e)
    keywords.value = ['AI分析', '智能协作', '办公规范']
  } finally {
    keywordsLoading.value = false
  }
}

// 改造自动摘要函数：把 currentModel 传给后端
const generateDocSummary = async (content) => {
  if (!content) return
  try {
    const plainText = content.replace(/<[^>]+>/g, '').trim()
    const res = await aiApi.summarizeText(plainText, 200, currentModel.value)
    if (res?.data?.summary) {
      aiSummary.value = res.data.summary
    }
  } catch (e) {
    console.error('摘要生成失败', e)
  }
}

// 当用户手动切换模型时，立刻重新触发当前文档的摘要和关键词刷新
const handleModelChange = () => {
  const paperElement = document.querySelector('.paper')
  if (paperElement) {
    const content = paperElement.innerHTML
    refreshKeywords(content)
    generateDocSummary(content)
  }
}

// ===== 2. 交互逻辑 =====
const openChatFilePicker = () => chatFileInputRef.value?.click()

const onChatFileSelected = async (event) => {
  const rawFile = event.target.files?.[0]
  if (!rawFile) return

  chatUploadLoading.value = true
  try {
    const fileRes = await fileApi.upload(rawFile)
    const fileId = fileRes?.data?.fileId || fileRes?.data?.id
    if (!fileId) throw new Error('File upload did not return a fileId')
    const docRes = await docApi.createDoc({ title: rawFile.name, fileId, category: 'default' })
    const createdDocument = docRes?.data || docRes || {}
    await fetchChatDocuments()
    const createdId = createdDocument.id || createdDocument.documentId
    if (createdId && !selectedChatDocumentIds.value.includes(createdId)) {
      selectedChatDocumentIds.value.push(createdId)
    }
    chatHistory.value.push({ role: 'user', text: `已上传文档：${rawFile.name}` })
    chatHistory.value.push({ role: 'ai', text: '文档已保存到云端文档库。开启“基于文档回答”后，可针对已完成索引的文档进行问答。' })
    ElMessage.success(`「${rawFile.name}」已上传到云端文档库`)
    isRagMode.value = true
    scrollToBottom()
  } catch (error) {
    console.error('Chat file upload failed', error)
  } finally {
    chatUploadLoading.value = false
    event.target.value = ''
  }
}
const fetchChatDocuments = async () => {
  const userId = currentUserId.value || localStorage.getItem('userId')
  if (!userId) return

  chatDocumentsLoading.value = true
  try {
    const response = await docApi.getUserDocs(userId)
    const documents = response?.data || []
    chatDocuments.value = documents.map(doc => ({
      id: doc.id,
      title: doc.title || '未命名文档'
    }))
  } catch (error) {
    console.error('Failed to load chat documents', error)
  } finally {
    chatDocumentsLoading.value = false
  }
}

const buildSelectedDocumentContext = async () => {
  if (!selectedChatDocumentIds.value.length) return { content: '', ids: [], titles: [], sources: [] }

  const selectedIds = [...selectedChatDocumentIds.value]
  const details = await Promise.all(selectedIds.map(async id => {
    try {
      const response = await docApi.getDocDetail(id)
      return response?.data || response || {}
    } catch {
      return {}
    }
  }))

  const documents = details.map((doc, index) => {
    const title = doc.title || chatDocuments.value.find(item => item.id === selectedIds[index])?.title || `文档 ${index + 1}`
    const content = String(doc.content || '').replace(/<[^>]+>/g, ' ').replace(/\s+/g, ' ').trim()
    return { id: selectedIds[index], title, content }
  })

  return {
    ids: selectedIds,
    titles: documents.map(doc => doc.title),
    sources: documents.map(doc => ({ documentId: doc.id, title: doc.title, segmentTitle: '已选择文档', excerpt: doc.content.slice(0, 180) })),
    content: documents.map(doc => `【${doc.title}】\n${doc.content || '（该文档尚无可读取正文，请结合知识库检索回答。）'}`).join('\n\n').slice(0, 12000)
  }
}
const normalizeSourceDocumentId = result => {
  const explicit = result?.documentId || result?.metadata?.documentId
  if (explicit) return String(explicit)
  return String(result?.id || '').split('_segment_')[0].split(':segment:')[0]
}

const extractRagSources = toolResults => {
  const collected = []
  for (const tool of Array.isArray(toolResults) ? toolResults : []) {
    if (!['rag-search', 'rag-answer'].includes(tool?.toolName)) continue
    const results = tool?.data?.results || tool?.data?.retrieved || []
    for (const result of Array.isArray(results) ? results : []) {
      const documentId = normalizeSourceDocumentId(result)
      if (!documentId) continue
      const known = chatDocuments.value.find(item => String(item.id) === documentId)
      const rawScore = Number(result.rerankScore ?? result.similarity ?? result.score)
      collected.push({ documentId, title: result.documentTitle || result.title || known?.title || `文档 ${documentId}`, segmentTitle: result.segmentTitle || (result.segmentIndex !== undefined ? `第 ${Number(result.segmentIndex) + 1} 个片段` : '知识库片段'), excerpt: String(result.content || '').replace(/\s+/g, ' ').trim().slice(0, 180), score: Number.isFinite(rawScore) && rawScore > 0 ? Math.round(rawScore <= 1 ? rawScore * 100 : rawScore) : null })
    }
  }
  return collected
}

const mergeSources = (...groups) => {
  const unique = new Map()
  groups.flat().filter(Boolean).forEach(source => {
    const key = `${source.documentId}-${source.segmentTitle || ''}`
    if (!unique.has(key)) unique.set(key, source)
  })
  return [...unique.values()].slice(0, 8)
}

const openSourceDocument = source => {
  if (source?.documentId) router.push(`/editor/${source.documentId}`)
}
const handleSend = async () => {
  if (!userMsg.value.trim() || isAiThinking.value) return

  const q = userMsg.value
  const pendingSelectedText = selectedText.value
  const frontendWriteIntent = shouldWriteToCurrentDocument(q, pendingSelectedText)
  const frontendWriteMode = inferFrontendWriteMode(q, pendingSelectedText)
  const frontendDeleteIntent = shouldDeleteCurrentDocument(q)
  const shouldSendCurrentDocumentId = frontendWriteIntent || frontendDeleteIntent

  if (frontendDeleteIntent) {
    if (!docId || isChatMode.value) {
      ElMessage.warning('当前没有可删除的文档')
      return
    }

    try {
      await ElMessageBox.confirm(
        `确定要永久删除「${docName.value || '当前文档'}」吗？删除后不会进入回收站，也无法通过回收站恢复。`,
        '确认永久删除文档',
        {
          confirmButtonText: '确认永久删除',
          cancelButtonText: '取消',
          type: 'warning',
          distinguishCancelAndClose: true,
          confirmButtonClass: 'el-button--danger'
        }
      )
    } catch {
      ElMessage.info('已取消删除')
      return
    }
  }

  chatHistory.value.push({ role: 'user', text: q })
  userMsg.value = ''
  isAiThinking.value = true
  clearSelection()
  scrollToBottom()
  startProgressTracking(null, currentUserId.value || '1')

  if (isChatMode.value && chatTitle.value === '新对话') {
    chatTitle.value = q.substring(0, 10) + '...'
  }

  try {
    const contextText = document.querySelector('.paper')?.innerText || ''
    let finalPrompt = q
    let chatDocumentContext = ''
    let chatDocumentIds = []
    let chatDocumentSources = []

    if (isChatMode.value) {
      const selectedContext = await buildSelectedDocumentContext()
      chatDocumentContext = selectedContext.content
      chatDocumentIds = selectedContext.ids
      chatDocumentSources = selectedContext.sources || []

      if (selectedContext.content) {
        finalPrompt = `【系统指令】请优先且仅基于用户选中的文档回答。若文档正文不足以回答，请明确说明并再检索知识库补充，不得编造。\n\n【已选择文档】${selectedContext.titles.join('、')}\n\n【文档内容】\n${selectedContext.content}\n\n【用户问题】\n${q}`
      } else if (isRagMode.value) {
        finalPrompt = `【系统指令】用户未选择具体文档。请先使用 rag-search 工具检索该用户的全部知识库，再基于检索结果回答；如果没有检索到相关文档，请明确说明，不得要求用户再次提供文档。\n\n【用户问题】\n${q}`
      }
    }

    if (!isChatMode.value && contextText.trim().length > 0) {
      const writePolicy = replaceOnWrite.value
        ? ''
        : '\n【注意】用户关闭了「替换原文」开关，请仅在对话框中回复，不要调用 document-write 工具写回文档。'
      if (isRagMode.value) {
        finalPrompt = `【系统指令：用户开启了云端知识库查询。如果下面提供的局部文档无法回答，请务必调用 rag-search 工具去检索全量知识库】${writePolicy}\n\n【当前正在阅览的文档片段】\n${contextText.substring(0, 2000)}\n\n【用户指令】\n${q}`
      } else {
        finalPrompt = `【系统指令：请主要基于以下正在编辑的文档内容执行用户的指令，例如总结、润色、纠错或生成PPT等】${writePolicy}\n\n【当前文档内容】\n${contextText.substring(0, 3000)}\n\n【用户指令】\n${q}`
      }
    }

    const res = await agentApi.executeTask({
      // 始终使用 finalPrompt（含文档内容），让 AI 知道上下文
      task: finalPrompt,
      model: currentModel.value,
      conversationId: currentConvId.value || undefined,
      context: {
        documentId: shouldSendCurrentDocumentId ? docId : (isChatMode.value ? chatDocumentIds[0] : undefined),
        frontendDocumentWrite: frontendWriteIntent,
        frontendDeleteConfirmed: frontendDeleteIntent,
        documentContent: (isChatMode.value ? chatDocumentContext : contextText).substring(0, 12000),
        selectedDocumentIds: isChatMode.value ? chatDocumentIds : undefined,
        selectedText: pendingSelectedText,
        writeMode: frontendWriteMode,
        ragEnabled: isChatMode.value ? true : isRagMode.value
      }
    })

    const agentResult = res.data
    let answerText = agentResult.answer || agentResult.text || "任务已完成！"

    // 去除回答中的来源标记（来源：xxx）
    answerText = answerText.replace(/[（(]\s*来源.*?[）)]\s*/g, '').trim()

    // 保存后端返回的 ConversationId，后续对话复用同一会话
    if (agentResult.conversationId) {
      currentConvId.value = agentResult.conversationId
      sessionStorage.setItem('doc_conv_' + docId, agentResult.conversationId)
    }

    isAiThinking.value = false

    const writeResult = applyFrontendWriteFromToolResults(agentResult.toolResults)
    const fileResult = parseToolResults(agentResult.toolResults)
    const responseSources = mergeSources(chatDocumentSources, extractRagSources(agentResult.toolResults))

    chatHistory.value.push({
      role: 'ai',
      text: answerText,
      actionResult: writeResult || fileResult,
      toolCalls: agentResult.toolResults || [],
      sources: responseSources
    })

    if (frontendDeleteIntent && hasDeletedCurrentDocument(agentResult.toolResults)) {
      ElMessage.success('文档已永久删除，即将返回工作台')
      setTimeout(() => router.push('/dashboard'), 600)
    }

    scrollToBottom()

  } catch (error) {
    console.error("AI 交互失败", error)
    isAiThinking.value = false

    const lowerQ = q.toLowerCase()
    let mockReply = "网络或模型未响应，这是本地兜底回复：建议在此处增加更多量化指标以提升专业度。"

    if (lowerQ.includes('纠错') || lowerQ.includes('错别字')) {
      mockReply = `【智能纠错】\n发现 1 处拼写错误：\n- ❌ "严尽" 应修改为 ✅ "严禁"。`
    } else if (lowerQ.includes('润色')) {
      mockReply = `【智能润色】建议修改为："为了杜绝安全隐患，严禁私拉乱接电线。"`
    } else if (lowerQ.includes('总结') || lowerQ.includes('摘要')) {
      mockReply = `【文档摘要】本文档强调了办公用电安全，要求离开时关闭电源，严禁私拉电线。`
    }

    chatHistory.value.push({ role: 'ai', text: mockReply })
    scrollToBottom()
  }
}


const shouldWriteToCurrentDocument = (message, selection = '') => {
  if (isChatMode.value) return false
  if (!replaceOnWrite.value) return false
  if (/纠错/.test(message)) return false // 纠错只标注不替换
  const text = message || ''
  // 有选中文本且指令包含编辑关键词 → 写入
  if (selection && /润色|纠错|改写|优化|替换|修改/.test(text)) return true
  // 即使没选中文本，只要 replaceOnWrite 勾选且指令包含编辑关键词 → 也写入
  if (/润色|改写|优化|替换|修改|重写|重命名/.test(text)) return true
  return /写入|写进|写到|输出到文档|插入|追加|添加到|放到文档|生成到文档|生成在文档|保存到文档|应用到文档|更新文档|替换选中|替换这段|改到文档|直接生成在文档/.test(text)
}

const shouldDeleteCurrentDocument = (message) => {
  if (isChatMode.value) return false
  const text = (message || '').trim().toLowerCase()
  if (!text) return false

  const hasDeleteIntent = /删除|删掉|移除|清除|delete|remove/.test(text)
  const hasDocumentTarget = /当前文档|这个文档|该文档|本文档|文档|文件|current document|this document|current file/.test(text)
  const contentOnlyTarget = /选中|这段|段落|内容|文字|selection|paragraph|text/.test(text)

  return hasDeleteIntent && (hasDocumentTarget || (!contentOnlyTarget && text.length <= 16))
}

const hasDeletedCurrentDocument = (toolResults) => {
  return Array.isArray(toolResults) && toolResults.some(tr => {
    const data = tr?.data || {}
    return tr?.toolName === 'file-delete' && tr.status !== 'error' && data.status === 'deleted'
  })
}

const inferFrontendWriteMode = (message, selection = '') => {
  const text = message || ''
  if (selection) return 'replace-selection'
  if (/覆盖|替换全文|重写全文|改写全文|更新全文|修改全文/.test(text)) return 'overwrite'
  if (/插入|追加|添加|续写|写入|写进|写到|输出到文档|生成到文档|生成在文档|放到文档|保存到文档|直接生成在文档/.test(text)) return 'append'
  return 'append'
}

const applyFrontendWriteFromToolResults = (toolResults) => {
  if (!toolResults || !Array.isArray(toolResults) || isChatMode.value) return null
  if (!replaceOnWrite.value) return null

  const target = toolResults.find(tr => {
    const d = tr?.data || {}
    return tr.status !== 'error' && (d.requiresFrontendWrite || d.result === 'frontend-document-write')
  })
  if (!target) return null

  const data = target.data || {}
  const applied = applyFrontendDocumentWrite(data)
  if (!applied) return null

  return {
    type: 'document-write',
    changeLog: data.changeLog || 'AI 内容已应用，尚未保存',
    writeMode: data.writeMode || 'append',
    insertAfterText: data.insertAfterText || '',
    contentLength: data.contentLength || 0
  }
}

const applyFrontendDocumentWrite = (data) => {
  const paperElement = document.querySelector('.paper')
  if (!paperElement || !data?.content) return false

  const contentHtml = data.contentFormat === 'html'
      ? data.content
      : textToEditorHtml(data.content)
  const mode = data.writeMode || 'append'
  const insertAfterText = data.insertAfterText || ''

  if (mode === 'overwrite') {
    paperElement.innerHTML = contentHtml
  } else if (mode === 'replace-selection') {
    const replaced = replaceTextInElement(paperElement, data.selectionText || '', contentHtml)
    if (!replaced) appendHtmlToPaper(paperElement, contentHtml)
  } else if (insertAfterText && insertHtmlAfterAnchor(paperElement, insertAfterText, contentHtml)) {
    // 已按 Agent 返回的文档锚点完成局部插入。
  } else {
    appendHtmlToPaper(paperElement, contentHtml)
  }

  docContent.value = paperElement.innerHTML
  paperElement.dispatchEvent(new Event('input', { bubbles: true }))
  saveStatusText.value = 'AI 已写入，修改未保存'
  refreshKeywords(paperElement.innerHTML)
  generateDocSummary(paperElement.innerHTML)
  ElMessage.success('AI 内容已写入当前文档，保存后才会同步到云端')
  return true
}

const appendHtmlToPaper = (paperElement, html) => {
  const separator = paperElement.innerText.trim() ? '<p><br></p>' : ''
  paperElement.insertAdjacentHTML('beforeend', `${separator}${html}`)
}

const insertHtmlAfterAnchor = (root, anchorText, html) => {
  const normalizedAnchor = normalizeTextForSearch(anchorText)
  if (!normalizedAnchor) return false

  const blocks = Array.from(root.querySelectorAll('p, div, li, h1, h2, h3, h4, h5, h6'))
  for (const block of blocks) {
    const blockText = normalizeTextForSearch(block.innerText || block.textContent || '')
    if (blockText && (blockText.includes(normalizedAnchor) || (blockText.length >= 8 && normalizedAnchor.includes(blockText)))) {
      block.insertAdjacentHTML('afterend', html)
      return true
    }
  }

  const walker = document.createTreeWalker(root, window.NodeFilter.SHOW_TEXT)
  let node
  while ((node = walker.nextNode())) {
    const nodeText = normalizeTextForSearch(node.nodeValue || '')
    if (!nodeText || (!nodeText.includes(normalizedAnchor) && !(nodeText.length >= 8 && normalizedAnchor.includes(nodeText)))) continue

    const container = findBlockContainer(node, root)
    if (container) {
      container.insertAdjacentHTML('afterend', html)
      return true
    }

    const temp = document.createElement('div')
    temp.innerHTML = html
    const fragment = document.createDocumentFragment()
    while (temp.firstChild) fragment.appendChild(temp.firstChild)
    const range = document.createRange()
    range.setStartAfter(node)
    range.insertNode(fragment)
    return true
  }
  return false
}

const findBlockContainer = (node, root) => {
  const blockTags = new Set(['P', 'DIV', 'LI', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6'])
  let current = node.parentElement
  while (current && current !== root) {
    if (blockTags.has(current.tagName)) return current
    current = current.parentElement
  }
  return null
}

const replaceTextInElement = (root, targetText, replacementHtml) => {
  if (!targetText) return false
  const normalizedTarget = normalizeTextForSearch(targetText)
  if (!normalizedTarget) return false
  const walker = document.createTreeWalker(root, window.NodeFilter.SHOW_TEXT)
  let node
  while ((node = walker.nextNode())) {
    const exactIndex = node.nodeValue.indexOf(targetText)
    const index = exactIndex >= 0 ? exactIndex : (normalizeTextForSearch(node.nodeValue).includes(normalizedTarget) ? 0 : -1)
    if (index < 0) continue

    const replaceLength = exactIndex >= 0 ? targetText.length : node.nodeValue.length
    const before = node.nodeValue.slice(0, index)
    const after = node.nodeValue.slice(index + replaceLength)
    const fragment = document.createDocumentFragment()
    if (before) fragment.appendChild(document.createTextNode(before))

    const temp = document.createElement('div')
    temp.innerHTML = replacementHtml
    while (temp.firstChild) fragment.appendChild(temp.firstChild)

    if (after) fragment.appendChild(document.createTextNode(after))
    node.parentNode.replaceChild(fragment, node)
    return true
  }
  return false
}

const textToEditorHtml = (text) => {
  return normalizeAiDocumentText(text)
      .split(/\n{2,}/)
      .map(block => `<p>${escapeHtml(block).replace(/\n/g, '<br>')}</p>`)
      .join('')
}

const normalizeAiDocumentText = (text) => {
  return String(text || '')
      .replace(/\r\n/g, '\n')
      .replace(/\r/g, '\n')
      .replace(/^```[a-zA-Z]*\s*/g, '')
      .replace(/\s*```$/g, '')
      .replace(/^\s{0,3}#{1,6}\s*/gm, '')
      .replace(/^\s*>\s?/gm, '')
      .replace(/^\s*[-*+]\s+/gm, '')
      .replace(/\*\*([^*\n]+)\*\*/g, '$1')
      .replace(/__([^_\n]+)__/g, '$1')
      .replace(/`([^`\n]+)`/g, '$1')
      .replace(/\$([^$\n]{1,200})\$/g, '$1')
      .replace(/\$/g, '')
      .replace(/\\mathbb\{C\}/g, 'C')
      .replace(/\\mathbb\{Q\}/g, 'Q')
      .replace(/\\mathbb\{R\}/g, 'R')
      .replace(/\\mathbb\{Z\}/g, 'Z')
      .replace(/\\mathbb\{N\}/g, 'N')
      .replace(/\\in/g, '∈')
      .replace(/\\sum/g, '∑')
      .replace(/\\cdot/g, '·')
      .replace(/\\times/g, '×')
      .replace(/\\leq/g, '≤')
      .replace(/\\geq/g, '≥')
      .replace(/\\neq/g, '≠')
      .replace(/\\mid/g, '|')
      .replace(/\\[()[\]]/g, '')
      .replace(/[ \t]+$/gm, '')
      .replace(/\n{3,}/g, '\n\n')
      .trim()
}

const normalizeTextForSearch = (text) => {
  return normalizeAiDocumentText(text)
      .replace(/[：:，,。；;、（）()【】[\]\s]/g, '')
      .toLowerCase()
}

const escapeHtml = (value) => {
  return String(value || '')
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;')
}

// 解析 toolResults，提取文件信息
const parseToolResults = (toolResults) => {
  if (!toolResults || !Array.isArray(toolResults)) return null

  for (const tr of toolResults) {
    if (tr.status === 'error') continue
    const d = tr.data || {}
    if (d.requiresFrontendWrite || d.result === 'frontend-document-write') continue

    // PPT
    if (tr.toolName?.includes('ppt') || d.htmlContent || d.filePath?.endsWith('.html')) {
      return {
        type: 'ppt',
        title: d.title || 'PPT演示文稿',
        htmlContent: d.htmlContent || null,
        filePath: d.filePath || null,
        fileName: d.fileName || null,
        url: d.url || d.downloadUrl || null
      }
    }

    // Word
    if (d.fileName?.endsWith('.docx') || d.objectName?.endsWith('.docx')) {
      return {
        type: 'doc',
        fileName: d.fileName || d.objectName,
        objectName: d.objectName || d.fileName,
        bucketName: 'doc-ai',
        url: d.url || d.downloadUrl || null
      }
    }

    // 通用文件
    if (d.fileName || d.url) {
      return {
        type: 'file',
        fileName: d.fileName,
        url: d.url || d.downloadUrl
      }
    }
  }

  return null
}

const typeEffect = (text) => {
  isAiThinking.value = false; const aiMsg = reactive({ role: 'ai', text: '' }); chatHistory.value.push(aiMsg)
  let i = 0; const t = setInterval(() => { if(i < text.length) { aiMsg.text += text.charAt(i); i++; scrollToBottom() } else clearInterval(t) }, 30)
}

// ===== 3. 手动保存与恢复逻辑 =====
const handleManualSave = async () => {
  if (isChatMode.value) return
  if (!canEditDocument.value) { ElMessage.warning('你当前是只读协作者，不能修改文档'); return }
  isSaving.value = true; saveStatusText.value = '正在保存...'
  try {
    const content = document.querySelector('.paper').innerHTML
    await docApi.updateDoc(docId, { title: docName.value, content: content, category: 'default' })
    docContent.value = content
    window.dispatchEvent(new CustomEvent('smartdoc-document-saved', { detail: { documentId: docId } }))
    saveStatusText.value = '已同步'; ElMessage.success('保存成功')
  } catch (e) { saveStatusText.value = '保存失败'; ElMessage.error('同步失败') }
  finally { isSaving.value = false }
}

const handleRestore = async (verNum) => {
  ElMessageBox.confirm(`确定要将文档恢复到 V${verNum} 吗？当前未保存的修改将丢失。`, '版本回溯').then(async () => {
    docLoading.value = true
    try {
      await docApi.restoreVersion(docId, verNum)
      ElMessage.success('版本已回溯')

      // 恢复成功后重新拉取数据
      await loadDocData()

      showHistory.value = false
    } catch (e) {
      ElMessage.error('回溯失败')
    } finally {
      docLoading.value = false
    }
  }).catch(() => {})
}

// ===== 4. 文件导出与选中逻辑 =====
const handleDownload = async () => {
  isSaving.value = true
  saveStatusText.value = '正在导出最新版...'
  try {
    if (isSourcePreview.value && sourceFileId.value) {
      const originalImage = await fileApi.download(sourceFileId.value)
      downloadBlob(originalImage, docName.value || 'image')
      ElMessage.success('原文件已开始下载')
      saveStatusText.value = '已同步'
      return
    }

    // 获取 A4 纸里的纯文本（innerText 会自动保留换行和空格）
    const latestText = document.querySelector('.paper').innerText

    // 包装成 Word 认得的格式，并强制要求保留空白符
    const wordHtml = `
      <html xmlns:o='urn:schemas-microsoft-com:office:office' xmlns:w='urn:schemas-microsoft-com:office:office:word' xmlns='http://www.w3.org/TR/REC-html40'>
      <head><meta charset='utf-8'></head>
      <body style="font-family: sans-serif;">
        <pre style="white-space: pre-wrap; word-break: break-all;">${latestText}</pre>
      </body>
      </html>`

    const blob = new Blob([wordHtml], { type: 'application/msword' })
    const url = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.setAttribute('download', `[最新]_${docName.value}.doc`)
    document.body.appendChild(link); link.click(); document.body.removeChild(link)
    window.URL.revokeObjectURL(url)

    ElMessage.success('导出成功')
    saveStatusText.value = '已同步'
  } catch (e) { ElMessage.error('下载失败') }
  finally { isSaving.value = false }
}

const handleTextSelection = () => {
  const s = window.getSelection(); const t = s.toString().trim()
  if (t && s.rangeCount > 0) {
    selectedText.value = t; const r = s.getRangeAt(0).getBoundingClientRect()
    ballStyle.top = `${r.top - 55}px`; ballStyle.left = `${r.left + r.width / 2}px`; showAiBall.value = true
  } else { showAiBall.value = false }
}

const downloadBlob = (blob, fileName) => {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

const downloadSkillFile = async (params, fileName = 'download') => {
  const blob = await request.get('/skills/file/download', {
    params,
    responseType: 'blob'
  })
  downloadBlob(blob, fileName)
}

const handleDownloadAgentFile = (actionResult) => {
  if (!actionResult) return

  // 优先用后端直接给的 URL
  if (actionResult.url) {
    window.open(actionResult.url, '_blank')
    return
  }

  // PPT 类型
  if (actionResult.type === 'ppt') {
    if (actionResult.htmlContent) {
      // 在这里给它包上一层完整的 HTML 模板
      const fullHtml = `
<!DOCTYPE html>
<html>
<head>
    <meta charset="utf-8">
    <title>${actionResult.title || 'PPT'}</title>
    <style>
        /* 强制给 PPT 内容加点样式，你可以根据需要调整 */
        body { font-family: 'Microsoft YaHei', sans-serif; background: #f0f0f0; padding: 50px; }
        .slide { background: white; padding: 40px; margin-bottom: 20px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #3370ff; }
        p { line-height: 1.6; color: #333; }
        /* 清洗掉 Markdown 留下的多余符号 */
        pre { background: #f8f8f8; padding: 10px; border-radius: 4px; }
    </style>
</head>
<body>
    ${actionResult.htmlContent}
</body>
</html>`;
      const blob = new Blob([fullHtml], { type: 'text/html' })
      downloadBlob(blob, `${actionResult.title || 'PPT'}.html`)
    } else if (actionResult.fileName) {
      downloadSkillFile({ objectName: actionResult.fileName }, actionResult.fileName)
          .catch(() => ElMessage.error('下载失败'))
    }
    return
  }

  // Word 类型
  if (actionResult.type === 'doc') {
    const objectName = actionResult.objectName || actionResult.fileName
    if (objectName) {
      downloadSkillFile({ bucket: 'doc-ai', objectName }, objectName)
          .catch(() => ElMessage.error('下载失败'))
    } else {
      ElMessage.warning('无法获取文件名')
    }
    return
  }

  // 通用文件
  if (actionResult.fileName) {
    ElMessage.success(`正在下载：${actionResult.fileName}`)
    downloadSkillFile({ objectName: actionResult.fileName }, actionResult.fileName)
        .catch(() => ElMessage.error('下载失败'))
  } else {
    ElMessage.info('该任务没有返回可下载的文件')
  }
}

const openRagSettings = async () => {
  ragDrawerVisible.value = true
  // 拉取后端支持的策略列表 (如果后端接口没好，就用我们自己的假数据)
  try {
    const res = await aiApi.getSegmentStrategies()
    if (res.data) strategyMap.value = res.data

    // 拉取当前文档的分段列表
    const segRes = await aiApi.getDocumentSegments(docId)
    if (segRes.data) segments.value = segRes.data
  } catch (e) {
    console.warn('获取分段策略失败，使用本地配置')
    strategyMap.value = {
      'FIXED_LENGTH': '固定长度 (1000字符/10%重叠)',
      'CHAPTER': '智能按章节分段',
      'SEMANTIC': '语义分割 (句子级别)'
    }
  }
}

// 执行高级索引
const doIndexDocument = async () => {
  // 获取 A4 纸里最新的纯文本内容
  const content = document.querySelector('.paper').innerText || '无内容'
  if (content.length < 10) return ElMessage.warning('文档内容过少，无法分段')

  indexing.value = true
  try {
    // 提交给后端的 /index/segment 接口
    await aiApi.indexWithSegment(docId, content, selectedStrategy.value)
    ElMessage.success('向量化重构完成！')

    // 重新拉取展示分段结果
    const segRes = await aiApi.getDocumentSegments(docId)
    segments.value = segRes.data || []
  } catch (e) {
    ElMessage.error('分段策略执行失败')
    // Mock 演示效果
    segments.value = [
      { content: content.substring(0, 500) + '...' },
      { content: content.substring(500, 1000) + '...' }
    ]
  } finally {
    indexing.value = false
  }
}


const askAiWithContext = (type) => {
  userMsg.value = type === '纠错' ? `纠错并分析原因："${selectedText.value}"` : `润色使表达更专业："${selectedText.value}"`
  showAiBall.value = false;
  handleSend()
}
const handleScroll = () => { if(showAiBall.value) showAiBall.value = false }
const clearSelection = () => { selectedText.value = ''; showAiBall.value = false }
const paragraphIdFromSelection = () => {
  const paper = document.querySelector('.paper')
  const selection = window.getSelection()
  if (!paper || !selection?.rangeCount || !paper.contains(selection.anchorNode)) return 'p-0'
  let node = selection.anchorNode.nodeType === Node.ELEMENT_NODE ? selection.anchorNode : selection.anchorNode.parentElement
  while (node?.parentElement && node.parentElement !== paper) node = node.parentElement
  const index = Math.max(0, [...paper.children].indexOf(node))
  return 'p-' + index
}
const releaseParagraphLock = async () => {
  if (paragraphLockHeartbeat) { clearInterval(paragraphLockHeartbeat); paragraphLockHeartbeat = null }
  const paragraphId = activeParagraphLock.value
  activeParagraphLock.value = ''
  activeParagraphLockedByOther.value = false
  paragraphLockOwner.value = ''
  if (!paragraphId || isChatMode.value || isSourcePreview.value) return
  try { await docApi.releaseParagraphLock(docId, paragraphId) } catch { /* lock will expire */ }
}
const acquireParagraphLock = async () => {
  if (isChatMode.value || isSourcePreview.value || !docId || !canEditDocument.value) return
  const paragraphId = paragraphIdFromSelection()
  if (paragraphId === activeParagraphLock.value && !activeParagraphLockedByOther.value) return
  const previous = activeParagraphLock.value
  if (previous && previous !== paragraphId) {
    try { await docApi.releaseParagraphLock(docId, previous) } catch { /* lock will expire */ }
  }
  try {
    const res = await docApi.acquireParagraphLock(docId, paragraphId)
    const state = res.data || {}
    activeParagraphLock.value = paragraphId
    activeParagraphLockedByOther.value = !state.mine
    paragraphLockOwner.value = state.ownerUserId || ''
    if (!state.mine) {
      ElMessage.warning(`该段落正由用户 ${paragraphLockOwner.value || '其他协作者'} 编辑，请稍后再试`)
    } else {
      if (paragraphLockHeartbeat) clearInterval(paragraphLockHeartbeat)
      paragraphLockHeartbeat = setInterval(() => {
        if (activeParagraphLock.value === paragraphId) docApi.acquireParagraphLock(docId, paragraphId).catch(() => {})
      }, 40000)
    }
  } catch {
    activeParagraphLock.value = ''
    activeParagraphLockedByOther.value = false
  }
}
const handleInput = () => {
  if (!canEditDocument.value) return
  if (activeParagraphLockedByOther.value) {
    const paper = document.querySelector('.paper')
    if (paper) paper.innerHTML = docContent.value
    ElMessage.warning('当前段落已被协作人锁定，内容未保存')
    return
  }
  const paper = document.querySelector('.paper')
  if (paper) docContent.value = paper.innerHTML
  saveStatusText.value = '修改未保存'
}
const openHistoryDrawer = async () => {
  const res = await docApi.getVersions(docId)
  versionList.value = res.data || []
  if (versionList.value.length > 1) {
    const ordered = [...versionList.value].sort((a, b) => a.versionNumber - b.versionNumber)
    diffFrom.value = ordered.at(-2).versionNumber
    diffTo.value = ordered.at(-1).versionNumber
  }
  versionDiff.value = null
  showHistory.value = true
}
const loadVersionDiff = async () => {
  if (diffFrom.value == null || diffTo.value == null) return ElMessage.warning('请选择两个版本')
  if (diffFrom.value === diffTo.value) return ElMessage.warning('请选择不同版本')
  diffLoading.value = true
  try { const res = await docApi.getVersionDiff(docId, diffFrom.value, diffTo.value); versionDiff.value = res.data }
  catch (e) { ElMessage.error(e?.message || '版本比较失败') }
  finally { diffLoading.value = false }
}
const formatReviewTime = value => value ? new Date(value).toLocaleString() : ''
const suggestionStatusLabel = status => ({ pending: '待处理', accepted: '已接受', rejected: '已拒绝' }[status] || status)
const suggestionStatusType = status => ({ pending: 'warning', accepted: 'success', rejected: 'info' }[status] || 'info')
const loadReviews = async () => {
  reviewLoading.value = true
  try {
    const [commentRes, suggestionRes] = await Promise.all([docApi.getComments(docId), docApi.getSuggestions(docId)])
    comments.value = commentRes.data || []
    suggestions.value = suggestionRes.data || []
  } catch (e) { ElMessage.error(e?.message || '加载审阅内容失败') }
  finally { reviewLoading.value = false }
}
const openReviewDrawer = async () => {
  suggestionForm.suggestedText = selectedText.value
  suggestionForm.reason = ''
  reviewDrawerVisible.value = true
  await loadReviews()
}
const submitComment = async () => {
  const content = commentContent.value.trim()
  if (!content) return ElMessage.warning('请输入评论内容')
  reviewSubmitting.value = true
  try { await docApi.createComment(docId, { content }); commentContent.value = ''; await loadReviews(); ElMessage.success('评论已发表') }
  finally { reviewSubmitting.value = false }
}
const removeComment = async comment => {
  try { await ElMessageBox.confirm('确定删除这条评论吗？', '删除评论', { type: 'warning' }); await docApi.deleteComment(docId, comment.id); await loadReviews() } catch {}
}
const submitSuggestion = async () => {
  if (!selectedText.value) return ElMessage.warning('请先选中原文')
  if (!suggestionForm.suggestedText.trim()) return ElMessage.warning('请输入建议内容')
  reviewSubmitting.value = true
  try {
    await docApi.createSuggestion(docId, { originalText: selectedText.value, suggestedText: suggestionForm.suggestedText.trim(), reason: suggestionForm.reason.trim() })
    suggestionForm.suggestedText = ''; suggestionForm.reason = ''; await loadReviews(); ElMessage.success('修订建议已提交')
  } finally { reviewSubmitting.value = false }
}
const decideSuggestion = async (suggestion, decision) => {
  try {
    if (decision === 'accepted') await ElMessageBox.confirm('接受后将立即写入正文并生成历史版本，是否继续？', '接受建议', { type: 'warning' })
    await docApi.decideSuggestion(docId, suggestion.id, { decision })
    await Promise.all([loadReviews(), loadDocData()])
    ElMessage.success(decision === 'accepted' ? '建议已接受并写入正文' : '建议已拒绝')
  } catch (e) { if (e !== 'cancel' && e !== 'close') ElMessage.error(e?.message || '处理建议失败') }
}
const startNewChat = () => { chatHistory.value = []; chatTitle.value = '新对话' }
const scrollToBottom = () => { nextTick(() => {
  const c = isChatMode.value ? document.querySelector('.chat-scroll-area') : document.querySelector('.chat-area'); if(c) c.scrollTop = c.scrollHeight
}) }
</script>


<style scoped>
/* ==================== 全局格式保护 ==================== */
.message-sources { margin-top: 14px; display: flex; flex-direction: column; gap: 7px; }
.sources-heading { font-size: 11px; font-weight: 700; color: #80758e; letter-spacing: .5px; }
.source-card { width: 100%; display: flex; gap: 9px; padding: 9px 10px; border: 1px solid #e8e1eb; border-radius: 10px; background: #faf8fb; color: inherit; text-align: left; cursor: pointer; transition: .18s ease; }
.source-card:hover { border-color: #aca0ce; background: #f4f0f7; transform: translateY(-1px); }
.source-card > span { flex: 0 0 21px; height: 21px; display: grid; place-items: center; border-radius: 7px; background: #8775a1; color: #fff; font-size: 10px; }
.source-card > div { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.source-card strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; color: #584f61; }
.source-card small { font-size: 10px; color: #94899d; }
.source-card p { margin: 3px 0 0; font-size: 11px; line-height: 1.55; color: #746b7b; }
.message-sources.compact .source-card { padding: 7px 8px; }
.preserve-format {
  white-space: pre-wrap !important;
  word-break: break-word;
  text-align: left;
}

/* ==================== 模式 A：飞书全局对话样式 ==================== */
.feishu-chat-layout {
  height: 100vh;
  width: 100vw;
  display: flex;
  background: #fff;
  overflow: hidden; /* 防止出现外层滚动条 */
}

/* 聊天侧边栏 (固定宽度，上下分布) */
.chat-sidebar {
  width: 260px;
  flex-shrink: 0; /* 绝对不被压缩 */
  background: #f9fafb;
  border-right: 1px solid #ebeef5;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
}
.sidebar-top { padding: 24px 20px; }
.brand-back { display: flex; align-items: center; gap: 8px; color: #5c5f66; font-size: 14px; cursor: pointer; margin-bottom: 30px; font-weight: 500;}
.brand-back:hover { color: #3370ff; }
.new-chat-btn { width: 100%; border-radius: 8px; margin-bottom: 30px; font-weight: 600; height: 36px; }
.group-title { font-size: 12px; color: #8f959e; margin-bottom: 12px; padding-left: 4px; }
.history-item { padding: 12px 16px; border-radius: 8px; display: flex; align-items: center; gap: 10px; font-size: 14px; background: #e1eaff; color: #3370ff; cursor: pointer; font-weight: 500; }
.history-item .text { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

/* 侧边栏底部用户 */
.sidebar-bottom { padding: 20px; border-top: 1px solid #ebeef5; }
.user-profile { display: flex; align-items: center; gap: 12px; }
.user-profile .username { font-size: 14px; font-weight: 500; color: #1f2329; }

/* 主聊天区 (占据剩余空间) */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  background: #fff;
  min-width: 0; /* 防止内容过长撑破 Flex 布局 */
}
.chat-header { height: 64px; display: flex; justify-content: center; border-bottom: 1px solid #f0f0f0; flex-shrink: 0;}
.header-inner { width: 100%; max-width: 900px; display: flex; justify-content: space-between; align-items: center; padding: 0 24px; }
.chat-header .title { font-size: 16px; font-weight: 600; color: #1f2329; }

/* 聊天滚动区 */
.chat-scroll-area {
  flex: 1;
  overflow-y: auto;
  padding: 40px 0 160px 0; /* 底部留出巨大空间给输入框 */
  display: flex;
  justify-content: center;
}
.chat-content-container { width: 100%; max-width: 850px; padding: 0 24px; display: flex; flex-direction: column; }

.message-row { display: flex; gap: 16px; margin-bottom: 32px; }
.message-row.user { flex-direction: row-reverse; }
.message-content-col { max-width: 80%; }

.user-bubble { background: #3370ff; color: #fff; padding: 14px 20px; border-radius: 16px 4px 16px 16px; font-size: 15px; line-height: 1.6; box-shadow: 0 4px 12px rgba(51, 112, 255, 0.2); }
.ai-structured-card { background: #fff; border: 1px solid #dee0e3; border-radius: 4px 16px 16px 16px; padding: 24px; box-shadow: 0 6px 18px rgba(0,0,0,0.04); transition: all 0.3s; }
.ai-structured-card:hover { border-color: #3370ff; box-shadow: 0 8px 24px rgba(51, 112, 255, 0.08); }
.ai-card-content { font-size: 15px; color: #1f2329; line-height: 1.8; font-family: inherit; margin: 0; }
.ai-structured-card.thinking { color: #8f959e; font-style: italic; display: flex; align-items: center; gap: 10px; padding: 16px 24px; }

/* 底部居中悬浮输入框 */
.chat-input-container {
  position: absolute;
  bottom: 0; left: 0; width: 100%;
  display: flex; flex-direction: column; align-items: center;
  padding: 24px 0 32px 0;
  background: linear-gradient(to top, #fff 80%, rgba(255,255,255,0));
}
.input-wrapper-inner {
  width: 100%; max-width: 800px; height: 60px; background: #fff; border-radius: 30px;
  box-shadow: 0 6px 24px rgba(31,35,41,0.08); display: flex; align-items: center; padding: 0 10px 0 24px;
  border: 1px solid #dee0e3; transition: all 0.3s;
}
.input-wrapper-inner.is-focused { border-color: #3370ff; box-shadow: 0 8px 32px rgba(51,112,255,0.15); }
.chat-input { flex: 1; border: none; outline: none; font-size: 16px; color: #1f2329; background: transparent; }
.input-right { display: flex; align-items: center; gap: 15px; }
.send-btn-circle {
  width: 44px; height: 44px; border-radius: 50%; background: #f2f3f5; color: #fff;
  display: flex; justify-content: center; align-items: center; cursor: pointer; transition: 0.3s;
}
.send-btn-circle.active { background: #3370ff; }
.ai-hint { font-size: 12px; color: #bbbfc4; margin-top: 16px; }


/* ==================== 模式 B：文档阅读模式样式 (保持不变) ==================== */
.editor-page { height: 100vh; display: flex; flex-direction: column; background: #f5f6f7; }
.paper.read-only-paper { background: #fafafa; cursor: default; }
.toolbar { height: 56px; background: #fff; border-bottom: 1px solid #dee0e3; display: flex; align-items: center; padding: 0 24px; justify-content: space-between; flex-shrink: 0;}
.workspace { flex: 1; display: flex; overflow: hidden; position: relative; }
.editor-main { flex: 1; overflow-y: auto; display: flex; justify-content: center; padding: 50px 0; background: #f0f2f5; position: relative; }
.paper-container { position: relative; }
.image-paper-container { width: min(940px, 100%); }
.image-viewer { width: 100%; min-height: 620px; overflow: hidden; border: 1px solid rgba(232, 224, 232, .9); border-radius: 16px; background: #fffdf9; box-shadow: 0 18px 44px rgba(71, 61, 80, .11); }
.image-viewer-toolbar { min-height: 62px; padding: 0 20px; border-bottom: 1px solid #e9e1e9; display: flex; align-items: center; justify-content: space-between; gap: 16px; color: var(--editor-ink); font-size: 14px; font-weight: 700; }
.image-viewer-toolbar > span { display: inline-flex; align-items: center; gap: 8px; }
.image-viewer-toolbar .el-icon { color: var(--editor-lavender-deep); }
.image-viewer-actions { display: inline-flex; align-items: center; gap: 8px; }
.image-zoom-value { min-width: 46px; text-align: center; color: var(--editor-muted); font-size: 12px; font-weight: 600; }
.image-canvas { min-height: 558px; padding: 36px; display: grid; place-items: center; overflow: auto; background-color: #f7f5f2; background-image: linear-gradient(45deg, #eee9e7 25%, transparent 25%), linear-gradient(-45deg, #eee9e7 25%, transparent 25%), linear-gradient(45deg, transparent 75%, #eee9e7 75%), linear-gradient(-45deg, transparent 75%, #eee9e7 75%); background-size: 24px 24px; background-position: 0 0, 0 12px, 12px -12px, -12px 0; }
.pdf-frame { width: min(100%, 900px); height: 820px; border: 0; border-radius: 8px; background: #fff; box-shadow: 0 14px 30px rgba(63, 52, 71, .18); }
.image-canvas img { max-width: min(100%, 820px); max-height: 920px; object-fit: contain; border-radius: 8px; box-shadow: 0 14px 30px rgba(63, 52, 71, .18); transform-origin: center; transition: transform .18s ease; }
@media (max-width: 680px) { .image-viewer { min-height: 460px; } .image-viewer-toolbar { padding: 10px 12px; align-items: flex-start; flex-direction: column; } .image-canvas { min-height: 390px; padding: 20px; } }
.paper { width: 780px; min-height: 1100px; background: #fff; padding: 80px 100px; box-shadow: 0 4px 16px rgba(0,0,0,0.06); outline: none; line-height: 1.8; font-size: 16px; color: #1f2329; }

.ai-sidebar { width: 360px; background: #fff; border-left: 1px solid #dee0e3; display: flex; flex-direction: column; height: 100%; flex-shrink: 0;}
.ai-sidebar-top { flex-shrink: 0; }
.ai-header {
  padding: 12px 20px;
  font-weight: 600;
  border-bottom: 1px solid #f0f0f0;
  color: #1f2329;
  display: flex; /* 变成 flex 布局 */
  align-items: center;
  justify-content: space-between;
}
.chat-area { flex-grow: 1; padding: 20px; overflow-y: auto; background: #fafbfc; display: flex; flex-direction: column; }
.chat-bubble { max-width: 90%; padding: 12px 16px; border-radius: 14px; margin-bottom: 16px; font-size: 14px; box-shadow: 0 2px 8px rgba(0,0,0,0.04); }
.chat-bubble.ai { background: #fff; border: 1px solid #dee0e3; align-self: flex-start; border-bottom-left-radius: 2px;}
.chat-bubble.user { background: #3370ff; color: #fff; align-self: flex-end; margin-left: auto; border-bottom-right-radius: 2px;}
.summary-box { background: #fff; padding: 18px; border-radius: 12px; border: 1px solid #e1eaff; border-left: 5px solid #3370ff; margin: 15px; box-shadow: 0 4px 12px rgba(51,112,255,0.06); }
.input-area { flex-shrink: 0; padding: 20px; border-top: 1px solid #f0f0f0; background: #fff; }
.send-btn { width: 100%; margin-top: 12px; height: 42px; font-weight: 600; letter-spacing: 1px;}

.ai-float-ball { position: fixed; z-index: 9999; transform: translateX(-50%); cursor: pointer; filter: drop-shadow(0 4px 12px rgba(51, 112, 255, 0.4)); }
.ball-inner {background: #3370ff;color: #fff;padding: 0 12px;border-radius: 8px;display: flex;align-items: center;height: 36px;font-size: 13px;font-weight: 600;}
.ball-inner:after { content:''; position: absolute; bottom: -6px; left: 50%; transform: translateX(-50%); border-left: 6px solid transparent; border-right: 6px solid transparent; border-top: 6px solid #3370ff; }
.menu-opt {padding: 0 8px;cursor: pointer;display: flex;align-items: center;gap: 4px;transition: opacity 0.2s;}
/* 🚨 文本分析看板样式 */
.stats-box {
  padding: 16px 20px 0 20px;
  background: #fff;
}
.stats-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  background: #f8f9fa;
  border-radius: 8px;
  padding: 12px 0;
  border: 1px solid #ebeef5;
}
.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  border-right: 1px solid #ebeef5;
}
.stat-item:last-child {
  border-right: none;
}
.stat-item .num {
  font-size: 15px;
  font-weight: 700;
  color: #3370ff;
  font-family: monospace; /* 让数字更整齐 */
}
.stat-item .desc {
  font-size: 11px;
  color: #8f959e;
  margin-top: 4px;
}

/* 1. 左侧文档信息栏 (260px) */
.left-sidebar {
  width: 260px;
  background: #f9fafb;
  border-right: 1px solid #dee0e3;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  padding: 20px;
  overflow-y: auto;
}
.sidebar-section {
  margin-bottom: 30px;
}
.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #1f2329;
  margin-bottom: 16px;
  display: flex;
  align-items: center;
  gap: 6px;
}

/* 文本分析微调 */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr); /* 变成 2x2 网格更适合左侧窄栏 */
  gap: 12px;
}
.stat-item {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  box-shadow: 0 2px 4px rgba(0,0,0,0.02);
}
.stat-item .num { font-size: 18px; font-weight: bold; color: #3370ff; font-family: monospace;}
.stat-item .desc { font-size: 11px; color: #8f959e; margin-top: 4px;}

/* 摘要区域 */
.summary-text {
  font-size: 13px;
  color: #646a73;
  line-height: 1.6;
  background: #fff;
  padding: 12px;
  border-radius: 8px;
  border: 1px solid #ebeef5;
}

.keywords-area {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;        /* 确保开启换行 */
  align-items: flex-start; /* 保证行对齐 */
  gap: 8px;
  width: 100%;           /* 确保容器占满父级宽度 */
  overflow: visible;     /* 必须开启可见，防止溢出触发滚动 */
}

/* 确保每个 Tag 不会因为太长而挤压别人 */
.keyword-tag {
  white-space: normal !important; /* 强制 Tag 内部内容可以处理换行 */
  height: auto !important;        /* 让高度随内容自动适配 */
  min-height: 24px;               /* 保持一定高度 */
}

/* 协作卡片 */
.collab-card {
  background: #fff;
  border: 1px solid #e1eaff;
  border-radius: 8px;
  padding: 16px;
}
.status-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-right: 6px;
}
.status-dot.online { background-color: #52c41a; box-shadow: 0 0 4px #52c41a; }

/* 2. 右侧 AI 助手栏 (拓宽到 380px) */
.expanded-ai {
  width: 380px !important; /* 加宽！让对话框更大 */
}
.chat-area {
  flex-grow: 1;
  padding: 20px;
  overflow-y: auto;
  background: #f4f5f7; /* 纯净灰底，突出气泡 */
}
.chat-bubble {
  max-width: 90%;
  padding: 14px 18px; /* 加大气泡内边距 */
  border-radius: 12px;
  margin-bottom: 20px;
  font-size: 14px;
  line-height: 1.6;
  box-shadow: 0 2px 8px rgba(0,0,0,0.04);
}
/* 分享弹窗样式 */
.share-body { padding: 0 4px; }
.share-section { margin-bottom: 4px; }
.share-label { font-size: 14px; font-weight: 600; color: #1f2329; margin-bottom: 10px; }
.link-row { display: flex; gap: 8px; align-items: center; }
.link-hint { font-size: 12px; color: #8f959e; margin-top: 6px; }
.grant-row { display: flex; gap: 8px; align-items: center; }
.granted-list { margin-top: 12px; }
.granted-item { display: flex; align-items: center; gap: 8px; padding: 6px 0; font-size: 13px; }
.grant-expiry { color: #9a909f; font-size: 10px; }
.granted-name { flex: 1; color: #1f2329; }
.no-granted { font-size: 13px; color: #8f959e; padding: 8px 0; }
.collab-hint { font-size: 13px; color: #646a73; margin: 0; line-height: 1.5; }

.selected-context {
  font-size: 12px;
  color: #3370ff;
  background: #e1eaff;
  padding: 6px 12px;
  border-radius: 4px;
  margin-bottom: 12px;
  display: inline-block;
}

/* ==================== Morandi editor refinement ==================== */
.editor-page {
  --editor-lavender: #aca0ce;
  --editor-lavender-deep: #74698e;
  --editor-green: #edf4e2;
  --editor-pink: #dfced6;
  --editor-ink: #413b4b;
  --editor-muted: #847d8d;
  --editor-line: #e8e0e8;
  background: #f7f6f3;
}
.toolbar { height: 68px; padding: 0 30px; background: rgba(255, 253, 249, .96); border-bottom-color: var(--editor-line); box-shadow: 0 4px 18px rgba(69, 58, 79, .04); }
.toolbar .left { display: flex; align-items: center; min-width: 0; }
.doc-title { max-width: 320px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--editor-ink); font-size: 15px; font-weight: 700; }
.save-status { margin-left: 12px; padding: 4px 9px; border-radius: 999px; background: #f1edf7; color: var(--editor-lavender-deep); font-size: 12px; }
.header-right { display: flex; align-items: center; gap: 8px; }
.version-compare-panel{padding:14px;border:1px solid #e7dfe9;border-radius:14px;background:#faf8fb}.review-section-title{display:flex;align-items:baseline;justify-content:space-between;margin-bottom:12px}.review-section-title span{color:#93899a;font-size:12px}.version-compare-actions{display:grid;grid-template-columns:1fr auto 1fr auto;align-items:center;gap:8px}.diff-summary{display:flex;gap:8px;margin:14px 0 10px}.diff-lines{max-height:320px;overflow:auto;border:1px solid #e9e3ea;border-radius:9px;background:#fff;font-family:Consolas,monospace}.diff-line{display:grid;grid-template-columns:38px 38px minmax(0,1fr);min-height:25px;border-bottom:1px solid #f1edf2;font-size:12px}.diff-line>span{padding:4px 6px;text-align:right;color:#9a929d;background:#f7f5f7}.diff-line code{padding:4px 8px;white-space:pre-wrap;word-break:break-word}.diff-line.added{background:#edf8f0}.diff-line.removed{background:#fff0f1}.diff-line.added code{color:#276e3b}.diff-line.removed code{color:#9b3440}.review-composer,.suggestion-composer{display:flex;flex-direction:column;align-items:flex-end;gap:10px;padding:14px;border:1px solid #e8e1e9;border-radius:14px;background:#faf8fb}.suggestion-composer{align-items:stretch}.suggestion-composer label{font-size:12px;font-weight:700;color:#766b7c}.suggestion-composer blockquote{max-height:110px;overflow:auto;margin:0;padding:10px 12px;border-left:3px solid #a99aba;background:#fff;color:#706775;white-space:pre-wrap}.suggestion-composer .el-button{align-self:flex-end}.review-list{display:flex;flex-direction:column;gap:11px;margin-top:14px}.review-card{padding:14px;border:1px solid #ebe5ec;border-radius:13px;background:#fff}.review-card header{display:flex;align-items:center;gap:9px}.review-card header>div{display:flex;flex:1;flex-direction:column;gap:2px}.review-card header small,.review-card>small{color:#968d9a}.review-card p{margin:12px 0 0;line-height:1.7;white-space:pre-wrap}.suggestion-change{display:flex;flex-direction:column;gap:7px;margin:12px 0}.suggestion-change del,.suggestion-change ins{padding:9px;border-radius:8px;white-space:pre-wrap;text-decoration:none}.suggestion-change del{background:#fff0f1;color:#994450}.suggestion-change ins{background:#edf8f0;color:#347249}.suggestion-change del:before{content:'− ';font-weight:700}.suggestion-change ins:before{content:'+ ';font-weight:700}.suggestion-actions{display:flex;justify-content:flex-end;margin-top:12px}
.editor-main { padding: 42px clamp(20px, 4vw, 64px) 64px; background: #f5f4f0; }
.paper { width: min(780px, 100%); min-height: 1120px; padding: 84px 104px; border: 1px solid rgba(232, 224, 232, .85); border-radius: 6px; box-shadow: 0 18px 44px rgba(71, 61, 80, .11); color: var(--editor-ink); font-family: "Microsoft YaHei", "PingFang SC", sans-serif; line-height: 1.92; }
.paper:focus { box-shadow: 0 18px 44px rgba(71, 61, 80, .11), 0 0 0 4px rgba(172, 160, 206, .2); }
.left-sidebar { width: 280px; padding: 24px 20px; background: #fffdf9; border-right-color: var(--editor-line); }
.ai-sidebar { background: #fffdf9; border-left-color: var(--editor-line); }
.expanded-ai { width: 400px !important; }
.section-title { color: var(--editor-ink); letter-spacing: .02em; }
.section-title .el-icon { color: var(--editor-lavender-deep); }
.stat-item { border-color: #eee7ee; border-radius: 12px; background: #fcfaf8; box-shadow: none; }
.stat-item .num { color: var(--editor-lavender-deep); font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
.summary-text { border-color: #e9e2e9; border-radius: 12px; background: #fbf9f7; color: #625b6a; }
.keyword-tag { --el-tag-bg-color: #f0ebf6; --el-tag-border-color: #e1d8eb; --el-tag-text-color: #74698e; }
.ai-header { padding: 16px 20px; background: #fffdf9; border-bottom-color: var(--editor-line); color: var(--editor-ink); }
.chat-area { padding: 20px; background: #f8f6f4; }
.chat-bubble.ai { border-color: #e8e0e8; border-radius: 15px 15px 15px 4px; background: #fffdf9; color: var(--editor-ink); box-shadow: 0 5px 16px rgba(69, 58, 79, .05); }
.chat-bubble.user { border-radius: 15px 15px 4px 15px; background: var(--editor-lavender-deep); box-shadow: 0 6px 16px rgba(116, 105, 142, .22); }
.input-area { padding: 18px 20px 20px; border-top-color: var(--editor-line); background: #fffdf9; }
.selected-context { color: var(--editor-lavender-deep); background: #f0ebf6; border-radius: 8px; }
.ai-float-ball { filter: drop-shadow(0 8px 16px rgba(116, 105, 142, .28)); }
.ball-inner { background: var(--editor-lavender-deep); border-radius: 12px; }
.ball-inner:after { border-top-color: var(--editor-lavender-deep); }
.share-label { color: var(--editor-ink); }
.link-hint, .collab-hint { color: var(--editor-muted); }
:deep(.el-button--primary) { --el-button-bg-color: #74698e; --el-button-border-color: #74698e; --el-button-hover-bg-color: #615778; --el-button-hover-border-color: #615778; --el-button-active-bg-color: #554c69; --el-button-active-border-color: #554c69; }
:deep(.el-switch.is-checked .el-switch__core) { border-color: #74698e; background-color: #74698e; }
:deep(.el-input__wrapper), :deep(.el-textarea__inner) { box-shadow: 0 0 0 1px #e7dfe7 inset; background: #fffdf9; }
:deep(.el-input__wrapper.is-focus), :deep(.el-textarea__inner:focus) { box-shadow: 0 0 0 1px #aca0ce inset, 0 0 0 3px rgba(172, 160, 206, .14); }
@media (max-width: 1180px) { .left-sidebar { width: 238px; } .expanded-ai { width: 330px !important; } .paper { padding: 68px 72px; } }
@media (max-width: 900px) { .left-sidebar { display: none; } .expanded-ai { width: 310px !important; } .toolbar { padding: 0 16px; } .header-right .el-button:nth-child(2) { display: none; } }
@media (max-width: 680px) { .expanded-ai { display: none; } .editor-main { padding: 18px 12px 32px; } .paper { min-height: calc(100vh - 100px); padding: 44px 26px; font-size: 15px; } .save-status { display: none; } }

/* ==================== Morandi chat-mode refinement ==================== */
.feishu-chat-layout { background: #f7f6f3; color: #413b4b; }
.chat-sidebar { width: 272px; background: #fffdf9; border-right-color: #e8e0e8; }
.sidebar-top { padding: 28px 20px; }
.brand-back { color: #74698e; }
.brand-back:hover { color: #615778; }
.new-chat-btn { --el-button-bg-color: #f4f0f8; --el-button-border-color: #e0d7ea; --el-button-text-color: #74698e; --el-button-hover-bg-color: #eee8f5; --el-button-hover-text-color: #615778; --el-button-hover-border-color: #cfc3de; border-radius: 12px; height: 40px; }
.group-title { color: #938a9b; letter-spacing: .08em; }
.history-item { background: #f0ebf6; color: #74698e; border-radius: 12px; }
.sidebar-bottom { border-top-color: #e8e0e8; }
.user-profile .username { color: #413b4b; }
.chat-main { background: #f7f6f3; }
.chat-header { height: 68px; border-bottom-color: #e8e0e8; background: rgba(255,253,249,.92); }
.chat-header .title { color: #413b4b; }
.chat-scroll-area { padding-top: 48px; }
.user-bubble { background: #74698e; border-radius: 17px 5px 17px 17px; box-shadow: 0 6px 16px rgba(116,105,142,.2); }
.ai-structured-card { border-color: #e6dee7; border-radius: 5px 17px 17px 17px; background: #fffdf9; color: #413b4b; box-shadow: 0 7px 20px rgba(70,59,80,.05); }
.ai-structured-card:hover { border-color: #aca0ce; box-shadow: 0 9px 22px rgba(116,105,142,.1); }
.chat-input-container { padding-bottom: 30px; background: linear-gradient(to top, #f7f6f3 78%, rgba(247,246,243,0)); }
.input-wrapper-inner { max-width: 860px; height: 68px; padding: 0 12px 0 16px; border-color: #e4dce7; border-radius: 20px; box-shadow: 0 8px 24px rgba(70,59,80,.07); }
.input-wrapper-inner.is-focused, .input-wrapper-inner.rag-active { border-color: #aca0ce; box-shadow: 0 9px 28px rgba(116,105,142,.14); }
.chat-input { margin-left: 16px; color: #413b4b; }
.input-right { gap: 18px; margin-left: 14px; }
.mic-icon { color: #9b92a2; }
.send-btn-circle { background: #e7e0eb; color: #9b92a2; }
.send-btn-circle.active { background: #74698e; color: #fff; }
.ai-hint { color: #9b92a2; }
.rag-mode-toggle { display: inline-flex; align-items: center; gap: 7px; min-width: 132px; height: 40px; padding: 0 13px; border: 1px solid #ddd4e5; border-radius: 12px; background: #f8f5fb; color: #74698e; font: inherit; font-size: 13px; font-weight: 650; cursor: pointer; transition: .18s ease; }
.rag-mode-toggle:hover, .rag-mode-toggle:focus-visible { border-color: #aca0ce; background: #f0ebf6; outline: none; }
.rag-mode-toggle.active { border-color: #74698e; background: #74698e; color: #fff; box-shadow: 0 6px 14px rgba(116,105,142,.22); }
.chat-attach-btn { display: grid; flex: 0 0 auto; place-items: center; width: 40px; height: 40px; margin-left: 16px; padding: 0; border: 1px solid #e3dce5; border-radius: 12px; background: #fffdf9; color: #74698e; cursor: pointer; transition: .18s ease; }
.chat-attach-btn:hover, .chat-attach-btn:focus-visible { border-color: #aca0ce; background: #f0ebf6; color: #615778; outline: none; }
.chat-attach-btn:disabled { cursor: wait; opacity: .6; }
.chat-file-input { display: none; }
@media (max-width: 760px) { .chat-sidebar { display: none; } .chat-input-container { padding: 16px; } .input-wrapper-inner { height: auto; min-height: 64px; border-radius: 16px; } .rag-mode-toggle { min-width: 40px; padding: 0 11px; } .rag-mode-toggle span { display: none; } .chat-attach-btn { margin-left: 10px; } .chat-input { margin-left: 10px; } }

/* ==================== Chat document context ==================== */
.chat-context-row { width: 100%; max-width: 860px; display: flex; align-items: center; gap: 12px; margin: 0 auto 8px; }
.chat-context-label { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 6px; color: #74698e; font-size: 12px; font-weight: 700; }
.chat-document-select { flex: 1; }
.chat-context-row :deep(.el-select__wrapper) { min-height: 36px; border-radius: 10px; background: #fffdf9; box-shadow: 0 0 0 1px #e4dce7 inset; }
.chat-context-hint { width: 100%; max-width: 860px; margin: 0 auto 12px; color: #938a9b; font-size: 12px; }
@media (max-width: 760px) { .chat-context-row { align-items: flex-start; flex-direction: column; gap: 6px; } .chat-document-select { width: 100%; } }
</style>
