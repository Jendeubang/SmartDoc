<template>
  <div class="preview-shell">
    <aside class="preview-aside">
      <div>
        <div class="preview-brand">
          <div class="preview-logo"><el-icon><Cpu /></el-icon></div>
          <span>SmartDoc</span>
        </div>

        <p class="preview-label">WORKSPACE</p>
        <button v-for="item in navItems" :key="item.key" class="preview-nav" :class="{ active: activeNav === item.key }" @click="handleNav(item.key)">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.label }}</span>
        </button>
      </div>

      <div class="preview-user" role="button" tabindex="0" @click="handleUserMenu" @keyup.enter="handleUserMenu">
        <el-avatar :size="34">J</el-avatar>
        <div><strong>{{ currentUserName }}</strong><span>个人工作区</span></div>
        <el-icon><MoreFilled /></el-icon>
      </div>
    </aside>

    <main class="preview-main">
      <header class="preview-header">
        <div><span class="preview-crumb">SmartDoc / {{ activeNavLabel }}</span><h1>{{ pageTitle }}</h1></div>
        <div class="preview-header-actions">
          <el-button text circle aria-label="通知" @click="notificationsVisible = true"><el-icon><Bell /></el-icon></el-button>
          <el-button round plain @click="helpVisible = true">帮助中心</el-button>
        </div>
      </header>

      <section v-if="activeNav === 'workbench'" class="preview-hero">
        <p class="eyebrow">SMART DOCUMENT WORKSPACE</p>
        <h2>今天想让 SmartDoc<br />帮你完成什么？</h2>
        <p class="hero-copy">上传文档、提取重点，或让 AI 为你规划下一步工作。</p>
        <div class="task-box">
          <button class="task-attach" type="button" aria-label="上传文件" title="上传文件" @click="openFilePicker">
            <el-icon><Plus /></el-icon>
          </button>
          <input ref="taskInput" v-model="task" placeholder="例如：总结这份项目周报，并提取风险事项" @keyup.enter="submitTask" />
          <el-button class="task-send" circle :loading="submitting" aria-label="发送任务" @click="submitTask"><el-icon><Top /></el-icon></el-button>
        </div>
        <input ref="fileInput" class="hidden-file-input" type="file" accept=".pdf,.doc,.docx,.txt,.md,.ppt,.pptx,.xls,.xlsx" @change="onFileSelected" />
        <div v-if="selectedFile" class="attachment-chip">
          <el-icon><Document /></el-icon>
          <span>{{ selectedFile.name }}</span>
          <small>{{ formatFileSize(selectedFile.size) }}</small>
          <span class="attachment-state">{{ uploading ? '上传中' : '已上传' }}</span>
        </div>
        <div class="prompt-row">
          <button v-for="prompt in prompts" :key="prompt.key" @click="handlePrompt(prompt)"><el-icon><component :is="prompt.icon" /></el-icon>{{ prompt.label }}</button>
        </div>
      </section>

      <section v-else-if="activeNav === 'library'" class="library-intro">
        <div><p class="eyebrow">DOCUMENT LIBRARY</p><h2>让每份文档都更有价值</h2><p>统一管理、智能提取，并随时回到你需要的内容。</p></div>
        <el-button type="primary" round @click="openFilePicker"><el-icon><Upload /></el-icon>上传文档</el-button>
      </section>

      <section v-else-if="activeNav === 'knowledge'" class="knowledge-surface">
        <p class="eyebrow">RAG KNOWLEDGE BASE</p>
        <h2>向你的知识库提问</h2>
        <p class="hero-copy">使用混合检索与重排序，从已索引的个人文档中找到答案。</p>
        <div class="knowledge-form">
          <el-input v-model="ragQuestion" placeholder="例如：项目方案中有哪些风险事项？" clearable @keyup.enter="askKnowledge" />
          <el-button type="primary" :loading="ragLoading" @click="askKnowledge">开始问答</el-button>
        </div>
        <div v-if="ragAnswer" class="knowledge-answer">{{ ragAnswer }}</div>
      </section>

      <section v-else-if="activeNav === 'agent'" class="feature-surface">
        <div><p class="eyebrow">AI AGENT</p><h2>复杂任务交给 Agent 处理</h2><p>进入工作台可查看计划、工具调用、审批和执行时间线。</p></div>
        <el-button type="primary" round @click="goToAgentWorkbench">打开 Agent 工作台</el-button>
      </section>

      <section v-else-if="activeNav === 'aiops'" class="feature-surface">
        <div><p class="eyebrow">AI OPS</p><h2>运行状态与健康监控</h2><p>查看服务指标、健康检查和故障记录。该功能仅对管理员开放。</p></div>
        <el-button type="primary" round @click="goToAIOps">打开运维中心</el-button>
      </section>

      <template v-if="activeNav === 'workbench' || activeNav === 'library'">
        <section class="stats-grid">
          <article v-for="stat in stats" :key="stat.label" class="stat-item" :class="stat.tone" role="button" tabindex="0" @click="handleStatClick(stat)" @keyup.enter="handleStatClick(stat)">
            <el-icon><component :is="stat.icon" /></el-icon>
            <div><strong>{{ stat.value }}</strong><span>{{ stat.label }}</span></div>
          </article>
        </section>

        <section class="content-grid">
          <article class="surface docs-surface">
            <div class="surface-title"><div><p class="eyebrow">RECENT DOCUMENTS</p><h3>最近文档</h3></div><el-button text @click="goToDocuments">查看全部 <el-icon><ArrowRight /></el-icon></el-button></div>
            <button v-for="doc in documents" :key="doc.name" class="doc-row" @click="handleDocumentOpen(doc)">
              <span class="doc-icon" :class="doc.color"><el-icon><Document /></el-icon></span>
              <span class="doc-name"><strong>{{ doc.name }}</strong><small>{{ doc.meta }}</small></span>
              <el-tag size="small" round :type="doc.status === '已分析' ? 'success' : 'info'">{{ doc.status }}</el-tag>
              <el-icon class="row-arrow"><ArrowRight /></el-icon>
            </button>
          </article>

          <article class="surface activity-surface">
            <div class="surface-title"><div><p class="eyebrow">AI ACTIVITY</p><h3>AI 正在处理</h3></div><span class="live-dot">实时</span></div>
            <div class="activity-item"><span class="activity-mark lavender"><el-icon><MagicStick /></el-icon></span><div><strong>{{ latestTask || '会议纪要提炼' }}</strong><small>正在生成结构化摘要</small><el-progress :percentage="68" :show-text="false" :stroke-width="6" /></div></div>
            <div class="activity-item"><span class="activity-mark pink"><el-icon><Connection /></el-icon></span><div><strong>产品知识库</strong><small>已完成 24 / 26 份文档索引</small><el-progress :percentage="92" :show-text="false" :stroke-width="6" status="success" /></div></div>
            <button class="timeline-link" @click="goToAgentWorkbench">查看 Agent 执行时间线<el-icon><ArrowRight /></el-icon></button>
          </article>
        </section>
      </template>
    </main>

    <el-dialog v-model="notificationsVisible" title="通知" width="420px">
      <div v-for="item in notifications" :key="item.title" class="notification-item" :class="{ unread: !item.read }"><el-icon><Bell /></el-icon><div><strong>{{ item.title }}</strong><span>{{ item.content }}</span></div></div>
      <template #footer><el-button @click="markNotificationsRead">全部标为已读</el-button></template>
    </el-dialog>

    <el-dialog v-model="helpVisible" title="SmartDoc 使用帮助" width="460px">
      <p>1. 点击 “+” 上传文档，系统会保存到文档库。</p>
      <p>2. 在输入框中描述任务，Agent 会调用已配置的工具完成处理。</p>
      <p>3. 在“知识库”中可对已索引的文档进行 RAG 问答。</p>
      <p>提示：使用真实服务前需要先登录。</p>
    </el-dialog>
  </div>
