package dev.ubox.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import java.io.File;

/**
 * 定时保活：ColorOS 的 HansManager 会在锁屏后整体冻结本应用（含 guest 内的 frpc /
 * cc-connect / 看门狗），只有入站数据包或闹钟能触发解冻。这里用 setExactAndAllowWhileIdle
 * 周期性唤醒，解冻后跑一次 services.sh，死掉的后台服务 10 秒内被看门狗重新拉起。
 */
public class KeepAliveReceiver extends BroadcastReceiver {
  static final int INTERVAL_MS = 4 * 60 * 1000;

  static void schedule(Context context) {
    AlarmManager am = context.getSystemService(AlarmManager.class);
    if (am == null) return;
    PendingIntent pi = pending(context);
    long at = System.currentTimeMillis() + INTERVAL_MS;
    try {
      am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    } catch (SecurityException noExact) {
      am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }
  }

  private static PendingIntent pending(Context context) {
    return PendingIntent.getBroadcast(
        context, 0, new Intent(context, KeepAliveReceiver.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
  }

  @Override
  public void onReceive(Context context, Intent intent) {
    schedule(context); // 先把下一次排上
    Rootfs rootfs = new Rootfs(context);
    if (!rootfs.isInstalled()) return;
    boolean frpc = new File(rootfs.rootfs, "root/.frpc.ini").isFile();
    boolean cc = new File(rootfs.rootfs, "root/.cc-connect/ENABLED").isFile();
    if (!frpc && !cc) return;

    // 监督器已死则重新拉起（TerminalService.onCreate 已拉起过，这里兑底防极端回收）
    try {
      ServicesDaemon daemon = new ServicesDaemon(context);
      if (daemon.needed() && !daemon.isRunning()) daemon.start();
    } catch (Exception error) {
      android.util.Log.e("UbuntuBox", "keepalive daemon restart failed", error);
    }

    // 闹钟唤醒窗口很短，拿一个短暂的 wakelock 保证检查能跑完
    PowerManager.WakeLock wl =
        context.getSystemService(PowerManager.class)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ubox:keepalive");
    wl.acquire(45_000);
    new Thread(
            () -> {
              try {
                new Proot(context, rootfs).run(
                    "pgrep -f services-daemon.sh >/dev/null 2>&1"
                        + " && { [ ! -f /root/.frpc.ini ] || pgrep -x frpc >/dev/null 2>&1; }"
                        + " && { [ ! -f /root/.cc-connect/ENABLED ]"
                        + "    || pgrep -f 'cc-connect --config' >/dev/null 2>&1; }"
                        + " || echo RESTART_NEEDED",
                    30_000);
              } catch (Exception error) {
                android.util.Log.e("UbuntuBox", "keepalive check failed", error);
              } finally {
                if (wl.isHeld()) wl.release();
              }
            },
            "keepalive-check")
        .start();
  }
}
