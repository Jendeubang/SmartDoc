export function requestErrorMessage(error) {
  const status = error?.response?.status
  const serverMessage = error?.response?.data?.message || error?.response?.data?.msg

  if (serverMessage) return serverMessage
  if (!error?.response) return '网络连接失败，请检查网络或服务是否已启动'
  if (status === 400) return '请求内容有误，请检查填写信息'
  if (status === 403) return '没有权限执行此操作'
  if (status === 404) return '请求的内容不存在或已被移除'
  if (status === 409) return '当前数据已被其他成员更新，请刷新后重试'
  if (status === 413) return '文件过大，请压缩后重新上传'
  if (status === 429) return '操作过于频繁，请稍后再试'
  if (status >= 500) return '服务暂时不可用，请稍后重试'
  if (error?.code === 'ECONNABORTED') return '请求超时，请稍后重试'
  return '操作未完成，请稍后重试'
}
