package com.udonsaber.bot.urasaber;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Standalone UraSaber 서비스 설정. udonsaber-bot 에서 분리된 후 자체 프로세스로 구동.
 * 토큰은 env (DISCORD_TOKEN) 우선, 없으면 config/local.properties.
 */
public record UraSaberConfig(String discordToken, Path dbPath, int httpPort, String devGuildId) {

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

        // 월드가 호출하는 HTTP 포트. udonsaber-bot 과 같은 라우트(/urasaber/api/*)를 그대로 서빙.
        int httpPort = Integer.parseInt(props.getProperty("urasaber.http.port",
                props.getProperty("http.port", "8080")));

        String devGuildId = props.getProperty("dev.guild.id");
        if (devGuildId != null && devGuildId.isBlank()) devGuildId = null;

        return new UraSaberConfig(token, dbPath, httpPort, devGuildId);
    }
}
