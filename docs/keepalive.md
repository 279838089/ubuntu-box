# UbuntuBox 保活方案（防杀完整指南）

在 ColorOS/OnePlus 等国产 ROM 上长期后台运行 UbuntuBox（frpc 隧道、cc-connect、sshd、Claude Code 等）的完整方案。踩过的坑全在这里，按顺序配置即可复现。

## 背景：进程为什么会被杀

国产 ROM（ColorOS HansManager、小米神隐、华为 DeepSleep 等）在锁屏后会**整体冻结 App 的所有进程**，包括 proot 容器里的 sshd/frpc/cc-connect。冻结不是杀死——解冻后进程还在，但冻结期间：

- WebSocket（飞书/Telegram）静默断开
- TCP 连接超时
- 你的看门狗也被冻住，无法重启任何东西

触发解冻的只有三样东西：**入站数据包、闹钟、前台交互**。

## 踩过的坑（重要！）

### 坑 1：proot `--kill-on-exit` 会杀死"脱离"的后台进程

**症状**：从 SSH 会话或 App 的临时 proot 调用里 `setsid`/`nohup` 出去的看门狗脚本，过一会儿全死了，日志里没有任何崩溃记录。

**原因**：proot 启动参数带 `--kill-on-exit`，proot 进程退出时会把它容器里**启动过的所有进程**一并杀掉，`setsid` 也逃不掉。

**铁证**：同一台手机上，由 App 直接启动的 sshd 活了三天没死，而 SSH 登录时拉起的看门狗活不过会话周期。

**解决**：后台服务必须由 **App 直接持有的常驻 proot 进程**运行（见方案第 1 层），绝不能从临时会话里孵化。

### 坑 2：tmux 套接字跨环境权限隔离

**症状**：SSH 会话里 `tmux new` 正常，但 App 内终端提示 `access not allowed`，两边互相看不到对方的会话。

**原因**：不同入口（App 终端 vs SSH）创建的 tmux server 运行在不同用户上下文，套接字目录权限互斥。

**解决**：不要用 tmux 做保活容器，直接用常驻 proot 进程。

### 坑 3：多套看门狗互相打架

**症状**：frpc 出现两个实例抢同一条隧道，cc-connect 报 `another instance is already running`，隧道反复重连。

**原因**：先后部署过多套自启（旧 watchdog.sh + profile.d 钩子 + 新看门狗），都在拉起同一批进程。

**解决**：清理所有旧脚本（`/root/watchdog.sh`、`/etc/profile.d/watchdog.sh`、`/etc/profile.d/cc-connect.sh`、`/root/tunnel-watchdog.sh`、`/root/ccconnect-watchdog.sh`），只保留 App 内置的 ServicesDaemon 一套。

### 坑 4：Wake lock 和电池白名单管不了系统冻结器

**症状**：持有 `PARTIAL_WAKE_LOCK`、加了 `deviceidle whitelist`，锁屏后进程照样被冻。

**原因**：ColorOS 的 HansManager 是独立于 AOSP 电池体系的私有冻结器，不受 wake lock / Doze 白名单约束。`settings put global cached_apps_freezer disabled` 需要 `WRITE_SECURE_SETTINGS`（无 root 拿不到）；`pm grant` 需要 `GRANT_RUNTIME_PERMISSIONS`（只有系统能授）；`adb root` 量产机不允许。

**解决**：绕不过就利用它——HansManager 对**闹钟**会放行（日志实锤 `up_alarm uid`），用精确闹钟周期性解冻（见方案第 2 层）。

### 坑 5：DNS AAAA 查询失败导致 Claude Code 报 EAI_AGAIN

**症状**：Claude Code 报 `Can't reach the API server — check your internet or DNS (EAI_AGAIN)`，但 `getent hosts` 正常。

**原因**：手机网络下 IPv6 的 AAAA DNS 查询被拒（host unreachable），glibc `getaddrinfo` 同时查 A/AAAA，AAAA 失败导致整体返回 EAI_AGAIN，即使 IPv4 完全可用。

**解决**：`/etc/resolv.conf` 加 `options no-aaaa`，并放 `/etc/profile.d/ubox-dns.sh` 自动补写。

### 坑 6：Windows 检出把 guest 脚本变 CRLF

**症状**：终端启动报 `syntax error near $'in\r'`。

**解决**：仓库加 `.gitattributes` 强制 `app/src/main/assets/guest/*` 和 `*.sh` 用 LF。

