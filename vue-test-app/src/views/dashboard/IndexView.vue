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
                  <div class="menu-item" @click="triggerUpload">
                    <el-icon><Paperclip /></el-icon> 上传文件并由 AI 解析
                  </div>


                </div>
              </div>
            </el-popover>

            <!-- 快捷胶囊按钮 -->
            <div class="quick-prompts">
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
                  <el-option v-for="category in categoryOptions" :key="category" :label="category" :value="category" />
                </el-select>
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
            <div class="stats-row">
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
                    <span class="doc-tags"><el-icon v-if="doc.favorite"><StarFilled /></el-icon><em>{{ doc.category }}</em></span>
                    <el-tag size="small" :type="statusType(doc)" round effect="light">{{ statusLabel(doc) }}</el-tag>
                  </div>
                </div>
              </div>
            </div>

            <el-empty v-else description="暂无文档，点击上方「上传文档」按钮上传" />
          </div>

          <!-- 最近文档列表（工作台视图） -->
          <div class="list-section" v-if="!showDocLibrary">
            <div class="section-header">
              <span class="section-title">我的云端文档 <span class="count">({{ docList.length }})</span></span>
              <div class="header-actions">
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
                    <span class="doc-tags"><el-icon v-if="doc.favorite"><StarFilled /></el-icon><em>{{ doc.category }}</em></span>
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

    <!-- PPT 生成器弹窗 -->
    <el-dialog v-model="pptDialogVisible" title="🎨 HTML PPT 生成器" width="600px" destroy-on-close>
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
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
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
const categoryFilter = ref('')
const thumbnailUrls = new Set()
const search = ref('')
const aiTask = ref('')
const fileInputRef = ref(null)
const modelsLoading = ref(false)
const aiModelsList = ref([])
const pptDialogVisible = ref(false)
const pptLoading = ref(false)
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
  category: item.category || '默认分类',
  tags: Array.isArray(item.tags) ? item.tags : [],
  favorite: Array.isArray(item.tags) && item.tags.includes('_favorite'),
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

onMounted(() => { fetchUser(); fetchFiles(); fetchModels() })

const categoryOptions = computed(() => [...new Set(docList.value.map(doc => doc.category).filter(Boolean))])
const filteredList = computed(() => docList.value.filter(doc => {
  if (libraryScope.value === 'favorite' && !doc.favorite) return false
  if (categoryFilter.value && doc.category !== categoryFilter.value) return false
  return doc.name.toLowerCase().includes(search.value.toLowerCase())
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
const triggerUpload = () => fileInputRef.value?.click()

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
      category: 'default'
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
    doc.favorite ? tags.delete('_favorite') : tags.add('_favorite')
    await docApi.updateDoc(doc.id, { tags: [...tags], changeLog: doc.favorite ? '取消收藏' : '收藏文档' })
    doc.tags = [...tags]
    doc.favorite = !doc.favorite
    ElMessage.success(doc.favorite ? '已收藏' : '已取消收藏')
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
    try {
      const { value } = await ElMessageBox.prompt('输入分类名称，例如：学习资料、项目文档', '移动分类', { inputValue: doc.category === '默认分类' ? '' : doc.category, inputPattern: /\S+/, inputErrorMessage: '分类不能为空' })
      await docApi.updateDoc(doc.id, { category: value.trim(), changeLog: '调整文档分类' })
      doc.category = value.trim(); ElMessage.success('分类已更新')
    } catch { /* 用户取消 */ }
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
.nav-item.toolbox-nav:hover { background: #edf4e2; color: #5d7755; }

.aside-bottom { padding: 16px; border-top: 1px solid #dee0e3; }
.user-profile { display: flex; align-items: center; gap: 10px; padding: 8px; cursor: pointer; border-radius: 8px; transition: 0.2s; }
.user-profile:hover { background: #f2f3f5; }
.username { font-size: 14px; font-weight: 500; color: #1f2329;}

/* AI 欢迎区 */
.feishu-main { padding: 0; display: flex; justify-content: center; overflow-y: auto; }
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
.doc-tags { min-width: 0; display: inline-flex; align-items: center; gap: 3px; color: #8b7da0; }
.doc-tags em { max-width: 72px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; font-style: normal; }
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
.library-actions { display: flex; gap: 12px; align-items: center; }

.stats-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 32px; }
.stat-card { background: #fff; border-radius: 12px; border: 1px solid #ebeef5; padding: 20px; display: flex; align-items: center; gap: 16px; }
.stat-info { display: flex; flex-direction: column; }
.stat-num { font-size: 28px; font-weight: 700; color: #1f2329; line-height: 1; }
.stat-label { font-size: 13px; color: #8f959e; margin-top: 4px; }

.doc-library-grid { grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); }
</style>
