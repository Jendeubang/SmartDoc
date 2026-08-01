<template>
  <div class="toolbox-page" v-loading="pageLoading">
    <header class="toolbox-header">
      <div>
        <button class="back-link" @click="router.push('/dashboard')"><el-icon><ArrowLeft /></el-icon> 返回工作台</button>
        <div class="title-row"><span class="title-mark"><el-icon><MagicStick /></el-icon></span><div><h1>文档处理工具箱</h1><p>用 AI 和实用工具，让每一份文档更清晰、更可靠、更易用。</p></div></div>
      </div>
      <div class="header-actions">
        <el-button plain @click="scrollToProductivity"><el-icon><Grid /></el-icon> 生产力中心</el-button><el-button plain @click="fetchDocuments"><el-icon><Refresh /></el-icon> 刷新文档</el-button>
        <el-button type="primary" @click="router.push('/editor/chat-mode')"><el-icon><ChatDotRound /></el-icon> SmartDoc AI 对话</el-button>
      </div>
    </header>

    <section class="toolbox-hero">
      <div class="hero-copy"><span class="eyebrow">SMART DOCUMENT WORKSPACE</span><h2>从内容优化，到交付输出</h2><p>选择一份已有文档后，可直接保存处理结果；未选择时也可以把文本粘贴进来试用。</p></div>
      <div class="hero-stats"><div><strong>{{ documents.length }}</strong><span>可用文档</span></div><div><strong>{{ completedCount }}</strong><span>已执行操作</span></div><div><strong>12</strong><span>工具能力</span></div></div>
    </section>

    <section class="workspace-grid">
      <aside class="document-pane">
        <div class="pane-heading"><div><span class="pane-kicker">01 / 选择内容</span><h3>当前文档</h3></div><el-button text @click="clearSelection" v-if="selectedDocumentId">清除</el-button></div>
        <el-select v-model="selectedDocumentId" filterable placeholder="选择已有文档" class="document-select" @change="loadSelectedDocument">
          <el-option v-for="doc in documents" :key="doc.id" :label="doc.title || doc.name || '未命名文档'" :value="String(doc.id)"><div class="option-row"><span>{{ doc.title || doc.name || '未命名文档' }}</span><small>{{ formatDate(doc.updateTime || doc.createdAt) }}</small></div></el-option>
        </el-select>
        <div class="doc-preview" :class="{ empty: !workingContent }">
          <template v-if="workingContent"><div class="preview-label">可处理文本</div><p>{{ workingContent }}</p></template>
          <template v-else><el-icon :size="28"><Document /></el-icon><strong>尚未选择可编辑文档</strong><span>可选择文档，或直接粘贴文本开始处理。</span></template>
        </div>
        <el-input v-model="workingContent" type="textarea" :rows="9" resize="none" placeholder="在这里粘贴或编辑待处理的文本…" class="content-input" />
        <div class="document-actions"><el-button @click="loadSelectedDocument" :disabled="!selectedDocumentId">重新读取</el-button><el-button type="primary" :loading="saving" @click="saveToDocument" :disabled="!selectedDocumentId || !workingContent"><el-icon><FolderChecked /></el-icon> 保存为新版本</el-button></div>
      </aside>

      <main class="tools-pane">
        <div class="pane-heading"><div><span class="pane-kicker">02 / 选择能力</span><h3>工具矩阵</h3></div><span class="selection-hint">{{ selectedTool ? selectedTool.name : '请选择一个工具' }}</span></div>
        <div class="tool-groups">
          <section v-for="group in toolGroups" :key="group.name" class="tool-group"><div class="group-label"><span :style="{ background: group.color }"></span>{{ group.name }}</div><div class="tool-card-grid"><button v-for="tool in group.items" :key="tool.id" class="tool-card" :class="{ active: activeToolId === tool.id }" @click="tool.id === 'quiz' ? router.push('/quiz') : activeToolId = tool.id"><span class="tool-icon" :style="{ background: tool.tint, color: tool.color }"><el-icon><component :is="tool.icon" /></el-icon></span><span class="tool-card-copy"><strong>{{ tool.name }}</strong><small>{{ tool.description }}</small></span><el-icon class="tool-arrow"><ArrowRight /></el-icon><em v-if="tool.mode === 'planned'">待接入</em></button></div></section>
        </div>
      </main>

      <aside class="operation-pane">
        <div class="pane-heading"><div><span class="pane-kicker">03 / 执行处理</span><h3>{{ selectedTool?.name || '选择工具' }}</h3></div></div>
        <template v-if="selectedTool">
          <p class="operation-description">{{ selectedTool.detail }}</p>
          <div v-if="selectedTool.id === 'quiz'" class="quiz-workbench">
            <input ref="quizFileInput" class="hidden-file-input" type="file" accept=".docx,.pdf,.txt,.md,.markdown,.html" @change="onQuizFileSelected" />
            <div class="quiz-source-actions"><el-button type="primary" :loading="quizUploading" @click="quizFileInput?.click()"><el-icon><UploadFilled /></el-icon> 上传题库文档</el-button><el-button :loading="quizBuilding" :disabled="!selectedDocumentId" @click="buildQuizFromSelectedDocument">从当前文档提取</el-button></div>
            <p class="helper-text">题库会保存到云端文档库。优先识别“题目 / 答案”格式；未检测到时用 DeepSeek 从正文生成练习题。</p>
            <div v-if="quizQuestions.length" class="quiz-card">
              <div class="quiz-progress"><span>{{ quizSource || '当前题库' }}</span><strong>{{ quizIndex + 1 }} / {{ quizQuestions.length }}</strong></div>
              <p class="quiz-question">{{ quizQuestions[quizIndex]?.question }}</p>
              <el-input v-model="quizAnswer" type="textarea" :rows="3" :disabled="Boolean(quizFeedback)" placeholder="输入你的答案，按 Enter 可提交" @keyup.ctrl.enter="submitQuizAnswer" />
              <div v-if="quizFeedback" class="quiz-feedback" :class="{ correct: quizFeedback.correct }"><strong>{{ quizFeedback.correct ? '回答正确' : '继续加油' }}</strong><p>{{ quizFeedback.message }}</p><p v-if="!quizFeedback.correct"><b>参考答案：</b>{{ quizQuestions[quizIndex]?.answer }}</p></div>
              <div class="quiz-actions"><span>答对 {{ quizScore }} 题</span><el-button v-if="!quizFeedback" type="primary" :loading="quizBuilding" @click="submitQuizAnswer">提交答案</el-button><el-button v-else type="primary" @click="nextQuizQuestion">{{ quizIndex + 1 === quizQuestions.length ? '完成练习' : '下一题' }}</el-button></div>
            </div>
            <div v-else class="quiz-empty"><el-icon><Reading /></el-icon><span>上传一份含题目、答案的文档，开始练习。</span></div>
          </div>
          <div v-else-if="selectedTool.id === 'format'" class="setting-block"><label>排版预设</label><el-radio-group v-model="formatPreset" class="preset-group"><el-radio-button label="formal">正式报告</el-radio-button><el-radio-button label="clear">清晰阅读</el-radio-button><el-radio-button label="compact">紧凑笔记</el-radio-button></el-radio-group><p class="helper-text">会统一标题、段落间距与项目列表格式，不改变原有文字内容。</p></div>
          <div v-else-if="selectedTool.id === 'compare'" class="setting-block"><label>对比对象</label><el-select v-model="compareDocumentId" filterable placeholder="选择第二份文档" class="document-select"><el-option v-for="doc in compareCandidates" :key="doc.id" :label="doc.title || doc.name" :value="String(doc.id)" /></el-select><p class="helper-text">按段落计算新增和删除内容，并生成可读摘要。</p></div>
          <div v-else-if="selectedTool.id === 'pdf'" class="setting-block"><label>PDF 操作</label><el-radio-group v-model="pdfOperation" class="preset-group"><el-radio-button label="split">拆分页面</el-radio-button><el-radio-button label="merge">合并两份 PDF</el-radio-button></el-radio-group><template v-if="pdfOperation === 'split'"><el-input v-model="pdfPages" class="page-input" placeholder="页码，例如 1-3,5；留空则导出全部页" /><p class="helper-text">填写要保留的页码或页码范围，留空时会生成一份完整副本。</p></template><template v-else><el-select v-model="compareDocumentId" filterable placeholder="选择第二份 PDF 文档" class="document-select"><el-option v-for="doc in compareCandidates" :key="doc.id" :label="doc.title || doc.name" :value="String(doc.id)" /></el-select><p class="helper-text">将当前 PDF 与第二份 PDF 按顺序合并为一个文件。</p></template></div>
          <div v-else-if="selectedTool.id === 'convert'" class="setting-block"><label>导出格式</label><el-radio-group v-model="exportFormat" class="preset-group"><el-radio-button label="txt">TXT</el-radio-button><el-radio-button label="md">Markdown</el-radio-button><el-radio-button label="pdf">PDF</el-radio-button><el-radio-button label="docx">Word</el-radio-button></el-radio-group><p class="helper-text">TXT 与 Markdown 可直接在浏览器生成下载；Word、PDF 转换需要后端文件转换服务。</p></div>
          <div v-else-if="selectedTool.id === 'ppt'" class="setting-block"><label>PPT 主题</label><el-select v-model="pptTheme" class="document-select"><el-option label="莫兰迪浅色" value="morandi" /><el-option label="商务蓝" value="business" /><el-option label="极简白" value="minimal" /></el-select><p class="helper-text">调用现有 HTML PPT Skill，根据正文提炼演示大纲。</p></div>
          <div v-else-if="selectedTool.mode === 'planned'" class="planned-state"><span class="planned-icon"><el-icon><Timer /></el-icon></span><strong>文件处理服务待接入</strong><p>当前项目尚未提供 {{ selectedTool.name }} 的后端处理接口。已保留统一入口与文件上下文，接入 OCR / PDF / 表格服务后即可启用。</p></div>
          <div v-else class="setting-block"><label>处理范围</label><div class="scope-card"><el-icon><DocumentChecked /></el-icon><span>{{ selectedDocumentId ? '当前选中文档（可保存为新版本）' : '当前文本草稿（不会自动覆盖文档）' }}</span></div><p class="helper-text">{{ selectedTool.tip }}</p></div>
          <el-button v-if="selectedTool.id !== 'quiz'" class="run-button" type="primary" :loading="running" :disabled="selectedTool.mode === 'planned' || (!workingContent && !['ocr', 'pdf', 'table', 'convert', 'quiz'].includes(selectedTool.id))" @click="runTool"><el-icon><MagicStick /></el-icon>{{ selectedTool.mode === 'planned' ? '等待服务接入' : `执行${selectedTool.name}` }}</el-button>
        </template>
        <div v-else class="empty-operation"><el-icon :size="32"><Operation /></el-icon><p>从中间选择一个工具开始。</p></div>
        <div class="result-panel" v-if="result"><div class="result-heading"><span>处理结果</span><el-button text size="small" @click="result = ''">清除</el-button></div><pre>{{ result }}</pre><div class="result-actions" v-if="resultCanApply"><el-button size="small" @click="copyResult">复制结果</el-button><el-button size="small" type="primary" @click="applyResult">应用到编辑区</el-button></div></div>
      </aside>
    </section>

    <div ref="productivityRef">
    <AdvancedWorkspace :current-content="workingContent" @apply="value => workingContent = value" />
    </div>

    <section class="capability-note"><el-icon><InfoFilled /></el-icon><span><strong>已可执行：</strong>智能清洗、排版预设、AI 校对、文档对比、摘要、关键词、敏感信息脱敏、TXT / Markdown 导出、HTML PPT 生成。<strong>已接入：</strong>OCR、PDF 拆分合并、DOCX ↔ PDF 格式转换、Word 表格提取 Excel。</span></section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { docApi } from '../../api/document'
