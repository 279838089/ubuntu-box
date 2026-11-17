package dev.ubox.app;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * 常驻后台服务监督器（frpc 隧道 + cc-connect）。
 *
 * 关键点：这些进程必须由 App 直接持有的常驻 proot 进程运行。proot 带 --kill-on-exit，
 * 临时会话（proot.run / SSH 登录）里 setsid 出来的看门狗会在会话结束时被一并杀掉——
 * 这正是之前“看门狗总死”的根因。sshd 用 ProcessBuilder 常驻所以两年不死，
 * 这里用同样的方式：前台监督循环永不退出，--kill-on-exit 永不触发。
 */
final class ServicesDaemon {
  private final Context context;
  private final Rootfs rootfs;
  private final Proot proot;
  private Process process;

  ServicesDaemon(Context context) {
    this.context = context;
    this.rootfs = new Rootfs(context);
    this.proot = new Proot(context, rootfs);
  }

  boolean needed() {
    return new File(rootfs.rootfs, "root/.frpc.ini").isFile()
        || new File(rootfs.rootfs, "root/.cc-connect/ENABLED").isFile();
  }

  boolean isRunning() {
    return process != null && process.isAlive();
  }

  /** 写入 guest 侧监督脚本（幂等）。 */
  private void writeDaemonScript() throws IOException {
    StringBuilder script = new StringBuilder();
    script.append("#!/bin/bash\n")
        .append("# 由 UbuntuBox 常驻运行，不要手动执行。循环监督 frpc 与 cc-connect，\n")
        .append("# 进程退出 10 秒内重启；cc-connect 由 ENABLED 标记控制。\n")
        .append("while true; do\n")
        .append("  if [ -x /usr/local/bin/frpc ] && [ -f /root/.frpc.ini ]")
        .append(" && ! pgrep -x frpc >/dev/null 2>&1; then\n")
        .append("    (frpc -c /root/.frpc.ini >> /root/sakura.log 2>&1 &)\n")
        .append("  fi\n")
        .append("  if [ -f /root/.cc-connect/ENABLED ]")
        .append(" && [ -x /usr/local/bin/cc-connect ]")
        .append(" && [ -f /root/.cc-connect/config.toml ]")
        .append(" && ! pgrep -f 'cc-connect --config' >/dev/null 2>&1; then\n")
        .append("    (cc-connect --config /root/.cc-connect/config.toml --force")
        .append(" >> /root/.cc-connect/cc-connect.log 2>&1 &)\n")
        .append("  fi\n")
        .append("  sleep 10\n")
        .append("done\n");
    File scriptFile = new File(rootfs.rootfs, "root/services-daemon.sh");
    try (OutputStream out = new FileOutputStream(scriptFile)) {
      out.write(script.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    try {
      android.system.Os.chmod(scriptFile.getAbsolutePath(), 0755);
    } catch (android.system.ErrnoException ignored) {
    }
  }

  /** 启动常驻监督进程（已运行则跳过）。 */
  synchronized void start() throws IOException {
    if (isRunning()) return;
    if (!rootfs.isInstalled()) return;
    proot.prepare();
    writeDaemonScript();
    ProcessBuilder builder =
        new ProcessBuilder(proot.argv("/root/services-daemon.sh"))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(new File(rootfs.base, "services-daemon.log")))
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    builder.environment().clear();
    for (String entry : proot.environment()) {
      int split = entry.indexOf('=');
      builder.environment().put(entry.substring(0, split), entry.substring(split + 1));
    }
    process = builder.start();
  }

  synchronized void stop() {
    if (process == null) return;
    // proot --kill-on-exit：结束监督器会连同它拉起的 frpc/cc-connect 一起结束
    process.destroy();
    try {
      if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    process = null;
  }

  /** cc-connect 开关：写/删 ENABLED 标记，监督器 10 秒内跟进。 */
  void setCcConnectEnabled(boolean enabled) {
    File flag = new File(rootfs.rootfs, "root/.cc-connect/ENABLED");
    try {
      if (enabled) {
        new File(flag.getParentFile().getAbsolutePath() + "/").mkdirs();
        flag.createNewFile();
      } else {
        flag.delete();
        // 停标记后主动杀掉 cc-connect，监督器不会再拉起
        try {
          proot.prepare();
          new ProcessBuilder(proot.argv("/bin/bash", "-c",
              "pkill -f 'cc-connect --config'; exit 0"))
              .start();
        } catch (IOException ignored) {
        }
      }
    } catch (IOException error) {
      android.util.Log.e("UbuntuBox", "set ENABLED failed", error);
    }
  }
}
