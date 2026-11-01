# 不 root、不装 Termux，我把一台完整的 Ubuntu 塞进了手机，还让它跑起了 Claude Code

> 一个下午，从「剥离开源项目」到「SSH 远程登录手机」，记录 UbuntuBox 的诞生和一路踩过的坑。

---

## 起因：我只想要那个 Ubuntu

GitHub 上有个挺火的开源项目 **DSHA**，作用是在安卓手机上跑 DeepSeek Harness：不用 root，也不用 Termux，装完 APK 就有一套完整的 Ubuntu 环境，`apt` 能用，AI 的输出还能实时显示在屏幕上。

但我对 DeepSeek Harness 本身没有需求。我看中的是它底下那套**免 root 的 Ubuntu 运行环境**，想要的是一个干净的 Linux 容器，在里面装 Claude Code、Codex、Gemini CLI，装什么我自己说了算。

所以目标很明确：**把 Ubuntu 环境单独剥离出来，做成一个独立的应用。**

---

## 不做减法，重新做加法

clone 下来一看，DSHA 有 500 多个 Java 文件。插件系统、备份恢复、几十个运行时补丁、版本门禁……都和 Ubuntu 运行时缠在一起。在这样的代码里删东西，删一处可能牵动十处。

最后的决定是**不在原项目上删，而是新建一个应用，只把真正有用的部分拿过来**：

| 拿过来的 | 作用 |
| --- | --- |
| Termux 版 proot 原生库 | 免 root 容器的核心：用 ptrace 拦截系统调用，假装自己是 root |
| proot 的启动参数和环境变量 | DSHA 在真机上验证过的配置，直接参考，少走弯路 |
| Termux 的终端组件 | terminal-view / terminal-emulator，成熟的真 PTY 终端 |

rootfs 没有沿用 DSHA 那份，换成了 Canonical 官方的 **Ubuntu Base 24.04**，构建时下载并校验 SHA256，再打进 APK。首次打开时在本地解压，不需要联网。

最后整个应用只有 **5 个核心 Java 类**，安装包大约 **30MB**。我给它起名 **UbuntuBox**。

DSHA 是 MIT 协议，剥离和二次开发都没问题，版权声明保留在应用里。

---

## 第一个坑：16KB 内存页

Android 15 开始，一部分新机型使用 16KB 的内存页，而 Termux 终端库自带的 `libtermux.so` 是按 4KB 对齐编译的，在这些机器上**直接加载失败**。

解决办法很简单：用 NDK 把上游的 `termux.c` 原封不动地重新编译一遍，加上 `-Wl,-z,max-page-size=16384`。一行链接参数，就避开了一类「只在某些新手机上闪退」的问题。

---

## 第二个坑：Android 不让创建硬链接

在模拟器上手动解压 rootfs 时，`tar` 报错：

```
tar: can't link 'usr/bin/perl5.38.2' -> 'usr/bin/perl': Permission denied
```

Android 的 SELinux 策略**不允许普通应用创建硬链接**。而 Linux 发行版里硬链接很常见，dpkg 安装软件时也会用到。

proot 有个参数 `--link2symlink`，用符号链接来模拟硬链接。打开以后，解压没问题了，`apt update` 也能跑。

但真机上第一次装软件就失败了：

```
error setting ownership of '/usr/bin/perl5.38.2.dpkg-new': No such file or directory
```

查了半天才明白：**link2symlink 生成的符号链接指向的是宿主机上的绝对路径**（应用私有目录里的某个位置），而 Ubuntu 容器里看不到这个路径。dpkg 顺着链接去改文件属主，结果什么也找不到。

回头看 DSHA 的源码，他们早就处理过：把那个目录按**同一个绝对路径**再绑定进容器。我当时漏抄了这一行。补上之后，20 多个包一次装完。

> 教训：参考成熟项目时，那些看起来多余的参数，往往是踩过坑之后才加上的。

---

## 第三个坑：标题栏把终端吃掉了

装上真机一看，终端前几行被标题栏挡住了。

原因是 Android 15 起，目标版本 35 以上的应用会被**强制全面屏（edge-to-edge）**：页面内容会画到状态栏和标题栏下面，应用得自己留出空白。更麻烦的是，原来的 `adjustResize`（键盘弹出时缩小窗口）也随之失效，**键盘会直接盖住输入行**。对终端应用来说，这几乎是致命的。

修法：去掉系统自带的标题栏，换成布局里自己的 Toolbar，再监听 WindowInsets，按**状态栏、导航栏、刘海和输入法**的实际高度给根布局加 padding。改完以后，键盘弹出时终端会自动缩到键盘上方，底部的 ESC / TAB / CTRL / 方向键也都还在。

---

## 第四个坑：连 ping 都没有？

有朋友装上以后第一句话就是：「这个系统居然没有 curl、ping？」

确实没有。Ubuntu Base 是给 Docker 镜像打底用的最小系统，只有 apt、bash 和 coreutils。

