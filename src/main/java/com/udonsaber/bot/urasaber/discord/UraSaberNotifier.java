package com.udonsaber.bot.urasaber.discord;

import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * UraSaber 점수가 서버에 들어올 때 Discord 채널들에 임베드를 보냄.
 */
public class UraSaberNotifier {
    private static final Logger log = LoggerFactory.getLogger(UraSaberNotifier.class);

    private final UraSaberDatabase db;
    private final Supplier<JDA> jdaSupplier;

    public UraSaberNotifier(UraSaberDatabase db, Supplier<JDA> jdaSupplier) {
        this.db = db;
        this.jdaSupplier = jdaSupplier;
    }

    public record ScoreEvent(
            String nickname,
            String mapId,
            String songName,
            String songAuthor,
            String characteristic,
            int difficulty,
            int score,
            Double accuracy,
            Integer combo,
            Integer maxCombo,
            Integer goodCut,
            Integer badCut,
            Integer miss,
            Integer noteCount,
            boolean fullCombo,
            String rankLetter,
            boolean personalBest,
            String country,
            long playedAt,
            String coverUrl,
            Integer mapNoteCount,
            String arcViewerUrl   // ArcViewer 리플레이 보기 링크 (없으면 null)
    ) {}

    /** 월드에서 BSR 코드로 BeatSaver 곡을 콜했을 때 알림에 쓰이는 이벤트. */
    public record ImportEvent(
            String bsr,
            String songName,
            String songAuthor,
            String mapper,
            String coverUrl,
            double bpm,
            double duration,
            long calledAt
    ) {}

    public void announce(ScoreEvent ev) {
        sendToType(UraSaberDatabase.NOTIFY_TYPE_SCORE, () -> buildEmbed(ev), "score");
    }

    /** 월드에서 BSR 콜 발생 시 알림. */
    public void announceImport(ImportEvent ev) {
        sendToType(UraSaberDatabase.NOTIFY_TYPE_IMPORT, () -> buildImportEmbed(ev), "import");
    }

    /** notify_type 별 등록 채널로 임베드 푸시. embed 는 등록 채널이 있을 때만 생성 (lazy). */
    private void sendToType(String notifyType, Supplier<MessageEmbed> embedSupplier, String logLabel) {
        JDA jda = jdaSupplier == null ? null : jdaSupplier.get();
        if (jda == null) return;

        final List<UraSaberDatabase.NotifyChannel> targets;
        try {
            targets = db.listNotifyChannels(notifyType);
        } catch (Exception e) {
            log.warn("listNotifyChannels({}) failed: {}", notifyType, e.getMessage());
            return;
        }
        if (targets.isEmpty()) return;

        MessageEmbed embed = embedSupplier.get();
        for (var t : targets) {
            TextChannel ch = jda.getTextChannelById(t.channelId());
            if (ch == null) {
                log.warn("urasaber {} channel {} not found in guild {}", logLabel, t.channelId(), t.guildId());
                continue;
            }
            ch.sendMessageEmbeds(embed).queue(
                    ok -> {},
                    err -> log.warn("Failed to send urasaber {} notification to {} in {}: {}",
                            logLabel, t.channelId(), t.guildId(), err.getMessage()));
        }
    }

    private MessageEmbed buildImportEmbed(ImportEvent ev) {
        String name = ev.songName() == null || ev.songName().isBlank() ? "(unknown)" : ev.songName();
        String beatsaverUrl = "https://beatsaver.com/maps/" + ev.bsr();

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(new Color(0x9B59B6))
                .setTitle("🎶 " + name, beatsaverUrl);

        StringBuilder body = new StringBuilder();
        if (ev.songAuthor() != null && !ev.songAuthor().isBlank()) {
            body.append("**").append(ev.songAuthor()).append("**");
        }
        if (ev.mapper() != null && !ev.mapper().isBlank()) {
            if (body.length() > 0) body.append("  ·  ");
            body.append("mapper: ").append(ev.mapper());
        }
        if (body.length() > 0) body.append('\n');
        body.append("BeatSaver: ").append(beatsaverUrl);
        eb.setDescription(body.toString());

        if (ev.bpm() > 0) {
            eb.addField("BPM", String.format(Locale.ROOT, "%.1f", ev.bpm()), true);
        }
        if (ev.duration() > 0) {
            int sec = (int) Math.round(ev.duration());
            eb.addField("Duration", String.format("%d:%02d", sec / 60, sec % 60), true);
        }
        eb.addField("BSR", "`" + ev.bsr() + "`", true);

        if (ev.coverUrl() != null && !ev.coverUrl().isBlank()) {
            eb.setThumbnail(ev.coverUrl());
        }
        if (ev.calledAt() > 0) eb.setTimestamp(Instant.ofEpochSecond(ev.calledAt()));
        eb.setFooter("UraSaber · BSR called");
        return eb.build();
    }

