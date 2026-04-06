import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameGrabber;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

public class CameraClient {

    static final String SERVER_URL    = "wss://pccon.onrender.com";
    static final int    FPS           = 10;
    static final int    RECONNECT_SEC = 5;

    static volatile CameraWebSocket ws;
    static volatile boolean reconnecting = false;
    static String pcId;
    static boolean hasCamera = false;
    static OpenCVFrameGrabber grabber;

    public static void main(String[] args) throws Exception {
        pcId = InetAddress.getLocalHost().getHostName();

        // Try to open camera 0
        try {
            grabber = new OpenCVFrameGrabber(0);
            grabber.start();
            hasCamera = true;
            System.out.println("[*] PC: " + pcId + " | Camera: true");
        } catch (Exception e) {
            hasCamera = false;
            System.out.println("[*] PC: " + pcId + " | Camera: false (" + e.getMessage() + ")");
        }

        if (hasCamera) startFrameLoop();

        connectAndRun();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (ws != null) ws.close();
            if (grabber != null) try { grabber.stop(); } catch (Exception ignored) {}
        }));

        Thread.currentThread().join();
    }

    static void startFrameLoop() {
        long intervalMs = 1000L / FPS;
        Java2DFrameConverter converter = new Java2DFrameConverter();
        final int[] sent = {0};

        Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
            try {
                if (ws == null || !ws.isOpen()) return;
                Frame frame = grabber.grab();
                if (frame == null || frame.image == null) return;
                java.awt.image.BufferedImage img = converter.convert(frame);
                if (img == null) return;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "jpg", baos);
                byte[] bytes = baos.toByteArray();
                if (bytes.length == 0) return;
                String b64 = Base64.getEncoder().encodeToString(bytes);
                ws.send("{\"type\":\"frame\",\"id\":\"" + pcId + "\",\"frame\":\"" + b64 + "\"}");
                sent[0]++;
                if (sent[0] % 50 == 0) System.out.println("[*] Frames sent: " + sent[0]);
            } catch (Exception e) {
                System.err.println("[!] Frame error: " + e.getMessage());
            }
        }, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    static void connectAndRun() {
        try {
            ws = new CameraWebSocket(new URI(SERVER_URL));
            ws.connectBlocking();
            if (!ws.isOpen()) { scheduleReconnect(); return; }
            System.out.println("[+] Connected.");
            ws.send("{\"type\":\"hello\",\"id\":\"" + pcId + "\",\"hasCamera\":" + hasCamera + "}");
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
        @Override public void onMessage(String msg) {}
        @Override public void onClose(int code, String reason, boolean remote) {
            System.out.println("[*] Disconnected: " + reason);
            scheduleReconnect();
        }
        @Override public void onError(Exception e) {
            System.err.println("[!] WS error: " + e.getMessage());
        }
    }
}
