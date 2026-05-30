package com.udonsaber.bot.urasaber;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Standalone UraSaber 서비스 설정. udonsaber-bot 에서 분리된 후 자체 프로세스로 구동.
 * 토큰은 env (DISCORD_TOKEN) 우선, 없으면 config/local.properties.
 * 키 이름은 기존 udonsaber-bot 의 local.properties 와 동일하게 유지 — 서버 config 재사용.
 */
public record UraSaberConfig(String discordToken, Path dbPath, int httpPort, String devGuildId,
                             Integer httpsPort, Path sslKeystorePath, String sslKeystorePassword,
                             String sslKeyPassword, Path sslCertPath, Path sslKeyPath) {

    public static UraSaberConfig load() throws IOException {
        Properties props = new Properties();
        Path local = Path.of("config", "local.properties");
        if (Files.exists(local)) {
            try (InputStream in = Files.newInputStream(local)) {
                props.load(in);
            }
        }
        String token = System.getenv("DISCORD_TOKEN");
        if (token == null) token = props.getProperty("discord.token");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "Discord token not set. Set DISCORD_TOKEN env var, or 'discord.token' in config/local.properties.");
        }

        Path dbPath = Path.of(props.getProperty("urasaber.db.path", "data/urasaber.db"));

        // 월드가 호출하는 평문 HTTP 포트. 기존 udonsaber-bot 과 동일하게 http.port (기본 8080).
        int httpPort = Integer.parseInt(props.getProperty("http.port", "8080"));

        String devGuildId = props.getProperty("dev.guild.id");
        if (devGuildId != null && devGuildId.isBlank()) devGuildId = null;

        // HTTPS 리스너 — 기존 봇과 동일 키. 월드가 https(8443)로 API 를 호출하므로 그대로 복제.
        Integer httpsPort = null;
        String httpsPortRaw = System.getenv("HTTPS_PORT");
        if (httpsPortRaw == null || httpsPortRaw.isBlank()) httpsPortRaw = props.getProperty("https.port");
        if (httpsPortRaw != null && !httpsPortRaw.isBlank()) {
            try { httpsPort = Integer.parseInt(httpsPortRaw); } catch (NumberFormatException ignored) {}
        }

        Path sslKeystorePath = null;
        String keystoreRaw = props.getProperty("ssl.keystore.path");
        if (keystoreRaw != null && !keystoreRaw.isBlank()) sslKeystorePath = Path.of(keystoreRaw);
        // Passwords: env wins over properties (same pattern as DISCORD_TOKEN).
        String sslKeystorePassword = System.getenv("SSL_KEYSTORE_PASSWORD");
        if (sslKeystorePassword == null || sslKeystorePassword.isBlank()) {
            sslKeystorePassword = props.getProperty("ssl.keystore.password");
        }
        if (sslKeystorePassword != null && sslKeystorePassword.isBlank()) sslKeystorePassword = null;
        String sslKeyPassword = System.getenv("SSL_KEY_PASSWORD");
        if (sslKeyPassword == null || sslKeyPassword.isBlank()) {
            sslKeyPassword = props.getProperty("ssl.key.password");
        }
        if (sslKeyPassword != null && sslKeyPassword.isBlank()) sslKeyPassword = null;

        // PEM mode (Let's Encrypt-friendly): fullchain.pem + privkey.pem.
        Path sslCertPath = pathFromEnvOrProp(System.getenv("SSL_CERT_PATH"), props.getProperty("ssl.cert.path"));
        Path sslKeyPath  = pathFromEnvOrProp(System.getenv("SSL_KEY_PATH"),  props.getProperty("ssl.key.path"));

        if (httpsPort != null) {
            boolean pemMode = sslCertPath != null && sslKeyPath != null;
            boolean keystoreMode = sslKeystorePath != null && sslKeystorePassword != null;
            if (!pemMode && !keystoreMode) {
                throw new IllegalStateException(
                        "https.port is set but no cert source — supply either "
                                + "(ssl.cert.path + ssl.key.path) or (ssl.keystore.path + ssl.keystore.password).");
            }
        }

        return new UraSaberConfig(token, dbPath, httpPort, devGuildId,
                httpsPort, sslKeystorePath, sslKeystorePassword, sslKeyPassword, sslCertPath, sslKeyPath);
    }

    private static Path pathFromEnvOrProp(String envVal, String propVal) {
        String v = (envVal != null && !envVal.isBlank()) ? envVal : propVal;
        return (v == null || v.isBlank()) ? null : Path.of(v);
    }
}
