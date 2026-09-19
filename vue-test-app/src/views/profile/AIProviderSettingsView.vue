<template>
  <div class="settings-page">
    <main class="settings-shell">
      <button class="back-button" type="button" @click="router.push('/profile')">‹ 返回个人信息</button>
      <section class="settings-card">
        <header class="settings-header">
          <p class="eyebrow">MODEL CONNECTIONS</p>
          <h1>模型配置</h1>
          <p>可以填写自己的 DeepSeek、DashScope 或 OpenAI API Key。密钥会在服务端加密保存，页面不会再次显示明文。</p>
        </header>

        <div class="settings-body">
          <el-alert title="安全提示" type="info" :closable="false" show-icon>
            只填写你自己的 API Key。SmartDoc 不会把密钥写入浏览器缓存；删除配置后，后续请求立即回退到服务器默认模型。
          </el-alert>

          <el-form class="credential-form" label-position="top" @submit.prevent>
            <div class="form-grid">
              <el-form-item label="服务商">
                <el-select v-model="form.provider" style="width: 100%" @change="applyProviderDefaults">
                  <el-option label="DeepSeek" value="deepseek" />
                  <el-option label="阿里云 DashScope" value="dashscope" />
                  <el-option label="OpenAI" value="openai" />
                </el-select>
              </el-form-item>
              <el-form-item label="模型名称">
                <el-input v-model="form.model" placeholder="例如 deepseek-chat、qwen-plus、gpt-4o-mini" />
              </el-form-item>
            </div>
            <el-form-item label="API 地址">
              <el-input v-model="form.baseUrl" placeholder="使用默认地址即可" />
              <div class="hint">出于安全考虑，目前仅允许受信任的 HTTPS 服务地址。</div>
            </el-form-item>
            <el-form-item label="API Key">
              <el-input v-model="form.apiKey" type="password" show-password autocomplete="new-password" placeholder="粘贴你的 API Key" />
            </el-form-item>
            <div class="form-actions">
              <el-checkbox v-model="form.defaultCredential">设为默认模型</el-checkbox>
              <span class="actions-spacer" />
              <el-button :loading="testing" @click="testCredential">测试连接</el-button>
              <el-button type="primary" :loading="saving" @click="saveCredential">保存配置</el-button>
            </div>
          </el-form>

          <div class="saved-title"><span>已保存的模型</span><el-button link :loading="loading" @click="loadCredentials">刷新</el-button></div>
          <el-empty v-if="!loading && credentials.length === 0" description="还没有配置自己的模型" />
          <div v-else class="credential-list" v-loading="loading">
            <div v-for="item in credentials" :key="item.id" class="credential-item">
              <div>
                <div class="credential-name">{{ providerName(item.provider) }} · {{ item.model }}</div>
                <div class="credential-meta">{{ item.baseUrl }} · {{ item.keyHint }}</div>
              </div>
              <div class="credential-actions">
                <el-tag v-if="item.defaultCredential" type="success" effect="plain">默认</el-tag>
                <el-button v-else link type="primary" @click="makeDefault(item.id)">设为默认</el-button>
                <el-button link type="danger" @click="removeCredential(item)">删除</el-button>
              </div>
            </div>
          </div>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { aiApi } from '../../api/ai'

