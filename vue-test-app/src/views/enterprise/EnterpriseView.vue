<template>
  <div class="enterprise-page">
    <aside class="enterprise-sidebar">
      <div class="brand" @click="router.push('/dashboard')"><div class="brand-icon"><el-icon><Cpu /></el-icon></div><strong>SmartDoc</strong></div>
      <button class="back-workbench" @click="router.push('/dashboard')"><el-icon><ArrowLeft /></el-icon> 返回工作台</button>
      <div class="space-title">企业空间</div>
      <button v-for="org in organizations" :key="org.id" class="org-item" :class="{ active: org.id === activeOrganizationId }" @click="selectOrganization(org.id)">
        <span>{{ org.name }}</span><small>{{ roleLabel(org.role) }}</small>
      </button>
      <button class="create-org" @click="createOrganization"><el-icon><Plus /></el-icon> 创建企业</button>
    </aside>

    <main class="enterprise-main" v-loading="loading">
      <template v-if="activeOrganizationId && overview.organization">
        <header class="enterprise-header">
          <div><p>ENTERPRISE WORKSPACE</p><h1>{{ overview.organization.name }}</h1><span>集中管理企业成员、文档权限、审批与安全审计</span></div>
          <div class="header-actions"><el-button @click="loadAll"><el-icon><Refresh /></el-icon>刷新</el-button><el-button v-if="isAdmin" type="primary" @click="folderDialog = true"><el-icon><FolderAdd /></el-icon>新建文件夹</el-button></div>
        </header>

        <section class="metric-grid">
          <div class="metric-card"><span>企业成员</span><strong>{{ overview.members.length }}</strong><small>{{ overview.departments.length }} 个部门</small></div>
          <div class="metric-card"><span>企业文档</span><strong>{{ overview.documents.length }}</strong><small>{{ overview.folders.length }} 个文件夹</small></div>
          <div class="metric-card warning"><span>待我审批</span><strong>{{ overview.pendingApprovals }}</strong><small>需要及时处理</small></div>
          <div class="metric-card accent"><span>未读通知</span><strong>{{ overview.unreadNotifications }}</strong><small>协作与审批动态</small></div>
        </section>

        <el-tabs v-model="activeTab" class="enterprise-tabs">
          <el-tab-pane v-if="isEnterpriseAdmin" label="管理看板" name="analytics">
            <section class="content-panel analytics-panel">
              <div class="panel-title"><div><h2>企业数据概览</h2><p>成员、文档、审批、安全分享和近 30 天操作趋势</p></div><el-tag effect="plain">仅企业管理员可见</el-tag></div>
              <div class="analytics-summary">
                <div><span>活跃成员</span><strong>{{ enterpriseStats.summary.members || 0 }}</strong></div><div><span>部门</span><strong>{{ enterpriseStats.summary.departments || 0 }}</strong></div><div><span>企业文档</span><strong>{{ enterpriseStats.summary.documents || 0 }}</strong></div><div><span>待审批</span><strong>{{ enterpriseStats.summary.pendingApprovals || 0 }}</strong></div><div><span>有效分享</span><strong>{{ enterpriseStats.summary.activeShares || 0 }}</strong></div><div><span>30 天操作</span><strong>{{ enterpriseStats.summary.operations30d || 0 }}</strong></div>
              </div>
              <div class="analytics-grid">
                <article class="analytics-card"><h3>部门文档分布</h3><div v-for="row in enterpriseStats.departmentDocuments" :key="row.id" class="bar-row"><span>{{ row.name }}</span><div><i :style="{width: barWidth(row.value, enterpriseStats.departmentDocuments)}"></i></div><b>{{ row.value }}</b></div><el-empty v-if="!enterpriseStats.departmentDocuments.length" description="暂无部门数据" :image-size="55" /></article>
                <article class="analytics-card"><h3>成员角色分布</h3><div v-for="row in enterpriseStats.roles" :key="row.name" class="bar-row"><span>{{ roleLabel(row.name) }}</span><div><i :style="{width: barWidth(row.value, enterpriseStats.roles)}"></i></div><b>{{ row.value }}</b></div><el-empty v-if="!enterpriseStats.roles.length" description="暂无成员数据" :image-size="55" /></article>
                <article class="analytics-card wide"><h3>近 14 天趋势</h3><div class="trend-legend"><span><i class="doc-dot"></i>新增文档</span><span><i class="activity-dot"></i>企业操作</span></div><div class="trend-chart"><div v-for="point in mergedTrend" :key="point.day" class="trend-column"><div class="trend-bars"><i class="document-bar" :style="{height: trendHeight(point.documents)}" :title="`新增文档 ${point.documents}`"></i><i class="activity-bar" :style="{height: trendHeight(point.activities)}" :title="`企业操作 ${point.activities}`"></i></div><small>{{ point.label }}</small></div></div></article>
                <article class="analytics-card"><h3>活跃用户（近 30 天）</h3><div v-for="(user,index) in enterpriseStats.topUsers" :key="user.user_id" class="top-user"><em>{{ index + 1 }}</em><span>{{ user.username || `用户 ID：${user.user_id}` }}</span><strong>{{ user.operations }} 次</strong></div><el-empty v-if="!enterpriseStats.topUsers.length" description="暂无操作记录" :image-size="55" /></article>
                <article class="analytics-card"><h3>审批状态</h3><div v-for="row in enterpriseStats.approvalStatus" :key="row.name" class="approval-stat"><el-tag :type="approvalType(row.name)">{{ approvalStatus(row.name) }}</el-tag><strong>{{ row.value }}</strong></div><el-empty v-if="!enterpriseStats.approvalStatus.length" description="暂无审批数据" :image-size="55" /></article>
              </div>
            </section>
          </el-tab-pane>
          <el-tab-pane label="文件空间" name="files">
            <div class="panel-toolbar"><div><h2>企业文件夹</h2><p>文档继承所属部门和文件夹权限</p></div></div>
            <div class="folder-grid">
              <button class="folder-card" :class="{ selected: !selectedFolderId }" @click="selectedFolderId = ''"><el-icon><Files /></el-icon><div><strong>全部企业文档</strong><small>{{ overview.documents.length }} 份</small></div></button>
              <button v-for="folder in overview.folders" :key="folder.id" class="folder-card" :class="{ selected: selectedFolderId === folder.id }" @click="selectedFolderId = folder.id"><el-icon><Folder /></el-icon><div><strong>{{ folder.name }}</strong><small>{{ folderDocumentCount(folder.id) }} 份 · {{ folder.visibility === 'department' ? '部门可见' : '企业可见' }}</small></div></button>
            </div>
            <div class="document-table">
              <div class="table-head"><span>文档</span><span>所属部门</span><span>更新时间</span><span>操作</span></div>
              <div v-for="doc in visibleDocuments" :key="doc.id" class="table-row">
                <button class="document-link" @click="openDocument(doc)"><el-icon><Document /></el-icon><span><strong>{{ doc.title }}</strong><small>{{ documentOwnershipLabel(doc) }} · {{ folderName(doc.folder_id) }}</small></span></button>
                <span><strong>{{ departmentName(doc.department_id) }}</strong><small>{{ accessLevelLabel(doc.enterprise_access_level) }} · {{ visibilityLabel(doc) }}</small></span>
                <span><strong>{{ formatTime(doc.update_time) }}</strong><small>{{ documentSyncLabel(doc) }}</small></span>
                <span><el-button text type="primary" @click="openMoveDocument(doc)">调整</el-button><el-button text @click="openApproval(doc)">发起审批</el-button><el-button text @click="openShare(doc)">安全分享</el-button><el-button v-if="isEnterpriseAdmin || String(doc.user_id) === currentUserId" text type="danger" @click="removeEnterpriseDocument(doc)">移出</el-button></span>
              </div>
              <el-empty v-if="!visibleDocuments.length" description="该文件夹暂无文档" :image-size="70" />
            </div>
          </el-tab-pane>

          <el-tab-pane label="部门与成员" name="members">
            <div class="split-panels">
              <section class="content-panel"><div class="panel-title"><div><h2>部门</h2><p>控制部门文档的默认可见范围</p></div><el-button v-if="isEnterpriseAdmin" type="primary" plain @click="createDepartment">新增部门</el-button></div><div class="department-list"><div v-for="department in overview.departments" :key="department.id" class="department-item"><el-icon><OfficeBuilding /></el-icon><div><strong>{{ department.name }}</strong><small>{{ memberCount(department.id) }} 名成员</small></div></div><el-empty v-if="!overview.departments.length" description="暂无部门" :image-size="60" /></div></section>
              <section class="content-panel members-panel"><div class="panel-title"><div><h2>企业成员</h2><p>企业管理员、部门管理员、成员和访客</p></div><el-button v-if="isAdmin" type="primary" @click="openMemberDialog">添加成员</el-button></div><div class="member-table"><div v-for="member in overview.members" :key="member.id" class="member-row"><el-avatar :size="34">{{ accountName(member).slice(0, 1).toUpperCase() }}</el-avatar><div><strong>{{ accountName(member) }}</strong><small>用户 ID：{{ member.user_id }} · {{ departmentName(member.department_id) }}</small></div><el-tag>{{ roleLabel(member.role) }}</el-tag><el-button v-if="isAdmin && member.role !== 'enterprise_admin'" text type="danger" @click="removeMember(member)">移除</el-button></div></div></section>
            </div>
          </el-tab-pane>

          <el-tab-pane :label="`审批中心 ${pendingCount ? '(' + pendingCount + ')' : ''}`" name="approvals">
            <section class="content-panel"><div class="panel-title"><div><h2>审批与发布</h2><p>合同、发布、归档和常规评审</p></div></div><div class="approval-list"><article v-for="approval in approvals" :key="approval.id" class="approval-card"><div><el-tag :type="approvalType(approval.status)">{{ approvalStatus(approval.status) }}</el-tag><h3>{{ approval.document_title || approval.document_id }}</h3><p>{{ approvalTypeLabel(approval.approval_type) }} · 申请人 {{ approval.applicant_username || `用户 ID：${approval.applicant_user_id}` }} · 审批人 {{ approval.approver_username || `用户 ID：${approval.approver_user_id}` }}</p><small>{{ formatTime(approval.create_time) }}</small></div><div v-if="approval.status === 'pending' && String(approval.approver_user_id) === currentUserId" class="approval-actions"><el-button type="danger" plain @click="decide(approval, 'rejected')">驳回</el-button><el-button type="success" @click="decide(approval, 'approved')">通过</el-button></div></article><el-empty v-if="!approvals.length" description="暂无审批任务" /></div></section>
          </el-tab-pane>

          <el-tab-pane :label="`通知 ${unreadNotifications ? '(' + unreadNotifications + ')' : ''}`" name="notifications">
            <section class="content-panel"><div class="panel-title"><div><h2>通知中心</h2><p>企业邀请、协作和审批动态</p></div></div><div class="notification-list"><button v-for="notice in notifications" :key="notice.id" class="notification-item" :class="{ unread: !notice.is_read }" @click="readNotice(notice)"><span class="notice-dot"></span><div><strong>{{ notice.title }}</strong><p>{{ notice.content }}</p><small>{{ formatTime(notice.create_time) }}</small></div></button><el-empty v-if="!notifications.length" description="暂无通知" /></div></section>
          </el-tab-pane>

          <el-tab-pane label="安全分享" name="shares">
            <section class="content-panel"><div class="panel-title"><div><h2>受控分享链接</h2><p>支持密码、有效期、访问次数和随时撤销</p></div></div><div class="share-list"><div v-for="share in shares" :key="share.id" class="share-row"><div><strong>{{ share.title || share.document_id }}</strong><small>{{ share.password_protected ? '密码保护 · ' : '' }}已访问 {{ share.access_count }}/{{ share.max_access || '不限' }} · {{ share.status }}</small></div><span>{{ share.expires_at ? formatTime(share.expires_at) + ' 到期' : '永久有效' }}</span><el-button v-if="share.status === 'active'" type="danger" text @click="revokeShare(share)">撤销</el-button></div><el-empty v-if="!shares.length" description="暂无安全分享" /></div></section>
          </el-tab-pane>

          <el-tab-pane v-if="isEnterpriseAdmin" label="审计日志" name="audit">
            <section class="content-panel"><div class="panel-title"><div><h2>操作审计</h2><p>追踪企业文档和权限相关操作</p></div></div><el-table :data="audits" stripe><el-table-column prop="create_time" label="时间" width="180"><template #default="scope">{{ formatTime(scope.row.create_time) }}</template></el-table-column><el-table-column label="用户" width="150"><template #default="scope">{{ accountName(scope.row) }}</template></el-table-column><el-table-column prop="action" label="操作" width="170"/><el-table-column prop="target_type" label="对象" width="110"/><el-table-column prop="target_id" label="对象 ID" min-width="190"/><el-table-column prop="detail" label="详情" min-width="200" show-overflow-tooltip/><el-table-column prop="ip_address" label="IP" width="130"/></el-table></section>
          </el-tab-pane>
        </el-tabs>
      </template>
      <el-empty v-else description="还没有企业空间，点击左侧创建企业" />
    </main>

    <el-dialog v-model="memberDialog" title="添加企业成员" width="500px">
      <el-form label-position="top">
        <el-form-item label="选择已注册用户">
          <el-select v-model="memberForm.userId" filterable remote clearable reserve-keyword :remote-method="searchRegisteredUsers" :loading="memberUserLoading" placeholder="按用户 ID、关键词或完整名称搜索" style="width:100%">
            <el-option v-for="user in registeredUsers" :key="user.id" :label="`${userDisplayName(user)} · ID ${user.id}`" :value="String(user.id)" :disabled="isExistingMember(user.id)">
              <div class="member-user-option"><el-avatar :size="30">{{ userDisplayName(user).slice(0, 1).toUpperCase() }}</el-avatar><div><strong>{{ userDisplayName(user) }}</strong><small>用户 ID：{{ user.id }}<template v-if="user.nickname && user.nickname !== user.username"> · 用户名：{{ user.username }}</template></small></div><el-tag v-if="isExistingMember(user.id)" size="small" type="info">已加入</el-tag></div>
            </el-option>
          </el-select>
          <div class="member-search-tip">打开下拉栏可查看全部已注册用户；支持 ID、用户名和注册昵称搜索。</div>
        </el-form-item>
        <el-form-item label="所属部门"><el-select v-model="memberForm.departmentId" :disabled="!isEnterpriseAdmin" clearable style="width:100%"><el-option v-for="department in overview.departments" :key="department.id" :label="department.name" :value="department.id" /></el-select></el-form-item>
        <el-form-item label="角色"><el-select v-model="memberForm.role" style="width:100%"><el-option label="普通成员" value="member"/><el-option v-if="isEnterpriseAdmin" label="部门管理员" value="department_admin"/><el-option label="访客（只读）" value="guest"/><el-option v-if="isEnterpriseAdmin" label="企业管理员" value="enterprise_admin"/></el-select></el-form-item>
      </el-form>
      <template #footer><el-button @click="memberDialog=false">取消</el-button><el-button type="primary" :disabled="!memberForm.userId" @click="addMember">添加成员</el-button></template>
    </el-dialog>
    <el-dialog v-model="folderDialog" title="新建企业文件夹" width="460px"><el-form label-position="top"><el-form-item label="文件夹名称"><el-input v-model="folderForm.name" /></el-form-item><el-form-item label="所属部门"><el-select v-model="folderForm.departmentId" clearable style="width:100%"><el-option v-for="department in overview.departments" :key="department.id" :label="department.name" :value="department.id" /></el-select></el-form-item><el-form-item label="可见范围"><el-radio-group v-model="folderForm.visibility"><el-radio-button value="organization">企业可见</el-radio-button><el-radio-button value="department">部门可见</el-radio-button></el-radio-group></el-form-item><el-form-item label="最低编辑角色"><el-select v-model="folderForm.writeRole" style="width:100%"><el-option label="所有成员" value="member"/><el-option label="部门管理员" value="department_admin"/><el-option label="仅企业管理员" value="enterprise_admin"/></el-select></el-form-item></el-form><template #footer><el-button @click="folderDialog=false">取消</el-button><el-button type="primary" @click="createFolder">创建</el-button></template></el-dialog>
    <el-dialog v-model="moveDialog" title="调整企业文档" width="460px"><el-form label-position="top"><el-form-item label="部门"><el-select v-model="moveForm.departmentId" clearable style="width:100%"><el-option v-for="department in overview.departments" :key="department.id" :label="department.name" :value="department.id" /></el-select></el-form-item><el-form-item label="文件夹"><el-select v-model="moveForm.folderId" clearable style="width:100%"><el-option v-for="folder in matchingFolders" :key="folder.id" :label="folder.name" :value="folder.id" /></el-select></el-form-item><el-form-item label="企业成员权限"><el-radio-group v-model="moveForm.accessLevel"><el-radio-button label="read">只读</el-radio-button><el-radio-button label="comment">可评论</el-radio-button><el-radio-button label="edit">可编辑</el-radio-button></el-radio-group></el-form-item></el-form><template #footer><el-button @click="moveDialog=false">取消</el-button><el-button type="primary" @click="moveDocument">确认调整</el-button></template></el-dialog>
    <el-dialog v-model="approvalDialog" title="发起文档审批" width="460px"><el-form label-position="top"><el-form-item label="审批类型"><el-select v-model="approvalForm.approvalType" style="width:100%"><el-option label="常规评审" value="review"/><el-option label="发布审批" value="publish"/><el-option label="合同审批" value="contract"/><el-option label="归档审批" value="archive"/></el-select></el-form-item><el-form-item label="审批人用户 ID"><el-input v-model="approvalForm.approverUserId" /></el-form-item></el-form><template #footer><el-button @click="approvalDialog=false">取消</el-button><el-button type="primary" @click="submitApproval">提交审批</el-button></template></el-dialog>
    <el-dialog v-model="shareDialog" title="创建安全分享" width="480px"><el-alert title="链接创建后只显示一次，请及时复制。" type="warning" :closable="false"/><el-form label-position="top" style="margin-top:16px"><el-form-item label="访问密码（可选）"><el-input v-model="shareForm.password" show-password /></el-form-item><el-form-item label="有效期"><el-select v-model="shareForm.expiresHours" style="width:100%"><el-option label="24 小时" :value="24"/><el-option label="7 天" :value="168"/><el-option label="30 天" :value="720"/><el-option label="永久" :value="0"/></el-select></el-form-item><el-form-item label="最大访问次数（0 表示不限）"><el-input-number v-model="shareForm.maxAccess" :min="0" :max="100000" style="width:100%"/></el-form-item></el-form><template #footer><el-button @click="shareDialog=false">取消</el-button><el-button type="primary" @click="createShare">创建分享</el-button></template></el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { enterpriseApi } from '../../api/enterprise'

