#!/usr/bin/env python3
"""
Git 命令桥接脚本 — 基于 dulwich (纯 Python Git 实现)
在 Android 内嵌 Python 环境中提供常用 git 命令支持。

用法: python git_bridge.py <git 子命令> [参数...]
例如: python git_bridge.py clone https://github.com/user/repo.git
      python git_bridge.py add -A
      python git_bridge.py commit -m "message"
      python git_bridge.py push origin main
"""

import sys
import os
import subprocess

# 确保 pip 安装的包在 sys.path 中
_site_dir = os.path.join(os.environ.get("PYTHONHOME", ""), "lib", "python3.14", "site-packages")
if _site_dir not in sys.path:
    sys.path.insert(0, _site_dir)

# 内嵌 Python 的 sys.executable 为空字符串，pip 内部 subprocess 会用它
# 调用 python 子进程导致 PermissionError。设为一个占位值避免空字符串崩溃；
# 配合 --no-build-isolation 可避免 pip 启动子进程构建
if not getattr(sys, "executable", "") or not os.path.isfile(getattr(sys, "executable", "")):
    sys.executable = "/system/bin/sh"  # 占位，pip 不会真的执行它（--no-build-isolation + pure python wheel）


def ensure_dulwich():
    """确保 dulwich 已安装，未安装时自动 pip install"""
    try:
        import dulwich
        return True
    except ImportError:
        print("首次使用 git，正在安装 dulwich (纯 Python Git 库)...")
        try:
            # 内嵌 Python 无 sys.executable，不能 subprocess 调 python -m pip
            # 直接用 pip._internal 在进程内安装
            # --no-build-isolation 避免 pip 启动子进程（subprocess 会因 sys.executable 无效而失败）
            # --only-binary :all: 强制使用预编译 wheel，避免从 sdist 构建（需要 subprocess）
            from pip._internal import main as pip_main
            ret = pip_main([
                "install", "--no-cache-dir", "--quiet",
                "--no-build-isolation",
                "--only-binary", ":all:",
                "dulwich",
            ])
            # pip_main 可能返回退出码也可能调用 sys.exit()
            if ret is None:
                ret = 0
            if ret != 0:
                print(f"pip install dulwich 失败 (退出码 {ret})")
                print("可尝试手动下载 dulwich wheel 解压到:", _site_dir)
                return False
            # 重新尝试导入
            import importlib
            importlib.invalidate_caches()
            import dulwich
            print("dulwich 安装完成。")
            return True
        except SystemExit as se:
            # pip 可能调用 sys.exit()，捕获后判断
            if se.code is not None and se.code != 0:
                print(f"pip install dulwich 失败 (SystemExit: {se.code})")
                return False
            import importlib
            importlib.invalidate_caches()
            try:
                import dulwich
                print("dulwich 安装完成。")
                return True
            except ImportError:
                print("dulwich 安装后仍无法导入")
                return False
        except Exception as e:
            print(f"dulwich 安装失败: {e}")
            print("可尝试手动下载 dulwich wheel 解压到:", _site_dir)
            return False


def cmd_init(args):
    """git init [目录]"""
    from dulwich.repo import Repo
    target = args[0] if args else "."
    repo = Repo.init(target)
    print(f"已初始化空 Git 仓库: {os.path.abspath(target)}/.git/")
    return 0


def cmd_clone(args):
    """git clone <url> [目录]

    Android /sdcard 等外部存储不支持符号链接（os.symlink → PermissionError），
    而本仓库 src/main/AndroidManifest.xml 是符号链接。clone 时关闭自动
    checkout，手动 checkout 工作树（符号链接解析为目标文件 blob 内容写成
    普通文件），然后用 porcelain.add 把工作树文件批量加到 index，确保
    index 与工作树一致，git status 不会误报文件"已删除"。

    之前用 build_index_from_tree 尝试同时构建 index + checkout，但实际
    index 没有正确写入（git status 仍报"已删除并暂存"）。改用 porcelain.add
    基于"已存在的工作树文件"构建 index，最可靠。
    """
    import os
    from dulwich import porcelain
    if not args:
        print("用法: git clone <url> [目录]", file=sys.stderr)
        return 1
    url = args[0]
    target = args[1] if len(args) > 1 else None
    print(f"正在克隆 {url} ...")
    # checkout=False: 避免符号链接在 /sdcard 等不支持 symlink 的文件系统上失败
    try:
        repo = porcelain.clone(url, target, checkout=False)
    except TypeError:
        # 老版本 dulwich 无 checkout 参数，回退到默认 clone
        repo = porcelain.clone(url, target)
        dest = target or url.rstrip("/").split("/")[-1].replace(".git", "")
        print(f"克隆完成: {dest}")
        return 0
    dest = target or url.rstrip("/").split("/")[-1].replace(".git", "")
    # 手动 checkout 工作树 + 构建与 HEAD tree 一致的 index
    # （_manual_checkout 内部同时写工作树文件和 index，符号链接记录原始 mode+sha）
    _manual_checkout(repo)
    print(f"克隆完成: {dest}")
    return 0


