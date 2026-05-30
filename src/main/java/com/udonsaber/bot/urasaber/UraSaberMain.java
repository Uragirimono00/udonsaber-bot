package com.udonsaber.bot.urasaber;

import com.udonsaber.bot.urasaber.api.UraSaberHttpApi;
import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import com.udonsaber.bot.urasaber.discord.UraSaberNotifier;

/**
 * Standalone UraSaber 서비스 진입점.
 * 월드가 호출하는 HTTP API(/urasaber/api/*) + Discord 알림/playlist 명령어를 한 프로세스로 구동.
 * udonsaber-bot 에서 분리된 독립 서비스 — 자체 DB(urasaber.db)와 자체 Discord 봇.
 */
public class UraSaberMain {
    public static void main(String[] args) throws Exception {
        UraSaberConfig config = UraSaberConfig.load();

        UraSaberDatabase uraDb = new UraSaberDatabase(config.dbPath());
        UraSaberHttpApi uraApi = new UraSaberHttpApi(uraDb);

        // Discord 봇 (slash commands). JDA 가 준비되기 전엔 null 이므로 lazy supplier 로 알림에 주입.
        UraSaberBot bot = new UraSaberBot(config, uraDb);
        UraSaberNotifier notifier = new UraSaberNotifier(uraDb, bot::getJda);
        uraApi.setNotifier(notifier);

        // 월드가 호출하는 HTTP 라우트 — 자체 포트로 standalone 구동.
        uraApi.startStandalone(config.httpPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                uraApi.stop();
                bot.shutdown();
                uraDb.close();
            } catch (Exception ignored) {}
        }));

        bot.start();
    }
}
