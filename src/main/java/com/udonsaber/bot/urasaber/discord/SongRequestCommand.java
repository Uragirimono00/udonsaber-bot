package com.udonsaber.bot.urasaber.discord;

import com.udonsaber.bot.urasaber.api.BeatSaverImporter;
import com.udonsaber.bot.urasaber.api.BeatSaverMapLookup;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
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
 */
public class SongRequestCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(SongRequestCommand.class);

    /** BeatSaver 브랜드 느낌의 청록색. */
    private static final int EMBED_COLOR = 0x00BCD4;

    private static final Pattern BEATSAVER_URL_KEY = Pattern.compile("beatsaver\\.com/maps/([0-9a-fA-F]{1,12})");

    private final BeatSaverMapLookup lookup;
    private final Executor exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "song-request-cmd");
        t.setDaemon(true);
        return t;
    });

    public SongRequestCommand(BeatSaverMapLookup lookup) {
        this.lookup = lookup;
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
            ev.reply("⚠️ Couldn't recognize a BeatSaver link or BSR key.\n"
                    + "e.g. `4ede8` · `!bsr 4ede8` · `https://beatsaver.com/maps/4ede8`")
                    .setEphemeral(true).queue();
            return;
        }

        String requester = ev.getUser().getEffectiveName();
        String requesterAvatar = ev.getUser().getEffectiveAvatarUrl();

        ev.deferReply().queue();
        InteractionHook hook = ev.getHook();

        exec.execute(() -> {
            try {
                BeatSaverMapLookup.MapInfo info = lookup.lookup(key);
                if (info == null) {
                    hook.editOriginal("⚠️ Map `" + key + "` was not found on BeatSaver.").queue();
                    return;
                }
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

                hook.editOriginalEmbeds(eb.build())
                        .setComponents(ActionRow.of(buttons))
                        .queue();
            } catch (Exception e) {
                log.error("song-request failed (key={})", key, e);
                hook.editOriginal("⚠️ Internal error: " + e.getClass().getSimpleName() + " — " + e.getMessage()).queue();
            }
        });
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