import { aiApi } from '../../api/ai'
import { fileApi } from '../../api/file'
import AdvancedWorkspace from './AdvancedWorkspace.vue'

const router = useRouter()
const pageLoading = ref(false)
const saving = ref(false)
const running = ref(false)
const documents = ref([])
const selectedDocumentId = ref('')
const compareDocumentId = ref('')
const workingContent = ref('')
const activeToolId = ref('clean')
const formatPreset = ref('formal')
const exportFormat = ref('txt')
const pdfOperation = ref('split')
const pdfPages = ref('')
const quizFileInput = ref(null)
const quizUploading = ref(false)
const quizBuilding = ref(false)
const quizQuestions = ref([])
const quizIndex = ref(0)
const quizAnswer = ref('')
const quizFeedback = ref(null)
const quizSource = ref('')
const quizScore = ref(0)
const pptTheme = ref('morandi')
const result = ref('')
const resultCanApply = ref(false)
const operationCount = ref(Number(localStorage.getItem('smartdoc_toolbox_operations') || 0))
const productivityRef = ref(null)

const toolGroups = [
  { name: '内容优化', color: '#ACA0CE', items: [
    { id: 'format', name: '智能排版', description: '统一标题与段落', detail: '为正文套用更整洁的排版结构，适合报告、方案和会议纪要。', tip: '处理结果可先预览，再保存为文档新版本。', icon: 'Brush', color: '#7B6A9C', tint: '#EEEAF6' },
    { id: 'clean', name: '文本清洗', description: '空行、空格与重复段落', detail: '自动清理多余空白、重复段落和常见全半角问题，让文本回到清爽状态。', tip: '清洗规则只处理格式噪音，不会主动改写内容。', icon: 'Delete', color: '#5F8C80', tint: '#E5F0EA' },
    { id: 'proofread', name: 'AI 校对', description: '错别字、语病与标点', detail: '使用当前配置的大模型给出校对后的文本，并保持原意和语气。', tip: '建议先阅读 AI 结果，再应用到编辑区。', icon: 'CircleCheck', color: '#B87869', tint: '#F8E9E4' },
    { id: 'compare', name: '文档对比', description: '查看两份文档差异', detail: '按段落标出新增和删除内容，同时统计两份文档的相似部分。', tip: '需要先选择当前文档或粘贴文本，再选择第二份文档。', icon: 'Connection', color: '#6585A8', tint: '#E7EEF7' }
  ]},
  { name: 'AI 生成', color: '#DFCED6', items: [
    { id: 'summary', name: '智能摘要', description: '提炼核心结论', detail: '基于正文生成短摘要，适合快速浏览和对外同步。', tip: '摘要会显示在结果区，可复制或应用到编辑区。', icon: 'DocumentCopy', color: '#A16F87', tint: '#F7E9EF' },
    { id: 'keywords', name: '关键词提取', description: '识别主题标签', detail: '从正文提取最关键的主题词，可直接用于文档标签或汇报标题。', tip: '默认生成 8 个关键词。', icon: 'CollectionTag', color: '#A16F87', tint: '#F7E9EF' },
    { id: 'ppt', name: '一键生成 PPT', description: '生成可预览演示稿', detail: '调用平台现有 PPT Skill，为文档生成可直接预览的 HTML 演示稿。', tip: '生成后将在新页面打开演示稿预览。', icon: 'Monitor', color: '#A16F87', tint: '#F7E9EF' },
    { id: 'mindmap', name: '思维导图', description: 'DeepSeek 结构化导图', detail: '通过 DeepSeek 提炼内容层级并生成 Mermaid 思维导图。', tip: '结果可直接复制到支持 Mermaid 的 Markdown 文档。', icon: 'Share', color: '#A16F87', tint: '#F7E9EF' },
    { id: 'quiz', name: '智能练题', description: '上传题库，提取题目与答案', detail: '上传含题目和答案的文档，自动提取题目；没有标准题目格式时，将由 AI 根据内容生成练习题。', tip: '支持 DOCX、PDF、TXT、Markdown；扫描 PDF 请先使用 OCR。', icon: 'Reading', color: '#A16F87', tint: '#F7E9EF' }
  ]},
  { name: '安全与交付', color: '#EDF4E2', items: [
    { id: 'mask', name: '敏感信息脱敏', description: '手机号、邮箱、身份证', detail: '在浏览器本地对常见手机号、邮箱和身份证号进行掩码处理，便于安全分享。', tip: '仅处理常见规则，不代替企业级数据安全审查。', icon: 'Lock', color: '#6D8A64', tint: '#EAF2E5' },
    { id: 'convert', name: '格式导出', description: 'TXT / Markdown / PDF / Word', detail: '将当前文本导出为通用的 TXT 或 Markdown 文件。', tip: '直接下载不上传内容；其他格式转换待后端文件服务接入。', icon: 'Download', color: '#6D8A64', tint: '#EAF2E5' },
    { id: 'pdf', name: 'PDF 工具', description: '拆分、合并与提取页面', detail: '选择页码可拆分 PDF；另选一份文档则合并两份 PDF。', tip: '任务由 RabbitMQ 异步执行，完成后直接下载。', icon: 'Files', color: '#6D8A64', tint: '#EAF2E5' },
    { id: 'ocr', name: 'OCR 识别', description: '图片转可编辑文本', detail: '识别已上传图片中的中英文文字，并生成 TXT 结果。', tip: '任务完成后可下载识别文本。', icon: 'Picture', color: '#6D8A64', tint: '#EAF2E5' },
    { id: 'table', name: '表格提取', description: 'Word 表格导出 Excel', detail: '提取 DOCX 文档内的表格，生成可编辑 Excel 文件。', tip: '任务完成后可下载 XLSX 文件。', icon: 'Grid', color: '#6D8A64', tint: '#EAF2E5' }
  ]}
]