def _resolve_symlink_target(repo, link_path_in_repo, link_target):
    """解析符号链接，返回目标 blob 内容（bytes），目标不在仓库内返回 None。

    link_path_in_repo: 符号链接在仓库内的路径（bytes），如 b'src/main/AndroidManifest.xml'
    link_target: 链接目标字符串，如 '../../AndroidManifest.xml'
    """
    import os
    from dulwich.objects import Tree, Blob
    # 用 posixpath 风格解析（仓库内路径用 / 分隔）
    link_dir = link_path_in_repo.rsplit(b"/", 1)[0] if b"/" in link_path_in_repo else b""
    resolved = os.path.normpath(os.path.join(link_dir.decode("utf-8", "replace")
                                             if link_dir else ".", link_target))
    parts = [p for p in resolved.replace("\\", "/").split("/") if p and p != "."]
    try:
        head = repo.head()
        tree = repo[repo[head].tree]
    except Exception:
        return None
    for i, part in enumerate(parts):
        entry = None
        for e in tree.iteritems():
            if e.path == part.encode("utf-8"):
                entry = e
                break
        if entry is None:
            return None
        obj = repo[entry.sha]
        if i == len(parts) - 1:
            return obj.data if isinstance(obj, Blob) else None
        if not isinstance(obj, Tree):
            return None
        tree = obj
    return None


def _manual_checkout(repo):
    """手动 checkout 工作树 + 构建与 HEAD tree 完全一致的 index。

    Android /sdcard 不支持符号链接，dulwich 默认 checkout 调 os.symlink 会
    PermissionError。这里分两步：
    1. 写工作树：符号链接解析为目标文件 blob 内容写成普通文件（文件系统能存）
    2. 构建 index：记录**原始** mode + sha（symlink 记录 0o120000 + 链接 blob sha，
       普通文件记录原始 mode + blob sha）

    这样 index 与 HEAD tree 完全一致，git status 显示干净（不会因符号链接被
    写成普通文件而报 typechange/modified）。
    """
    import os
    from dulwich.objects import Tree, Blob
    from dulwich.index import Index
    try:
        head = repo.head()
    except Exception:
        return
    root_tree = repo[repo[head].tree]
    # 构建全新的 index（read=False 创建空 index）
    index_path = os.path.join(repo.controldir(), "index")
    index = Index(index_path, read=False)
    _write_tree_recursive(repo, repo.path, root_tree, b"", index)
    # 写入 index 文件
    # 新版 dulwich (≥1.0): index.write() 无参数（用初始化时的 filename）
    # 老版 dulwich: index.write(f) 需要 file 参数
    # 用 try 两次法兼容两种 API（inspect.signature 在某些版本返回 None）
    written = False
    try:
        index.write()  # 新版 dulwich
        written = True
    except TypeError:
        try:
            with open(index_path, "wb") as f:
                index.write(f)  # 老版 dulwich
            written = True
        except Exception as write_e2:
            print(f"[警告] 写入 index 失败: {write_e2}", file=sys.stderr)
    except Exception as write_e:
        print(f"[警告] 写入 index 失败: {write_e}", file=sys.stderr)


