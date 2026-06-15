package com.udonsaber.bot.urasaber.discord;

import com.udonsaber.bot.urasaber.api.BeatSaverImporter;
import com.udonsaber.bot.urasaber.api.BeatSaverMapLookup;
import com.udonsaber.bot.urasaber.api.BplistFetcher;
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
import net.dv8tion.jda.api.utils.FileUpload;
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
 * <p>
 * 같은 request 채널에 <b>playlist 링크</b>(BeatSaver playlist 페이지 또는 직접 {@code .bplist} URL)를
 * 올리면 해당 .bplist 를 그대로 첨부로 돌려줘서 전곡을 한 번에 다운로드(import)할 수 있게 한다.
 */
public class SongRequestCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(SongRequestCommand.class);

    /** BeatSaver 브랜드 느낌의 청록색. */
    private static final int EMBED_COLOR = 0x00BCD4;

    private static final Pattern BEATSAVER_URL_KEY = Pattern.compile("beatsaver\\.com/maps/([0-9a-fA-F]{1,12})");

    /** BeatSaver playlist 링크 — {@code beatsaver.com/playlists/123} / {@code api.beatsaver.com/playlists/id/123/...}. */
    private static final Pattern BEATSAVER_PLAYLIST_URL =
            Pattern.compile("beatsaver\\.com/playlists/(?:id/)?(\\d+)", Pattern.CASE_INSENSITIVE);
    /** 직접 .bplist URL (Hitbloq / ScoreSaber / 임의 호스팅). */
    private static final Pattern BPLIST_FILE_URL =
            Pattern.compile("(https?://\\S+?\\.bplist(?:\\?\\S*)?)", Pattern.CASE_INSENSITIVE);

    private final BeatSaverMapLookup lookup;
    private final UraSaberDatabase db;
    private final BplistFetcher bplistFetcher;
    private final Executor exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "song-request-cmd");
        t.setDaemon(true);
        return t;
    });

    public SongRequestCommand(BeatSaverMapLookup lookup, UraSaberDatabase db, BplistFetcher bplistFetcher) {
        this.lookup = lookup;
        this.db = db;
        this.bplistFetcher = bplistFetcher;
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

        PlaylistRef playlist = parsePlaylistRef(content);
        String key = parseBsrKey(content);
        boolean explicitBsrCall = content.trim().toLowerCase(Locale.ROOT).startsWith("!bsr");
        // request 채널 DB 조회 전에 먼저 거름 — playlist 도 BSR 도 !bsr 콜도 아니면 볼 필요 없음.
        if (playlist == null && key == null && !explicitBsrCall) return;
        if (!isRequestChannel(ev.getGuild().getId(), ev.getChannel().getId())) return;

        Message message = ev.getMessage();
        String requester = ev.getAuthor().getEffectiveName();
        String requesterAvatar = ev.getAuthor().getEffectiveAvatarUrl();

        // playlist 링크 → .bplist 를 첨부로 돌려줘서 전곡 다운로드 가능하게. (BSR 보다 우선)
        if (playlist != null) {
            exec.execute(() -> handlePlaylist(message, playlist, requester, requesterAvatar));
            return;
        }

        if (key == null) {
            // !bsr 로 시작했는데 키 인식 실패 — 의도가 명확하니 피드백.
            message.reply(UNRECOGNIZED_INPUT_MESSAGE).mentionRepliedUser(false).queue();
            return;
        }

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

    // ========================================
    // Playlist 링크 → .bplist 첨부 (전곡 다운로드)
    // ========================================

    /** 채널에 올라온 playlist 참조 — syncUrl 은 실제 .bplist 다운로드 URL, pageUrl 은 BeatSaver 페이지(없으면 null). */
    private record PlaylistRef(String syncUrl, String pageUrl) {}

    /** 메시지 본문에서 playlist 링크 추출 — BeatSaver playlist 페이지 / 직접 .bplist URL. 없으면 null. */
    static PlaylistRef parsePlaylistRef(String content) {
        if (content == null) return null;
        Matcher bs = BEATSAVER_PLAYLIST_URL.matcher(content);
        if (bs.find()) {
            String id = bs.group(1);
            return new PlaylistRef(
                    "https://api.beatsaver.com/playlists/id/" + id + "/download",
                    "https://beatsaver.com/playlists/" + id);
        }
        Matcher bp = BPLIST_FILE_URL.matcher(content);
        if (bp.find()) {
            return new PlaylistRef(bp.group(1), null);
        }
        return null;
    }

    /** playlist .bplist 다운로드 → 같은 파일을 첨부로 돌려줘 전곡 다운로드(import) 가능하게. exec 스레드에서만 호출. */
    private void handlePlaylist(Message message, PlaylistRef ref, String requester, String requesterAvatar) {
        try {
            BplistFetcher.RawResult r = bplistFetcher.fetchRaw(ref.syncUrl());
            if (!r.ok) {
                message.reply(playlistErrorMessage(r.error)).mentionRepliedUser(false).queue();
                return;
            }
            if (r.songCount == 0) {
                message.reply("⚠️ This playlist has no songs.").mentionRepliedUser(false).queue();
                return;
            }

            String title = (r.title == null || r.title.isBlank()) ? "Playlist" : r.title;
            EmbedBuilder eb = new EmbedBuilder()
                    .setColor(EMBED_COLOR)
                    // pageUrl 이 null 이면 링크 없는 제목. 제목은 256자 제한이라 잘라냄.
                    .setTitle(trimTo("📑 " + title, MessageEmbed.TITLE_MAX_LENGTH), ref.pageUrl())
                    .setFooter("Requested by " + requester, requesterAvatar);

            StringBuilder desc = new StringBuilder();
            if (r.author != null && !r.author.isBlank()) {
                desc.append("by **").append(r.author).append("**\n");
            }
            desc.append("🎵 **").append(r.songCount).append("** songs\n\n");
            desc.append("Import the attached `.bplist` (ModAssistant ▸ *Mods/Playlists*, or drop it in your "
                    + "`Beat Saber/Playlists` folder) to download every song at once.");
            eb.setDescription(trimTo(desc.toString(), MessageEmbed.DESCRIPTION_MAX_LENGTH));

            FileUpload file = FileUpload.fromData(r.bytes, safeFileName(title) + ".bplist");

            var action = message.replyEmbeds(eb.build())
                    .setFiles(file)
                    .mentionRepliedUser(false);
            if (ref.pageUrl() != null) {
                action.setComponents(ActionRow.of(Button.link(ref.pageUrl(), "🔗 BeatSaver (One-Click)")));
            }
            action.queue();
        } catch (Exception e) {
            log.error("playlist handling failed (url={})", ref.syncUrl(), e);
            message.reply(internalErrorMessage(e)).mentionRepliedUser(false).queue();
        }
    }

    private static String playlistErrorMessage(String error) {
        return "⚠️ Couldn't fetch that playlist (`" + error + "`).\n"
                + "Make sure the link is a BeatSaver playlist page or a direct `.bplist` URL.";
    }

    /** 첨부 파일명용 — 위험/특수 문자 제거, 60자 이내. 비면 \"playlist\". */
    private static String safeFileName(String title) {
        if (title == null) return "playlist";
        StringBuilder b = new StringBuilder(title.length());
        for (int i = 0; i < title.length() && b.length() < 60; i++) {
            char c = title.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_') b.append(c);
            else b.append('_');
        }
        String s = b.toString().trim();
        return s.isEmpty() ? "playlist" : s;
    }

    /** s 를 max 길이 이내로 자름 (초과 시 끝에 … ). */
    private static String trimTo(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
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