const selectedTool = computed(() => toolGroups.flatMap(group => group.items).find(tool => tool.id === activeToolId.value))
const compareCandidates = computed(() => documents.value.filter(doc => String(doc.id) !== selectedDocumentId.value))
const completedCount = computed(() => operationCount.value)
const currentQuiz = computed(() => quizQuestions.value[quizIndex.value])

function scrollToProductivity() { productivityRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }
function normalizeQuizText(value) { return String(value || '').toLowerCase().replace(/[\s\p{P}\p{S}]/gu, '') }
function extractQuizPairs(content) {
  const questions = []
  const marked = /(?:^|\n)\s*(?:题目|问题)\s*[:：]\s*([\s\S]*?)\s*\n\s*(?:参考)?答案\s*[:：]\s*([^\n]+)/g
  let match
  while ((match = marked.exec(content)) !== null) questions.push({ question: match[1].trim(), answer: match[2].trim(), analysis: '答案来自上传文档。' })
  if (questions.length) return questions
  const chunks = content.split(/(?=^\s*(?:第?\d+\s*[\.、．]|[（(]\d+[)）]))/m)
  for (const chunk of chunks) {
    const answer = chunk.match(/(?:参考)?答案\s*[:：]\s*([^\n]+)/)
    if (!answer) continue
    const question = chunk.slice(0, answer.index).replace(/^\s*(?:第?\d+\s*[\.、．]|[（(]\d+[)）])\s*/, '').replace(/^(?:题目|问题)\s*[:：]?\s*/, '').trim()
    if (question.length > 2) questions.push({ question, answer: answer[1].trim(), analysis: '答案来自上传文档。' })
  }
  return questions
}
function parseQuizJson(payload) {
  const text = typeof payload === 'string' ? payload : (payload?.finalAnswer || payload?.answer || payload?.content || JSON.stringify(payload || ''))
  const cleaned = text.replace(/```(?:json)?/gi, '').replace(/```/g, '').trim()
  const first = cleaned.indexOf('['); const last = cleaned.lastIndexOf(']')
  if (first < 0 || last <= first) return []
  try {
    const parsed = JSON.parse(cleaned.slice(first, last + 1))
    return Array.isArray(parsed) ? parsed.map(item => ({ question: String(item.question || item.title || '').trim(), answer: String(item.answer || item.referenceAnswer || '').trim(), analysis: String(item.analysis || item.explanation || '根据文档内容生成。').trim() })).filter(item => item.question && item.answer) : []
  } catch { return [] }
}
function setQuizQuestions(questions, source) {
  quizQuestions.value = questions.slice(0, 12)
  quizIndex.value = 0; quizAnswer.value = ''; quizFeedback.value = null; quizScore.value = 0; quizSource.value = source
  localStorage.setItem(`smartdoc_quiz_${currentUserId() || 'guest'}`, JSON.stringify({ source, questions: quizQuestions.value, createdAt: new Date().toISOString() }))
}
async function buildQuizFromText(content, source) {
  const extracted = extractQuizPairs(content)
  if (extracted.length) {
    setQuizQuestions(extracted, source)
    ElMessage.success(`已从题库提取 ${Math.min(extracted.length, 12)} 道题`)
    return
  }
  quizBuilding.value = true
  try {
    const prompt = `请基于以下文档制作 8 道练习题。只输出 JSON 数组，不要 Markdown；每项格式为 {"question":"题目","answer":"标准答案","analysis":"简短解析"}。题目和答案都必须来自文档，覆盖关键知识点。\n\n文档：\n${content.slice(0, 18000)}`
    const payload = responseData(await aiApi.executeAgent({ task: prompt, context: { source: 'toolbox-quiz', model: 'deepseek-chat' } }))
    const generated = parseQuizJson(payload)
    if (!generated.length) throw new Error('未能从文档生成可用练习题')
    setQuizQuestions(generated, source)
    ElMessage.success(`已根据文档生成 ${generated.length} 道练习题`)
  } finally { quizBuilding.value = false }
}
async function onQuizFileSelected(event) {
  const rawFile = event.target.files?.[0]
  if (!rawFile) return
  quizUploading.value = true
  try {
    const uploaded = responseData(await fileApi.upload(rawFile))
    const fileId = uploaded.fileId || uploaded.id
    if (!fileId) throw new Error('文件上传后未返回文件标识')
    const created = responseData(await docApi.createDoc({ title: rawFile.name, fileId, category: 'quiz' }))
    const documentId = created.id || created.documentId
    if (!documentId) throw new Error('题库文档创建失败')
    await fetchDocuments()
    selectedDocumentId.value = String(documentId)
    const detail = responseData(await docApi.getDocDetail(documentId))
    workingContent.value = detail.content || ''
    if (!workingContent.value.trim()) throw new Error('未提取到可练习的正文；扫描件请先使用 OCR 识别')
    await buildQuizFromText(workingContent.value, rawFile.name)
  } catch (error) { ElMessage.error(error?.message || '题库上传或解析失败') } finally { event.target.value = ''; quizUploading.value = false }
}
async function buildQuizFromSelectedDocument() {
  if (!selectedDocumentId.value) return ElMessage.warning('请先选择一份文档')
  quizBuilding.value = true
  try {
    const detail = responseData(await docApi.getDocDetail(selectedDocumentId.value))
    const content = detail.content || workingContent.value
    if (!content?.trim()) throw new Error('该文档没有可提取正文；扫描件请先使用 OCR')
    workingContent.value = content
    await buildQuizFromText(content, documentTitle(detail))
  } catch (error) { ElMessage.error(error?.message || '题目提取失败') } finally { quizBuilding.value = false }
}
async function submitQuizAnswer() {
  if (!quizAnswer.value.trim() || !currentQuiz.value || quizFeedback.value) return ElMessage.warning('请输入答案后再提交')
  quizBuilding.value = true
  try {
    const expected = currentQuiz.value.answer
    let correct = normalizeQuizText(quizAnswer.value) === normalizeQuizText(expected) || normalizeQuizText(expected).includes(normalizeQuizText(quizAnswer.value))
    let message = currentQuiz.value.analysis || '请对照参考答案巩固知识点。'
    if (!correct) {
      try {
        const prompt = `判断学生答案是否与标准答案语义一致。只输出 JSON：{"correct":true或false,"message":"一句反馈"}。\n题目：${currentQuiz.value.question}\n标准答案：${expected}\n学生答案：${quizAnswer.value}`
        const judged = responseData(await aiApi.executeAgent({ task: prompt, context: { source: 'toolbox-quiz-check', model: 'deepseek-chat' } }))
        const raw = typeof judged === 'string' ? judged : (judged.finalAnswer || judged.answer || judged.content || '')
        const match = String(raw).replace(/```json|```/g, '').match(/\{[\s\S]*\}/)
        if (match) { const json = JSON.parse(match[0]); correct = Boolean(json.correct); message = json.message || message }
      } catch { /* fall back to exact answer comparison */ }
    }
    if (correct) quizScore.value += 1
    quizFeedback.value = { correct, message }
  } finally { quizBuilding.value = false }
}
function nextQuizQuestion() {
  if (quizIndex.value + 1 >= quizQuestions.value.length) { ElMessage.success(`练习完成，答对 ${quizScore.value} / ${quizQuestions.value.length} 题`); quizIndex.value = 0; quizAnswer.value = ''; quizFeedback.value = null; return }
  quizIndex.value += 1; quizAnswer.value = ''; quizFeedback.value = null
}
function responseData(res) { return res?.data ?? res ?? {} }
function currentUserId() { return localStorage.getItem('userId') }
function documentTitle(doc) { return doc?.title || doc?.name || '未命名文档' }
function formatDate(value) { return value ? String(value).replace('T', ' ').slice(0, 16) : '刚刚更新' }
function incrementOperation() { operationCount.value += 1; localStorage.setItem('smartdoc_toolbox_operations', String(operationCount.value)) }