</template>
<script setup>
import { computed, nextTick, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { STORAGE_KEYS } from '../../constants'
import { aiApi } from '../../api/ai'
import { fileApi } from '../../api/file'
import { docApi } from '../../api/document'

const router = useRouter()
const activeNav = ref('workbench')
const task = ref('')
const latestTask = ref('')
const taskInput = ref(null)
const fileInput = ref(null)
const selectedFile = ref(null)
const uploading = ref(false)
const submitting = ref(false)
const notificationsVisible = ref(false)
const helpVisible = ref(false)
const ragQuestion = ref('')
const ragAnswer = ref('')
const ragLoading = ref(false)
const notifications = ref([
  { title: '文档索引已完成', content: 'RAG 知识库方案评审纪要已可用于问答。', read: false },
  { title: 'Agent 任务可查看', content: '你可以在 Agent 工作台查看最近的执行时间线。', read: false }
])

const navItems = [
  { key: 'workbench', label: '我的工作台', icon: 'Monitor' },
  { key: 'library', label: '云端文档库', icon: 'FolderOpened' },
  { key: 'agent', label: 'AI Agent', icon: 'Cpu' },
  { key: 'knowledge', label: '知识库', icon: 'Collection' },
  { key: 'aiops', label: '运行监控', icon: 'DataLine' }
]

const prompts = [
  { key: 'upload', label: '上传文档', icon: 'Upload' },
  { key: 'ppt', label: '生成 PPT', value: '根据已上传文档生成一份项目汇报 PPT', icon: 'Monitor' },
  { key: 'chat', label: '开始对话', value: '基于我的知识库回答问题', icon: 'ChatLineSquare' }
]

const stats = [
  { key: 'documents', label: '云端文档', value: '128', icon: 'Document', tone: 'lavender' },
  { key: 'agent', label: 'AI 已分析', value: '86', icon: 'MagicStick', tone: 'green' },
  { key: 'tasks', label: '待处理任务', value: '07', icon: 'Clock', tone: 'pink' },
  { key: 'knowledge', label: '知识库', value: '12', icon: 'Collection', tone: 'sand' }
]

const documents = [
  { name: 'SmartDoc 项目需求说明书', meta: '今天 10:24 · 1.8 MB', status: '已分析', color: 'lavender' },
  { name: 'RAG 知识库方案评审纪要', meta: '昨天 16:40 · 860 KB', status: '已分析', color: 'pink' },
  { name: '七月运营周报', meta: '2026-07-29 · 420 KB', status: '待处理', color: 'green' }
]

const activeNavLabel = computed(() => navItems.find(item => item.key === activeNav.value)?.label || '我的工作台')
const pageTitle = computed(() => ({ workbench: '我的工作台', library: '云端文档库', agent: 'AI Agent', knowledge: '知识库问答', aiops: '运行监控' }[activeNav.value] || '我的工作台'))
const currentUserName = computed(() => {
  try {
    const user = JSON.parse(localStorage.getItem(STORAGE_KEYS.USER_INFO) || '{}')
    return user.username || user.nickname || user.name || 'Jendeubang'
  } catch {
    return 'Jendeubang'
  }
})

function ensureLogin() {
  if (localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN)) return true
  ElMessage.warning('请先登录后再使用真实服务')
  router.push('/login')
  return false
}

