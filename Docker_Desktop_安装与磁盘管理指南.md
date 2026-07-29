# Docker Desktop for Windows 从零安装与磁盘空间管理指南

> **适用读者**：Windows 新手用户，第一次安装 Docker Desktop
> **目标**：正确安装 + 防止虚拟磁盘无限膨胀

---

## 📥 第一部分：下载与安装

### 1.1 检查你的 Windows 版本

打开「设置」→「系统」→「关于」，查看"系统类型"和"Windows 规格"。

**要求**：
- Windows 11（或 Windows 10 22H2 以上）
- 64 位 CPU，支持虚拟化
- 至少 8GB 内存（推荐 16GB）
- D 盘预留至少 30GB 空闲空间

### 1.2 开启 CPU 虚拟化

在安装前，需要确保 BIOS 中开启了虚拟化技术。

**检查方法**：
1. 打开 **任务管理器**（`Ctrl + Shift + Esc`）
2. 点击 **「性能」** 标签
3. 查看底部 **「虚拟化」** 状态

如果显示 **"已启用"** → 跳过这一步。  
如果显示 **"已禁用"** → 需要重启电脑，开机时按 `F2`/`Del`/`F10` 进入 BIOS，找到 **Intel VT-x** 或 **AMD-V** 选项，设为 **Enabled**，保存退出。

### 1.3 启用 WSL2（Windows 子系统 for Linux）

Docker Desktop for Windows 依赖 WSL2。以**管理员身份**打开 **PowerShell**（右键 → 以管理员身份运行），执行：

```powershell
# 启用 WSL 功能
dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart

# 启用虚拟机平台
dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart

# 重启电脑
Restart-Computer
```

重启后，再次打开管理员 PowerShell，执行：

```powershell
# 设置 WSL2 为默认版本
wsl --set-default-version 2
```

### 1.4 下载并安装 Docker Desktop

1. 打开浏览器，访问官方下载页面：
   **https://www.docker.com/products/docker-desktop/**
   
2. 点击 **"Download for Windows"**（约 600MB）

3. 下载完成后，双击 `Docker Desktop Installer.exe`

4. 安装向导中：
   - ✅ **勾选「Use WSL 2 instead of Hyper-V」**（重要）
   - ✅ **勾选「Add shortcut to desktop」**（可选）
   - 其余默认即可

5. 点击 **OK** 开始安装，等待约 5-10 分钟

6. 安装完成后，**务必重启电脑**

### 1.5 首次启动

1. 重启后，桌面上会出现 **Docker Desktop** 图标，双击打开
2. 接受许可协议
3. 跳过登录（点击 **"Continue without signing in"**）
4. 等待底部状态栏的 Docker 鲸鱼图标停止转动 → 变成稳定状态 ✅

> ⏳ 首次启动需要 2-5 分钟，Docker 会在后台下载 WSL2 内核

---

## ⚙️ 第二部分：安装后的关键配置

> **目标**：把虚拟磁盘放到 D 盘 + 限制大小 + 限制 WSL2 内存

### 2.1 🔥 最重要的一步：迁移虚拟磁盘到 D 盘

Docker Desktop 默认把虚拟磁盘文件放在 C 盘，随着使用会越来越大（可能涨到 100GB+）。**一定要迁移到 D 盘。**

#### 第一步：先完全退出 Docker Desktop

1. 右键系统托盘（右下角）的 Docker 鲸鱼图标
2. 点击 **"Quit Docker Desktop"**

#### 第二步：在 D 盘创建存放目录

```powershell
# 在 D 盘创建 Docker 数据目录
mkdir D:\DockerData

# 创建 WSL 数据子目录
mkdir D:\DockerData\wsl
```

#### 第三步：关闭所有 WSL 实例

```powershell
# 查看当前运行的 WSL 实例
wsl --list --verbose

# 停止所有 WSL 实例
wsl --shutdown
```

#### 第四步：导出、删除、导入 WSL 发行版

