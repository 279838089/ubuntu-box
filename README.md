# UbuntuBox

在 Android 上运行一个完整的 Ubuntu 24.04 (arm64) 环境，免 root、免 Termux，用来安装和运行各种 CLI Agent。

运行时部分剥离自 [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA)（MIT），只保留了 proot 运行时、启动参数和 Termux 终端组件，DSH 相关的插件、备份、补丁体系都去掉了。

## 构建

```sh
./scripts/fetch-rootfs.sh        # 下载并校验 Ubuntu Base 24.04.5 arm64 → assets/rootfs.tar.gz.bin
./gradlew assembleDebug          # 输出 app/build/outputs/apk/debug/app-debug.apk
```

需要 JDK 17、Android SDK 36；只有修改 `native/termux-jni` 时才需要 NDK（运行 `native/termux-jni/build.sh` 重新生成 `libtermux.so`）。

## 结构

| 文件 | 作用 |
| --- | --- |
| `Rootfs.java` | 首次启动时把 APK 内的 rootfs 解压到 `files/ubox/rootfs`，写入 DNS、apt、Android 补充组等配置；每次启动时刷新辅助脚本 |
| `Proot.java` | 组装 proot 命令行（自动检测是否需要 `--link2symlink`，Android 无权读取 `/proc/stat` 等文件时绑定假数据），guest 内用 `env -i` 重建干净的环境 |
| `TerminalService.java` | 前台服务，持有所有终端会话，切到后台后继续运行；支持保持 CPU 唤醒 |
| `MainActivity.java` | 安装进度、终端界面、额外按键（ESC/TAB/CTRL/ALT/方向键…）、多会话 |
| `assets/guest/` | 安装进 Ubuntu 的辅助脚本：`ubox-mirror`、`ubox-node`、Node 兼容补丁 |

## 在里面装 Agent

```sh
ubox-mirror tuna && apt update      # 国内建议先换源
ubox-node                           # Node.js 22 → /usr/local
npm i -g @anthropic-ai/claude-code  # 或 @openai/codex、@google/gemini-cli 等
```

`/etc/profile.d/ubox.sh` 会把 `node-compat.cjs` 加进 `NODE_OPTIONS`。Android 不允许普通应用读取网卡列表和 `/proc/stat`，这个补丁可以避免 `os.networkInterfaces()` 抛错，以及 `os.cpus()` 返回空数组。

## 许可

- 本项目代码：沿用 DSHA 的 MIT 许可（见 `assets/licenses/DSHA-LICENSE.txt`）
- `libproot.so` / `libprootloader*.so`：Termux proot，GPL-2.0（`assets/licenses/proot-COPYING.txt`）。对外分发 APK 时需要按 GPL 提供对应源码
- `libtalloc.so`：LGPL；`libandroidshmem.so`：来源同 DSHA，原仓库标注许可未核实
- Termux terminal-view / terminal-emulator 及 `native/termux-jni/termux.c`：Apache-2.0
- Ubuntu Base：各软件包各自的许可