def _write_tree_recursive(repo, base, tree, prefix, index):
    """递归写出 tree 到 base 目录，同时构建 index。

    prefix: 当前目录相对仓库根的路径（bytes）
    index: dulwich.index.Index 对象，写入 entry（path, mode, sha）
    """
    import os
    import time
    from dulwich.objects import Tree, Blob
    from dulwich.index import IndexEntry
    for entry in tree.iteritems():
        name = entry.path
        path = os.path.join(base, name.decode())
        full_path = prefix + name
        obj = repo[entry.sha]
        if isinstance(obj, Tree):
            os.makedirs(path, exist_ok=True)
            _write_tree_recursive(repo, path, obj, full_path + b"/", index)
        elif isinstance(obj, Blob):
            mode = entry.mode
            if mode == 0o120000:
                # 符号链接：工作树写目标文件内容（普通文件），index 记录原始 symlink mode + sha
                link_target = obj.data.decode("utf-8", "replace")
                content = _resolve_symlink_target(repo, full_path, link_target)
                if content is None:
                    content = obj.data
                with open(path, "wb") as f:
                    f.write(content)
            else:
                with open(path, "wb") as f:
                    f.write(obj.data)
                if mode & 0o100:
                    try:
                        os.chmod(path, 0o755)
                    except OSError:
                        pass
            # 构建 index entry
            # Android /sdcard 不支持 symlink，工作树中符号链接被写成普通文件。
            # 这是 Android 文件系统的根本限制，无法让 git status 完全干净。
            # 两种方案权衡：
            #   A) index 记录 symlink mode + symlink sha（与 HEAD 一致）
            #      → staged 干净，unstaged 报 typechange（工作树是普通文件）
            #   B) index 记录普通文件 mode + 工作树内容 sha
            #      → unstaged 干净，staged 报 modify（与 HEAD symlink sha 不同）
            # 方案 A 更合理：typechange 明确表示"文件类型变化"，用户能理解是
            # symlink 限制；且只影响符号链接文件（通常只有 1-2 个），不影响
            # 普通文件的状态。方案 B 会让符号链接显示为"内容修改"，误导用户
            # 以为文件内容变了。
            # 采用方案 A：index 记录原始 mode + sha（与 HEAD tree 一致）。
            try:
                st = os.lstat(path)
                # git index 的 ctime/mtime 是 (秒, 纳秒) 两个 32 位字段
                ctime_s = int(st.st_ctime) & 0xFFFFFFFF
                ctime_ns = int(st.st_ctime_ns) % 1000000000 & 0xFFFFFFFF
                mtime_s = int(st.st_mtime) & 0xFFFFFFFF
                mtime_ns = int(st.st_mtime_ns) % 1000000000 & 0xFFFFFFFF
                # entry.sha 在 dulwich 中已是 20 字节二进制
                sha_bin = entry.sha if isinstance(entry.sha, bytes) else bytes.fromhex(entry.sha)
                entry_obj = IndexEntry(
                    ctime=(ctime_s, ctime_ns),
                    mtime=(mtime_s, mtime_ns),
                    dev=st.st_dev & 0xFFFFFFFF,
                    ino=st.st_ino & 0xFFFFFFFF,
                    mode=mode,  # 原始 mode（symlink=0o120000, regular=0o100644/0o100755）
                    uid=st.st_uid & 0xFFFFFFFF,
                    gid=st.st_gid & 0xFFFFFFFF,
                    size=len(obj.data),
                    sha=sha_bin,
                )
                index[full_path] = entry_obj
            except Exception:
                # lstat 失败则跳过 index entry（不影响工作树文件）
                pass


def cmd_add(args):
    """git add <文件...> / git add -A"""
    from dulwich import porcelain
    paths = []
    all_files = False
    for a in args:
        if a in ("-A", "--all", "."):
            all_files = True
        else:
            paths.append(a)
    if all_files:
        porcelain.add(".", paths=[])  # 空 paths 表示添加全部
        print("已暂存所有更改")
    elif paths:
        porcelain.add(".", paths=paths)
        print(f"已暂存: {', '.join(paths)}")
    else:
        print("没有指定文件", file=sys.stderr)
        return 1
    return 0


def cmd_commit(args):
    """git commit -m 'message'"""
    from dulwich import porcelain
    message = ""
    i = 0
    while i < len(args):
        if args[i] == "-m" and i + 1 < len(args):
            message = args[i + 1]
            i += 2
        elif args[i].startswith("-m"):
            message = args[i][2:]
            i += 1
        else:
            i += 1
    if not message:
        print("请使用 -m 指定提交信息", file=sys.stderr)
        return 1
    try:
        # 检查是否有暂存的更改
        status = porcelain.status(".")
        if not status.staged:
            print("没有暂存的更改，无需提交", file=sys.stderr)
            return 1
        porcelain.commit(".", message=message.encode())
        print(f'[{porcelain.active_branch(".")}] {message}')
        return 0
    except Exception as e:
        print(f"提交失败: {e}", file=sys.stderr)
        return 1