我想过把常用工具预装进 rootfs，但那样安装包会变大。最后换了个思路：**安装包保持精简，做一个设置页，常用工具一键安装。**

【此处插图：设置页截图】

设置页分四组：

- **软件源**：官方、清华、中科大、阿里云，点一下就切换
- **常用工具**：curl、wget、dig、htop、jq、ripgrep、tmux、vim……
- **开发环境**：Git、Node.js 22、Python 3、编译工具链
- **AI Agent**：Claude Code、Codex CLI、Gemini CLI、OpenCode（没装 Node.js 时会先自动装上）

点「安装」会**新开一个终端会话**执行，apt 和 npm 的进度都直接显示在终端里，失败了也能看到完整报错。我不太喜欢那种只转圈、出错了也说不清原因的安装按钮。

装好 curl 以后，ping 还是不行：

```
ping: prctl: Operation not permitted
```

Ubuntu 的 iputils-ping 启动时要调用 `prctl` 调整进程权限，被 Android 的 seccomp 拦截了。解决办法有点取巧：手机系统里本来就有一个 `/system/bin/ping`，它用的是普通应用就能使用的 ICMP datagram socket。把 `/system` 和 `/apex` 绑定进容器，再在 `/usr/local/bin/ping` 放一个包装脚本去调用它，`ping` 就能正常用了。

---

## 第五个坑：被打断的 dpkg

调试时我从电脑上强制停止了应用，正好把手机上一个正在进行的安装打断了。下次再装，apt 直接报错：

```
E: dpkg was interrupted, you must manually run 'dpkg --configure -a'
```

手机上的应用，被系统杀掉、被用户划掉、手机没电重启，都是常有的事。所以现在每次安装前，脚本都会**先检测 dpkg 有没有停在半完成状态，有的话自动修复**。用户不需要知道 `dpkg --configure -a` 是什么。

---

## 最终效果：手机上跑 Claude Code

在一台 vivo（Android 16）上实测：

```
$ node -v
v22.23.4
$ claude --version
2.1.316 (Claude Code)
```

一台手机，一个 30MB 的 APK，**完整的 Ubuntu 24.04 + Node.js 22 + Claude Code**，全程不用 root。

另外有几个细节，是为了「让 Agent 长时间跑」专门做的：

- **前台服务**：切到后台，会话也不会被杀
- **保持 CPU 唤醒**：可选，熄屏后 Agent 继续工作
- **Node.js 兼容补丁**：Android 不让普通应用读网卡列表和 `/proc/stat`，Node 的 `os.networkInterfaces()` 会抛错，`os.cpus()` 会返回空数组，不少 CLI 工具因此直接崩溃。我加了一个预加载脚本，给这两个函数兜底。

---

## 最后一步：直接暴露 SSH

在手机屏幕上敲命令终究不方便。我更想坐在电脑前，直接登录到手机里的 Ubuntu：看日志、发指令、传文件，还能用 VS Code Remote-SSH 打开。

于是设置页又加了一组「**SSH 远程访问**」：

- 一个开关：在后台运行 sshd，端口 8022，不占用终端会话
- 连接命令：`ssh root@<手机IP> -p 8022`，**点一下就复制**
- 添加公钥：剪贴板里如果已经有公钥，会自动填进去
- 设置 root 密码：可选，默认只允许公钥登录
- 查看 SSH 日志：启动失败时自动弹出

这里还有两个小坑：

1. **umask 是 0000**。sshd 发现自己的运行目录权限是 777，拒绝启动。现在所有脚本都统一设置 `umask 022`。
2. **代理 VPN 吞掉了回包**。sshd 已经在监听，从手机本机也能连上，但从电脑连就超时。排查后发现，手机上的代理 VPN 把这个应用的**全部流量**都接进了隧道，包括回给局域网电脑的包。解决办法是在 VPN 应用里开启「绕过局域网」，这样代理照常工作，只有局域网流量直连。

最后，从电脑上一行命令：

```
$ ssh root@<手机IP> -p 8022
root@localhost:~# claude
```

手机就成了一台可以远程登录的 Linux 小主机。

---

## 写在最后

回头看，真正花时间的地方不在「把 Ubuntu 跑起来」，proot 早就解决了这件事。花时间的是 Android 这个宿主环境里各种细碎的限制：

- 不能创建硬链接
- 内存页从 4KB 变成 16KB
- 强制全面屏，键盘不再自动让位
- seccomp 拦截 `prctl`
- 不能读 `/proc/stat` 和网卡列表
- 代理 VPN 接管应用的全部流量

这些限制单独看都不难，但要一个一个在真机上遇到、定位、解决。

如果你也想在手机上跑自己的 Agent，可以照着这个思路试试：**proot 负责隔离，官方 rootfs 保证干净，工具按需安装，SSH 负责远程操作。**

---

**关于开源协议**：UbuntuBox 的代码基于 DSHA（MIT）；proot 采用 GPL-2.0，对外分发 APK 时需要按 GPL 提供对应源码；Termux 终端组件采用 Apache-2.0。感谢这些开源项目。

*（全文完）*