const router = useRouter(), loading = ref(false), organizations = ref([]), activeOrganizationId = ref(''), activeTab = ref('files'), selectedFolderId = ref('')
const overview = ref({ organization:null, membership:null, departments:[], members:[], folders:[], documents:[], pendingApprovals:0, unreadNotifications:0 })
const approvals=ref([]), notifications=ref([]), audits=ref([]), shares=ref([])
const enterpriseStats=ref({summary:{},roles:[],departmentDocuments:[],approvalStatus:[],documentTrend:[],activityTrend:[],topUsers:[]})
const memberDialog=ref(false), folderDialog=ref(false), moveDialog=ref(false), approvalDialog=ref(false), shareDialog=ref(false)
const memberForm=ref({userId:'',departmentId:'',role:'member'}), folderForm=ref({name:'',departmentId:'',visibility:'organization',writeRole:'member'})
const registeredUsers=ref([]), memberUserLoading=ref(false)
let memberSearchSequence=0
const moveTarget=ref(null), moveForm=ref({departmentId:'',folderId:'',accessLevel:'edit'}), approvalTarget=ref(null), approvalForm=ref({approvalType:'review',approverUserId:''})
const shareTarget=ref(null), shareForm=ref({password:'',expiresHours:168,maxAccess:0})
const currentUserId=String(localStorage.getItem('userId')||'')
const isAdmin=computed(()=>['enterprise_admin','department_admin'].includes(overview.value.membership?.role))
const isEnterpriseAdmin=computed(()=>overview.value.membership?.role==='enterprise_admin')
const pendingCount=computed(()=>approvals.value.filter(a=>a.status==='pending'&&String(a.approver_user_id)===currentUserId).length)
const unreadNotifications=computed(()=>notifications.value.filter(n=>!n.is_read).length)
const visibleDocuments=computed(()=>selectedFolderId.value?overview.value.documents.filter(d=>d.folder_id===selectedFolderId.value):overview.value.documents)
const matchingFolders=computed(()=>overview.value.folders.filter(f=>!moveForm.value.departmentId||f.department_id===moveForm.value.departmentId))
const mergedTrend=computed(()=>{const byDay=new Map();for(const row of enterpriseStats.value.documentTrend||[]){const day=String(row.day).slice(0,10);byDay.set(day,{day,label:day.slice(5),documents:Number(row.value)||0,activities:0})}for(const row of enterpriseStats.value.activityTrend||[]){const day=String(row.day).slice(0,10);const item=byDay.get(day)||{day,label:day.slice(5),documents:0,activities:0};item.activities=Number(row.value)||0;byDay.set(day,item)}return [...byDay.values()].sort((a,b)=>a.day.localeCompare(b.day))})
const roleLabel=r=>({enterprise_admin:'企业管理员',department_admin:'部门管理员',member:'成员',guest:'访客'}[r]||r)
const departmentName=id=>overview.value.departments.find(d=>d.id===id)?.name||(id?'其他部门':'企业公共')
const formatTime=v=>v?new Date(v).toLocaleString():'-'
const folderDocumentCount=id=>overview.value.documents.filter(d=>d.folder_id===id).length
const memberCount=id=>overview.value.members.filter(m=>m.department_id===id).length
const approvalType=s=>({pending:'warning',approved:'success',rejected:'danger'}[s]||'info')
const approvalStatus=s=>({pending:'待审批',approved:'已通过',rejected:'已驳回'}[s]||s)
const approvalTypeLabel=s=>({review:'常规评审',publish:'发布审批',contract:'合同审批',archive:'归档审批'}[s]||s)
const accessLevelLabel=s=>({read:'只读',comment:'可评论',edit:'可编辑'}[s]||'可编辑')
const folderName=id=>overview.value.folders.find(folder=>String(folder.id)===String(id))?.name||(id?'其他文件夹':'企业根目录')
const documentOwnershipLabel=doc=>String(doc.user_id)===currentUserId?'我创建的文档':`${doc.owner_username||doc.username||'企业成员'} 创建`
const visibilityLabel=doc=>doc.department_id?'部门范围':'企业范围'
const documentSyncLabel=doc=>doc.sync_status==='pending'?'正在同步':doc.sync_status==='failed'?'同步失败':'已同步到企业空间'
const barWidth=(value,rows)=>`${Math.max(5,Math.round((Number(value)||0)/Math.max(1,...rows.map(row=>Number(row.value)||0))*100))}%`
const trendHeight=value=>`${Math.max(Number(value)?8:0,Math.round((Number(value)||0)/Math.max(1,...mergedTrend.value.flatMap(item=>[item.documents,item.activities]))*92))}px`
async function loadOrganizations(){const r=await enterpriseApi.organizations();organizations.value=r.data||[];const saved=localStorage.getItem('smartdoc.activeOrganizationId');if(!activeOrganizationId.value&&organizations.value.length)activeOrganizationId.value=organizations.value.some(org=>org.id===saved)?saved:organizations.value[0].id;if(activeOrganizationId.value)localStorage.setItem('smartdoc.activeOrganizationId',activeOrganizationId.value)}
async function loadAll(){if(!activeOrganizationId.value)return;loading.value=true;try{const [o,a,n,s]=await Promise.all([enterpriseApi.overview(activeOrganizationId.value),enterpriseApi.approvals(),enterpriseApi.notifications(),enterpriseApi.shares()]);overview.value={...overview.value,...o.data};approvals.value=a.data||[];notifications.value=n.data||[];shares.value=s.data||[];if(isEnterpriseAdmin.value){const [auditResult,statsResult]=await Promise.all([enterpriseApi.audits(activeOrganizationId.value),enterpriseApi.statistics(activeOrganizationId.value)]);audits.value=auditResult.data||[];enterpriseStats.value={...enterpriseStats.value,...statsResult.data}}else{audits.value=[];enterpriseStats.value={summary:{},roles:[],departmentDocuments:[],approvalStatus:[],documentTrend:[],activityTrend:[],topUsers:[]}}}finally{loading.value=false}}
async function selectOrganization(id){activeOrganizationId.value=id;localStorage.setItem('smartdoc.activeOrganizationId',id);selectedFolderId.value='';await loadAll()}
async function createOrganization(){try{const {value}=await ElMessageBox.prompt('请输入企业名称','创建企业',{inputPattern:/\S+/,inputErrorMessage:'企业名称不能为空'});await enterpriseApi.createOrganization({name:value.trim()});await loadOrganizations();activeOrganizationId.value=organizations.value.at(-1)?.id||activeOrganizationId.value;await loadAll();ElMessage.success('企业空间创建成功')}catch{}}
async function createDepartment(){try{const {value}=await ElMessageBox.prompt('请输入部门名称','新增部门',{inputPattern:/\S+/,inputErrorMessage:'部门名称不能为空'});await enterpriseApi.createDepartment(activeOrganizationId.value,{name:value.trim()});await loadAll()}catch{}}
const userDisplayName=user=>user?.nickname||user?.username||`用户 ${user?.id||''}`
const accountName=user=>user?.username||`用户 ID：${user?.user_id||user?.id||'-'}`
const isExistingMember=userId=>overview.value.members.some(member=>String(member.user_id)===String(userId))
async function searchRegisteredUsers(keyword=''){const sequence=++memberSearchSequence;memberUserLoading.value=true;try{const result=await enterpriseApi.registeredUsers(keyword);if(sequence===memberSearchSequence)registeredUsers.value=result.data||[]}finally{if(sequence===memberSearchSequence)memberUserLoading.value=false}}
async function openMemberDialog(){memberForm.value={userId:'',departmentId:isEnterpriseAdmin.value?'':(overview.value.membership?.department_id||''),role:'member'};memberDialog.value=true;await searchRegisteredUsers('')}
async function addMember(){if(!memberForm.value.userId){ElMessage.warning('请先选择已注册用户');return}await enterpriseApi.addMember(activeOrganizationId.value,memberForm.value);memberDialog.value=false;memberForm.value={userId:'',departmentId:'',role:'member'};registeredUsers.value=[];await loadAll();ElMessage.success('成员添加成功')}
async function removeMember(m){try{await ElMessageBox.confirm(`确定移除成员 ${accountName(m)} 吗？`,'移除成员',{type:'warning'});await enterpriseApi.removeMember(activeOrganizationId.value,m.user_id);await loadAll()}catch{}}
async function createFolder(){await enterpriseApi.createFolder(activeOrganizationId.value,folderForm.value);folderDialog.value=false;folderForm.value={name:'',departmentId:'',visibility:'organization',writeRole:'member'};await loadAll()}
function openDocument(d){sessionStorage.setItem('currentDocName',d.title);router.push(`/editor/${d.id}`)}
function openMoveDocument(d){moveTarget.value=d;moveForm.value={departmentId:d.department_id||'',folderId:d.folder_id||'',accessLevel:d.enterprise_access_level||'edit'};moveDialog.value=true}
async function moveDocument(){await enterpriseApi.moveDocument(moveTarget.value.id,{organizationId:activeOrganizationId.value,...moveForm.value});moveDialog.value=false;await loadAll();ElMessage.success('文档位置已更新')}
async function removeEnterpriseDocument(d){try{await ElMessageBox.confirm(`确定将《${d.title}》移出企业空间吗？个人工作台中的原文档会保留。`,'移出企业空间',{type:'warning'});await enterpriseApi.removeDocument(d.id);await loadAll();ElMessage.success('已移出企业空间，个人原件已保留')}catch{}}
function openApproval(d){approvalTarget.value=d;approvalForm.value={approvalType:'review',approverUserId:''};approvalDialog.value=true}
async function submitApproval(){await enterpriseApi.submitApproval({documentId:approvalTarget.value.id,...approvalForm.value});approvalDialog.value=false;await loadAll();ElMessage.success('审批已提交')}
async function decide(a,decision){let comment='';if(decision==='rejected'){try{({value:comment}=await ElMessageBox.prompt('请输入驳回原因','驳回审批',{inputPattern:/\S+/,inputErrorMessage:'请输入原因'}))}catch{return}}await enterpriseApi.decideApproval(a.id,{decision,comment});await loadAll()}
async function readNotice(n){if(!n.is_read){await enterpriseApi.readNotification(n.id);n.is_read=1}}
function openShare(d){shareTarget.value=d;shareForm.value={password:'',expiresHours:168,maxAccess:0};shareDialog.value=true}
async function createShare(){const r=await enterpriseApi.createShare({documentId:shareTarget.value.id,...shareForm.value});const url=`${location.origin}/share/${r.data.token}`;shareDialog.value=false;await navigator.clipboard.writeText(url);await loadAll();ElMessageBox.alert(url,'分享链接已创建并复制',{confirmButtonText:'知道了'})}
async function revokeShare(s){await enterpriseApi.revokeShare(s.id);await loadAll()}
onMounted(async()=>{await loadOrganizations();await loadAll()})
</script>