def cmd_status(args):
    """git status"""
    from dulwich import porcelain
    try:
        result = porcelain.status(".")
        branch = porcelain.active_branch(".") or "HEAD"
        print(f"位于分支 {branch}")
        staged = result.staged
        # 兼容 dulwich 不同版本：staged 可能是 dict 或 tuple
        if isinstance(staged, dict):
            staged_add = staged.get('add', [])
            staged_modify = staged.get('modify', [])
            staged_delete = staged.get('delete', [])
        else:
            staged_add, staged_modify, staged_delete = staged[0], staged[1], staged[2]
        unstaged = result.unstaged if hasattr(result, 'unstaged') else []
        untracked = result.untracked if hasattr(result, 'untracked') else []

        if staged_add or staged_modify or staged_delete:
            print("\n要提交的变更:")
            for f in staged_add:
                print(f"  新文件:   {f.decode() if isinstance(f, bytes) else f}")
            for f in staged_modify:
                print(f"  修改:     {f.decode() if isinstance(f, bytes) else f}")
            for f in staged_delete:
                print(f"  删除:     {f.decode() if isinstance(f, bytes) else f}")
        if unstaged:
            print("\n尚未暂存以提交的变更:")
            for f in unstaged:
                print(f"  修改:     {f.decode() if isinstance(f, bytes) else f}")
        if untracked:
            print("\n未跟踪的文件:")
            for f in untracked:
                print(f"  {f.decode() if isinstance(f, bytes) else f}")
        if not (staged_add or staged_modify or staged_delete or unstaged or untracked):
            print("nothing to commit, working tree clean")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_log(args):
    """git log [--oneline] [-n 数量]"""
    from dulwich import porcelain
    try:
        max_entries = 20
        oneline = False
        i = 0
        while i < len(args):
            if args[i] == "--oneline":
                oneline = True
            elif args[i] == "-n" and i + 1 < len(args):
                max_entries = int(args[i + 1])
                i += 1
            i += 1

        repo = porcelain.open_repo(".")
        from dulwich.objects import Commit
        walker = repo.get_walker(max_entries=max_entries)
        for entry in walker:
            c = entry.commit
            sha = c.id.decode() if isinstance(c.id, bytes) else str(c.id)
            author = c.author.decode() if isinstance(c.author, bytes) else str(c.author)
            msg = c.message.decode() if isinstance(c.message, bytes) else str(c.message)
            msg = msg.strip()
            if oneline:
                print(f"{sha[:7]} {msg.splitlines()[0] if msg else ''}")
            else:
                print(f"commit {sha}")
                print(f"Author: {author}")
                print(f"    {msg}")
                print()
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_push(args):
    """git push [remote] [branch]"""
    from dulwich import porcelain
    remote = args[0] if args else "origin"
    branch = args[1] if len(args) > 1 else None
    try:
        print(f"推送到 {remote}...")
        porcelain.push(".", remote_location=remote, refspec=branch)
        print("推送完成")
        return 0
    except Exception as e:
        # dulwich push 可能需要凭据
        print(f"推送失败: {e}", file=sys.stderr)
        print("提示: 如果需要认证，请先配置 remote URL 包含凭据:", file=sys.stderr)
        print("  git remote set-url origin https://user:token@github.com/user/repo.git", file=sys.stderr)
        return 1


def cmd_pull(args):
    """git pull [remote]"""
    from dulwich import porcelain
    remote = args[0] if args else "origin"
    try:
        print(f"从 {remote} 拉取...")
        porcelain.pull(".", remote_location=remote)
        print("拉取完成")
        return 0
    except Exception as e:
        print(f"拉取失败: {e}", file=sys.stderr)
        return 1


def cmd_fetch(args):
    """git fetch [remote]"""
    from dulwich import porcelain
    remote = args[0] if args else "origin"
    try:
        porcelain.fetch(".", remote_location=remote)
        print("获取完成")
        return 0
    except Exception as e:
        print(f"获取失败: {e}", file=sys.stderr)
        return 1


