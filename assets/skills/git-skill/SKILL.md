# Git Skill for agent-toolbox

## Description
本技能用于 agent-toolbox 项目中的 Git 操作指南，涵盖常用命令、权限问题解决方案及最佳实践。

## When to use
- 用户需要克隆、回退、重置或管理 agent-toolbox 仓库时
- 遇到 Git 权限错误（如 dubious ownership）时
- 需要批量处理 Git 操作时
- 使用 dulwich 或内嵌 Git 二进制时

## Git 操作指南

### 基础命令
- `git clone <url> <target_dir>`：克隆仓库到指定目录
- `git reset --hard <commit>`：硬回退到指定 commit
- `git status`：查看当前状态
- `git log --oneline`：查看简洁提交历史

### 常见问题与解决方案

#### 1. 权限错误：dubious ownership
当执行 Git 命令时遇到 "fatal: detected dubious ownership in repository"，使用以下命令配置安全目录：
```bash
git config --global safe.directory /path/to/repo
```
或使用 `-c safe.directory=/path/to/repo` 临时绕过。

#### 2. 符号链接权限问题
在 Android 环境中，创建符号链接可能失败（Permission denied）。使用 `-c core.symlinks=false` 禁用符号链接：
```bash
git -C /path/to/repo -c core.symlinks=false reset --hard <commit>
```

#### 3. 段错误与 dulwich 回退
如果内嵌 Git 出现段错误（退出码 139），系统会自动回退到 dulwich 纯 Python 实现。此时某些 Git 子命令（如 `-C`）可能不支持，建议直接进入目录操作：
```bash
cd /path/to/repo
git reset --hard <commit>
```

#### 4. 克隆失败与清理
克隆前确保目标目录不存在或已删除：
```bash
rm -rf /sdcard/Download/agent-toolbox /sdcard/Download/agent-toolbox.git
git clone https://github.com/Aasdqwe1/agent-toolbox.git /sdcard/Download/agent-toolbox
```

### 常用工作流

#### 回退到指定 commit（完整流程）
1. 删除旧目录（如有）：`rm -rf /sdcard/Download/agent-toolbox`
2. 克隆仓库：`git clone https://github.com/Aasdqwe1/agent-toolbox.git /sdcard/Download/agent-toolbox`
3. 执行回退：`git -C /sdcard/Download/agent-toolbox -c core.symlinks=false reset --hard <commit>`

#### 更新到最新版本
```bash
cd /sdcard/Download/agent-toolbox
git pull origin main
```

#### 创建分支与切换
```bash
cd /sdcard/Download/agent-toolbox
git checkout -b new-feature
git branch
```

### 注意事项
- 路径优先使用 `/sdcard/Download/`，避免使用 `/storage/emulated/0/` 可能导致的路径解析问题
- 大量文件操作时建议使用 `-c core.symlinks=false` 避免符号链接权限错误
- 若 Git 操作连续失败 2 次，建议改用 Python 的 dulwich 库直接操作

### 示例

#### 克隆并回退到特定 commit
```bash
rm -rf /sdcard/Download/agent-toolbox
git clone https://github.com/Aasdqwe1/agent-toolbox.git /sdcard/Download/agent-toolbox
git -C /sdcard/Download/agent-toolbox -c core.symlinks=false reset --hard 74c3cdae15fd6882da075cc06fa2a46edbbba91f
```

#### 仅重置索引（不修改工作区）
```bash
git -C /sdcard/Download/agent-toolbox reset --mixed <commit>
```

## 技能工具
本技能不提供直接调用的工具，而是作为知识库供 AI 在执行 Git 相关任务时参考。

### 5. 克隆失败：目录已存在或残留 .git
克隆前应先删除目标目录及可能存在的 .git 目录：
```bash
rm -rf /sdcard/Download/agent-toolbox /sdcard/Download/agent-toolbox.git
```
若出现 `FileExistsError`，说明有残留 .git 文件夹，同样清理后重试。

### 6. Git 配置命令参数错误
使用 `git config --global --add safe.directory` 时可能报错 "wrong number of arguments"，应改用：
```bash
git config --global safe.directory /path/to/repo
```
或使用 `-c safe.directory` 临时指定。

### 7. commit 时遇到锁文件（HEAD.lock / main.lock）
若执行 `git commit` 提示无法创建锁文件，说明之前的 Git 进程未正常结束。删除所有 .lock 文件即可：
```bash
find .git -name '*.lock' -delete
```
然后重新 commit。

### 8. 内嵌 Git 不支持 -C 参数
当使用内嵌静态 Git 二进制（或 dulwich 回退模式）时，`-C` 子命令可能不被支持。应改为先 `cd` 到仓库目录再执行：
```bash
cd /sdcard/Download/agent-toolbox
git reset --hard <commit>
```

### 9. 符号链接导致 reset 失败（Permission denied）
在 Android 文件系统上，某些符号链接操作会因权限不足失败。使用 `-c core.symlinks=false` 可绕过：
```bash
git -C /sdcard/Download/agent-toolbox -c core.symlinks=false reset --hard <commit>
```

### 10. 分段错误（Segmentation fault）
内嵌 Git 二进制在某些设备上可能触发段错误（退出码 139），此时会自动回退到 dulwich 纯 Python 实现。若问题持续，建议改用 Python 脚本操作（如 dulwich 或直接下载 ZIP 解压）。

