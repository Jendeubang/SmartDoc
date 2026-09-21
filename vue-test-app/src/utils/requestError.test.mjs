import test from 'node:test'
import assert from 'node:assert/strict'
import { requestErrorMessage } from './requestError.js'

test('server business message has the highest priority', () => {
  assert.equal(requestErrorMessage({
    response: { status: 500, data: { message: '文件存储失败' } }
  }), '文件存储失败')
})

test('network, permission, conflict and server errors are user friendly', () => {
  assert.match(requestErrorMessage({}), /网络连接失败/)
  assert.equal(requestErrorMessage({ response: { status: 403, data: {} } }), '没有权限执行此操作')
  assert.match(requestErrorMessage({ response: { status: 409, data: {} } }), /其他成员更新/)
  assert.equal(requestErrorMessage({ response: { status: 503, data: {} } }), '服务暂时不可用，请稍后重试')
})
