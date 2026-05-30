package com.udonsaber.bot.urasaber;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * HTTPS 리스너 빌더 — 기존 udonsaber-bot 의 HttpApi 에서 그대로 가져온 TLS 로직.
 * keystore(PKCS12/JKS) 또는 PEM(Let's Encrypt fullchain+privkey) 두 모드 지원.
 */
final class TlsSupport {
    private TlsSupport() {}

    /** 설정된 cert 소스로 HttpsServer 를 만들어 반환 (start 는 호출자가). */
    static HttpsServer createHttpsServer(UraSaberConfig cfg) throws IOException {
        SSLContext ctx = (cfg.sslCertPath() != null)
                ? buildSslContextFromPem(cfg.sslCertPath(), cfg.sslKeyPath())
                : buildSslContextFromKeystore(cfg.sslKeystorePath(),
                        cfg.sslKeystorePassword().toCharArray(),
                        (cfg.sslKeyPassword() != null ? cfg.sslKeyPassword() : cfg.sslKeystorePassword()).toCharArray());
        HttpsServer server = HttpsServer.create(new InetSocketAddress("0.0.0.0", cfg.httpsPort()), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "urasaber-https");
            t.setDaemon(true);
            return t;
        }));
        return server;
    }

    private static SSLContext buildSslContextFromKeystore(Path keystorePath,
                                                          char[] keystorePassword,
                                                          char[] keyPassword) throws IOException {
        try {
            String name = keystorePath.getFileName().toString().toLowerCase(Locale.ROOT);
            String type = name.endsWith(".jks") ? "JKS" : "PKCS12";
            KeyStore ks = KeyStore.getInstance(type);
            try (InputStream in = Files.newInputStream(keystorePath)) {
                ks.load(in, keystorePassword);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, keyPassword);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("Failed to load keystore: " + e.getMessage(), e);
        }
    }

    private static SSLContext buildSslContextFromPem(Path certPath, Path keyPath) throws IOException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain;
            try (InputStream in = new BufferedInputStream(Files.newInputStream(certPath))) {
                Collection<? extends Certificate> certs = cf.generateCertificates(in);
                if (certs.isEmpty()) throw new IOException("no certificates found in " + certPath);
                chain = new ArrayList<>(certs.size());
                for (Certificate c : certs) chain.add((X509Certificate) c);
            }
            PrivateKey key = readPemPrivateKey(keyPath);

            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            char[] empty = new char[0];
            ks.setKeyEntry("server", key, empty, chain.toArray(new Certificate[0]));

            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, empty);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (IOException ioe) {
            throw ioe;
        } catch (Exception e) {
            throw new IOException("Failed to build SSL context from PEM: " + e.getMessage(), e);
        }
    }

    /** Reads an unencrypted PKCS#8 PEM private key (RSA or EC — covers Let's Encrypt). */
    private static PrivateKey readPemPrivateKey(Path keyPath) throws IOException {
        String pem = Files.readString(keyPath);
        if (pem.contains("BEGIN RSA PRIVATE KEY") || pem.contains("BEGIN EC PRIVATE KEY")) {
            throw new IOException(keyPath + " is PKCS#1 (BEGIN RSA/EC PRIVATE KEY); convert to PKCS#8 via "
                    + "`openssl pkcs8 -topk8 -nocrypt -in old.pem -out new.pem`");
        }
        String base64 = pem.replaceAll("-----BEGIN [^-]+-----", "")
                           .replaceAll("-----END [^-]+-----", "")
                           .replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(base64);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        Exception last = null;
        for (String alg : new String[] { "RSA", "EC", "DSA" }) {
            try { return KeyFactory.getInstance(alg).generatePrivate(spec); }
            catch (Exception e) { last = e; }
        }
        throw new IOException("Could not parse private key " + keyPath
                + " (tried RSA/EC/DSA): " + (last != null ? last.getMessage() : "unknown"));
    }
}
