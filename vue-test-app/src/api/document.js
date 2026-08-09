/**
 * @description 文档业务 API
 */
import request from '../utils/request'

export const docApi = {
    // 获取用户所有的文档列表
    getUserDocs: (userId) => {
        return request.get(`/documents/user/${userId}`)
    },

    // 获取单个文档详情（含 content 和 summary）
    getDocDetail: (id) => {
        return request.get(`/documents/${id}`)
    },

    // 新增全文搜索接口
    searchDocs: (keyword) => {
        return request.get('/documents/search', {
            params: { keyword }
        })
    },

    // 创建文档
    createDoc: (data) => {
        return request.post('/documents', data) // data 对应后端的 DocumentCreateDTO
    },

    // 更新文档内容 (保存编辑)
    reparseDoc: (id) => request.post(`/documents/${id}/reparse`),

    updateDoc: (id, data) => {
        return request.put(`/documents/${id}`, data)
    },

    // 获取文档所有历史版本
    getVersions: (id) => {
        return request.get(`/documents/${id}/versions`)
    },

    // 恢复到指定版本
    restoreVersion: (id, versionNumber) => {
        return request.post(`/documents/${id}/restore/${versionNumber}`)
    },

    // 删除文档
    deleteDoc: (id) => {
        return request.delete(`/documents/${id}`)
    },

    getTrash: () => request.get('/documents/trash'),

    restoreDeleted: (id) => request.post(`/documents/${id}/restore`),

    purgeDoc: (id) => request.delete(`/documents/${id}/purge`),

    grantCollaborator: (id, collaboratorUserId, role = 'editor') => {
        return request.post(`/documents/${id}/collaborators/${collaboratorUserId}`, null, {
            params: { role }
        })
    },

    revokeCollaborator: (id, collaboratorUserId) => {
        return request.delete(`/documents/${id}/collaborators/${collaboratorUserId}`)
    },

    getCollaborators: (id) => {
        return request.get(`/documents/${id}/collaborators`)
    },

    submitToolboxJob: (data) => request.post('/documents/toolbox/jobs', data),
    listToolboxJobs: () => request.get('/documents/toolbox/jobs'),
    retryToolboxJob: (jobId) => request.post(`/documents/toolbox/jobs/${jobId}/retry`),
    getToolboxJob: (jobId) => request.get(`/documents/toolbox/jobs/${jobId}`),
    downloadToolboxJob: (jobId) => request.get(`/documents/toolbox/jobs/${jobId}/download`, { responseType: 'blob' })
}
