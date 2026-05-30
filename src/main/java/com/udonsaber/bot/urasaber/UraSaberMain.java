package com.udonsaber.bot.urasaber;

import com.sun.net.httpserver.HttpsServer;
import com.udonsaber.bot.urasaber.api.UraSaberHttpApi;
import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import com.udonsaber.bot.urasaber.discord.UraSaberNotifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Standalone UraSaber 서비스 진입점.
 * 월드가 호출하는 HTTP API(/urasaber/api/*) + Discord 알림/playlist 명령어를 한 프로세스로 구동.
 * udonsaber-bot 에서 분리된 독립 서비스 — 자체 DB(urasaber.db)와 자체 Discord 봇.
 * 평문 HTTP + (설정 시) HTTPS 둘 다 같은 라우트로 서빙 — 기존 udonsaber-bot 동작 그대로.
 */
public class UraSaberMain {
    private static final Logger log = LoggerFactory.getLogger(UraSaberMain.class);

    public static void main(String[] args) throws Exception {
        UraSaberConfig config = UraSaberConfig.load();

        UraSaberDatabase uraDb = new UraSaberDatabase(config.dbPath());
        UraSaberHttpApi uraApi = new UraSaberHttpApi(uraDb);

        // Discord 봇 (slash commands). JDA 가 준비되기 전엔 null 이므로 lazy supplier 로 알림에 주입.
        UraSaberBot bot = new UraSaberBot(config, uraDb);
        UraSaberNotifier notifier = new UraSaberNotifier(uraDb, bot::getJda);
        uraApi.setNotifier(notifier);

        // 월드가 호출하는 평문 HTTP — 자체 포트로 standalone 구동.
        uraApi.startStandalone(config.httpPort());

        // HTTPS 리스너 — 설정되면 같은 라우트를 mount (월드가 https 로 호출).
        final HttpsServer secureServer;
        if (config.httpsPort() != null) {
            secureServer = TlsSupport.createHttpsServer(config);
            uraApi.attachTo(secureServer);
            secureServer.start();
            log.info("UraSaber HTTPS API listening on 0.0.0.0:{} ({})",
                    config.httpsPort(), config.sslCertPath() != null ? "PEM" : "keystore");
        } else {
            secureServer = null;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (secureServer != null) secureServer.stop(0);
                uraApi.stop();
                bot.shutdown();
                uraDb.close();
            } catch (Exception ignored) {}
        }));

        bot.start();
    }
}
