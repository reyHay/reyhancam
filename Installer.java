import java.io.*;
import java.net.*;

public class Installer {

    // ─── CONFIG ───────────────────────────────────────────────────────────────
    static final String APP_NAME        = "MyApp";
    static final String DOWNLOAD_URL    = "https://example.com/myapp.exe"; // <-- change this
    static final String INSTALL_DIR     = System.getenv("ProgramFiles") + "\\" + APP_NAME;
    static final String EXE_NAME        = "myapp.exe";
    static final boolean REQUEST_ADMIN  = false;  // request UAC elevation
    static final boolean ADD_TO_STARTUP = false;  // register in Windows startup
    // ──────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws Exception {

        if (REQUEST_ADMIN && !isAdmin()) {
            relaunchAsAdmin();
            return;
        }

        System.out.println("[*] Starting installation of " + APP_NAME);

        // 1. Create install directory
        File installDir = new File(INSTALL_DIR);
        if (!installDir.exists()) {
            installDir.mkdirs();
            System.out.println("[*] Created directory: " + INSTALL_DIR);
        }

        // 2. Download the application
        File target = new File(INSTALL_DIR + "\\" + EXE_NAME);
        System.out.println("[*] Downloading from: " + DOWNLOAD_URL);
        downloadFile(DOWNLOAD_URL, target);
        System.out.println("[*] Downloaded to: " + target.getAbsolutePath());

        // 3. Register in Windows startup (if enabled)
        if (ADD_TO_STARTUP) {
            addToStartup(target.getAbsolutePath());
            System.out.println("[*] Registered in startup.");
        }

        System.out.println("[+] Installation complete.");
    }

    // ── Check if running as administrator ────────────────────────────────────
    static boolean isAdmin() {
        try {
            ProcessBuilder pb = new ProcessBuilder("net", "session");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Relaunch self with UAC elevation via PowerShell ───────────────────────
    static void relaunchAsAdmin() throws Exception {
        String jarPath = new File(
            Installer.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).getAbsolutePath();

        // Uses PowerShell Start-Process with RunAs verb to trigger UAC prompt
        String[] cmd = {
            "powershell", "-Command",
            "Start-Process", "java",
            "-ArgumentList", "'-jar','" + jarPath + "'",
            "-Verb", "RunAs"
        };

        new ProcessBuilder(cmd).start();
        System.out.println("[*] Requesting admin rights...");
    }

    // ── Download a file from a URL ────────────────────────────────────────────
    static void downloadFile(String urlStr, File dest) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.connect();

        try (InputStream in = conn.getInputStream();
             FileOutputStream out = new FileOutputStream(dest)) {

            byte[] buf = new byte[8192];
            int read;
            long total = 0;
            long size = conn.getContentLengthLong();

            while ((read = in.read(buf)) != -1) {
                out.write(buf, 0, read);
                total += read;
                if (size > 0) {
                    int pct = (int) (total * 100 / size);
                    System.out.print("\r[*] Progress: " + pct + "%");
                }
            }
            System.out.println();
        }
    }

    // ── Add app to Windows startup via registry ───────────────────────────────
    static void addToStartup(String exePath) throws Exception {
        // Uses reg.exe to write to HKLM run key (requires admin)
        String[] cmd = {
            "reg", "add",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Run",
            "/v", APP_NAME,
            "/t", "REG_SZ",
            "/d", exePath,
            "/f"
        };
        Process p = new ProcessBuilder(cmd).start();
        p.waitFor();
        if (p.exitValue() != 0) {
            System.err.println("[!] Failed to add startup entry. Are you running as admin?");
        }
    }
}