function handleNav(key) {
  activeNav.value = key
  if (key === 'knowledge') nextTick(() => document.querySelector('.knowledge-form input')?.focus())
}

function handlePrompt(prompt) {
  if (prompt.key === 'upload') return openFilePicker()
  task.value = prompt.value
  nextTick(() => taskInput.value?.focus())
}

async function submitTask() {
  const content = task.value.trim()
  if (!content || !ensureLogin()) return

  submitting.value = true
  latestTask.value = content
  try {
    const response = await aiApi.executeAgent({ task: content, context: {} })
    const result = response?.data || response || {}
    latestTask.value = result.finalAnswer || result.answer || result.message || content
    task.value = ''
    ElMessage.success('Agent 任务已完成')
  } catch (error) {
    latestTask.value = content
    console.error('Agent task failed', error)
  } finally {
    submitting.value = false
  }
}

function openFilePicker() {
  if (!ensureLogin()) return
  fileInput.value?.click()
}

async function onFileSelected(event) {
  const rawFile = event.target.files?.[0]
  if (!rawFile) return

  selectedFile.value = rawFile
  uploading.value = true
  try {
    const fileRes = await fileApi.upload(rawFile)
    const fileId = fileRes?.data?.fileId || fileRes?.data?.id
    if (!fileId) throw new Error('File upload did not return a fileId')

    await docApi.createDoc({ title: rawFile.name, fileId, category: 'default' })
    latestTask.value = `已上传「${rawFile.name}」，可以继续告诉 AI 你想如何处理它。`
    notifications.value.unshift({ title: '文档上传成功', content: `「${rawFile.name}」已保存到云端文档库。`, read: false })
    ElMessage.success(`「${rawFile.name}」已上传到云端文档库`)
  } catch (error) {
    selectedFile.value = null
    console.error('File upload failed', error)
  } finally {
    uploading.value = false
    event.target.value = ''
  }
}

