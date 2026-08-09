<template>
  <div class="feishu-layout">
    <el-container class="full-height">

      <!-- 1. 左侧：极简侧边栏 -->
      <el-aside width="240px" class="feishu-aside">
        <div class="aside-top">
          <div class="brand">
            <div class="logo-box"><el-icon><Cpu /></el-icon></div>
            <span class="brand-text">SmartDoc</span></div>
          <div class="nav-menu">
            <div class="nav-item" :class="{ active: !showDocLibrary }" @click="goToWorkbench"><el-icon><Monitor /></el-icon> 我的工作台</div>
            <div class="nav-item" :class="{ active: showDocLibrary }" @click="goToDocLibrary"><el-icon><FolderOpened /></el-icon> 云端文档库</div>
            <div class="nav-item ai-chat-nav" @click="goToAiChat"><el-icon><ChatLineSquare /></el-icon> SmartDoc AI 对话</div>
            <div class="nav-item toolbox-nav" @click="goToToolbox"><el-icon><MagicStick /></el-icon> 文档处理工具箱</div>
            <div v-if="isAdminUser" class="nav-item aiops-nav" @click="goToAIOps">
              <el-icon><Cpu /></el-icon> AI Ops 运维中心
            </div>
          </div>
        </div>

        <!-- 底部：真实用户信息 (头像取首字母) -->
        <div class="aside-bottom">
          <el-dropdown trigger="click" placement="top-start" @command="handleUserCommand">
            <div class="user-profile">
              <el-avatar :size="32" style="background-color: #3370ff; font-weight: bold; color: white;">
                {{ currentUserName.charAt(0).toUpperCase() }}
              </el-avatar>
              <span class="username">{{ currentUserName }}</span>
              <el-icon class="more-icon"><MoreFilled /></el-icon>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout" icon="SwitchButton" style="color: #F56C6C">退出系统</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-aside>

      <!-- 2. 右侧：主内容区 -->
      <el-main class="feishu-main" v-loading="loading">
        <div class="main-container">

          <!-- AI 欢迎与对话区 (对标飞书 aily) -->
          <div class="ai-hero" v-if="!showDocLibrary">
            <el-avatar :size="64" style="background-color: #3370ff; font-size: 28px; font-weight: bold; margin-bottom: 24px; color: white;">
              {{ currentUserName.charAt(0).toUpperCase() }}
            </el-avatar>
            <h1 class="hero-title">Hi {{ currentUserName }}，今天需要我帮你分析什么？</h1>

            <!-- 飞书级交互：巨大输入框 + 气泡弹出菜单 -->
            <el-popover
                placement="bottom-start"
                :width="700"
                trigger="click"
                :show-arrow="false"
                popper-class="feishu-popover"
            >
              <template #reference>
                <div class="hero-search">
                  <el-icon class="prefix-icon"><Plus /></el-icon>
                  <input
                      v-model="aiTask"
                      class="hero-input"
                      placeholder="总结我的上周工作，或者让我帮你写一份大纲..."
                      @keyup.enter="handleAiTask"
                  />
                  <el-icon class="mic-icon"><Microphone /></el-icon>
                  <div class="send-action-btn" @click.stop="handleAiTask">
                    <el-icon size="18"><Top /></el-icon>
                  </div>
                </div>
              </template>

              <!-- 弹出菜单：上传入口隐藏在这里 -->
              <div class="popover-content">
                <div class="menu-list">
                  <input type="file" ref="fileInputRef" style="display: none;" accept=".pdf,.doc,.docx,.txt,.md,.ppt,.pptx,.xls,.xlsx,.jpg,.jpeg,.png,.gif,.webp" @change="onFileSelected" />
                  <div class="upload-category-picker" @click.stop>
  <span>保存到</span>
  <el-select v-model="uploadCategory" size="small" clearable placeholder="未分类" style="width: 160px"><el-option v-for="category in categoryOptions" :key="category" :label="category" :value="category" /></el-select>
  <el-button text type="primary" @click.stop="categoryDialogVisible = true">新建板块</el-button>
</div>
<div class="menu-item" @click="triggerUpload">
                    <el-icon><Paperclip /></el-icon> 上传文件并由 AI 解析
                  </div>


                </div>
              </div>
            </el-popover>

            <!-- 快捷胶囊按钮 -->
            <div class="quick-prompts">
              <div class="prompt-pill" @click="newDocDialogVisible = true"><el-icon><EditPen /></el-icon> 新建文档</div>
              <div class="prompt-pill" @click="triggerUpload"><el-icon><Upload /></el-icon> 上传文档</div>
              <div class="prompt-pill" @click="pptDialogVisible = true"><el-icon><Monitor /></el-icon> 一键生成 PPT</div>
              <div class="prompt-pill" @click="goToAiChat"><el-icon><ChatLineSquare /></el-icon> 开启对话</div>
            </div>
          </div>

          <!-- ===== 文档库视图 ===== -->
          <div v-if="showDocLibrary" class="doc-library">
            <div class="library-header">
              <div>
                <h2 class="library-title"><el-icon><FolderOpened /></el-icon> 云端文档库</h2>
                <p class="library-subtitle">共 {{ docList.length }} 篇文档</p>
              </div>
              <div class="library-actions">
                <el-radio-group v-model="libraryScope" size="small" @change="changeLibraryScope">
                  <el-radio-button label="all">全部</el-radio-button>
                  <el-radio-button label="favorite">收藏</el-radio-button>
                  <el-radio-button label="trash">回收站</el-radio-button>
                </el-radio-group>
                <el-select v-if="libraryScope !== 'trash'" v-model="categoryFilter" size="small" clearable placeholder="全部分类" style="width: 120px">
                  <el-option label="未分类" :value="UNCATEGORIZED_CATEGORY" />
                  <el-option v-for="category in categoryOptions" :key="category" :label="category" :value="category" />
                </el-select>
                <el-button v-if="libraryScope !== 'trash'" size="small" icon="FolderOpened" @click="categoryDialogVisible = true">分类板块</el-button>
                <el-button v-if="libraryScope !== 'trash'" size="small" icon="EditPen" @click="newDocDialogVisible = true">新建文档</el-button>
                <el-button v-if="libraryScope !== 'trash'" type="primary" size="small" icon="Upload" @click="triggerUpload">上传文档</el-button>
                <el-input
                    v-model="search"
                    placeholder="搜索文档标题..."
                    size="small"
                    style="width: 220px"
                    prefix-icon="Search"
                    clearable
                    @keyup.enter="handleSearch"
                    @clear="fetchFiles"
                />
              </div>
            </div>

            <!-- 文档统计卡片 -->
            <div v-if="libraryScope !== 'trash'" class="category-boards">
  <button class="category-board category-board-all" type="button" :class="{ active: !categoryFilter }" @click="selectCategory('', $event)"><span>全部文档</span><strong>{{ docList.length }}</strong></button>
  <button v-for="board in categoryBoards" :key="board.id" class="category-board" :class="{ active: categoryFilter === board.value }" type="button" @click="selectCategory(board.value, $event)"><i class="category-dot" :style="{ backgroundColor: board.color }"></i><span>{{ board.name }}</span><strong>{{ board.count }}</strong></button>
  <button class="category-board category-board-create" type="button" @click="categoryDialogVisible = true"><el-icon><Plus /></el-icon> 新建板块</button>