### 坑 7：Android 13+ 的 Stream API 在低版本崩溃

**症状**：Android 12 上首次解压 rootfs 或打开设置页即闪退，`NoSuchMethodError: InputStream.transferTo` / `ByteArrayOutputStream.toString(Charset)`。

**解决**：这些是 API 33+ 方法，用 `Io.copy/Io.readAll` 等价实现替换（见 `Io.java`）。

## 最终方案：三层保活架构

```
第 1 层：App 常驻持有（ServicesDaemon.java）
  TerminalService（前台服务）
   └─ ServicesDaemon —— App 用 ProcessBuilder 直接启动 proot 进程
       └─ guest 内 services-daemon.sh 监督循环（永不退出）
           ├─ frpc 死了 → 10 秒内重启
           └─ cc-connect 死了 → 10 秒内重启（由 ENABLED 标记控制开关）

第 2 层：精确闹钟穿冻结（KeepAliveReceiver.java）
  每 4 分钟 setExactAndAllowWhileIdle 闹钟
   └─ HansManager 对闹钟放行（up_alarm）→ App 解冻
       └─ ServicesDaemon 被系统极端回收了 → 重新拉起

第 3 层：用户入口兜底
  打开一次 App → TerminalService.onCreate → 全部自动恢复
  （手机重启后只需点一下图标）
```

### 关键实现点

1. **ServicesDaemon**（`app/src/main/java/dev/ubox/app/ServicesDaemon.java`）
   - 与 sshd 完全相同的进程模型：App 直接 `ProcessBuilder.start()` 持有 proot
   - proot 不退出 → `--kill-on-exit` 永不触发 → 监督循环永生
   - `MainActivity` 退出/`exit()` 时显式 `stop()`，`--kill-on-exit` 反过来用于干净清理

2. **KeepAliveReceiver**（`app/src/main/java/dev/ubox/app/KeepAliveReceiver.java`）
   - `SCHEDULE_EXACT_ALARM` 权限 + `setExactAndAllowWhileIdle`（低权限回退 `setAndAllowWhileIdle`）
   - 每次触发先排下一次（自续期），再检查守护进程
   - 拿 45 秒临时 wake lock 保证 proot 检查跑完

3. **监督脚本**（运行时生成到 guest `/root/services-daemon.sh`）
   ```bash
   while true; do
     [ -x /usr/local/bin/frpc ] && [ -f /root/.frpc.ini ] \
       && ! pgrep -x frpc >/dev/null \
       && (frpc -c /root/.frpc.ini >> /root/sakura.log 2>&1 &)
     [ -f /root/.cc-connect/ENABLED ] \
       && [ -x /usr/local/bin/cc-connect ] \
       && [ -f /root/.cc-connect/config.toml ] \
       && ! pgrep -f 'cc-connect --config' >/dev/null \
       && (cc-connect --config /root/.cc-connect/config.toml --force \
            >> /root/.cc-connect/cc-connect.log 2>&1 &)
     sleep 10
   done
   ```

4. **cc-connect 开关**：`/root/.cc-connect/ENABLED` 标记文件，设置页 UI 写删标记，监督器按标记决定是否拉起。`--force` 参数保证单实例。

## 手机侧还需要的一次性配置（ADB 能做的）

```bash
adb shell cmd deviceidle whitelist +dev.ubox.app   # Doze 白名单
adb shell am set-standby-bucket dev.ubox.app active # 待机桶
```

## 手机侧需要手动点的（ADB 做不了）

ColorOS：设置 → 电池 → 应用耗电管理 → UbuntuBox：
- 允许完全后台行为：**开启**
- 应用速冻：**关闭**（如果版本提供入口）

即使不做这步，闹钟保活也能维持（最坏情况：掉线后 4~5 分钟内自动恢复）。

## 验证方法

```bash
# 1. 强杀 App 重启，三件套应自动恢复
adb shell am force-stop dev.ubox.app
adb shell am start -n dev.ubox.app/.MainActivity
adb shell "ps -A | grep -E 'frpc|cc-connect|sshd'"

# 2. 锁屏耐久测试（15 分钟后进程应存活）
adb shell input keyevent KEYCODE_POWER
sleep 900
adb shell "ps -A | grep -E 'frpc|cc-connect'"

# 3. 闹钟触发统计（应持续累计）
adb shell dumpsys alarm | grep -A3 "dev.ubox.app"

# 4. 排查冻结记录
adb logcat -d | grep -iE "hans.*dev.ubox"   # freeze/unfreeze 时间线
```