async function askKnowledge() {
  const question = ragQuestion.value.trim()
  if (!question || !ensureLogin()) return

  ragLoading.value = true
  ragAnswer.value = ''
  try {
    const response = await aiApi.ragQuery(question)
    const result = response?.data || response || {}
    ragAnswer.value = result.answer || '知识库中暂未找到可用答案。'
  } catch (error) {
    console.error('RAG query failed', error)
  } finally {
    ragLoading.value = false
  }
}

function handleStatClick(stat) {
  if (stat.key === 'documents') return handleNav('library')
  if (stat.key === 'knowledge') return handleNav('knowledge')
  if (stat.key === 'agent' || stat.key === 'tasks') return goToAgentWorkbench()
}

function handleDocumentOpen(doc) {
  if (doc.id) return router.push(`/editor/${doc.id}`)
  ElMessage.info('示例文档将在云端文档库中打开')
  goToDocuments()
}

function goToDocuments() {
  router.push('/dashboard')
}

function goToAgentWorkbench() {
  router.push('/agent-workbench')
}

function goToAIOps() {
  router.push('/aiops')
}

function markNotificationsRead() {
  notifications.value = notifications.value.map(item => ({ ...item, read: true }))
  ElMessage.success('通知已全部标为已读')
}

async function handleUserMenu() {
  if (!localStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN)) {
    router.push('/login')
    return
  }
  try {
    await ElMessageBox.confirm('确定要退出当前账号吗？', '退出登录', { confirmButtonText: '退出', cancelButtonText: '取消', type: 'warning' })
    localStorage.removeItem(STORAGE_KEYS.ACCESS_TOKEN)
    localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN)
    localStorage.removeItem(STORAGE_KEYS.USER_INFO)
    router.push('/login')
  } catch {
    // User cancelled the dialog.
  }
}