</div><div class="stats-row">
              <div class="stat-card">
                <el-icon :size="24" color="#3370ff"><Document /></el-icon>
                <div class="stat-info">
                  <span class="stat-num">{{ docList.length }}</span>
                  <span class="stat-label">总文档数</span>
                </div>
              </div>
              <div class="stat-card">
                <el-icon :size="24" color="#67c23a"><SuccessFilled /></el-icon>
                <div class="stat-info">
                  <span class="stat-num">{{ docList.filter(d => d.analyzed).length }}</span>
                  <span class="stat-label">AI 已分析</span>
                </div>
              </div>
              <div class="stat-card">
                <el-icon :size="24" color="#e6a23c"><Clock /></el-icon>
                <div class="stat-info">
                  <span class="stat-num">{{ docList.filter(d => !d.analyzed).length }}</span>
                  <span class="stat-label">待处理</span>
                </div>
              </div>
              <div class="stat-card">
                <el-icon :size="24" color="#f56c6c"><Delete /></el-icon>
                <div class="stat-info">
                  <el-button type="danger" size="small" text @click="clearDirtyData">一键清理</el-button>
                </div>
              </div>
            </div>

            <!-- 文档卡片网格（全宽） -->
            <div class="card-grid doc-library-grid" v-if="filteredList.length > 0">
              <div class="doc-card" v-for="doc in filteredList" :key="doc.id">
                <div class="card-cover" :class="doc.color" @click="libraryScope !== 'trash' && goToEditor(doc.id, doc.name)">
                  <img v-if="doc.thumbnail" :src="doc.thumbnail" :alt="doc.name" class="document-thumbnail" />
                  <el-icon v-else :size="48" color="rgba(255,255,255,0.9)">
                    <Picture v-if="doc.kind === 'image'" />
                    <Tickets v-else-if="doc.kind === 'pdf'" />
                    <Document v-else />
                  </el-icon>
                  <span class="doc-category-badge">{{ doc.category || '未分类' }}</span>
                  <el-icon v-if="doc.favorite" class="doc-favorite-badge" aria-label="已收藏"><StarFilled /></el-icon>
                  <span class="file-kind">{{ doc.extension || 'DOC' }}</span>
                </div>
                <div class="card-info">
                  <div class="info-top">
                    <el-tooltip :content="doc.name" placement="top" :show-after="500">
                      <div class="doc-name" @click="goToEditor(doc.id, doc.name)">{{ doc.name }}</div>
                    </el-tooltip>
                    <el-dropdown trigger="click" @command="(cmd) => handleCardCommand(cmd, doc)">
                      <el-icon class="more-btn"><MoreFilled /></el-icon>
                      <template #dropdown>
                        <el-dropdown-menu v-if="libraryScope !== 'trash'">
                          <el-dropdown-item command="favorite" icon="Star">{{ doc.favorite ? '取消收藏' : '收藏文档' }}</el-dropdown-item>
                          <el-dropdown-item command="rename" icon="EditPen">重命名</el-dropdown-item>
                          <el-dropdown-item command="category" icon="Folder">移动分类</el-dropdown-item>
                          <el-dropdown-item command="download" icon="Download">下载原文件</el-dropdown-item>
                          <el-dropdown-item command="delete" icon="Delete" divided style="color: #F56C6C">移入回收站</el-dropdown-item>
                        </el-dropdown-menu>
                        <el-dropdown-menu v-else>
                          <el-dropdown-item command="restore" icon="RefreshLeft">恢复文档</el-dropdown-item>
                          <el-dropdown-item command="purge" icon="Delete" divided style="color: #F56C6C">永久删除</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </div>
                  <div class="doc-meta">
                    <span>{{ doc.time }}</span>
                    <el-tag size="small" :type="statusType(doc)" round effect="light">{{ statusLabel(doc) }}</el-tag>
                  </div>
                </div>
              </div>
            </div>

            <el-empty v-else description="暂无文档，点击上方「上传文档」按钮上传" />
          </div><!-- 最近文档列表（工作台视图） -->
          <div ref="workbenchListRef" class="list-section" v-if="!showDocLibrary" :style="{ minHeight: workbenchListMinHeight }">
            <div class="section-header">
              <span class="section-title">我的云端文档 <span class="count">({{ docList.length }})</span></span>
              <div class="header-actions">
                <el-button size="small" icon="EditPen" @click="newDocDialogVisible = true">新建文档</el-button>
                <el-button type="danger" size="small" plain icon="Delete" @click="clearDirtyData">一键清理文档</el-button>
                <el-input
                    v-model="search"
                    placeholder="输入关键词并按回车搜索..."
                    size="small"
                    style="width: 220px"
                    prefix-icon="Search"
                    clearable
                    @keyup.enter="handleSearch"
                    @clear="fetchFiles"
                />
              </div>
            </div>
            <div class="workbench-category-section" v-if="!showDocLibrary">
  <div class="workbench-category-heading"><div><span class="workbench-category-title">文档分类</span><span class="workbench-category-subtitle">按板块快速查看和管理文档</span></div><el-button text type="primary" @click="categoryDialogVisible = true">管理分类</el-button></div>
  <div class="category-boards workbench-category-boards">
    <button class="category-board category-board-all" type="button" :class="{ active: !categoryFilter }" @click="selectCategory('', $event)"><span>全部文档</span><strong>{{ docList.length }}</strong></button>
    <button v-for="board in categoryBoards" :key="board.id" class="category-board" :class="{ active: categoryFilter === board.value }" type="button" @click="selectCategory(board.value, $event)"><i class="category-dot" :style="{ backgroundColor: board.color }"></i><span>{{ board.name }}</span><strong>{{ board.count }}</strong></button>
    <button class="category-board category-board-create" type="button" @click="categoryDialogVisible = true"><el-icon><Plus /></el-icon> 新建板块</button>
  </div>
