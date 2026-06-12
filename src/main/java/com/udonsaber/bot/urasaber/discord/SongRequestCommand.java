package com.udonsaber.bot.urasaber.discord;

import com.udonsaber.bot.urasaber.api.BeatSaverImporter;
import com.udonsaber.bot.urasaber.api.BeatSaverMapLookup;
import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <pre>
 *   /song-request map:&lt;BeatSaver 링크 | BSR 키&gt;   → 곡 정보 임베드 + 다운로드/BeatSaver 버튼
 * </pre>
 * 허용 입력: {@code 4ede8} / {@code !bsr 4ede8} / {@code https://beatsaver.com/maps/4ede8}.
 * BeatSaver 조회는 네트워크 호출이라 deferReply + editOriginal 패턴.
 * <p>
 * 추가로 {@code /urasaber-channel set type:request} 로 지정된 채널에서는 명령어 없이
 * BSR 키 / {@code !bsr} 콜 / BeatSaver 링크만 올려도 같은 임베드로 자동 응답한다
 * (MESSAGE_CONTENT 인텐트 필요).
 */
public class SongRequestCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(SongRequestCommand.class);

    /** BeatSaver 브랜드 느낌의 청록색. */
    private static final int EMBED_COLOR = 0x00BCD4;

    private static final Pattern BEATSAVER_URL_KEY = Pattern.compile("beatsaver\\.com/maps/([0-9a-fA-F]{1,12})");

    private final BeatSaverMapLookup lookup;
    private final UraSaberDatabase db;
    private final Executor exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "song-request-cmd");
        t.setDaemon(true);
        return t;
    });

    public SongRequestCommand(BeatSaverMapLookup lookup, UraSaberDatabase db) {
        this.lookup = lookup;
        this.db = db;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent ev) {
        if (!"song-request".equals(ev.getName())) return;
        var mapOpt = ev.getOption("map");
        if (mapOpt == null) {
            ev.reply("The `map` option is required.").setEphemeral(true).queue();
            return;
        }
        String key = parseBsrKey(mapOpt.getAsString());
        if (key == null) {
            ev.reply(UNRECOGNIZED_INPUT_MESSAGE).setEphemeral(true).queue();
            return;
        }

        String requester = ev.getUser().getEffectiveName();
        String requesterAvatar = ev.getUser().getEffectiveAvatarUrl();

        ev.deferReply().queue();
        InteractionHook hook = ev.getHook();

        exec.execute(() -> {
            try {
                MapResponse r = buildResponse(key, requester, requesterAvatar);
                if (r == null) {
                    hook.editOriginal(notFoundMessage(key)).queue();
                    return;
                }
                hook.editOriginalEmbeds(r.embed())
                        .setComponents(ActionRow.of(r.buttons()))
                        .queue();
            } catch (Exception e) {
                log.error("song-request failed (key={})", key, e);
                hook.editOriginal(internalErrorMessage(e)).queue();
            }
        });
    }

    /**
     * 지정된 request 채널에서는 명령어 없이 올라온 BSR 키 / {@code !bsr} 콜 / BeatSaver 링크에도
     * /song-request 와 동일하게 응답. 그 외 일반 잡담은 조용히 무시한다.
     */
    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent ev) {
        if (!ev.isFromGuild() || ev.getAuthor().isBot() || ev.isWebhookMessage()) return;
        String content = ev.getMessage().getContentRaw();
        if (content.isBlank()) return;

        String key = parseBsrKey(content);
        boolean explicitBsrCall = content.trim().toLowerCase(Locale.ROOT).startsWith("!bsr");
        // request 채널 DB 조회 전에 먼저 거름 — BSR 형태도 아니고 !bsr 콜도 아니면 볼 필요 없음.
        if (key == null && !explicitBsrCall) return;
        if (!isRequestChannel(ev.getGuild().getId(), ev.getChannel().getId())) return;

        Message message = ev.getMessage();
        if (key == null) {
            // !bsr 로 시작했는데 키 인식 실패 — 의도가 명확하니 피드백.
            message.reply(UNRECOGNIZED_INPUT_MESSAGE).mentionRepliedUser(false).queue();
            return;
        }

        String requester = ev.getAuthor().getEffectiveName();
        String requesterAvatar = ev.getAuthor().getEffectiveAvatarUrl();

        exec.execute(() -> {
            try {
                MapResponse r = buildResponse(key, requester, requesterAvatar);
                if (r == null) {
                    message.reply(notFoundMessage(key)).mentionRepliedUser(false).queue();
                    return;
                }
                message.replyEmbeds(r.embed())
                        .setComponents(ActionRow.of(r.buttons()))
                        .mentionRepliedUser(false)
                        .queue();
            } catch (Exception e) {
                log.error("song-request (message) failed (key={})", key, e);
                message.reply(internalErrorMessage(e)).mentionRepliedUser(false).queue();
            }
        });
    }

    /** 이 길드의 request 채널로 등록된 채널인지. 미등록이면 false. */
    private boolean isRequestChannel(String guildId, String channelId) {
        try {
            for (UraSaberDatabase.NotifyChannel r : db.getGuildNotifyChannels(guildId)) {
                if (UraSaberDatabase.NOTIFY_TYPE_REQUEST.equals(r.notifyType())) {
                    return r.channelId().equals(channelId);
                }
            }
        } catch (Exception e) {
            log.warn("request channel lookup failed (guild={})", guildId, e);
        }
        return false;
    }

    private record MapResponse(MessageEmbed embed, List<Button> buttons) {}

    /** BeatSaver 조회 + 임베드/버튼 구성. 맵이 없으면 null. 네트워크 호출 — exec 스레드에서만 부를 것. */
    private MapResponse buildResponse(String key, String requester, String requesterAvatar) {
        BeatSaverMapLookup.MapInfo info = lookup.lookup(key);
        if (info == null) return null;
        String pageUrl = "https://beatsaver.com/maps/" + key;

        EmbedBuilder eb = new EmbedBuilder()
                .setColor(EMBED_COLOR)
                .setTitle(blankDash(info.name()), pageUrl)
                .setFooter("Requested by " + requester, requesterAvatar);

        StringBuilder desc = new StringBuilder();
        if (info.subName() != null && !info.subName().isBlank()) {
            desc.append('*').append(info.subName()).append("*\n");
        }
        desc.append("`!bsr ").append(key).append('`');
        eb.setDescription(desc.toString());

        if (info.coverUrl() != null && !info.coverUrl().isBlank()) {
            eb.setThumbnail(info.coverUrl());
        }
        eb.addField("Artist", blankDash(info.author()), true);
        eb.addField("Mapper", blankDash(info.mapper()), true);
        eb.addField("Length", formatDuration(info.duration()), true);
        eb.addField("BPM", formatBpm(info.bpm()), true);

        List<Button> buttons = new ArrayList<>(2);
        if (info.downloadUrl() != null && !info.downloadUrl().isBlank()) {
            buttons.add(Button.link(info.downloadUrl(), "⬇️ Download"));
        }
        buttons.add(Button.link(pageUrl, "🔗 BeatSaver"));

        return new MapResponse(eb.build(), buttons);
    }

    private static final String UNRECOGNIZED_INPUT_MESSAGE =
            "⚠️ Couldn't recognize a BeatSaver link or BSR key.\n"
                    + "e.g. `4ede8` · `!bsr 4ede8` · `https://beatsaver.com/maps/4ede8`";

    private static String notFoundMessage(String key) {
        return "⚠️ Map `" + key + "` was not found on BeatSaver.";
    }

    private static String internalErrorMessage(Exception e) {
        return "⚠️ Internal error: " + e.getClass().getSimpleName() + " — " + e.getMessage();
    }

    /** 입력에서 BSR 키 추출 — raw 키 / "!bsr 키" / beatsaver.com 링크 모두 허용. 인식 실패 시 null. */
    static String parseBsrKey(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        Matcher m = BEATSAVER_URL_KEY.matcher(s);
        if (m.find()) {
            s = m.group(1);
        } else {
            String lower = s.toLowerCase(Locale.ROOT);
            if (lower.startsWith("!bsr")) s = s.substring(4).trim();
            else if (lower.startsWith("bsr ")) s = s.substring(4).trim();
        }
        return BeatSaverImporter.isValidBsr(s) ? s.toLowerCase(Locale.ROOT) : null;
    }

    /** 초 → "m:ss" (1시간 이상이면 "h:mm:ss"). 0 이하면 "—". */
    private static String formatDuration(double seconds) {
        long total = Math.round(seconds);
        if (total <= 0) return "—";
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private static String formatBpm(double bpm) {
        if (bpm <= 0) return "—";
        if (bpm == Math.floor(bpm)) return Long.toString((long) bpm);
        return String.format(Locale.ROOT, "%.1f", bpm);
    }

    private static String blankDash(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
