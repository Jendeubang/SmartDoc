/**
 * @description AI 核心功能接口 (直连 8083 服务)
 */
import request from '../utils/request'
import { createAsyncJobRunner } from './asyncJob.js'

const wrapResult = (data) => ({ code: 200, message: 'success', data })
const getAsyncJob = (jobId, config = {}) => request.get(`/ai/async/jobs/${jobId}`, config)
const runAsyncJob = (submit, config = {}) => createAsyncJobRunner({
    submit,
    getJob: jobId => getAsyncJob(jobId, config),
    timeoutMs: 300000
}).runAsyncJob().then(wrapResult)

export const aiApi = {
    // 统一 Agent 执行入口：规划、工具调用、RAG、审批和最终回答
    executeAgent: (payload) => {
        return runAsyncJob(() => request.post('/ai/async/agent/execute', payload))
    },

    // 仅生成执行计划，不实际调用工具
    planAgentTask: (task, context = {}, options = {}) => {
        return runAsyncJob(() => request.post('/ai/async/agent/execute', {
            task,
            context,
            dryRun: true,
            ...options
        }))
    },

    // 获取 Agent 可调用工具列表
    listAgentTools: () => {
        return request.get('/ai/agent/tools')
    },


    getModels: (config = {}) => request.get('/ai/models', config),

    // 用户自带模型密钥（BYOK）：服务端只返回脱敏信息，明文 Key 不会回显。
    listProviderCredentials: () => request.get('/ai/provider-credentials'),
    testProviderCredential: (data) => request.post('/ai/provider-credentials/test', data),
    saveProviderCredential: (data) => request.post('/ai/provider-credentials', data),
    deleteProviderCredential: (id) => request.delete(`/ai/provider-credentials/${id}`),
    setDefaultProviderCredential: (id) => request.put(`/ai/provider-credentials/${id}/default`),

    // 开启一个带有记忆的新对话 (返回 conversationId)
    startChat: (userId) => {
        return request.post(`/ai/agent/chat/start?userId=${userId}`)
    },

    // 发送聊天消息给 AI (带着 conversationId 就能记住上下文)
    sendMessage: (conversationId, userInput) => {
        return runAsyncJob(() => request.post('/ai/async/agent/execute', {
            conversationId,
            task: userInput,
            context: {}
        }))
    },

    // 直接调用指定模型，适合翻译等不需要工具规划的纯文本任务
    chatText: (prompt, model = 'deepseek-chat') => {
        return runAsyncJob(() => request.post('/ai/async/chat', { prompt }, {
            params: { model }
        }))
    },
    // 针对选中文本进行总结/润色 (单次调用，不需要记忆)
    // Upload recording, transcribe it with ASR, then ask DeepSeek to create structured minutes.
    createMeetingMinutesFromAudio: (file, title, language = 'auto') => {
        const form = new FormData()
        form.append('file', file)
        form.append('title', title || '')
        form.append('language', language || 'auto')
        return request.post('/ai/meeting-minutes/audio', form, { timeout: 660000 })
    },
    summarizeText: (content, maxLength = 200, model, config = {}) => {
        return runAsyncJob(() => request.post('/ai/async/summarize', { content, maxLength }, {
            params: { model: model || undefined },
            ...config
        }), config)
    },

    // AI 文档分析/纠错接口
    analyzeText: (content, config = {}) => {
        return request.post('/ai/analyze', { content }, config)
    },

    // 关键词提取
    extractKeywords: (content, count = 5, model, config = {}) => {
        return runAsyncJob(() => request.post('/ai/async/keywords', { content, count }, {
            params: { model: model || undefined },
            ...config
        }), config)
    },

    getAsyncJob,


    // 获取分段策略列表
    getSegmentStrategies: () => request.get('/ai/rag/segment/strategies'),
    // 带有分段策略的文档索引
    indexWithSegment: (documentId, content, strategy) => request.post(`/ai/agent/knowledge/index/segment?documentId=${encodeURIComponent(documentId)}&strategy=${encodeURIComponent(strategy || 'AUTO')}`, content, { headers: { 'Content-Type': 'text/plain' } }),
    getKnowledgeJobs: (config = {}) => request.get('/ai/agent/knowledge/jobs', config),
    retryKnowledgeJob: (jobId, content) => request.post(`/ai/agent/knowledge/jobs/${jobId}/retry`, content, { headers: { 'Content-Type': 'text/plain' } }),
    // 获取已分段的列表
    getDocumentSegments: (documentId) => request.get(`/ai/rag/document/${documentId}/segments`),
    getIndexedDocuments: (config = {}) => request.get('/ai/rag/documents', config),
    removeIndexedDocument: (documentId) => request.delete(`/ai/rag/document/${documentId}`),
    getIndexMetadata: (documentId) => request.get(`/ai/rag/document/${documentId}/metadata`),
    // 获取统计信息
    getRagStatistics: () => request.get('/ai/rag/statistics'),

    /**
     * RAG 问答
     * 基于知识库直接搜索并回答，不带历史记忆，追求极致准确
     */
    ragQuery: (question) => {
        // 后端 @RequestBody 是 String，需要发纯文本
        return request.post('/ai/rag/query?strategy=HYBRID', question, {
            headers: { 'Content-Type': 'text/plain' }
        })
    },

// ================= PPT 生成 Skill 接口 =================
    // 下载 PPT
    downloadPpt: (data) => request.post('/skills/html-ppt/download', data, { responseType: 'blob' }),
    // 预览 PPT (返回 HTML 字符串)
    previewPpt: (data) => request.post('/skills/html-ppt/view', data)
}