const router = useRouter()
const loading = ref(false)
const testing = ref(false)
const saving = ref(false)
const credentials = ref([])
const defaults = {
  deepseek: { model: 'deepseek-chat', baseUrl: 'https://api.deepseek.com' },
  dashscope: { model: 'qwen-plus', baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1' },
  openai: { model: 'gpt-4o-mini', baseUrl: 'https://api.openai.com/v1' }
}
const form = reactive({ provider: 'deepseek', model: defaults.deepseek.model, baseUrl: defaults.deepseek.baseUrl, apiKey: '', defaultCredential: true })

const providerName = provider => ({ deepseek: 'DeepSeek', dashscope: '阿里云 DashScope', openai: 'OpenAI' }[provider] || provider)
const applyProviderDefaults = () => {
  const next = defaults[form.provider]
  if (!next) return
  form.model = next.model
  form.baseUrl = next.baseUrl
}
const errorMessage = error => error?.response?.data?.message || error?.message || '操作失败，请稍后重试'

const loadCredentials = async () => {
  loading.value = true
  try { credentials.value = aiApiResponse(await aiApi.listProviderCredentials()) } catch (error) { ElMessage.error(errorMessage(error)) } finally { loading.value = false }
}
const aiApiResponse = response => Array.isArray(response?.data) ? response.data : []
const payload = () => ({ provider: form.provider, model: form.model.trim(), baseUrl: form.baseUrl.trim(), apiKey: form.apiKey.trim(), defaultCredential: form.defaultCredential })

const testCredential = async () => {
  if (!form.apiKey.trim()) return ElMessage.warning('请先填写 API Key')
  testing.value = true
  try { await aiApi.testProviderCredential(payload()); ElMessage.success('连接成功，模型可以正常调用') } catch (error) { ElMessage.error(errorMessage(error)) } finally { testing.value = false }
}
const saveCredential = async () => {
  if (!form.apiKey.trim()) return ElMessage.warning('请先填写 API Key')
  saving.value = true
  try {
    await aiApi.saveProviderCredential(payload())
    form.apiKey = ''
    await loadCredentials()
    ElMessage.success('模型配置已保存')
  } catch (error) { ElMessage.error(errorMessage(error)) } finally { saving.value = false }
}
const makeDefault = async id => {
  try { await aiApi.setDefaultProviderCredential(id); await loadCredentials(); ElMessage.success('默认模型已切换') } catch (error) { ElMessage.error(errorMessage(error)) }
}
const removeCredential = async item => {
  try {
    await ElMessageBox.confirm(`确定删除 ${providerName(item.provider)} · ${item.model} 吗？`, '删除模型配置', { type: 'warning' })
    await aiApi.deleteProviderCredential(item.id)
    await loadCredentials()
    ElMessage.success('模型配置已删除')
  } catch (error) { if (error !== 'cancel' && error !== 'close') ElMessage.error(errorMessage(error)) }
}

onMounted(loadCredentials)
</script>

<style scoped>
.settings-page { min-height: 100vh; padding: 42px 24px; background: radial-gradient(circle at 90% 0%, #eaf1eb 0, transparent 31%), #f7f8f5; color: #334139; }
.settings-shell { width: min(900px, 100%); margin: 0 auto; }
.back-button { border: 0; padding: 8px 3px; color: #527461; background: transparent; cursor: pointer; font-size: 14px; }
.settings-card { margin-top: 17px; overflow: hidden; border: 1px solid #dce6de; border-radius: 22px; background: rgba(255,255,255,.95); box-shadow: 0 18px 45px rgba(57,83,63,.1); }
.settings-header { padding: 38px 48px 30px; background: linear-gradient(118deg,#edf4ed,#fbfcfa 68%); border-bottom: 1px solid #e2ebe3; }
.eyebrow { margin: 0 0 10px; color: #6c9277; font-size: 11px; font-weight: 700; letter-spacing: .16em; }
.settings-header h1 { margin: 0; color: #355441; font-size: 28px; }
.settings-header p:last-child { margin: 10px 0 0; color: #728176; font-size: 14px; line-height: 1.7; }
.settings-body { padding: 28px 48px 44px; }
.credential-form { margin-top: 24px; }
.form-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; }
.hint { margin-top: 6px; color: #89978c; font-size: 12px; }
.form-actions { display: flex; align-items: center; gap: 12px; margin-top: 8px; }
.actions-spacer { flex: 1; }
.saved-title { display: flex; align-items: center; justify-content: space-between; margin-top: 34px; padding-bottom: 12px; border-bottom: 1px solid #e6ede7; color: #46614e; font-weight: 700; }
.credential-list { display: grid; gap: 10px; margin-top: 13px; }
.credential-item { display: flex; align-items: center; justify-content: space-between; gap: 18px; padding: 15px 16px; border: 1px solid #e3ebe4; border-radius: 13px; background: #fbfdfb; }
.credential-name { color: #385542; font-weight: 650; }
.credential-meta { margin-top: 5px; color: #88958b; font-size: 12px; word-break: break-all; }
.credential-actions { display: flex; align-items: center; gap: 8px; white-space: nowrap; }
.form-actions :deep(.el-button--primary) { border-color: #6c9277; background: #6c9277; }
@media (max-width: 660px) { .settings-page { padding: 24px 14px; } .settings-header,.settings-body { padding-left: 24px; padding-right: 24px; } .form-grid { grid-template-columns: 1fr; gap: 0; } .credential-item { align-items: flex-start; flex-direction: column; } }
</style>