function formatFileSize(size) {
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
</script>
<style scoped>
.preview-shell { min-height: 100vh; display: grid; grid-template-columns: 238px minmax(0, 1fr); background: var(--sd-bg); color: var(--sd-text); }
.preview-aside { min-height: 100vh; display: flex; flex-direction: column; justify-content: space-between; padding: 28px 16px 18px; background: rgba(255, 253, 249, .92); border-right: 1px solid var(--sd-border); }
.preview-brand { display: flex; align-items: center; gap: 11px; padding: 0 10px 28px; color: var(--sd-lavender-deep); font-size: 19px; font-weight: 750; }
.preview-logo { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 12px; background: var(--sd-lavender); color: #fffdf9; box-shadow: 0 8px 18px rgba(172,160,206,.28); }
.preview-label { margin: 0 10px 10px; color: #a39bac; font-size: 10px; font-weight: 700; letter-spacing: .14em; }
.preview-nav { width: 100%; display: flex; align-items: center; gap: 11px; margin: 4px 0; padding: 11px 12px; border: 0; border-radius: 12px; background: transparent; color: var(--sd-text-muted); font: inherit; font-size: 14px; text-align: left; cursor: pointer; transition: .18s ease; }
.preview-nav:hover { background: #f3edf1; color: var(--sd-lavender-deep); }
.preview-nav.active { background: #eee8f5; color: var(--sd-lavender-deep); font-weight: 700; }
.preview-user { display: flex; align-items: center; gap: 10px; padding: 10px; border-radius: 14px; background: #f7f3ed; }
.preview-user .el-avatar { background: var(--sd-pink); color: var(--sd-lavender-deep); font-weight: 700; }.preview-user div { flex: 1; min-width: 0; }.preview-user strong,.preview-user span { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.preview-user strong { font-size:13px; }.preview-user span { margin-top:3px; color:var(--sd-text-muted); font-size:11px; }
.preview-main { padding: 30px clamp(24px, 5vw, 72px) 50px; overflow: auto; }.preview-header { display:flex; align-items:center; justify-content:space-between; margin:0 auto 26px; max-width:1280px; }.preview-header h1 { margin:5px 0 0; font-size:22px; line-height:1.2; }.preview-crumb,.eyebrow { color:var(--sd-text-muted); font-size:11px; font-weight:700; letter-spacing:.12em; }.preview-header-actions { display:flex; align-items:center; gap:6px; }
.preview-hero,.library-intro { max-width:1280px; margin:0 auto; padding:40px; border:1px solid var(--sd-border); border-radius:24px; background:#fffdf9; box-shadow:var(--sd-shadow); }.preview-hero h2,.library-intro h2 { margin:9px 0 10px; color:var(--sd-text); font-size:clamp(28px,4vw,42px); line-height:1.18; letter-spacing:-.03em; }.hero-copy,.library-intro p:not(.eyebrow) { margin:0; color:var(--sd-text-muted); font-size:15px; }.task-box { display:flex; align-items:center; gap:12px; max-width:780px; height:62px; margin-top:30px; padding:0 10px 0 18px; border:1px solid var(--sd-border); border-radius:18px; background:#fff; transition:.18s ease; }.task-box:focus-within { border-color:var(--sd-lavender); box-shadow:0 0 0 4px rgba(172,160,206,.16); }.task-box input { min-width:0; flex:1; border:0; outline:0; background:transparent; color:var(--sd-text); font:inherit; font-size:15px; }.task-box input::placeholder { color:#aaa3b1; }.task-plus { color:var(--sd-lavender-deep); font-size:19px; }.task-send { --el-button-bg-color:var(--sd-lavender-deep); --el-button-border-color:var(--sd-lavender-deep); --el-button-hover-bg-color:#71678b; --el-button-hover-border-color:#71678b; }.prompt-row { display:flex; flex-wrap:wrap; gap:9px; margin-top:16px; }.prompt-row button { display:flex; gap:7px; align-items:center; padding:8px 12px; border:1px solid var(--sd-border); border-radius:999px; background:#fffdf9; color:var(--sd-text-muted); font:inherit; font-size:13px; cursor:pointer; }.prompt-row button:hover { background:#eee8f5; color:var(--sd-lavender-deep); }
.library-intro { display:flex; align-items:center; justify-content:space-between; }.stats-grid { display:grid; grid-template-columns:repeat(4,1fr); gap:14px; max-width:1280px; margin:22px auto; }.stat-item { display:flex; align-items:center; gap:14px; padding:18px; border:1px solid var(--sd-border); border-radius:16px; background:rgba(255,253,249,.82); }.stat-item>.el-icon { display:grid; place-items:center; width:39px; height:39px; border-radius:12px; font-size:19px; }.stat-item strong,.stat-item span { display:block; }.stat-item strong { font-size:23px; line-height:1; }.stat-item span { margin-top:5px; color:var(--sd-text-muted); font-size:12px; }.stat-item.lavender>.el-icon { background:#eee8f5; color:#84789f; }.stat-item.green>.el-icon { background:#e5f0e3; color:#638570; }.stat-item.pink>.el-icon { background:#f2e7eb; color:#a36e7b; }.stat-item.sand>.el-icon { background:#f2eadc; color:#a58252; }
.content-grid { display:grid; grid-template-columns:minmax(0,1.25fr) minmax(320px,.75fr); gap:18px; max-width:1280px; margin:0 auto; }.surface { padding:24px; border:1px solid var(--sd-border); border-radius:20px; background:#fffdf9; }.surface-title { display:flex; align-items:flex-start; justify-content:space-between; margin-bottom:14px; }.surface-title h3 { margin:5px 0 0; font-size:18px; }.surface-title .el-button { color:var(--sd-lavender-deep); }.doc-row { width:100%; display:grid; grid-template-columns:38px minmax(0,1fr) auto 20px; align-items:center; gap:11px; padding:12px 0; border:0; border-top:1px solid #f0eaed; background:transparent; color:inherit; text-align:left; cursor:pointer; }.doc-row:first-of-type { border-top:0; }.doc-icon { display:grid; place-items:center; width:36px; height:36px; border-radius:10px; }.doc-icon.lavender { background:#eee8f5; color:#84789f; }.doc-icon.pink { background:#f2e7eb; color:#a36e7b; }.doc-icon.green { background:#e5f0e3; color:#638570; }.doc-name { min-width:0; }.doc-name strong,.doc-name small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.doc-name strong { font-size:13px; }.doc-name small { margin-top:4px; color:var(--sd-text-muted); font-size:11px; }.row-arrow { color:#b5adbb; }.activity-surface { background:#f7f3ed; }.live-dot { display:flex; align-items:center; gap:6px; color:#638570; font-size:12px; }.live-dot::before { content:''; width:7px; height:7px; border-radius:50%; background:#84a896; }.activity-item { display:flex; gap:11px; padding:14px 0; border-top:1px solid #ebe3e4; }.activity-mark { display:grid; flex:0 0 auto; place-items:center; width:34px; height:34px; border-radius:10px; }.activity-mark.lavender { background:#e8e0f1; color:#84789f; }.activity-mark.pink { background:#f0dfe5; color:#a36e7b; }.activity-item div { flex:1; }.activity-item strong,.activity-item small { display:block; }.activity-item strong { font-size:13px; }.activity-item small { margin:4px 0 8px; color:var(--sd-text-muted); font-size:11px; }.timeline-link { display:flex; align-items:center; gap:6px; margin-top:4px; padding:0; border:0; background:transparent; color:var(--sd-lavender-deep); font:inherit; font-size:13px; cursor:pointer; }
@media (max-width:960px) { .preview-shell { grid-template-columns:76px minmax(0,1fr); }.preview-brand span,.preview-label,.preview-nav span,.preview-user div,.preview-user>.el-icon { display:none; }.preview-brand { justify-content:center; padding:0 0 28px; }.preview-nav { justify-content:center; padding:13px; }.preview-user { justify-content:center; padding:8px; }.stats-grid { grid-template-columns:repeat(2,1fr); }.content-grid { grid-template-columns:1fr; } }
@media (max-width:640px) { .preview-shell { grid-template-columns:1fr; }.preview-aside { display:none; }.preview-main { padding:22px 16px 34px; }.preview-header { align-items:flex-start; }.preview-header-actions .el-button:last-child { display:none; }.preview-hero,.library-intro { padding:26px 20px; }.preview-hero h2,.library-intro h2 { font-size:29px; }.library-intro { align-items:flex-start; gap:16px; flex-direction:column; }.stats-grid { gap:10px; }.stat-item { padding:14px; }.surface { padding:18px; }.doc-row { grid-template-columns:34px minmax(0,1fr) 20px; }.doc-row .el-tag { display:none; } }

.task-send {
  width: 42px !important;
  min-width: 42px !important;
  height: 42px !important;
  padding: 0 !important;
  border: 1px solid #74698e !important;
  background: #74698e !important;
  color: #fff !important;
  box-shadow: 0 6px 14px rgba(116, 105, 142, 0.3) !important;
}
.task-send:hover,
.task-send:focus-visible {
  border-color: #615778 !important;
  background: #615778 !important;
  color: #fff !important;
  box-shadow: 0 0 0 4px rgba(172, 160, 206, 0.28), 0 7px 16px rgba(116, 105, 142, 0.32) !important;
}
.task-send :deep(.el-icon),
.task-send :deep(.el-icon svg) {
  color: #fff !important;
  fill: currentColor;
}
.task-box { padding-left: 12px; }
.task-attach { display: grid; flex: 0 0 auto; place-items: center; width: 34px; height: 34px; padding: 0; border: 0; border-radius: 10px; background: transparent; color: var(--sd-lavender-deep); cursor: pointer; transition: .18s ease; }
.task-attach:hover, .task-attach:focus-visible { background: #eee8f5; color: #615778; outline: none; }
.task-attach .el-icon { font-size: 20px; }
.hidden-file-input { display: none; }
.attachment-chip { display: flex; align-items: center; gap: 7px; width: max-content; max-width: 100%; margin-top: 10px; padding: 7px 10px; border: 1px solid #e1d8e7; border-radius: 10px; background: #f7f3fb; color: var(--sd-lavender-deep); font-size: 12px; }
.attachment-chip > span:not(.attachment-state) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.attachment-chip small { flex: 0 0 auto; color: var(--sd-text-muted); }
.attachment-state { flex: 0 0 auto; color: #638570; }

.preview-user { cursor: pointer; }
.preview-user:focus-visible { outline: 3px solid rgba(172,160,206,.45); outline-offset: 2px; }
.stat-item { cursor: pointer; transition: transform .18s ease, box-shadow .18s ease; }
.stat-item:hover { transform: translateY(-2px); box-shadow: 0 10px 20px rgba(78,65,92,.08); }
.knowledge-surface, .feature-surface { max-width: 1280px; margin: 0 auto 22px; padding: 32px; border: 1px solid var(--sd-border); border-radius: 24px; background: #fffdf9; box-shadow: var(--sd-shadow); }
.knowledge-form { display: flex; gap: 10px; margin-top: 22px; }
.knowledge-form .el-input { flex: 1; }
.knowledge-answer { margin-top: 18px; padding: 18px; border: 1px solid #e1d8e7; border-radius: 16px; background: #f7f3fb; color: var(--sd-text); line-height: 1.75; white-space: pre-wrap; }
.feature-surface { display: flex; align-items: center; justify-content: space-between; gap: 28px; }
.feature-surface h2 { margin: 8px 0; font-size: 28px; }
.feature-surface p:not(.eyebrow) { margin: 0; color: var(--sd-text-muted); }
.notification-item { display: flex; gap: 10px; padding: 13px 0; border-bottom: 1px solid var(--sd-border); }
.notification-item:last-child { border-bottom: 0; }
.notification-item.unread strong::after { content: ''; display: inline-block; width: 6px; height: 6px; margin-left: 6px; border-radius: 50%; background: #84789f; vertical-align: middle; }
.notification-item span { display: block; margin-top: 3px; color: var(--sd-text-muted); font-size: 12px; }
@media (max-width: 640px) { .knowledge-surface, .feature-surface { padding: 24px 20px; } .knowledge-form { flex-direction: column; } .feature-surface { align-items: flex-start; flex-direction: column; gap: 18px; } }
</style>