</div>



            <!-- 卡片网格布局 -->
            <div class="card-grid" v-if="filteredList.length > 0">
              <div class="doc-card" v-for="doc in filteredList" :key="doc.id">

                <!-- 卡片上半部分渐变封面 -->
                <div class="card-cover" :class="doc.color" @click="libraryScope !== 'trash' && goToEditor(doc.id, doc.name)">
                  <img v-if="doc.thumbnail" :src="doc.thumbnail" :alt="doc.name" class="document-thumbnail" />
                  <el-icon v-else :size="48" color="rgba(255,255,255,0.9)">
                    <Picture v-if="doc.kind === 'image'" />
                    <Tickets v-else-if="doc.kind === 'pdf'" />
                    <Document v-else />
                  </el-icon>
                  <span class="doc-category-badge">{{ doc.category || '未分类' }}</span>
                  <el-icon v-if="doc.favorite" class="doc-favorite-badge" aria-label="已收藏"><StarFilled /></el-icon>
                  <span class="file-kind">{{ doc.extension || 'DOC' }}</span>
                </div>

                <!-- 卡片下半部分 -->
                <div class="card-info">
                  <div class="info-top">
                    <el-tooltip :content="doc.name" placement="top" :show-after="500">
                      <div class="doc-name" @click="goToEditor(doc.id, doc.name)">{{ doc.name }}</div>
                    </el-tooltip>
                    <el-dropdown trigger="click" @command="(cmd) => handleCardCommand(cmd, doc)">
                      <el-icon class="more-btn"><MoreFilled /></el-icon>
                      <template #dropdown>
                        <el-dropdown-menu v-if="libraryScope !== 'trash'">
                          <el-dropdown-item command="favorite" icon="Star">{{ doc.favorite ? '取消收藏' : '收藏文档' }}</el-dropdown-item>
                          <el-dropdown-item command="rename" icon="EditPen">重命名</el-dropdown-item>
                          <el-dropdown-item command="category" icon="Folder">移动分类</el-dropdown-item>
                          <el-dropdown-item command="download" icon="Download">下载原文件</el-dropdown-item>
                          <el-dropdown-item command="delete" icon="Delete" divided style="color: #F56C6C">移入回收站</el-dropdown-item>
                        </el-dropdown-menu>
                        <el-dropdown-menu v-else>
                          <el-dropdown-item command="restore" icon="RefreshLeft">恢复文档</el-dropdown-item>
                          <el-dropdown-item command="purge" icon="Delete" divided style="color: #F56C6C">永久删除</el-dropdown-item>
                        </el-dropdown-menu>
                      </template>
                    </el-dropdown>
                  </div>
                  <div class="doc-meta">
                    <span>{{ doc.time }}</span>
                    <el-tag size="small" :type="statusType(doc)" round effect="light">{{ statusLabel(doc) }}</el-tag>
                  </div>
                </div>
              </div>
            </div>

            <el-empty v-else description="暂无文档，点击上方对话框上传" />
          </div>

        </div>
      </el-main>
    </el-container>

    <el-dialog v-model="newDocDialogVisible" title="新建文档" width="680px" destroy-on-close @closed="resetNewDocumentForm">
      <el-form :model="newDocForm" label-position="top">
        <el-alert title="粘贴或输入文字后，SmartDoc 会生成原始 TXT 或 Word 文档，并同步保存到云端文档库。" type="info" :closable="false" show-icon style="margin-bottom: 16px" />
        <el-form-item label="文档名称" required>
          <el-input v-model="newDocForm.title" maxlength="80" show-word-limit placeholder="例如：会议纪要、项目周报" />
        </el-form-item>
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="生成格式">
              <el-radio-group v-model="newDocForm.format">
                <el-radio-button label="txt">TXT 文本</el-radio-button>
                <el-radio-button label="docx">Word 文档</el-radio-button>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="文档分类">
  <div class="category-select-row">
    <el-select v-model="newDocForm.category" filterable clearable style="flex: 1" placeholder="未分类"><el-option v-for="category in categoryOptions" :key="category" :label="category" :value="category" /></el-select>
    <el-button text type="primary" @click="categoryDialogVisible = true">新建板块</el-button>
  </div>
</el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="文档正文" required>
          <el-input v-model="newDocForm.content" type="textarea" :rows="12" maxlength="50000" show-word-limit resize="none" placeholder="在这里粘贴或输入文字内容，支持多段落。按 Ctrl + Enter 可直接生成。" @keyup.ctrl.enter="saveNewDocument" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newDocDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="newDocSaving" @click="saveNewDocument">生成并保存</el-button>
      </template>
    </el-dialog>
    <!-- PPT 生成器弹窗 -->
    <el-dialog v-model="categoryMoveDialogVisible" title="移动分类" width="460px" @closed="resetCategoryMoveForm">
      <p class="category-dialog-tip">选择一个已有板块；也可以在下方新建分类并立即将当前文档归入其中。</p>
      <el-form :model="categoryMoveForm" label-position="top" @submit.prevent>
        <el-form-item label="已有文档分类">
          <el-select v-model="categoryMoveForm.category" clearable placeholder="选择分类或保留为未分类" style="width: 100%">
            <el-option label="未分类" value="" />
            <el-option v-for="category in categoryOptions" :key="category" :label="category" :value="category" />
          </el-select>
        </el-form-item>
        <el-divider>或新建分类</el-divider>
        <el-form-item label="新分类名称">
          <el-input v-model="categoryMoveForm.newName" maxlength="50" show-word-limit placeholder="例如：项目资料、课程学习" />
        </el-form-item>
        <el-form-item label="标识颜色">
          <el-color-picker v-model="categoryMoveForm.color" show-alpha="false" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="categoryMoveDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="categoryMoving" @click="moveDocumentCategory">确认移动</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="categoryDialogVisible" title="分类板块" width="500px" @closed="resetCategoryForm">
  <p class="category-dialog-tip">创建板块后，上传、新建和整理文档时都能直接选择。板块内仍有文档时不能删除，避免误分类。</p>
  <el-form :model="categoryForm" label-position="top" @submit.prevent>
    <el-form-item label="板块名称" required><el-input v-model="categoryForm.name" maxlength="50" show-word-limit placeholder="例如：项目资料、课程学习、会议纪要" @keyup.enter="createCategory" /></el-form-item>
    <el-form-item label="标识颜色"><el-color-picker v-model="categoryForm.color" show-alpha="false" /></el-form-item>
    <el-button type="primary" :loading="categorySaving" @click="createCategory">创建板块</el-button>
  </el-form>
  <div class="category-manager-list">
    <p class="category-delete-note">删除板块不会删除文档；板块内的文档会自动归入“未分类”。</p>
    <div v-for="board in manageableCategoryBoards" :key="board.id" class="category-manager-item">
      <div class="category-manager-name"><i class="category-dot" :style="{ backgroundColor: board.color }"></i><span>{{ board.name }}</span><small>{{ board.count ? `${board.count} 篇文档将归入未分类` : '空板块' }}</small></div>
      <el-button size="small" type="danger" plain @click="removeCategory(board)">删除板块</el-button>
    </div>
    <el-empty v-if="!manageableCategoryBoards.length" description="还没有可管理的分类板块" :image-size="64" />
  </div>
