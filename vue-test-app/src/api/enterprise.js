import request from '../utils/request'

export const enterpriseApi = {
  organizations: () => request.get('/enterprise/organizations'),
  createOrganization: data => request.post('/enterprise/organizations', data),
  overview: id => request.get(`/enterprise/organizations/${id}/overview`),
  createDepartment: (id, data) => request.post(`/enterprise/organizations/${id}/departments`, data),
  addMember: (id, data) => request.post(`/enterprise/organizations/${id}/members`, data),
  updateMember: (id, userId, data) => request.put(`/enterprise/organizations/${id}/members/${userId}`, data),
  removeMember: (id, userId) => request.delete(`/enterprise/organizations/${id}/members/${userId}`),
  createFolder: (id, data) => request.post(`/enterprise/organizations/${id}/folders`, data),
  moveDocument: (documentId, data) => request.put(`/enterprise/documents/${documentId}/location`, data),
  approvals: () => request.get('/enterprise/approvals'),
  submitApproval: data => request.post('/enterprise/approvals', data),
  decideApproval: (id, data) => request.post(`/enterprise/approvals/${id}/decision`, data),
  notifications: () => request.get('/enterprise/notifications'),
  readNotification: id => request.put(`/enterprise/notifications/${id}/read`),
  audits: id => request.get(`/enterprise/organizations/${id}/audits`),
  statistics: id => request.get(`/enterprise/organizations/${id}/statistics`),
  createShare: data => request.post('/enterprise/shares', data),
  shares: () => request.get('/enterprise/shares'),
  revokeShare: id => request.delete(`/enterprise/shares/${id}`),
  openShare: (token, password = '') => request.post(`/public/shares/${token}`, { password })
}
