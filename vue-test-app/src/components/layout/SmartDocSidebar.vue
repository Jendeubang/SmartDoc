<template>
  <el-aside width="240px" class="feishu-aside smartdoc-sidebar">
    <div class="aside-top">
      <div class="brand" aria-label="SmartDoc 文档智能处理平台">
        <div class="logo-box"><el-icon><Cpu /></el-icon></div>
        <span class="brand-text">SmartDoc</span>
      </div>
      <nav class="nav-menu" aria-label="主导航">
        <button type="button" class="nav-item" :class="{ active: active === 'workbench' }" @click="$emit('navigate', 'workbench')"><el-icon><Monitor /></el-icon><span>我的工作台</span></button>
        <button type="button" class="nav-item" :class="{ active: active === 'library' }" @click="$emit('navigate', 'library')"><el-icon><FolderOpened /></el-icon><span>云端文档库</span></button>
        <button type="button" class="nav-item ai-chat-nav" @click="$emit('navigate', 'ai')"><el-icon><ChatLineSquare /></el-icon><span>SmartDoc AI 对话</span></button>
        <button type="button" class="nav-item toolbox-nav" @click="$emit('navigate', 'toolbox')"><el-icon><MagicStick /></el-icon><span>文档处理工具箱</span></button>
        <button type="button" class="nav-item enterprise-nav" @click="$emit('navigate', 'enterprise')"><el-icon><OfficeBuilding /></el-icon><span>企业空间</span></button>
        <button v-if="admin" type="button" class="nav-item aiops-nav" @click="$emit('navigate', 'aiops')"><el-icon><Cpu /></el-icon><span>AI Ops 运维中心</span></button>
      </nav>
    </div>

    <div class="aside-bottom">
      <el-dropdown trigger="click" placement="top-start" @command="$emit('user-command', $event)">
        <button type="button" class="user-profile" aria-label="打开个人菜单">
          <el-avatar :size="32" :src="avatar || undefined" class="sidebar-avatar">{{ userName.charAt(0).toUpperCase() }}</el-avatar>
          <span class="username">{{ userName }}</span>
          <el-icon class="more-icon"><MoreFilled /></el-icon>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="profile" icon="User">个人信息</el-dropdown-item>
            <el-dropdown-item command="logout" icon="SwitchButton" class="danger-menu-item">退出系统</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </el-aside>
</template>

<script setup>
defineProps({
  active: { type: String, default: 'workbench' },
  userName: { type: String, default: 'User' },
  avatar: { type: String, default: '' },
  admin: { type: Boolean, default: false }
})
defineEmits(['navigate', 'user-command'])
</script>

<style scoped>
.smartdoc-sidebar {
  width: 240px !important;
  min-width: 240px;
  max-width: 240px;
  height: 100%;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  border-right: 1px solid var(--smartdoc-border);
  background: rgba(255, 253, 249, .91);
  box-sizing: border-box;
}

.aside-top {
  min-height: 0;
  flex: 1;
  padding: 24px 16px;
  overflow-y: auto;
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 20px;
  padding: 0 8px;
  color: var(--smartdoc-text);
  font-size: 18px;
  font-weight: 700;
}

.logo-box {
  width: 32px;
  height: 32px;
  display: flex;
  flex: 0 0 32px;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--smartdoc-primary);
  color: #fff;
  font-size: 20px;
  box-shadow: 0 8px 18px rgba(93, 117, 101, .22);
}

.brand-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-menu {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.nav-item {
  width: 100%;
  min-height: 42px;
  margin: 0 0 4px;
  padding: 12px 16px;
  border: 0;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 10px;
  background: transparent;
  color: #4d5951;
  text-align: left;
  font: inherit;
  font-size: 14px;
  font-weight: 600;
  line-height: 18px;
  cursor: pointer;
  transition: color .18s ease, background .18s ease, box-shadow .18s ease;
}

.nav-item > span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.nav-item .el-icon {
  flex: 0 0 auto;
  color: #68796d;
  font-size: 17px;
}

.nav-item:hover,
.nav-item.active {
  background: var(--smartdoc-primary-soft);
  color: var(--smartdoc-primary-active);
}

.nav-item.active {
  box-shadow: inset 3px 0 0 var(--smartdoc-primary);
}

.nav-item:hover .el-icon,
.nav-item.active .el-icon {
  color: var(--smartdoc-primary-active);
}

.aside-bottom {
  flex: 0 0 auto;
  padding: 16px;
  border-top: 1px solid var(--smartdoc-border);
  background: rgba(255, 253, 249, .96);
}

.aside-bottom :deep(.el-dropdown) {
  width: 100%;
  display: block;
}

.user-profile {
  width: 100%;
  min-height: 48px;
  padding: 8px;
  border: 0;
  border-radius: 8px;
  display: flex;
  align-items: center;
  gap: 10px;
  background: transparent;
  color: var(--smartdoc-text);
  cursor: pointer;
  text-align: left;
  transition: background .18s ease;
}

.user-profile:hover {
  background: var(--smartdoc-primary-subtle);
}

.sidebar-avatar {
  flex: 0 0 auto;
  background: var(--smartdoc-primary);
  color: #fff;
  font-weight: 700;
}

.username {
  min-width: 0;
  flex: 1;
  overflow: hidden;
  color: #465249;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
  font-weight: 600;
}

.more-icon {
  flex: 0 0 auto;
  color: var(--smartdoc-text-muted);
}

.danger-menu-item {
  color: var(--smartdoc-danger) !important;
}
</style>