</el-dialog><el-dialog v-model="pptDialogVisible" title="🎨 HTML PPT 生成器" width="600px" destroy-on-close>
      <el-form :model="pptForm" label-position="top">
        <el-alert :title="`即将使用 [${currentModelName}] 为您生成内容`" type="info" show-icon :closable="false" style="margin-bottom: 15px;" />
        <el-form-item label="📌 演示标题">
          <el-input v-model="pptForm.title" placeholder="例如：2026年DocAI项目" />
        </el-form-item>

        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="🎨 视觉主题">
              <el-select v-model="pptForm.theme" style="width: 100%">
                <el-option label="Tokyo Night（深色）" value="tokyo-night" />
                <el-option label="Dracula（深色）" value="dracula" />
                <el-option label="Catppuccin Mocha（深色）" value="catppuccin-mocha" />
                <el-option label="Nord（深色）" value="nord" />
                <el-option label="Corporate Clean（商务）" value="corporate-clean" />
                <el-option label="Minimal White（极简白）" value="minimal-white" />
                <el-option label="Cyberpunk Neon（赛博）" value="cyberpunk-neon" />
                <el-option label="Aurora（极光）" value="aurora" />
              </el-select>
            </el-form-item>
          </el-col>

          <el-col :span="12">
            <el-form-item label="🧠 驱动大模型">
              <el-select v-model="pptForm.model" style="width: 100%" placeholder="加载中..." :loading="modelsLoading">
                <el-option
                    v-for="m in aiModelsList"
                    :key="m.code"
                    :label="m.name"
                    :value="m.code"
                    :disabled="!m.available"
                >
                  <span style="float: left">{{ m.name }}</span>
                  <span style="float: right; color: #8492a6; font-size: 12px">{{ m.provider }}</span>
                </el-option>
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="📝 大纲（每行一个章节）">
          <el-input v-model="pptForm.outline" type="textarea" :rows="5" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="handlePptAction('preview')" :loading="pptLoading">👀 浏览器预览</el-button>
          <el-button type="primary" @click="handlePptAction('download')" :loading="pptLoading">💾 下载</el-button>
        </span>
      </template>
    </el-dialog>

  </div>

</template>

<script setup>
import { ref, computed, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Document as WordDocument, Packer, Paragraph, TextRun } from 'docx'
import { fileApi } from '../../api/file'
import { userApi } from '../../api/user'
import { docApi } from '../../api/document'
import { aiApi } from '../../api/ai'
import { STORAGE_KEYS } from '../../constants'
import { isAdmin } from '../../utils/jwt'

const router = useRouter()
const loading = ref(false)
const showDocLibrary = ref(false)
const currentUserName = ref('User')
const docList = ref([])
const libraryScope = ref('all')
const UNCATEGORIZED_CATEGORY = '__uncategorized__'
const categoryFilter = ref('')
const categories = ref([])
const uploadCategory = ref('')
const categoryDialogVisible = ref(false)
const categorySaving = ref(false)
const categoryForm = ref({ name: '', color: '#ACA0CE' })
const categoryMoveDialogVisible = ref(false)
const categoryMoving = ref(false)
const categoryMoveTarget = ref(null)
const categoryMoveForm = ref({ category: '', newName: '', color: '#ACA0CE' })
const workbenchListRef = ref(null)
const workbenchListMinHeight = ref('')
const thumbnailUrls = new Set()
const search = ref('')
const aiTask = ref('')
const fileInputRef = ref(null)
const modelsLoading = ref(false)
const aiModelsList = ref([])
const pptDialogVisible = ref(false)
const pptLoading = ref(false)
const newDocDialogVisible = ref(false)
const newDocSaving = ref(false)
const newDocForm = ref({ title: '', content: '', format: 'txt', category: '' })
const pptForm = ref({
  title: 'DocAI 项目',
  theme: 'tokyo-night',
  outline: '项目背景与痛点\n核心微服务架构\nRAG 知识检索增强\n未来商业展望',
  model: 'deepseek-chat'
})

const isAdminUser = ref(isAdmin())

const getCurrentUserId = () => localStorage.getItem('userId')

// 计算当前选中的模型名字（用于弹窗提示）
const currentModelName = computed(() => {
  const model = aiModelsList.value.find(m => m.code === pptForm.value.model)
  return model ? model.name : pptForm.value.model
})

// 获取用户信息
const fetchUser = async () => {
  const uid = getCurrentUserId()
  if (uid) {
    try {
      const res = await userApi.getUserInfo(uid)
      currentUserName.value = res.data.username || 'User'
    } catch (e) { currentUserName.value = '测试用户' }
  }
}

const extensionOf = name => (String(name || '').match(/\.([^.]+)$/)?.[1] || '').toUpperCase()
const kindOf = name => /\.(jpe?g|png|gif|webp)$/i.test(name || '') ? 'image' : /\.pdf$/i.test(name || '') ? 'pdf' : 'document'
const mapDocument = item => ({
  id: item.id,
  fileId: item.fileId,
  name: item.title || '未命名文档',
  time: item.createTime ? new Date(item.createTime).toLocaleDateString() : '未知',
  analyzed: !!item.summary || getProcessedDocumentIds().includes(String(item.id)),
  parseStatus: item.parseStatus || 'ready',
  category: item.category && item.category !== 'default' ? item.category : '',
  tags: Array.isArray(item.tags) ? item.tags : [],
  favorite: Array.isArray(item.tags) && item.tags.includes('_favorite'),
  favoriteAt: Number(String((Array.isArray(item.tags) ? item.tags : []).find(tag => /^_favorite_at:\d+/.test(String(tag))) || '').split(':')[1]) || Date.parse(item.updateTime || item.createTime || '') || 0,
  updatedAt: Date.parse(item.updateTime || item.createTime || '') || 0,
  extension: extensionOf(item.title),
  kind: kindOf(item.title),
  thumbnail: '',
  color: ['bg-blue', 'bg-orange', 'bg-green', 'bg-purple'][Math.abs(String(item.id || '').split('').reduce((sum, char) => sum + char.charCodeAt(0), 0)) % 4]
})

const releaseThumbnails = () => {
  thumbnailUrls.forEach(url => URL.revokeObjectURL(url))
  thumbnailUrls.clear()
}

const hydrateThumbnails = async documents => {
  releaseThumbnails()
  const targets = documents.filter(doc => doc.kind === 'image' && doc.fileId).slice(0, 16)
  await Promise.all(targets.map(async doc => {
    try {
      const blob = await fileApi.preview(doc.fileId)
      const url = URL.createObjectURL(blob)
      thumbnailUrls.add(url)
      doc.thumbnail = url
    } catch { /* 卡片图标降级 */ }
  }))
}

// 获取用户文档或回收站列表
const fetchFiles = async () => {
  const uid = getCurrentUserId()
  if (!uid) { router.push('/login'); return }
  loading.value = true
  try {
    const res = libraryScope.value === 'trash' ? await docApi.getTrash() : await docApi.getUserDocs(uid)
    docList.value = Array.isArray(res.data) ? res.data.map(mapDocument) : []
    if (libraryScope.value !== 'trash') await hydrateThumbnails(docList.value)
    else releaseThumbnails()
  } catch (e) {
    console.error('拉取列表失败', e)
  } finally { loading.value = false }
}

const changeLibraryScope = async scope => {
  categoryFilter.value = ''
  if (scope === 'trash' || docList.value.some(doc => doc.status === 'deleted')) await fetchFiles()
  else if (scope === 'all' || scope === 'favorite') await fetchFiles()
}

onBeforeUnmount(releaseThumbnails)

const fetchCategories = async () => {
  try {
    const res = await docApi.getCategories()
    categories.value = Array.isArray(res.data) ? res.data : []
  } catch (error) {
    console.warn('获取分类板块失败，将显示文档现有分类', error)
    categories.value = []
  }
}


