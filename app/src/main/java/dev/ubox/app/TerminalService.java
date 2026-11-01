package dev.ubox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.IBinder;
import android.os.PowerManager;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 持有所有终端会话；前台服务保证切到后台后 Agent 仍在运行。 */
public class TerminalService extends Service {
  static final String ACTION_EXIT = "dev.ubox.app.EXIT";
  private static final String CHANNEL = "sessions";
  private static final int NOTIFICATION_ID = 1;

  final class LocalBinder extends Binder {
    TerminalService service() {
      return TerminalService.this;
    }
  }

  private final IBinder binder = new LocalBinder();
  private final List<TerminalSession> sessions = new ArrayList<>();
  private PowerManager.WakeLock wakeLock;
  private Runnable onExit;

  @Override
  public void onCreate() {
    super.onCreate();
    NotificationManager manager = getSystemService(NotificationManager.class);
    manager.createNotificationChannel(
        new NotificationChannel(CHANNEL, "终端会话", NotificationManager.IMPORTANCE_LOW));
    startForeground(
        NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_SSH, false)
        && new Rootfs(this).isInstalled()) {
      try {
        startSsh();
      } catch (IOException error) {
        android.util.Log.e("UbuntuBox", "ssh autostart failed", error);
      }
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && ACTION_EXIT.equals(intent.getAction())) {
      exit();
      return START_NOT_STICKY;
    }
    return START_NOT_STICKY;
  }

  @Override
  public IBinder onBind(Intent intent) {
    return binder;
  }

  @Override
  public void onDestroy() {
    stopSshProcess();
    for (TerminalSession session : sessions) session.finishIfRunning();
    sessions.clear();
    releaseWakeLock();
    super.onDestroy();
  }

  List<TerminalSession> sessions() {
    return sessions;
  }

  TerminalSession createSession(TerminalSessionClient client) throws IOException {
    return createSession(client, null, null);
  }

  /** guestCommand 为 null 时启动登录 shell，否则执行这条命令（用于设置页的安装任务）。 */
  TerminalSession createSession(TerminalSessionClient client, String name, String guestCommand)
      throws IOException {
    Rootfs rootfs = new Rootfs(this);
    rootfs.refreshGuestFiles(this);
    Proot proot = new Proot(this, rootfs);
    proot.prepare();
    String[] argv =
        guestCommand == null ? proot.argv() : proot.argv("/bin/bash", "-lc", guestCommand);
    TerminalSession session =
        new TerminalSession(
            argv[0], rootfs.base.getAbsolutePath(), argv, proot.environment(), 5000, client);
    session.mSessionName = name != null ? name : "会话 " + (sessions.size() + 1);
    sessions.add(session);
    updateNotification();
    return session;
  }

  void removeSession(TerminalSession session) {
    session.finishIfRunning();
    sessions.remove(session);
    updateNotification();
  }

  /** Activity 不在前台时，会话回调交给这个空实现。 */
  void detachClient(TerminalSessionClient fallback) {
    for (TerminalSession session : sessions) session.updateTerminalSessionClient(fallback);
  }

  // ---------------------------------------------------------------- SSH

  static final String PREFS = "ubox";
  static final String PREF_SSH = "ssh_enabled";
  static final int SSH_PORT = 8022;
  private Process sshProcess;

  File sshLog() {
    return new File(new Rootfs(this).base, "sshd.log");
  }

  boolean isSshRunning() {
    return sshProcess != null && sshProcess.isAlive();
  }

  /** 在后台（不占终端会话）运行 sshd，输出写到 sshd.log。 */
  synchronized void startSsh() throws IOException {
    if (isSshRunning()) return;
    Rootfs rootfs = new Rootfs(this);
    rootfs.refreshGuestFiles(this);
    Proot proot = new Proot(this, rootfs);
    proot.prepare();
    ProcessBuilder builder =
        new ProcessBuilder(proot.argv("/usr/local/bin/ubox-sshd", String.valueOf(SSH_PORT)))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(sshLog()))
            .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
    builder.environment().clear();
    for (String entry : proot.environment()) {
      int split = entry.indexOf('=');
      builder.environment().put(entry.substring(0, split), entry.substring(split + 1));
    }
    sshProcess = builder.start();
    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_SSH, true).apply();
    updateNotification();
  }

  synchronized void stopSsh() {
    stopSshProcess();
    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_SSH, false).apply();
    updateNotification();
  }

  private void stopSshProcess() {
    if (sshProcess == null) return;
    // proot 带 --kill-on-exit，结束它会连同 sshd 及其子进程一起结束
    sshProcess.destroy();
    try {
      if (!sshProcess.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) sshProcess.destroyForcibly();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    sshProcess = null;
  }

  void setOnExit(Runnable onExit) {
    this.onExit = onExit;
  }

  void exit() {
    stopSshProcess();
    for (TerminalSession session : new ArrayList<>(sessions)) session.finishIfRunning();
    sessions.clear();
    releaseWakeLock();
    stopForeground(STOP_FOREGROUND_REMOVE);
    stopSelf();
    if (onExit != null) onExit.run();
  }

  boolean isWakeLockHeld() {
    return wakeLock != null && wakeLock.isHeld();
  }

  void setWakeLock(boolean hold) {
    if (hold && !isWakeLockHeld()) {
      wakeLock =
          getSystemService(PowerManager.class)
              .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ubox:sessions");
      wakeLock.acquire();
    } else if (!hold) {
      releaseWakeLock();
    }
    updateNotification();
  }

  private void releaseWakeLock() {
    if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    wakeLock = null;
  }

  private void updateNotification() {
    getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
  }

  private Notification buildNotification() {
    PendingIntent open =
        PendingIntent.getActivity(
            this,
            0,
            new Intent(this, MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    PendingIntent exit =
        PendingIntent.getService(
            this,
            1,
            new Intent(this, TerminalService.class).setAction(ACTION_EXIT),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    String text =
        sessions.size()
            + " 个会话运行中"
            + (isSshRunning() ? " · SSH :" + SSH_PORT : "")
            + (isWakeLockHeld() ? " · 保持唤醒" : "");
    return new Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("UbuntuBox")
        .setContentText(text)
        .setContentIntent(open)
        .setOngoing(true)
        .addAction(new Notification.Action.Builder(null, "退出", exit).build())
        .build();
  }
}
