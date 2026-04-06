import java.security.*;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;

// Trusts self-signed certs for local HTTPS — do NOT use in production
public class TrustAllSSL {
    public static SSLSocketFactory getSocketFactory() throws Exception {
        TrustManager[] trustAll = new TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, trustAll, new SecureRandom());
        return ctx.getSocketFactory();
    }
}