// 获取 AI 模型列表
const fetchModels = async () => {
  modelsLoading.value = true
  try {
    const res = await aiApi.getModels().catch(() => null)
    if (res && res.data && res.data.length > 0) {
      aiModelsList.value = res.data
      const firstAvailable = res.data.find(m => m.available)
      if (firstAvailable) pptForm.value.model = firstAvailable.code
    } else {
      // 容灾假数据
      aiModelsList.value = [
        { code: 'qwen-plus', name: '通义千问 Plus (Mock)', available: true, provider: '阿里云' },
        { code: 'deepseek-v3.2', name: 'DeepSeek (Mock)', available: true, provider: 'DeepSeek' }
      ]
    }
  } finally {
    modelsLoading.value = false
  }
}

onMounted(() => { fetchUser(); fetchFiles(); fetchModels(); fetchCategories() })

const categoryOptions = computed(() => [...new Set([
  ...categories.value.map(category => category.name).filter(Boolean),
  ...docList.value.map(doc => doc.category).filter(Boolean)
])])
const categoryDocumentCount = name => docList.value.filter(doc => doc.category === name).length
const uncategorizedDocumentCount = computed(() => docList.value.filter(doc => !doc.category).length)
const categoryBoards = computed(() => {
  const boards = categoryOptions.value.map(name => {
    const persisted = categories.value.find(category => category.name === name)
    return { id: persisted?.id || `legacy-${name}`, name, value: name, color: persisted?.color || '#ACA0CE', count: categoryDocumentCount(name), persisted: !!persisted }
  })
  if (uncategorizedDocumentCount.value) {
    boards.unshift({ id: UNCATEGORIZED_CATEGORY, name: '未分类', value: UNCATEGORIZED_CATEGORY, color: '#B7AFBD', count: uncategorizedDocumentCount.value, persisted: false })
  }
  return boards
})
const persistedCategoryBoards = computed(() => categoryBoards.value.filter(board => board.persisted))
const manageableCategoryBoards = computed(() => categoryBoards.value.filter(board => board.value !== UNCATEGORIZED_CATEGORY))
const selectCategory = async (value, event) => {
  event?.preventDefault()
  event?.currentTarget?.blur()

  const scrollContainer = document.querySelector('.feishu-main')
  const containerScrollTop = scrollContainer?.scrollTop || 0
  const documentScrollTop = document.scrollingElement?.scrollTop || window.scrollY
  const listElement = workbenchListRef.value

  // Keep the list's previous height while cards are filtered. Otherwise the
  // browser clamps scrollTop when a small category makes the page shorter.
  if (listElement) {
    workbenchListMinHeight.value = `${Math.ceil(listElement.getBoundingClientRect().height)}px`
  }

  categoryFilter.value = value
  await nextTick()

  requestAnimationFrame(() => {
    if (scrollContainer) scrollContainer.scrollTop = containerScrollTop
    if (document.scrollingElement) document.scrollingElement.scrollTop = documentScrollTop
  })
}
const filteredList = computed(() => docList.value
  .filter(doc => {
    if (libraryScope.value === 'favorite' && !doc.favorite) return false
    if (categoryFilter.value === UNCATEGORIZED_CATEGORY && doc.category) return false
    if (categoryFilter.value && categoryFilter.value !== UNCATEGORIZED_CATEGORY && doc.category !== categoryFilter.value) return false
    return doc.name.toLowerCase().includes(search.value.toLowerCase())
  })
  .sort((left, right) => {
    if (left.favorite !== right.favorite) return left.favorite ? -1 : 1
    if (left.favorite && right.favorite) return right.favoriteAt - left.favoriteAt
    return right.updatedAt - left.updatedAt
  }))
const statusLabel = doc => doc.parseStatus === 'parsing' ? '解析中' : doc.parseStatus === 'failed' ? '解析失败' : doc.analyzed ? '已处理' : '已解析'
const statusType = doc => doc.parseStatus === 'parsing' ? 'warning' : doc.parseStatus === 'failed' ? 'danger' : doc.analyzed ? 'success' : 'info'

const processedDocumentsKey = () => `smartdoc_processed_documents_${getCurrentUserId() || 'guest'}`

const getProcessedDocumentIds = () => {
  try {
    const ids = JSON.parse(localStorage.getItem(processedDocumentsKey()) || '[]')
    return Array.isArray(ids) ? ids.map(String) : []
  } catch {
    return []
  }
}

const markDocumentProcessed = (id) => {
  const documentId = String(id)
  const processedIds = new Set(getProcessedDocumentIds())
  processedIds.add(documentId)
  localStorage.setItem(processedDocumentsKey(), JSON.stringify([...processedIds]))
  const target = docList.value.find(doc => String(doc.id) === documentId)
  if (target) target.analyzed = true
}

// 搜索逻辑
const handleSearch = async () => {
  if (!search.value.trim()) {
    return fetchFiles() // 如果搜空，恢复全量列表
  }

  loading.value = true
  try {
    // 调 8084 接口进行全文检索（搜标题+内容）
    const res = await docApi.searchDocs(search.value)

    if (res.data) {
      docList.value = res.data.map(mapDocument)
      await hydrateThumbnails(docList.value)
    }
    ElMessage.success(`搜索到 ${docList.value.length} 篇相关文档`)
  } catch (e) {
    ElMessage.error('搜索失败')
  } finally {
    loading.value = false
  }
}


// 飞书化上传交互
const triggerUpload = () => {
  if (categoryFilter.value === UNCATEGORIZED_CATEGORY) uploadCategory.value = ''
  else if (categoryFilter.value) uploadCategory.value = categoryFilter.value
  fileInputRef.value?.click()
}

const onFileSelected = async (event) => {
  const rawFile = event.target.files[0]
  if (!rawFile) return

  loading.value = true
  try {
    // 先传文件仓库
    const fileRes = await fileApi.upload(rawFile)
    const fileId = fileRes?.data?.fileId || fileRes?.data?.id
    if (!fileId) {
      throw new Error('文件上传成功但没有返回 fileId')
    }

    // 在文档服务注册
    await docApi.createDoc({
      title: rawFile.name,
      fileId: fileId,
      category: uploadCategory.value || null
    })

    ElMessage.success(`《${rawFile.name}》已成功存入云端！`)
    await fetchFiles()
    event.target.value = ''
  } catch (e) {
    console.error('上传链路异常', e)
    ElMessage.error('上传链路异常')
  } finally {
    event.target.value = ''
    loading.value = false
  }
}

const resetCategoryForm = () => {
  categorySaving.value = false
  categoryForm.value = { name: '', color: '#ACA0CE' }
}

const ensureCategoryExists = async (name, color = '#ACA0CE') => {
  const normalizedName = String(name || '').trim()
  if (!normalizedName || categories.value.some(category => category.name === normalizedName)) return
  const res = await docApi.createCategory({ name: normalizedName, color })
  if (res.data) categories.value.push(res.data)
  else await fetchCategories()
}

const resetCategoryMoveForm = () => {
  categoryMoving.value = false
  categoryMoveTarget.value = null
  categoryMoveForm.value = { category: '', newName: '', color: '#ACA0CE' }
}

