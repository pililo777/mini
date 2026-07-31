package com.pililo777.minissh;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

public class TProxyService extends VpnService {
    public static final String ACTION_CONNECT = "com.pililo777.minissh.CONNECT_VPN";
    public static final String ACTION_DISCONNECT = "com.pililo777.minissh.DISCONNECT_VPN";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_USER = "user";
    public static final String EXTRA_PASSWORD = "password";
    public static final String EXTRA_FINGERPRINT = "fingerprint";

    private static final String CHANNEL_ID = "minissh_vpn";
    private static final int NOTIFICATION_ID = 7;
    private static final int SOCKS_PORT = 10808;

    private static native boolean TProxyStartService(String configPath, int fd);
    private static native boolean TProxyStopService();

    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private final Object lock = new Object();
    private volatile boolean stopping;
    private volatile boolean nativeStarted;
    private ParcelFileDescriptor tunFd;
    private Session sshSession;
    private SocksOverSshServer socksServer;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            stopping = true;
            cleanup("off", "Desconectado");
            stopSelf();
            return START_NOT_STICKY;
        }

        if (worker != null && worker.isAlive()) return START_NOT_STICKY;

        String host = intent == null ? null : intent.getStringExtra(EXTRA_HOST);
        int port = intent == null ? 22 : intent.getIntExtra(EXTRA_PORT, 22);
        String user = intent == null ? null : intent.getStringExtra(EXTRA_USER);
        String password = intent == null ? null : intent.getStringExtra(EXTRA_PASSWORD);
        String fingerprint = intent == null ? null : intent.getStringExtra(EXTRA_FINGERPRINT);

        if (host == null || host.trim().isEmpty() || user == null || user.trim().isEmpty()
                || password == null || password.isEmpty() || PinnedHostKeyRepository.normalize(fingerprint) == null) {
            setState("error", "Datos VPN incompletos");
            stopSelf();
            return START_NOT_STICKY;
        }

        stopping = false;
        setState("connecting", "Conectando SSH...");
        startForeground(NOTIFICATION_ID, buildNotification("Conectando con Ubuntu..."));

        worker = new Thread(() -> connectAndRun(host.trim(), port, user.trim(), password, fingerprint), "MiniSSH-VPN");
        worker.start();
        return START_NOT_STICKY;
    }

    private void connectAndRun(String host, int port, String user, String password, String fingerprint) {
        try {
            PinnedHostKeyRepository repository = new PinnedHostKeyRepository(fingerprint);
            JSch jsch = new JSch();
            Session session = jsch.getSession(user, host, port);
            session.setHostKeyRepository(repository);
            session.setPassword(password);
            Properties config = new Properties();
            config.put("StrictHostKeyChecking", "yes");
            config.put("PreferredAuthentications", "password,keyboard-interactive");
            session.setConfig(config);
            session.setServerAliveInterval(10_000);
            session.setServerAliveCountMax(2);
            session.connect(15_000);
            sshSession = session;

            SocksOverSshServer localSocks = new SocksOverSshServer(session, SOCKS_PORT);
            localSocks.start();
            socksServer = localSocks;

            Builder builder = new Builder()
                    .setSession("Mini SSH - Ubuntu")
                    .setMtu(1500)
                    .addAddress("198.18.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("198.18.0.2");
            try {
                builder.addDisallowedApplication(getPackageName());
            } catch (PackageManager.NameNotFoundException ignored) { }

            ParcelFileDescriptor descriptor = builder.establish();
            if (descriptor == null) throw new IllegalStateException("Android no pudo crear la interfaz VPN");
            tunFd = descriptor;

            File configFile = new File(getCacheDir(), "minissh-tun2socks.conf");
            String nativeConfig =
                    "tunnel:\n" +
                    " mtu: 1500\n" +
                    " ipv4: 198.18.0.1\n" +
                    " icmp: 'reply'\n" +
                    "socks5:\n" +
                    " address: '127.0.0.1'\n" +
                    " port: " + SOCKS_PORT + "\n" +
                    " udp: 'udp'\n" +
                    "mapdns:\n" +
                    " address: 198.18.0.2\n" +
                    " port: 53\n" +
                    " network: 100.64.0.0\n" +
                    " netmask: 255.192.0.0\n" +
                    " cache-size: 10000\n" +
                    "misc:\n" +
                    " connect-timeout: 10000\n" +
                    " tcp-read-write-timeout: 300000\n" +
                    " log-level: warn\n";
            try (FileOutputStream fos = new FileOutputStream(configFile, false)) {
                fos.write(nativeConfig.getBytes(StandardCharsets.UTF_8));
            }

            nativeStarted = TProxyStartService(configFile.getAbsolutePath(), descriptor.getFd());
            if (!nativeStarted) throw new IllegalStateException("No se pudo iniciar tun2socks");

            setState("on", "VPN activa: salida por Ubuntu");
            updateNotification("VPN activa - salida por Ubuntu");

            while (!stopping && session.isConnected()) {
                Thread.sleep(2_000);
            }

            if (!stopping) {
                cleanup("error", "SSH se perdió; VPN cerrada automáticamente");
                stopSelf();
            }
        } catch (Exception e) {
            if (!stopping) {
                cleanup("error", "VPN cerrada: " + safeMessage(e));
                stopSelf();
            }
        }
    }

    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return (message == null || message.trim().isEmpty()) ? e.getClass().getSimpleName() : message;
    }

    private void cleanup(String state, String message) {
        synchronized (lock) {
            ParcelFileDescriptor descriptor = tunFd;
            tunFd = null;
            if (descriptor != null) {
                try { descriptor.close(); } catch (Exception ignored) { }
            }

            if (nativeStarted) {
                try { TProxyStopService(); } catch (Throwable ignored) { }
                nativeStarted = false;
            }

            SocksOverSshServer server = socksServer;
            socksServer = null;
            if (server != null) {
                try { server.close(); } catch (Exception ignored) { }
            }

            Session session = sshSession;
            sshSession = null;
            if (session != null) {
                try { session.disconnect(); } catch (Exception ignored) { }
            }

            setState(state, message);
            stopForeground(true);
        }
    }

    private void setState(String state, String message) {
        getSharedPreferences("vpn", MODE_PRIVATE).edit()
                .putString("state", state)
                .putString("message", message)
                .apply();
    }

    private Notification buildNotification(String text) {
        Intent openIntent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent disconnectIntent = new Intent(this, TProxyService.class).setAction(ACTION_DISCONNECT);
        PendingIntent disconnectPending = PendingIntent.getService(
                this, 1, disconnectIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("Mini SSH VPN")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentIntent(openPending)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        null, "DESCONECTAR", disconnectPending).build())
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Mini SSH VPN", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onRevoke() {
        stopping = true;
        cleanup("off", "VPN revocada por Android");
        stopSelf();
        super.onRevoke();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        cleanup("off", "VPN detenida");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