<style scoped>
.enterprise-page{height:100vh;display:flex;overflow:hidden;background:#f5f3f1;color:#4d4552}.enterprise-sidebar{position:sticky;top:0;width:246px;height:100vh;padding:24px 18px;background:#ece7ef;border-right:1px solid #ddd4e1;box-sizing:border-box}.brand{display:flex;align-items:center;gap:10px;font-size:20px;cursor:pointer}.brand-icon{width:38px;height:38px;display:grid;place-items:center;border-radius:12px;background:#8775a1;color:#fff}.back-workbench,.org-item,.create-org{width:100%;border:0;text-align:left;cursor:pointer}.back-workbench{margin:28px 0 24px;padding:10px 12px;border-radius:10px;background:transparent;color:#756a7c}.space-title{margin:0 10px 10px;font-size:12px;font-weight:700;color:#92879a}.org-item{display:flex;flex-direction:column;gap:3px;margin-bottom:7px;padding:12px;border-radius:11px;background:transparent;color:#574e5d}.org-item small{color:#94899d}.org-item.active{background:#fff;box-shadow:0 6px 18px rgba(74,58,82,.08)}.create-org{margin-top:10px;padding:11px;border:1px dashed #a99caf;border-radius:10px;background:transparent;color:#796a8a}.enterprise-main{flex:1;min-width:0;height:100vh;overflow-y:auto;overscroll-behavior:contain;padding:34px 42px 56px;scrollbar-gutter:stable}.enterprise-main::-webkit-scrollbar{width:9px}.enterprise-main::-webkit-scrollbar-track{background:transparent}.enterprise-main::-webkit-scrollbar-thumb{border:2px solid transparent;border-radius:99px;background:#c6bccc;background-clip:padding-box}.enterprise-main::-webkit-scrollbar-thumb:hover{background:#aa9bb3;background-clip:padding-box}.enterprise-header{display:flex;align-items:flex-end;justify-content:space-between}.enterprise-header p{margin:0;color:#91839a;font-size:11px;letter-spacing:1.8px}.enterprise-header h1{margin:8px 0 5px;font-size:30px}.enterprise-header span{color:#847a89}.metric-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin:28px 0}.metric-card{padding:20px;border:1px solid #e8e1e9;border-radius:16px;background:#fff}.metric-card span,.metric-card small{display:block;color:#8b818f}.metric-card strong{display:block;margin:10px 0 4px;font-size:28px}.metric-card.warning{background:#f7f0e8}.metric-card.accent{background:#eee9f4}.enterprise-tabs{padding:0 22px 22px;border:1px solid #e7e0e8;border-radius:18px;background:#fff}.panel-toolbar,.panel-title{display:flex;align-items:center;justify-content:space-between}.panel-toolbar h2,.panel-title h2{margin-bottom:5px}.panel-toolbar p,.panel-title p{margin:0;color:#918895}.folder-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(210px,1fr));gap:12px;margin:20px 0}.folder-card{display:flex;align-items:center;gap:13px;padding:16px;border:1px solid #e8e1ea;border-radius:13px;background:#faf9fa;text-align:left;color:inherit;cursor:pointer}.folder-card>.el-icon{font-size:27px;color:#8b78a2}.folder-card div{display:flex;flex-direction:column;gap:4px}.folder-card small{color:#918895}.folder-card.selected{border-color:#a99ab8;background:#f1ecf4}.document-table{border:1px solid #ece6ed;border-radius:13px;overflow:hidden}.table-head,.table-row{display:grid;grid-template-columns:minmax(250px,2fr) 1fr 1fr 330px;align-items:center;padding:12px 16px}.table-head{background:#f5f1f6;color:#776d7c;font-size:12px;font-weight:700}.table-row{border-top:1px solid #eee9ef}.document-link{display:flex;align-items:center;gap:8px;border:0;background:transparent;color:#5e506c;font-weight:600;cursor:pointer;text-align:left}.split-panels{display:grid;grid-template-columns:340px 1fr;gap:16px}.content-panel{padding:8px 4px}.split-panels .content-panel{padding:18px;border:1px solid #ebe5ec;border-radius:14px}.department-item,.member-row,.share-row{display:flex;align-items:center;gap:11px;padding:13px 5px;border-bottom:1px solid #eee9ef}.department-item div,.member-row>div,.share-row>div{flex:1;display:flex;flex-direction:column;gap:3px}.department-item small,.member-row small,.share-row small{color:#928898}.approval-card{display:flex;align-items:center;justify-content:space-between;padding:18px;margin:12px 0;border:1px solid #e9e3ea;border-radius:14px}.approval-card h3{margin:10px 0 6px}.approval-card p{margin:0 0 7px;color:#7f7484}.notification-item{width:100%;display:flex;gap:12px;padding:16px;border:0;border-bottom:1px solid #eee9ef;background:transparent;text-align:left;cursor:pointer}.notice-dot{width:8px;height:8px;margin-top:6px;border-radius:50%;background:#c8c1ca}.notification-item.unread .notice-dot{background:#8775a1}.notification-item div{flex:1}.notification-item p{margin:5px 0;color:#7e7483}.notification-item small{color:#9a919e}@media(max-width:1000px){.enterprise-sidebar{width:210px}.enterprise-main{padding:25px 25px 48px}.metric-grid{grid-template-columns:repeat(2,1fr)}.split-panels{grid-template-columns:1fr}.table-head,.table-row{grid-template-columns:2fr 1fr 230px}.table-head span:nth-child(3),.table-row>span:nth-child(3){display:none}}
.analytics-panel{padding-top:18px}.analytics-summary{display:grid;grid-template-columns:repeat(6,1fr);gap:10px;margin:20px 0}.analytics-summary>div{padding:15px;border-radius:13px;background:#f4f0f5}.analytics-summary span{display:block;color:#8b818f;font-size:12px}.analytics-summary strong{display:block;margin-top:8px;font-size:24px}.analytics-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.analytics-card{padding:18px;border:1px solid #ebe5ec;border-radius:14px;background:#faf9fa;min-height:180px}.analytics-card.wide{grid-column:1/-1}.analytics-card h3{margin:0 0 16px;font-size:15px}.bar-row{display:grid;grid-template-columns:110px minmax(70px,1fr) 34px;align-items:center;gap:10px;margin:12px 0;font-size:12px}.bar-row>span{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.bar-row>div{height:8px;overflow:hidden;border-radius:99px;background:#e8e1eb}.bar-row i{display:block;height:100%;border-radius:99px;background:#9785ad}.bar-row b{text-align:right}.trend-legend{display:flex;justify-content:flex-end;gap:15px;color:#847a89;font-size:12px}.trend-legend span{display:flex;align-items:center;gap:5px}.trend-legend i{width:8px;height:8px;border-radius:50%}.doc-dot,.document-bar{background:#a899bb}.activity-dot,.activity-bar{background:#d1bba9}.trend-chart{height:135px;display:flex;align-items:flex-end;justify-content:space-around;gap:6px;padding-top:10px;border-bottom:1px solid #e4dde6}.trend-column{height:125px;flex:1;display:flex;flex-direction:column;justify-content:flex-end;align-items:center;gap:7px}.trend-bars{height:96px;display:flex;align-items:flex-end;gap:3px}.trend-bars i{display:block;width:8px;min-height:0;border-radius:4px 4px 0 0}.trend-column small{font-size:10px;color:#918895}.top-user,.approval-stat{display:flex;align-items:center;gap:10px;padding:9px 0;border-bottom:1px solid #ece6ed}.top-user em{width:23px;height:23px;display:grid;place-items:center;border-radius:50%;background:#ece6f0;font-style:normal}.top-user span{flex:1}.approval-stat{justify-content:space-between}@media(max-width:1200px){.analytics-summary{grid-template-columns:repeat(3,1fr)}}@media(max-width:760px){.analytics-grid{grid-template-columns:1fr}.analytics-summary{grid-template-columns:repeat(2,1fr)}}
.member-search-tip{margin-top:7px;color:#94899d;font-size:12px;line-height:1.5}.member-user-option{width:100%;display:flex;align-items:center;gap:10px;padding:5px 0}.member-user-option>div{min-width:0;flex:1;display:flex;flex-direction:column;line-height:1.35}.member-user-option strong,.member-user-option small{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.member-user-option small{color:#94899d;font-size:11px}
.document-link>span,.table-row>span{min-width:0}.document-link strong,.document-link small,.table-row>span>strong,.table-row>span>small{display:block}.document-link strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:inherit}.document-link small,.table-row>span>small{margin-top:4px;color:#879087;font-size:10px;font-weight:400}.table-row>span>strong{font-size:12px;font-weight:600}.document-link{align-items:flex-start}.document-link>.el-icon{margin-top:2px;flex:none}
</style>