const openCategoryMoveDialog = doc => {
  categoryMoveTarget.value = doc
  categoryMoveForm.value = { category: doc.category || '', newName: '', color: '#ACA0CE' }
  categoryMoveDialogVisible.value = true
}

const moveDocumentCategory = async () => {
  const doc = categoryMoveTarget.value
  if (!doc) return
  const newName = categoryMoveForm.value.newName.trim()
  const targetCategory = newName || categoryMoveForm.value.category.trim()
  categoryMoving.value = true
  try {
    if (newName) await ensureCategoryExists(newName, categoryMoveForm.value.color || '#ACA0CE')
    await docApi.updateDoc(doc.id, {
      category: targetCategory || '',
      changeLog: targetCategory ? "调整文档分类" : "移出文档分类"
    })
    doc.category = targetCategory
    categoryMoveDialogVisible.value = false
    ElMessage.success(targetCategory ? "已移动到“" + targetCategory + "”" : "已归入未分类")
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || "移动分类失败")
  } finally {
    categoryMoving.value = false
  }
}

const createCategory = async () => {
  const name = categoryForm.value.name.trim()
  if (!name) { ElMessage.warning('请填写板块名称'); return }
  categorySaving.value = true
  try {
    const res = await docApi.createCategory({ name, color: categoryForm.value.color || '#ACA0CE' })
    if (res.data) categories.value.push(res.data)
    else await fetchCategories()
    newDocForm.value.category = name
    uploadCategory.value = name
    categoryDialogVisible.value = false
    ElMessage.success(`已创建“${name}”板块`)
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '创建分类板块失败')
  } finally {
    categorySaving.value = false
  }
}

const removeCategory = async board => {
  try {
    const description = board.count
      ? `“${board.name}”中有 ${board.count} 篇文档。删除后，这些文档会自动变为“未分类”，但仍保留在全部文档中。`
      : `确定删除“${board.name}”板块吗？`
    await ElMessageBox.confirm(description, '删除分类板块', { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' })
    await docApi.deleteCategoryByName(board.name)
    categories.value = categories.value.filter(category => category.name !== board.name)
    if (categoryFilter.value === board.value) categoryFilter.value = ''
    await fetchFiles()
    ElMessage.success(board.count ? '分类已删除，原文档已归为未分类' : '分类板块已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') ElMessage.error(error?.response?.data?.message || '删除分类板块失败')
  }
}
const resetNewDocumentForm = () => {
  newDocSaving.value = false
  newDocForm.value = { title: '', content: '', format: 'txt', category: '' }
}

const buildNewDocumentFile = async () => {
  const format = newDocForm.value.format === 'docx' ? 'docx' : 'txt'
  const title = newDocForm.value.title.trim()
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/\.(txt|docx)$/i, '') || '未命名文档'
  const fileName = `${title}.${format}`
  const content = newDocForm.value.content.replace(/\r\n/g, '\n')

  if (format === 'txt') {
    return new File([`\uFEFF${content}`], fileName, { type: 'text/plain;charset=utf-8' })
  }

  const paragraphs = content.split('\n').map(line => new Paragraph({
    children: line ? [new TextRun({ text: line })] : []
  }))
  const wordDocument = new WordDocument({
    sections: [{ children: paragraphs.length ? paragraphs : [new Paragraph('')] }]
  })
  const blob = await Packer.toBlob(wordDocument)
  return new File([blob], fileName, {
    type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'
  })
}

const saveNewDocument = async () => {
  if (!newDocForm.value.content.trim()) {
    ElMessage.warning('请先粘贴或输入文档正文')
    return
  }
  if (!newDocForm.value.title.trim()) {
    ElMessage.warning('请填写文档名称')
    return
  }

  newDocSaving.value = true
  loading.value = true
  try {
    const targetCategory = newDocForm.value.category.trim() || null
    await ensureCategoryExists(targetCategory)
    const rawFile = await buildNewDocumentFile()
    const fileRes = await fileApi.upload(rawFile)
    const fileId = fileRes?.data?.fileId || fileRes?.data?.id
    if (!fileId) throw new Error('文档生成后未返回文件标识')

    await docApi.createDoc({
      title: rawFile.name,
      fileId,
      content: newDocForm.value.content,
      category: targetCategory
    })
    newDocDialogVisible.value = false
    await fetchFiles()
    ElMessage.success(`《${rawFile.name}》已生成并保存到云端文档库`)
  } catch (error) {
    console.error('新建文档失败', error)
    ElMessage.error(error?.response?.data?.message || '文档生成失败，请稍后重试')
  } finally {
    newDocSaving.value = false
    loading.value = false
  }
}
const downloadBlob = (blob, fileName) => {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  link.remove()
  URL.revokeObjectURL(url)
}

// 文档卡片操作
const handleCardCommand = async (cmd, doc) => {
  if (cmd === 'favorite') {
    const tags = new Set(doc.tags || [])
    ;[...tags].filter(tag => String(tag).startsWith("_favorite_at:")).forEach(tag => tags.delete(tag))
    if (doc.favorite) tags.delete("_favorite")
    else {
      tags.add("_favorite")
      tags.add("_favorite_at:" + Date.now())
    }
    const nextTags = [...tags]
    await docApi.updateDoc(doc.id, { tags: nextTags, changeLog: doc.favorite ? "取消收藏" : "收藏文档" })
    doc.tags = nextTags
    doc.favorite = !doc.favorite
    doc.favoriteAt = doc.favorite ? Date.now() : 0
    ElMessage.success(doc.favorite ? "已收藏，已按收藏时间重新排序" : "已取消收藏")
    return
  }
  if (cmd === 'rename') {
    try {
      const { value } = await ElMessageBox.prompt('请输入新的文档名称', '重命名', { inputValue: doc.name, inputPattern: /\S+/, inputErrorMessage: '名称不能为空' })
      await docApi.updateDoc(doc.id, { title: value.trim(), changeLog: '重命名文档' })
      doc.name = value.trim(); doc.extension = extensionOf(doc.name); doc.kind = kindOf(doc.name)
      ElMessage.success('重命名成功')
    } catch { /* 用户取消 */ }
    return
  }
  if (cmd === 'category') {
    openCategoryMoveDialog(doc)
    return
  }
  if (cmd === 'delete') {
    try {
      await ElMessageBox.confirm(`确定将《${doc.name}》移入回收站吗？原文件仍会保留。`, '移入回收站')
      loading.value = true
      await docApi.deleteDoc(doc.id)
      ElMessage.success('已移入回收站，可随时恢复')
      await fetchFiles()
    } catch { /* 用户取消 */ } finally { loading.value = false }
    return
  }
  if (cmd === 'restore') {
    await docApi.restoreDeleted(doc.id)
    ElMessage.success('文档已恢复')
    await fetchFiles()
    return
  }
  if (cmd === 'purge') {
    try {
      await ElMessageBox.confirm(`永久删除《${doc.name}》后不可恢复，确定继续吗？`, '永久删除', { type: 'warning', confirmButtonText: '永久删除' })
      await docApi.purgeDoc(doc.id)
      if (doc.fileId) await fileApi.delete(doc.fileId).catch(() => {})
      ElMessage.success('已永久删除')
      await fetchFiles()
    } catch { /* 用户取消 */ }
    return
  }
  if (cmd === 'download') {
    loading.value = true
    try {
      if (doc.fileId) {
        downloadBlob(await fileApi.download(doc.fileId), doc.name)
      } else {
        const res = await docApi.getDocDetail(doc.id)
        const blob = new Blob([res.data.content || ''], { type: 'text/plain;charset=utf-8' })
        downloadBlob(blob, `${doc.name}.txt`)
      }
      ElMessage.success('下载完成')
    } catch { ElMessage.error('文件下载失败') } finally { loading.value = false }
  }
}
// 一键清理文档
const clearDirtyData = () => {
  ElMessageBox.confirm('这会强行清空列表里所有的文档记录，确定吗？').then(async () => {
    loading.value = true
    for (const doc of docList.value) {
      await docApi.deleteDoc(doc.id).catch(()=>{})
    }
    docList.value = []
    loading.value = false
    ElMessage.success('清理完成')
  })
}

// ppt生成逻辑
const handlePptAction = async (actionType) => {
  pptLoading.value = true
  try {
    // 获取原始 HTML (根据类型选择接口)
    let rawHtml = ""
    if (actionType === 'download') {
      const blob = await aiApi.downloadPpt(pptForm.value)
      rawHtml = await blob.text()
    } else {
      const result = await aiApi.previewPpt(pptForm.value)
      rawHtml = typeof result === 'string' ? result : result.data
    }

    if (!rawHtml || rawHtml.length < 10) throw new Error('生成内容为空')

    rawHtml = rawHtml.replace(/<li>\s*(?:[-*•○]|o\s+|O\s+|\d+[.、)）])\s*/g, '<li>')
    //  **加粗** 符号，也可以顺手清理掉
    rawHtml = rawHtml.replace(/\*\*/g, '')

    // 统一净化与增强
    const cdn = "https://cdn.jsdelivr.net/npm/reveal.js@5.0.4"
    let processedHtml = rawHtml
        .replace(/dist\/reveal\.css/g, `${cdn}/dist/reveal.css`)
        .replace(/dist\/reveal\.js/g, `${cdn}/dist/reveal.js`)
        .replace(/plugin\/notes\/notes\.js/g, `${cdn}/plugin/notes/notes.js`)
        .replace(/dist\/theme\/[a-z-]+\.css/g, (m) => `${cdn}/${m}`)
        .replace(/history:\s*true/g, 'history: false')

    // 注入安全补丁
    const injection = `
    <script>
      if (window.top !== window.self && !window.location.search.includes('preview')) { window.stop(); }
    <\/script>`
    processedHtml = processedHtml.replace('</head>', `${injection}</head>`)

    // 执行最终动作
    if (actionType === 'download') {
      const blob = new Blob([processedHtml], { type: 'text/html' })
      const url = window.URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.setAttribute('download', `${pptForm.value.title || '演示文稿'}.html`)
      document.body.appendChild(link); link.click(); document.body.removeChild(link)
      window.URL.revokeObjectURL(url)
      ElMessage.success('PPT 已导出')
    } else {
      sessionStorage.setItem('temp_ppt_render_source', processedHtml)
      window.open('/ppt-runtime', '_blank')
    }

  } catch (e) {
    console.error('PPT 处理失败:', e)
    ElMessage.error('生成失败，请重试')
  } finally {
    pptLoading.value = false
  }
}