```powershell
# 查看 WSL 发行版名称（通常是 docker-desktop-data）
wsl --list

# ── 导出 docker-desktop-data 到 D 盘备份 ──
wsl --export docker-desktop-data D:\DockerData\wsl\docker-desktop-data.tar

# ── 注销原来的发行版 ──
wsl --unregister docker-desktop-data

# ── 导入到 D 盘新位置 ──
wsl --import docker-desktop-data D:\DockerData\wsl D:\DockerData\wsl\docker-desktop-data.tar --version 2

# ── （可选）删除导出的 tar 文件 ──
del D:\DockerData\wsl\docker-desktop-data.tar
```

> ⚠️ 如果 `wsl --list` 没有显示 `docker-desktop-data`，先启动一次 Docker Desktop（它会在启动过程中自动创建 WSL 发行版），然后运行 `docker run hello-world` 触发创建，再退出执行以上命令。

#### 第五步：验证是否成功

```powershell
# 重启 Docker Desktop（双击桌面图标）

# 打开 PowerShell，执行：
wsl --list --verbose
# 应该看到 docker-desktop-data 的状态是 Running

# 确认磁盘位置
dir D:\DockerData\wsl\ext4.vhdx
# 应该能看到这个文件
```

### 2.2 限制虚拟磁盘最大大小

> **注意**：新版 Docker Desktop 可能已移除图形界面的磁盘上限设置，可通过 `.wslconfig` 间接控制。

如果 Docker Desktop 中有此选项：

1. 打开 **Docker Desktop**
2. 点击顶部齿轮图标 ⚙️ **Settings**
3. 左侧菜单点击 **Resources** → **Advanced**
4. 找到 **Disk image size**，设为 **60 GB**（可根据你的 D 盘空间调整）
5. 点击右下角 **Apply & Restart**

### 2.3 创建 .wslconfig 限制 WSL2 资源

这个文件可以防止 WSL2 吃光你的内存和 CPU。

1. 打开 PowerShell，执行：

```powershell
notepad "$env:USERPROFILE\.wslconfig"
```

2. 如果提示"是否创建新文件"，点**是**

3. 粘贴以下内容：

```ini
[wsl2]
# 限制内存上限（单位 GB），防止 WSL2 疯狂吃内存
memory=4GB

# 限制 CPU 核心数（填你 CPU 的逻辑核心数，通常 4 即可）
processors=4

# 限制交换空间大小（单位 GB），防止磁盘文件膨胀
swap=2GB

# 磁盘自动回收，让 Docker 尝试回收未使用的空间
autoMemoryReclaim=gradual
```

4. **保存文件**，关闭记事本

5. **让配置生效**：

```powershell
# 关闭所有 WSL 实例
wsl --shutdown

# 重启 Docker Desktop
```

> 💡 改完 `.wslconfig` 后，必须执行 `wsl --shutdown` 再重启 Docker Desktop 才会生效。

### 2.4 验证配置是否生效

```powershell
# 确认 WSL2 版本
wsl --list --verbose
```

---

## 🧹 第三部分：日常维护习惯

养成定期清理的习惯，磁盘空间就不会暴涨。

### 3.1 每周一键清理

```powershell
# 清理所有未使用的容器、镜像、网络、构建缓存
docker system prune -a --volumes

# 如果确认要清理，输入 y 回车
```

**各参数含义**：
- `prune` = 修剪，把没用的东西剪掉
- `-a` = 连那些没有被使用的镜像也删掉
- `--volumes` = 连悬空的数据卷也清掉

> ⚠️ **注意**：这条命令会删除所有没在运行的容器和未被使用的镜像。

### 3.2 更温和的清理（只删最没用的）

```powershell
# 只删除已停止的容器
docker container prune

# 只删除悬空镜像（没有标签的旧镜像）
docker image prune

# 只删除构建缓存
docker builder prune
```

### 3.3 查看当前磁盘占用

```powershell
# 查看 Docker 虚拟磁盘文件大小
dir D:\DockerData\wsl\ext4.vhdx

# 查看所有容器的磁盘占用
docker system df
```

**输出示例**：
```
TYPE                TOTAL    ACTIVE    SIZE      RECLAIMABLE
Images              12       5         3.2GB     1.1GB (34%)
Containers          8        3         500MB     200MB (40%)
Local Volumes       6        2         1.5GB     800MB (53%)
Build Cache         -        -         2.0GB     1.2GB (60%)
```