async function fetchDocuments() {
  const userId = currentUserId()
  if (!userId) { router.push('/login'); return }
  pageLoading.value = true
  try {
    const res = await docApi.getUserDocs(userId)
    documents.value = responseData(res) || []
  } catch (error) { documents.value = []; ElMessage.error('读取文档列表失败，请稍后重试') } finally { pageLoading.value = false }
}

async function loadSelectedDocument() {
  if (!selectedDocumentId.value) return
  pageLoading.value = true
  try {
    const detail = responseData(await docApi.getDocDetail(selectedDocumentId.value))
    workingContent.value = detail.content || ''
    if (!workingContent.value) ElMessage.info('该文件尚未提取正文；可直接使用 OCR、PDF 或 Word 表格工具处理原始文件')
  } catch (error) { ElMessage.error('读取文档正文失败') } finally { pageLoading.value = false }
}

function clearSelection() { selectedDocumentId.value = ''; compareDocumentId.value = ''; workingContent.value = ''; result.value = ''; resultCanApply.value = false }

async function saveToDocument() {
  if (!selectedDocumentId.value || !workingContent.value.trim()) return
  saving.value = true
  try {
    const document = documents.value.find(item => String(item.id) === selectedDocumentId.value)
    await docApi.updateDoc(selectedDocumentId.value, { title: documentTitle(document), content: workingContent.value, changeLog: `通过文档工具箱完成${selectedTool.value?.name || '内容更新'}` })
    ElMessage.success('已保存为文档新版本，可在编辑器版本历史中恢复')
    incrementOperation()
  } catch (error) { ElMessage.error('保存失败，请稍后重试') } finally { saving.value = false }
}