## 各组件日志位置（guest 内）

| 组件 | 日志 |
|---|---|
| frpc 隧道 | `/root/sakura.log` |
| cc-connect | `/root/.cc-connect/cc-connect.log` |
| sshd | `/data/data/dev.ubox.app/files/ubox/sshd.log` |
| 监督器 | `/data/data/dev.ubox.app/files/ubox/services-daemon.log` |

## 效果

- OnePlus 7 Pro / ColorOS 12 / Android 12 实测
- 锁屏 15 分钟耐久：frpc + cc-connect 全程存活
- cc-connect 飞书 WebSocket 断线后最多 4~5 分钟自动恢复（闹钟周期 + 10 秒重启）
- sshd / SakuraFrp 公网隧道 / cc-connect 后台执行，全部无需 root

## 附：cc-connect（Claude Code 桥接）的坑

在容器里跑 cc-connect + Claude Code 时还有三个坑，全部实测踩过：

### 坑 8：Go 程序在 proot 下崩溃 `fatal error: newosproc (errno=38)`

**症状**：cc-connect 收到一条消息、处理完就整个进程消失，飞书再无响应。日志里：

```
runtime: failed to create new OS thread (have 12 already; errno=38)
fatal error: newosproc
```

**原因**：proot 容器里 Go 运行时并发创建线程（GC、调度器）时，clone 系统调用的属性经 proot 翻译后偶尔非法，内核返回 `EINVAL(38)`，Go 直接 fatal 退出。

**解决**：给服务进程限制运行时并发（已内置在 ServicesDaemon 生成的监督脚本里）：

```bash
export GOMAXPROCS=1   # 消除并发线程创建
export GOGC=200       # GC 频率减半，进一步降低触发概率
```

注意：proot 的 argv 末尾是 `env -i` 重建环境，所以变量必须写进监督脚本里 `export`，
在启动 proot 的外层设置是无效的。另外 ServicesDaemon 每次 App 启动都会重写监督脚本，
所以要改必须改 `ServicesDaemon.java` 里的模板，直接改手机上的文件会被覆盖。

### 坑 9：root 下 yolo 模式被降级，每次都问权限

**症状**：config.toml 设了 `mode = "yolo"`，但每次会话都打警告且仍要权限确认：

```
⚠️ Running as root: bypassPermissions mode is not supported
and has been downgraded to auto.
```

**原因**：cc-connect 检测到自己以 uid 0 运行（proot `-0` 把容器内 uid 映射成 root），
出于安全主动把 `bypassPermissions` 降级为 `auto`。`dontAsk` 模式更糟——未预授权的工具
会被**自动拒绝**。

**解决**：用 `acceptEdits` + 全量 `allowed_tools` 预授权，不触发降级路径，效果等同 yolo：

```toml
[projects.agent.options]
work_dir = "/root"
mode = "acceptEdits"
allowed_tools = ["Bash", "Read", "Write", "Edit", "MultiEdit", "Glob", "Grep",
  "NotebookEdit", "WebFetch", "WebSearch", "Task", "TodoWrite", "SlashCommand",
  "BashOutput", "KillShell", "ExitPlanMode", "ListMcpResources", "ReadMcpResource"]

[projects.agent.options.env]
# Claude Code 自身在 root 下拒绝 --dangerously-skip-permissions，
# IS_SANDBOX 让它认为处于沙箱，允许该组合
IS_SANDBOX = "1"
```

### 坑 10：TOML 表边界——`allowed_tools` 悄悄掉进 env 子表

**症状**：config.toml 里明明写了 `allowed_tools = [...]`，重启后解析结果却是 0 个工具，
权限照样问。

**原因**：TOML 的键属于"最近一个表头"。如果 `allowed_tools` 写在
`[projects.agent.options.env]` 表头之后，它就成了 env 表的一个键，而不是 options 的。

**解决**：确认它出现在 `[projects.agent.options]` 之后、`[projects.agent.options.env]`
之前；改完用 python 验证：

```bash
python3 -c "import tomllib; d=tomllib.load(open('/root/.cc-connect/config.toml','rb')); \
o=d['projects'][0]['agent']['options']; print(o['mode'], len(o.get('allowed_tools',[])))"
# 期望输出: acceptEdits 18
```