    private MessageEmbed buildEmbed(ScoreEvent ev) {
        String diffLabel = difficultyLabel(ev.difficulty());
        String charPrefix = "Standard".equalsIgnoreCase(ev.characteristic()) ? "" : ev.characteristic() + " · ";
        String songLine = ev.songName() == null ? "(unknown)" : ev.songName();
        if (ev.songAuthor() != null && !ev.songAuthor().isBlank()) songLine += "  —  " + ev.songAuthor();

        Color color = ev.fullCombo() ? new Color(0xF5C242)
                    : ev.personalBest() ? new Color(0x4FB3F5)
                    : new Color(0x808890);
        String titlePrefix = ev.fullCombo() ? "💎 FC! "
                           : ev.personalBest() ? "✨ PB! "
                           : "🎵 ";
        String flag = countryFlag(ev.country());
        String nickDisplay = (flag.isEmpty() ? "" : flag + " ") + safe(ev.nickname());

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(color)
                .setTitle(titlePrefix + nickDisplay + "  " + String.format("%,d", ev.score()));

        // 첫 줄: 곡 + 아티스트
        StringBuilder body = new StringBuilder();
        body.append("**").append(songLine).append("**\n");
        body.append("`").append(charPrefix).append(diffLabel).append("`");
        if (ev.rankLetter() != null && !ev.rankLetter().isBlank() && !"-".equals(ev.rankLetter()))
            body.append("  ·  ").append(ev.rankLetter());
        if (ev.accuracy() != null)
            body.append("  ·  ").append(String.format(Locale.ROOT, "%.2f%%", ev.accuracy()));
        if (ev.fullCombo()) body.append("  ·  **FULL COMBO**");
        eb.setDescription(body.toString());

        // 곡/난이도 썸네일 (DB 또는 BeatSaver 에서 해석된 cover)
        if (ev.coverUrl() != null && !ev.coverUrl().isBlank()) {
            eb.setThumbnail(ev.coverUrl());
        }

        // 난이도 총 노트 수: 맵 메타(DB/BeatSaver) 우선, 없으면 제출값.
        Integer notesTotal = ev.mapNoteCount() != null ? ev.mapNoteCount() : ev.noteCount();

        // 콤보
        if (ev.maxCombo() != null && notesTotal != null && notesTotal > 0) {
            eb.addField("Max combo", ev.maxCombo() + " / " + notesTotal, true);
        } else if (ev.maxCombo() != null) {
            eb.addField("Max combo", String.valueOf(ev.maxCombo()), true);
        }

        // 노트 정보
        if (notesTotal != null && notesTotal > 0) {
            eb.addField("Notes", String.valueOf(notesTotal), true);
        }

        // Good / Bad / Miss
        if (ev.goodCut() != null || ev.badCut() != null || ev.miss() != null) {
            String good = ev.goodCut() == null ? "—" : ev.goodCut().toString();
            String bad  = ev.badCut()  == null ? "—" : ev.badCut().toString();
            String miss = ev.miss()    == null ? "—" : ev.miss().toString();
            eb.addField("Good · Bad · Miss",
                    "✅ " + good + "  ·  ⚠️ " + bad + "  ·  ❌ " + miss, true);
        }

        // ArcViewer 리플레이 보기 링크 (있을 때만)
        if (ev.arcViewerUrl() != null && !ev.arcViewerUrl().isBlank()) {
            eb.addField("리플레이", "[▶ ArcViewer 에서 보기](" + ev.arcViewerUrl() + ")", false);
        }

        if (ev.mapId() != null && !ev.mapId().isBlank()) {
            eb.setFooter("BS " + ev.mapId());
        }
        if (ev.playedAt() > 0) eb.setTimestamp(Instant.ofEpochSecond(ev.playedAt()));

        return eb.build();
    }

    /** ISO 2자 코드 → 🇰🇷 같은 regional indicator 이모지. 비어있거나 형식 안 맞으면 "". */
    private static String countryFlag(String iso2) {
        if (iso2 == null || iso2.length() != 2) return "";
        char a = Character.toUpperCase(iso2.charAt(0));
        char b = Character.toUpperCase(iso2.charAt(1));
        if (a < 'A' || a > 'Z' || b < 'A' || b > 'Z') return "";
        int base = 0x1F1E6 - 'A';
        return new String(Character.toChars(base + a)) + new String(Character.toChars(base + b));
    }

    private static String difficultyLabel(int d) {
        switch (d) {
            case 0: return "Easy";
            case 1: return "Normal";
            case 2: return "Hard";
            case 3: return "Expert";
            case 4: return "Expert+";
            default: return "diff#" + d;
        }
    }

    private static String safe(String s) { return s == null ? "(unknown)" : s; }
}