function normalizeText(text) {
  const seen = new Set()
  return text.replace(/\r\n/g, '\n').split('\n').map(line => line.replace(/[\u3000]/g, ' ').replace(/[ \t]+/g, ' ').trim()).filter((line, index, list) => {
    if (!line) return index > 0 && Boolean(list[index - 1])
    const key = line.replace(/\s/g, '')
    if (seen.has(key)) return false
    seen.add(key); return true
  }).join('\n').replace(/\n{3,}/g, '\n\n').trim()
}

function applyFormat(text) {
  const cleaned = normalizeText(text)
  const lines = cleaned.split('\n')
  const config = { formal: { title: '正式报告', spacing: '1.75', indent: '　　' }, clear: { title: '清晰阅读', spacing: '1.9', indent: '' }, compact: { title: '紧凑笔记', spacing: '1.35', indent: '' } }[formatPreset.value]
  const body = lines.map((line, index) => {
    if (/^(第[一二三四五六七八九十\d]+[章节]|[一二三四五六七八九十\d]+[、.]|#+\s)/.test(line)) return `## ${line.replace(/^#+\s*/, '')}`
    return index === 0 && line.length < 40 ? `# ${line}` : `${config.indent}${line}`
  }).join('\n\n')
  return `<!-- SmartDoc ${config.title}排版｜行距 ${config.spacing} -->\n${body}`
}

function maskSensitive(text) {
  return text
    .replace(/(?<!\d)1[3-9]\d{9}(?!\d)/g, value => `${value.slice(0, 3)}****${value.slice(-4)}`)
    .replace(/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/g, value => `${value.slice(0, Math.min(2, value.indexOf('@')))}***${value.slice(value.indexOf('@'))}`)
    .replace(/(?<!\d)\d{6}(?:18|19|20)?\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx](?!\d)/g, value => `${value.slice(0, 6)}********${value.slice(-4)}`)
}

function compareText(left, right, leftTitle, rightTitle) {
  const leftLines = normalizeText(left).split('\n').filter(Boolean)
  const rightLines = normalizeText(right).split('\n').filter(Boolean)
  const leftSet = new Set(leftLines); const rightSet = new Set(rightLines)
  const shared = leftLines.filter(line => rightSet.has(line)); const removed = leftLines.filter(line => !rightSet.has(line)); const added = rightLines.filter(line => !leftSet.has(line))
  const similarity = Math.round((shared.length * 2 / Math.max(leftLines.length + rightLines.length, 1)) * 100)
  const show = (items) => items.slice(0, 10).map(item => `• ${item}`).join('\n') || '• 无'
  return `对比完成：${leftTitle} ↔ ${rightTitle}\n\n相似度：${similarity}%\n相同段落：${shared.length} 条｜新增：${added.length} 条｜删除：${removed.length} 条\n\n【${rightTitle} 新增内容】\n${show(added)}\n\n【${leftTitle} 独有内容】\n${show(removed)}\n\n【共同内容】\n${show(shared)}`
}

function downloadText(content, extension) {
  const blob = new Blob([content], { type: 'text/plain;charset=utf-8' })
  const url = URL.createObjectURL(blob); const link = document.createElement('a')
  const title = documents.value.find(doc => String(doc.id) === selectedDocumentId.value)
  link.href = url; link.download = `${documentTitle(title).replace(/[\\/:*?"<>|]/g, '_') || 'SmartDoc文档'}.${extension}`; link.click(); URL.revokeObjectURL(url)
}

async function runFileJob(toolType) {
  if (!selectedDocumentId.value) { ElMessage.warning('请先选择已上传的源文件'); return }
  const ids = [selectedDocumentId.value]
  if (toolType === 'PDF_MERGE') {
    if (!compareDocumentId.value) { ElMessage.warning('请选择第二份 PDF 文档'); return }
    ids.push(compareDocumentId.value)
  }
  const submitted = responseData(await docApi.submitToolboxJob({ toolType, documentIds: ids, pages: toolType === 'PDF_SPLIT' ? pdfPages.value : undefined }))
  result.value = '任务已进入队列，正在处理…'; resultCanApply.value = false
  for (let index = 0; index < 90; index++) {
    await new Promise(resolve => setTimeout(resolve, 1200))
    const job = responseData(await docApi.getToolboxJob(submitted.jobId))
    result.value = `${job.message}\n\n当前进度：${job.progress}%`
    if (job.status === 'SUCCESS') {
      const blob = await docApi.downloadToolboxJob(job.jobId)
      const url = URL.createObjectURL(blob); const link = document.createElement('a')
      link.href = url; link.download = job.fileName; link.click(); URL.revokeObjectURL(url)
      result.value += '\n\n结果文件已开始下载。'
      return
    }
    if (job.status === 'FAILED') throw new Error(job.message)
  }
  throw new Error('任务处理超时，请稍后重试')
}async function runTool() {
  const tool = selectedTool.value
  const fileTool = ['ocr', 'pdf', 'table', 'convert'].includes(tool?.id)
  if (!tool || (!workingContent.value.trim() && !(fileTool && selectedDocumentId.value))) {
    ElMessage.warning('请先选择文档或输入待处理文本'); return
  }
  running.value = true; result.value = ''; resultCanApply.value = false
  try {
    if (tool.id === 'ocr') { await runFileJob('OCR') }
    else if (tool.id === 'table') { await runFileJob('WORD_TABLE_EXCEL') }
    else if (tool.id === 'pdf') { await runFileJob(pdfOperation.value === 'merge' ? 'PDF_MERGE' : 'PDF_SPLIT') }
    else if (tool.id === 'mindmap') { const prompt = `请基于以下内容输出 Mermaid mindmap 代码，只输出代码：\n\n${workingContent.value}`; const data = responseData(await aiApi.executeAgent({ task: prompt, context: { source: 'toolbox-mindmap' } })); result.value = data.finalAnswer || data.answer || data.content || JSON.stringify(data) }
    else if (tool.id === 'clean') { result.value = normalizeText(workingContent.value); resultCanApply.value = true }
    else if (tool.id === 'format') { result.value = applyFormat(workingContent.value); resultCanApply.value = true }
    else if (tool.id === 'mask') { result.value = maskSensitive(workingContent.value); resultCanApply.value = true }
    else if (tool.id === 'compare') {
      if (!compareDocumentId.value) { ElMessage.warning('请选择第二份文档'); return }
      const other = responseData(await docApi.getDocDetail(compareDocumentId.value))
      result.value = compareText(workingContent.value, other.content || '', documentTitle(documents.value.find(doc => String(doc.id) === selectedDocumentId.value)), documentTitle(other))
    } else if (tool.id === 'summary') {
      const data = responseData(await aiApi.summarizeText(workingContent.value, 300)); result.value = typeof data === 'string' ? data : (data.summary || data.content || JSON.stringify(data)); resultCanApply.value = true
    } else if (tool.id === 'keywords') {
      const data = responseData(await aiApi.extractKeywords(workingContent.value, 8)); const words = Array.isArray(data) ? data : (data.keywords || data.items || []); result.value = `关键词\n\n${Array.isArray(words) ? words.map((word, index) => `${index + 1}. ${typeof word === 'string' ? word : word.keyword || JSON.stringify(word)}`).join('\n') : JSON.stringify(words)}`
    } else if (tool.id === 'proofread') {
      const prompt = `请对以下文本进行中文校对。只输出校对后的完整文本，不要解释，不要改变原意：\n\n${workingContent.value}`
      const data = responseData(await aiApi.executeAgent({ task: prompt, context: { source: 'document-toolbox', action: 'proofread' } })); result.value = data.finalAnswer || data.answer || data.content || (typeof data === 'string' ? data : JSON.stringify(data)); resultCanApply.value = true
    } else if (tool.id === 'convert') {
      if (exportFormat.value === 'txt' || exportFormat.value === 'md') {
        if (!workingContent.value.trim()) { ElMessage.warning('TXT 或 Markdown 导出需要可编辑的文档正文'); return }
        downloadText(workingContent.value, exportFormat.value)
        result.value = `已生成 ${exportFormat.value.toUpperCase()} 文件并开始下载。`
      } else {
        const source = documents.value.find(doc => String(doc.id) === selectedDocumentId.value)
        const sourceName = documentTitle(source).toLowerCase()
        if (exportFormat.value === 'pdf') {
          if (!sourceName.endsWith('.docx')) throw new Error('PDF 导出目前请选 DOCX 文件')
          await runFileJob('DOCX_TO_PDF')
        } else {
          if (!sourceName.endsWith('.pdf')) throw new Error('Word 导出目前请选 PDF 文件')
          await runFileJob('PDF_TO_DOCX')
          result.value += '\n\n提示：PDF 转 Word 提取可编辑正文，不保证保留原始版式。'
        }
      }
    } else if (tool.id === 'ppt') {
      const title = documentTitle(documents.value.find(doc => String(doc.id) === selectedDocumentId.value))
      const html = await aiApi.previewPpt({ outline: workingContent.value.slice(0, 12000), theme: pptTheme.value, title, model: 'deepseek-chat' })
      const popup = window.open('', '_blank'); if (popup) { popup.document.write(html); popup.document.close(); result.value = '演示稿已在新标签页打开。' } else { throw new Error('浏览器拦截了新窗口，请允许弹窗后重试') }
    }
    incrementOperation(); ElMessage.success(`${tool.name}已完成`)
  } catch (error) { ElMessage.error(error?.message || `${tool.name}执行失败，请稍后重试`) } finally { running.value = false }
}
async function copyResult() { try { await navigator.clipboard.writeText(result.value); ElMessage.success('处理结果已复制') } catch (error) { ElMessage.warning('复制失败，请手动复制') } }
function applyResult() { workingContent.value = result.value; resultCanApply.value = false; ElMessage.success('已应用到编辑区，确认后可保存为新版本') }

onMounted(fetchDocuments)
</script>

<style scoped>
.toolbox-page{min-height:100vh;padding:30px 34px 38px;background:#fbfaf8;color:#3f3a4b;font-family:"Microsoft YaHei",sans-serif}.toolbox-header{max-width:1500px;margin:0 auto 20px;display:flex;justify-content:space-between;gap:20px;align-items:center}.back-link{border:0;background:transparent;padding:0;color:#827896;cursor:pointer;font-size:13px;display:inline-flex;align-items:center;gap:5px}.title-row{display:flex;gap:13px;align-items:center;margin-top:11px}.title-mark{height:46px;width:46px;display:grid;place-items:center;border-radius:15px;background:#aca0ce;color:#fff;font-size:21px;box-shadow:0 8px 20px #aca0ce55}.title-row h1{font-size:25px;letter-spacing:.3px;margin:0 0 5px;font-weight:700}.title-row p{margin:0;color:#948da0;font-size:13px}.header-actions{display:flex;gap:10px}.toolbox-hero{max-width:1500px;margin:0 auto 22px;padding:26px 31px;border-radius:24px;background:linear-gradient(120deg,#edf4e2 0%,#f7f1f1 52%,#dfced6 100%);display:flex;justify-content:space-between;align-items:center;overflow:hidden;position:relative}.toolbox-hero:after{content:"";position:absolute;width:200px;height:200px;border-radius:50%;right:22%;top:-105px;background:#fff7;}.hero-copy{position:relative;z-index:1}.eyebrow,.pane-kicker{font-size:10px;letter-spacing:1.4px;font-weight:700;color:#847a9a}.hero-copy h2{font-size:22px;margin:8px 0;color:#574f66}.hero-copy p{font-size:13px;color:#756e7d;margin:0}.hero-stats{display:flex;position:relative;z-index:1;background:#ffffff92;border:1px solid #fff;padding:10px 4px;border-radius:17px}.hero-stats div{min-width:83px;padding:3px 12px;text-align:center;border-right:1px solid #e3dce5}.hero-stats div:last-child{border-right:0}.hero-stats strong{display:block;font-size:19px;color:#74698e}.hero-stats span{font-size:11px;color:#887f8f}.workspace-grid{max-width:1500px;margin:0 auto;display:grid;grid-template-columns:minmax(260px,.85fr) minmax(440px,1.5fr) minmax(290px,.95fr);gap:16px}.document-pane,.tools-pane,.operation-pane{border:1px solid #ebe5ea;border-radius:20px;background:#fff;padding:20px;box-shadow:0 8px 28px #7a6d8a0b}.pane-heading{display:flex;justify-content:space-between;align-items:start;margin-bottom:16px}.pane-heading h3{margin:4px 0 0;font-size:17px;color:#4b4655}.selection-hint{font-size:11px;line-height:25px;color:#8a8194}.document-select{width:100%}.option-row{display:flex;justify-content:space-between;gap:12px}.option-row small{color:#a19baa}.doc-preview{margin:14px 0 12px;min-height:104px;padding:13px;border-radius:14px;background:#faf8fa;color:#716a78;overflow:hidden}.doc-preview p{font-size:12px;line-height:1.75;margin:7px 0 0;display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden}.preview-label{font-size:10px;letter-spacing:1px;color:#9a8da8}.doc-preview.empty{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:6px;text-align:center;background:#fcfbfa;color:#aaa2af}.doc-preview.empty strong{font-size:12px;color:#827990}.doc-preview.empty span{font-size:11px;line-height:1.5}.content-input :deep(.el-textarea__inner){background:#fdfcfd;border-color:#ece5ef;line-height:1.7;font-size:12px;color:#5f5969}.document-actions{display:flex;gap:8px;margin-top:12px}.document-actions .el-button{flex:1;margin:0}.tool-groups{display:flex;flex-direction:column;gap:18px}.group-label{display:flex;align-items:center;gap:7px;font-size:12px;color:#847b8d;margin-bottom:9px;font-weight:600}.group-label span{width:8px;height:8px;border-radius:50%}.tool-card-grid{display:grid;grid-template-columns:1fr 1fr;gap:9px}.tool-card{position:relative;text-align:left;border:1px solid #eee9ef;background:#fff;border-radius:14px;padding:12px 30px 12px 11px;min-height:66px;cursor:pointer;transition:.2s;display:flex;gap:9px;align-items:center}.tool-card:hover,.tool-card.active{border-color:#aca0ce;background:#faf8fe;box-shadow:0 6px 16px #76678f17}.tool-card.active:after{content:"";position:absolute;right:9px;width:6px;height:6px;border-radius:50%;background:#74698e}.tool-icon{height:34px;width:34px;border-radius:11px;display:grid;place-items:center;flex:none;font-size:16px}.tool-card-copy{display:flex;flex-direction:column;min-width:0;gap:3px}.tool-card strong{font-size:12px;color:#59515f}.tool-card small{font-size:10px;color:#a098a8;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.tool-arrow{display:none}.tool-card em{position:absolute;top:7px;right:8px;font-style:normal;font-size:9px;color:#b09ca6;background:#f8eced;padding:2px 4px;border-radius:4px}.operation-pane{display:flex;flex-direction:column}.operation-description{font-size:12px;line-height:1.7;color:#837b8b;margin:0 0 18px}.setting-block{padding:13px;border-radius:14px;background:#faf9f8;border:1px solid #f0ebee}.setting-block label{display:block;font-size:12px;font-weight:600;color:#625a68;margin-bottom:10px}.preset-group{display:flex;flex-wrap:wrap;gap:6px}.preset-group :deep(.el-radio-button__inner){border:1px solid #e8e1eb!important;border-radius:8px!important;box-shadow:none!important;font-size:11px;padding:7px 9px;color:#7b7283}.preset-group :deep(.el-radio-button:first-child .el-radio-button__inner){border-left:1px solid #e8e1eb}.preset-group :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner){background:#84799f;border-color:#84799f!important;color:#fff}.helper-text{font-size:11px;line-height:1.65;color:#958d9c;margin:11px 0 0}.scope-card{display:flex;gap:7px;align-items:flex-start;padding:10px;background:#fff;border-radius:9px;color:#7b7283;font-size:11px;line-height:1.45}.scope-card .el-icon{color:#7e729b;margin-top:1px}.planned-state{text-align:center;padding:22px 8px;color:#8f8695}.planned-icon{margin:auto auto 9px;width:40px;height:40px;border-radius:50%;display:grid;place-items:center;background:#f2edf3;color:#a08a96}.planned-state strong{font-size:13px;color:#716878}.planned-state p{font-size:11px;line-height:1.7;margin:8px 0 0}.run-button{width:100%;margin-top:16px;height:40px;background:#74698e;border-color:#74698e;border-radius:11px}.run-button:hover{background:#675d7f;border-color:#675d7f}.empty-operation{flex:1;min-height:230px;display:grid;place-items:center;align-content:center;color:#b0a8b6;text-align:center;gap:9px}.empty-operation p{font-size:12px}.result-panel{margin-top:17px;border-top:1px dashed #e5dfe7;padding-top:13px}.result-heading{display:flex;justify-content:space-between;align-items:center;font-size:12px;color:#756c7e;font-weight:600}.result-panel pre{margin:9px 0 0;max-height:218px;overflow:auto;padding:11px;border-radius:10px;white-space:pre-wrap;word-break:break-word;background:#f8f7f5;color:#655e6b;font:11px/1.7 "Microsoft YaHei",sans-serif}.result-actions{display:flex;justify-content:flex-end;gap:7px;margin-top:8px}.capability-note{max-width:1500px;margin:16px auto 0;padding:12px 16px;display:flex;gap:8px;align-items:flex-start;border-radius:13px;background:#f5f2f6;color:#847b8c;font-size:11px;line-height:1.7}.capability-note .el-icon{color:#8a7ba9;margin-top:2px;flex:none}.capability-note strong{color:#696075}@media(max-width:1120px){.workspace-grid{grid-template-columns:1fr 1fr}.operation-pane{grid-column:span 2}.toolbox-header,.toolbox-hero{align-items:flex-start}.hero-stats{margin-top:18px}.toolbox-hero{flex-direction:column}}@media(max-width:720px){.toolbox-page{padding:20px 14px}.toolbox-header{flex-direction:column}.header-actions{width:100%}.header-actions .el-button{flex:1}.workspace-grid{grid-template-columns:1fr}.operation-pane{grid-column:auto}.tool-card-grid{grid-template-columns:1fr}.hero-stats{width:100%;box-sizing:border-box;justify-content:space-between}.hero-stats div{min-width:0;flex:1;padding:3px}.title-row h1{font-size:21px}}
.quiz-workbench{padding:13px;border:1px solid #eee8f0;border-radius:14px;background:#faf9fb}.hidden-file-input{display:none}.quiz-source-actions{display:flex;gap:8px;flex-wrap:wrap}.quiz-card{margin-top:14px;padding:14px;border-radius:12px;background:#fff;border:1px solid #e8e1ee}.quiz-progress,.quiz-actions{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:11px;color:#837a8e}.quiz-progress strong{color:#74698e}.quiz-question{margin:15px 0 12px;font-size:14px;line-height:1.75;color:#4e4759;font-weight:600}.quiz-feedback{margin-top:10px;padding:10px;border-radius:9px;background:#fbefec;color:#9a625b;font-size:11px;line-height:1.65}.quiz-feedback.correct{background:#ebf4ed;color:#568064}.quiz-feedback p{margin:4px 0 0}.quiz-actions{margin-top:12px}.quiz-empty{min-height:150px;display:flex;gap:8px;flex-direction:column;align-items:center;justify-content:center;color:#a098a8;font-size:12px;text-align:center}.quiz-empty .el-icon{font-size:26px;color:#a16f87}</style>