> 💡 **RECLAIMABLE** 列显示的就是**可以回收的空间**。如果数字很大，就该 `docker system prune` 了。

---

## 🚨 第四部分：空间已满的紧急处理

如果你发现磁盘空间快满了，或者 `ext4.vhdx` 文件已经涨到了几十 GB，可以用这个方法把已删除文件占用的空间真正释放出来。

### 4.1 原理说明

Docker 的虚拟磁盘（vhdx）比较特殊：**你删了容器和镜像，空间标记为"可用"，但 vhdx 文件不会自动缩小**。就像一个大水缸，你把水倒掉了，但缸子体积不变。我们需要手动压缩它。

### 4.2 压缩步骤

#### 第一步：清理无用数据

```powershell
# 彻底清理
docker system prune -a --volumes
```

#### 第二步：关闭所有 WSL 实例

```powershell
# 彻底关闭 WSL
wsl --shutdown

# 确认 Docker Desktop 已退出（右键系统托盘 → Quit）
```

#### 第三步：使用 diskpart 压缩

> ⚠️ **【重要警告】**
> - **关闭所有 WSL 实例和 Docker Desktop** 后再操作，否则可能损坏磁盘
> - 如果文件很大（>50GB），压缩过程可能需要 10-30 分钟
> - **建议先备份**：把 `ext4.vhdx` 复制一份到其他位置

```powershell
# 以管理员身份打开 PowerShell
# （右键开始菜单 → Windows PowerShell (管理员)）

# 启动 diskpart 工具
diskpart
```

在 `DISKPART>` 提示符下依次执行：

```
# 选择虚拟磁盘文件
select vdisk file="D:\DockerData\wsl\ext4.vhdx"

# 以只读方式挂载（检查是否有问题）
attach vdisk readonly

# 压缩磁盘（核心命令！）
compact vdisk

# 分离磁盘
detach vdisk

# 退出 diskpart
exit
```

#### 第四步：验证结果

```powershell
# 查看压缩后的文件大小
dir D:\DockerData\wsl\ext4.vhdx

# 重启 Docker Desktop
```

**压缩前后对比**：
```
压缩前：ext4.vhdx = 85.3 GB  ← 虚胖
压缩后：ext4.vhdx = 12.1 GB  ← 回归真实大小
```

### 4.3 如果压缩失败怎么办

```powershell
# 先检查 WSL 是否完全关闭
wsl --list --verbose
# 确保所有实例都是 Stopped

# 如果还是失败，重启电脑后再试一次
Restart-Computer
```

---

## 📋 常见问题 FAQ

### Q1：迁移到其他盘后，Docker 还能正常工作吗？

✅ 完全正常。WSL2 的 `docker-desktop-data` 发行版只是存储位置变了，Docker 内部会用自动定位到新位置。

### Q2：磁盘空间还是不够怎么办？

```powershell
# 1. 查看 Docker 占用
docker system df

# 2. 看 vhdx 文件实际大小
dir D:\DockerData\wsl\ext4.vhdx

# 3. 清理构建缓存（通常很大）
docker builder prune -a
```

### Q3：我可以用 C 盘默认位置吗？

推荐迁移。如果 C 盘是 SSD 且空间充足（200GB+ 空闲），可以先用默认位置。

### Q4：做完 .wslconfig 配置后，Docker 变慢了？

检查配置是否合理：
- `memory=4GB` — 如果电脑只有 8GB 内存，设 4GB 是合理的
- 如果电脑有 32GB 内存，可以设到 8GB
- `processors=4` — 一般够用，如果经常并行编译可以提高到 6

---

## 🎯 总结：一劳永逸的配置清单

```
□ 第一步：下载安装 Docker Desktop
□ 第二步：虚拟机磁盘迁移到 D 盘
□ 第三步：创建 .wslconfig 限制内存 4GB + 交换 2GB
□ 第四步：每周执行 docker system prune -a --volumes
□ 第五步：每月检查 ext4.vhdx 大小，必要时 diskpart 压缩
```