def cmd_branch(args):
    """git branch [分支名] / git branch -a"""
    from dulwich import porcelain
    try:
        if args and not args[0].startswith("-"):
            # 创建新分支
            porcelain.branch_create(".", args[0])
            print(f"创建分支: {args[0]}")
            return 0
        # 列出分支
        list_all = "-a" in args or "--all" in args
        branches = porcelain.branch_list(".")
        active = porcelain.active_branch(".")
        for b in branches:
            name = b.decode() if isinstance(b, bytes) else str(b)
            prefix = "* " if name == active else "  "
            print(f"{prefix}{name}")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_checkout(args):
    """git checkout <分支>"""
    from dulwich import porcelain
    if not args:
        print("用法: git checkout <分支>", file=sys.stderr)
        return 1
    try:
        porcelain.checkout_branch(".", args[0])
        print(f"切换到分支 '{args[0]}'")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_reset(args):
    """git reset --hard [<commit>]

    Android /sdcard 不支持符号链接，静态 git 二进制执行 reset --hard 时
    调 os.symlink 恢复符号链接会 PermissionError。这里用 dulwich 实现：
    --hard 模式下用 _manual_checkout（符号链接解析为普通文件）重置工作树，
    再用 porcelain.add 重建 index，确保与工作树一致。
    """
    import os
    from dulwich import porcelain
    from dulwich.repo import Repo
    if not args or args[0] not in ("--hard", "--mixed", "--soft"):
        print("用法: git reset --hard [<commit>]", file=sys.stderr)
        print("支持: --hard（--mixed/--soft 暂不支持）", file=sys.stderr)
        return 1
    mode = args[0]
    target_ref = args[1] if len(args) > 1 else "HEAD"
    try:
        repo = Repo(".")
    except Exception as e:
        print(f"错误: 不在 git 仓库中 ({e})", file=sys.stderr)
        return 1
    if mode != "--hard":
        print(f"暂不支持的 reset 模式: {mode}", file=sys.stderr)
        return 1
    # 解析目标 commit
    try:
        if target_ref == "HEAD":
            target_sha = repo.head()
        else:
            # 支持 branch name / short sha
            refs = repo.get_refs()
            target_sha = None
            for name, sha in refs.items():
                if name.endswith(b"/" + target_ref.encode()):
                    target_sha = sha
                    break
            if target_sha is None:
                print(f"错误: 无法解析引用 '{target_ref}'", file=sys.stderr)
                return 1
        # 移动 HEAD 到目标 commit（reset 的本质）
        repo.refs[b"HEAD"] = target_sha
        # --hard: 重置工作树和 index
        # _manual_checkout 同时写工作树文件和构建与 HEAD tree 一致的 index
        # （符号链接记录原始 mode+sha，不会报 typechange/modified）
        _manual_checkout(repo)
        print(f"HEAD 现在指向: {target_sha.decode()[:7]}")
        print("已重置工作树和 index（符号链接解析为普通文件）")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_remote(args):
    """git remote [-v] / git remote add <name> <url> / git remote set-url <name> <url>"""
    from dulwich.repo import Repo
    try:
        repo = Repo(".")
        config = repo.get_config()
        if not args or args[0] == "-v":
            # 列出 remote
            for section in config.sections():
                if section[0] == b"remote":
                    name = section[1].decode()
                    url = config.get((b"remote", section[1]), b"url").decode()
                    print(f"{name}\t{url}")
            return 0
        elif args[0] == "add" and len(args) >= 3:
            name = args[1].encode()
            url = args[2].encode()
            config.set((b"remote", name), b"url", url)
            config.set((b"remote", name), b"fetch", f"+refs/heads/*:refs/remotes/{args[1]}/*".encode())
            config.write_to_path()
            print(f"添加远程仓库: {args[1]} -> {args[2]}")
            return 0
        elif args[0] == "set-url" and len(args) >= 3:
            name = args[1].encode()
            url = args[2].encode()
            config.set((b"remote", name), b"url", url)
            config.write_to_path()
            print(f"设置 {args[1]} URL: {args[2]}")
            return 0
        elif args[0] == "remove" and len(args) >= 2:
            name = args[1].encode()
            del config[(b"remote", name)]
            config.write_to_path()
            print(f"删除远程仓库: {args[1]}")
            return 0
        else:
            print(f"不支持的 remote 子命令: {args}", file=sys.stderr)
            return 1
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_config(args):
    """git config user.name "xxx" / git config user.email "xxx" / git config --list"""
    from dulwich.repo import Repo
    try:
        if "--list" in args or "-l" in args:
            repo = Repo(".")
            config = repo.get_config()
            for section in config.sections():
                for key, value in config.items(section):
                    s = ".".join(s.decode() for s in section)
                    k = key.decode()
                    v = value.decode()
                    print(f"{s}.{k}={v}")
            return 0
        if len(args) >= 3:
            # git config user.name "value"
            key_parts = args[0].split(".")
            section = tuple(p.encode() for p in key_parts[:-1])
            key = key_parts[-1].encode()
            value = args[2].encode()
            repo = Repo(".")
            config = repo.get_config()
            config.set(section, key, value)
            config.write_to_path()
            return 0
        if len(args) >= 1:
            # 读取: git config user.name
            key_parts = args[0].split(".")
            section = tuple(p.encode() for p in key_parts[:-1])
            key = key_parts[-1].encode()
            repo = Repo(".")
            config = repo.get_config()
            try:
                value = config.get(section, key)
                print(value.decode())
            except KeyError:
                pass
            return 0
        print("用法: git config <key> [value]", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_tag(args):
    """git tag [标签名] [-m "信息"]"""
    from dulwich import porcelain
    try:
        if not args:
            # 列出标签
            repo = porcelain.open_repo(".")
            for ref in repo.refs.keys():
                if ref.startswith(b"refs/tags/"):
                    print(ref[len(b"refs/tags/"):].decode())
            return 0
        tag_name = args[0]
        message = ""
        if "-m" in args:
            idx = args.index("-m")
            if idx + 1 < len(args):
                message = args[idx + 1]
        if message:
            porcelain.tag_create(".", tag_name, message=message.encode())
        else:
            porcelain.tag_create(".", tag_name)
        print(f"创建标签: {tag_name}")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_diff(args):
    """git diff"""
    from dulwich import porcelain
    try:
        result = porcelain.diff(".")
        if result:
            print(result.decode() if isinstance(result, bytes) else result)
        else:
            print("没有差异")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_rm(args):
    """git rm <文件...>"""
    from dulwich import porcelain
    if not args:
        print("用法: git rm <文件...>", file=sys.stderr)
        return 1
    try:
        porcelain.remove(".", paths=args)
        print(f"已移除: {', '.join(args)}")
        return 0
    except Exception as e:
        print(f"错误: {e}", file=sys.stderr)
        return 1


def cmd_version(args):
    """git version"""
    import dulwich
    print(f"git version (dulwich {dulwich.__version__}) — 内嵌 Python Git")
    return 0


# 子命令分发表
COMMANDS = {
    "init": cmd_init,
    "clone": cmd_clone,
    "add": cmd_add,
    "commit": cmd_commit,
    "status": cmd_status,
    "log": cmd_log,
    "push": cmd_push,
    "pull": cmd_pull,
    "fetch": cmd_fetch,
    "branch": cmd_branch,
    "checkout": cmd_checkout,
    "reset": cmd_reset,
    "remote": cmd_remote,
    "config": cmd_config,
    "tag": cmd_tag,
    "diff": cmd_diff,
    "rm": cmd_rm,
    "version": cmd_version,
    "--version": cmd_version,
}


def main():
    if len(sys.argv) < 2:
        print("用法: git <子命令> [参数...]", file=sys.stderr)
        print("支持: " + ", ".join(COMMANDS.keys()), file=sys.stderr)
        return 1

    subcmd = sys.argv[1]
    args = sys.argv[2:]

    if subcmd not in COMMANDS:
        print(f"不支持的 git 子命令: {subcmd}", file=sys.stderr)
        print("支持: " + ", ".join(COMMANDS.keys()), file=sys.stderr)
        return 1

    # version 不需要 dulwich
    if subcmd in ("version", "--version"):
        return COMMANDS[subcmd](args)

    if not ensure_dulwich():
        return 1

    return COMMANDS[subcmd](args)


# 通过 exec() 执行时不依赖 __name__，直接调用 main()
# 不使用 sys.exit()，避免 SystemExit 异常导致 JNI 层报错
_exit_code = main()
if _exit_code and _exit_code != 0:
    sys.stderr.write(f"\n[git_bridge 退出码: {_exit_code}]\n")