// 跳转编辑器
const goToEditor = (id, name) => {
  markDocumentProcessed(id)
  sessionStorage.setItem('currentDocName', name)
  router.push(`/editor/${id}`)
}


const handleAiTask = () => {
  if (!aiTask.value.trim()) return
  sessionStorage.setItem('global_ai_task', aiTask.value)
  aiTask.value = ''
  router.push('/editor/chat-mode')
}


const handleUserCommand = (cmd) => {
  if (cmd === 'logout') { localStorage.clear(); router.push('/login'); }
}

const goToDocLibrary = () => {
  showDocLibrary.value = true
}
const goToAiChat = () => {
  sessionStorage.removeItem('global_ai_task')
  router.push('/editor/chat-mode')
}

const goToToolbox = () => {
  router.push('/toolbox')
}

const goToWorkbench = async () => {
  showDocLibrary.value = false
  if (libraryScope.value !== 'all') {
    libraryScope.value = 'all'
    categoryFilter.value = ''
    await fetchFiles()
  }
}

const goToAIOps = () => {
  router.push('/aiops')
}
</script>

<style scoped>
.feishu-layout { height: 100vh; background-color: #f5f6f7; }
.full-height { height: 100%; }

/* 侧边栏样式 */
.feishu-aside { background: #fff; border-right: 1px solid #dee0e3; display: flex; flex-direction: column; justify-content: space-between; }
.aside-top { padding: 24px 16px; }
.brand { display: flex; align-items: center; gap: 12px; margin-bottom: 20px; font-weight: bold; font-size: 18px; padding: 0 8px; }
.logo-box { width: 32px; height: 32px; background: linear-gradient(135deg, #3370ff, #5c8dff); border-radius: 8px; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 20px;}
.nav-item { padding: 12px 16px; border-radius: 8px; cursor: pointer; display: flex; align-items: center; gap: 10px; font-size: 14px; margin-bottom: 4px; color: #444; }
.nav-item:hover { background: #f2f3f5; }
.nav-item.active { background: #eef3ff; color: #3370ff; font-weight: 500; }
.nav-item.aiops-nav { color: #e6a23c; }
.nav-item.aiops-nav:hover { background: #fdf6ec; color: #e6a23c; }
.nav-item.ai-chat-nav { color: #74698e; }
.nav-item.ai-chat-nav:hover { background: #f1edf7; color: #615778; }
.nav-item.toolbox-nav { color: #6d8a64; }
.nav-item.toolbox-nav:hover { background: #edf4e2; color: #5d7755; }.aside-bottom { padding: 16px; border-top: 1px solid #dee0e3; }
.user-profile { display: flex; align-items: center; gap: 10px; padding: 8px; cursor: pointer; border-radius: 8px; transition: 0.2s; }
.user-profile:hover { background: #f2f3f5; }
.username { font-size: 14px; font-weight: 500; color: #1f2329;}

/* AI 欢迎区 */
.feishu-main { padding: 0; display: flex; justify-content: center; overflow-y: auto; overflow-anchor: none; }
.main-container { width: 100%; max-width: 1050px; padding: 40px 32px; }
.ai-hero { display: flex; flex-direction: column; align-items: center; margin-bottom: 50px; padding-top: 40px;}
.hero-title { font-size: 26px; color: #1f2329; margin-bottom: 36px; font-weight: 600; letter-spacing: 0.5px;}

/* 飞书风巨大输入框 */
.hero-search {
  width: 100%; max-width: 700px; height: 64px; background: #fff; border-radius: 32px;
  box-shadow: 0 6px 20px rgba(31, 35, 41, 0.08); display: flex; align-items: center; padding: 0 12px 0 24px;
  border: 1px solid transparent; transition: 0.3s; cursor: text;
}
.hero-search:focus-within { border-color: #3370ff; box-shadow: 0 8px 32px rgba(51,112,255,0.15); }
.prefix-icon { font-size: 22px; color: #8f959e; margin-right: 12px; }
.hero-input { flex: 1; border: none; outline: none; font-size: 16px; color: #1f2329; background: transparent; }
.send-action-btn { width: 40px; height: 40px; border-radius: 50%; background: #3370ff; color: white; display: flex; justify-content: center; align-items: center; cursor: pointer; }

/* 菜单项样式 */
.menu-item { padding: 12px; border-radius: 6px; cursor: pointer; display: flex; align-items: center; gap: 10px; font-size: 14px; transition: 0.2s; }
.menu-item:hover { background: #f2f3f5; }

/* 快捷胶囊 */
.quick-prompts { display: flex; gap: 12px; margin-top: 24px; justify-content: center; }
.prompt-pill { padding: 8px 16px; background: #fff; border: 1px solid #dee0e3; border-radius: 20px; font-size: 13px; font-weight: 500; cursor: pointer; }
.prompt-pill:hover { border-color: #3370ff; color: #3370ff; }

/* 文档卡片区 */
.list-section { margin-top: 40px; }
.section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 24px; padding-bottom: 12px; border-bottom: 1px solid #ebeef5;}
.section-title { font-size: 18px; font-weight: 600; color: #1f2329;}

.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 24px; }
.doc-card { background: #fff; border-radius: 12px; border: 1px solid #ebeef5; overflow: hidden; transition: 0.3s; }
.doc-card:hover { transform: translateY(-4px); box-shadow: 0 12px 24px rgba(31, 35, 41, 0.08); }
.card-cover { height: 130px; display: flex; align-items: center; justify-content: center; position: relative; overflow: hidden; }
.document-thumbnail { width: 100%; height: 100%; object-fit: cover; transition: transform .25s ease; }
.doc-card:hover .document-thumbnail { transform: scale(1.04); }
.file-kind { position: absolute; right: 10px; bottom: 9px; min-width: 34px; padding: 3px 7px; border-radius: 999px; background: rgba(50, 42, 59, .48); color: #fff; text-align: center; font-size: 10px; font-weight: 700; backdrop-filter: blur(6px); }
.doc-category-badge { position: absolute; left: 10px; top: 9px; max-width: calc(100% - 78px); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 4px 8px; border-radius: 999px; background: rgba(255, 255, 255, .92); color: #746589; font-size: 10px; font-weight: 700; box-shadow: 0 2px 7px rgba(48, 39, 57, .14); }
.doc-favorite-badge { position: absolute; right: 10px; top: 9px; width: 25px; height: 25px; display: inline-flex; align-items: center; justify-content: center; border-radius: 50%; background: rgba(255, 255, 255, .94); color: #b18d43; font-size: 14px; box-shadow: 0 2px 8px rgba(78, 62, 96, .18); }
.card-info { padding: 12px 16px; }
.info-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
.doc-name { font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #1f2329; cursor: pointer; flex: 1; }
.more-btn { color: #8f959e; cursor: pointer; font-size: 18px; }

.bg-blue { background: linear-gradient(135deg, #5182ff, #a0cfff); }
.bg-orange { background: linear-gradient(135deg, #edab56, #f3d19e); }
.bg-green { background: linear-gradient(135deg, #7bcf52, #b3e19d); }
.bg-purple { background: linear-gradient(135deg, #9b72f7, #c0a8f9); }
</style>
<style>
.feishu-popover { padding: 8px !important; border-radius: 12px !important; box-shadow: 0 8px 24px rgba(31, 35, 41, 0.12) !important; }

/* ===== 文档库样式 ===== */
.doc-library { margin-top: 20px; }
.library-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 28px; }
.library-title { font-size: 24px; font-weight: 700; color: #1f2329; display: flex; align-items: center; gap: 10px; margin: 0 0 6px; }
.library-subtitle { font-size: 14px; color: #8f959e; margin: 0; }
.library-actions { display: flex; gap: 12px; align-items: center; }.category-boards { display: flex; flex-wrap: wrap; gap: 10px; margin: -8px 0 22px; }.workbench-category-section { margin: 0 0 28px; padding: 16px 0 6px; border: 0; border-top: 1px solid #e5e1e8; border-radius: 0; background: transparent; }
.workbench-category-heading { display: flex; align-items: center; justify-content: space-between; margin-bottom: 14px; }
.workbench-category-title { color: #4f4658; font-size: 16px; font-weight: 700; }
.workbench-category-subtitle { margin-left: 10px; color: #9a909f; font-size: 12px; }
.workbench-category-boards { margin: 0; }
.category-board { min-height: 36px; border: 1px solid #e2dde5; border-radius: 10px; background: #f8f7f8; color: #5f5870; padding: 0 13px; display: inline-flex; align-items: center; gap: 8px; cursor: pointer; font: inherit; transition: color .2s ease, border-color .2s ease, background .2s ease; }
.category-board:hover, .category-board.active { border-color: #a99ac2; background: #f5f1f7; color: #70618c; box-shadow: 0 4px 12px rgba(123, 104, 157, .12); }
.category-board strong { color: #8b7aa7; font-size: 12px; font-weight: 700; }
.category-board-create { border-style: dashed; color: #85749f; }
.category-dot { width: 9px; height: 9px; border-radius: 50%; display: inline-block; flex: 0 0 auto; }
.upload-category-picker { display: flex; align-items: center; gap: 8px; padding: 4px 8px 10px; color: #7b728a; font-size: 12px; border-bottom: 1px solid #f0edf2; margin-bottom: 5px; }
.category-select-row { display: flex; align-items: center; gap: 8px; width: 100%; }
.category-dialog-tip { margin: -4px 0 18px; color: #7d7488; font-size: 13px; line-height: 1.65; }
.category-manager-list { margin-top: 22px; border-top: 1px solid #eee9f0; padding-top: 12px; max-height: 260px; overflow: auto; }
.category-delete-note { margin: 2px 0 10px; color: #9b7280; font-size: 12px; line-height: 1.55; }
.category-manager-item { display: flex; align-items: center; justify-content: space-between; min-height: 42px; padding: 4px 2px; border-bottom: 1px solid #f5f1f6; }
.category-manager-name { display: flex; align-items: center; gap: 9px; color: #51495d; }
.category-manager-name small { color: #aaa1b0; margin-left: 2px; }

.stats-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 32px; }
.stat-card { background: #fff; border-radius: 12px; border: 1px solid #ebeef5; padding: 20px; display: flex; align-items: center; gap: 16px; }
.stat-info { display: flex; flex-direction: column; }
.stat-num { font-size: 28px; font-weight: 700; color: #1f2329; line-height: 1; }
.stat-label { font-size: 13px; color: #8f959e; margin-top: 4px; }

.doc-library-grid { grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); }
</style>
