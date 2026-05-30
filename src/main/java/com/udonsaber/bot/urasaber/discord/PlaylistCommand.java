package com.udonsaber.bot.urasaber.discord;

import com.udonsaber.bot.urasaber.api.BplistFetcher;
import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <pre>
 *   /urasaber-playlist add     url:&lt;syncUrl&gt; slot:&lt;0..4&gt;   → bplist 다운 + hash→BSR 매핑 + slot 등록
 *   /urasaber-playlist remove  slot:&lt;0..4&gt;                  → slot 해제 (playlist 자체는 유지)
 *   /urasaber-playlist list                                  → 5 slot 상태
 *   /urasaber-playlist refresh slot:&lt;0..4&gt;                  → slot 의 syncUrl 에서 재다운로드
 *   /urasaber-playlist songs   slot:&lt;0..4&gt; [page:N]         → slot 곡 페이지 미리보기
 * </pre>
 * Add/refresh 는 시간이 걸려 deferReply + editOriginal 패턴.
 */
public class PlaylistCommand extends ListenerAdapter {
    private static final Logger log = LoggerFactory.getLogger(PlaylistCommand.class);

    private static final int PROGRESS_UPDATE_MIN_MS = 1500; // Discord rate limit 안에서만 갱신

    private final UraSaberDatabase db;
    private final BplistFetcher fetcher;
    private final Executor exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "playlist-cmd");
        t.setDaemon(true);
        return t;
    });

    public PlaylistCommand(UraSaberDatabase db, BplistFetcher fetcher) {
        this.db = db;
        this.fetcher = fetcher;
    }

    @Override
    public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent ev) {
        if (!"urasaber-playlist".equals(ev.getName())) return;
        String sub = ev.getSubcommandName();
        if (sub == null) {
            ev.reply("Subcommand required: add / remove / list / refresh / songs").setEphemeral(true).queue();
            return;
        }
        switch (sub) {
            case "add"     -> handleAdd(ev);
            case "remove"  -> handleRemove(ev);
            case "list"    -> handleList(ev);
            case "refresh" -> handleRefresh(ev);
            case "songs"   -> handleSongs(ev);
            default        -> ev.reply("Unknown subcommand: " + sub).setEphemeral(true).queue();
        }
    }

    private void handleAdd(SlashCommandInteractionEvent ev) {
        var urlOpt = ev.getOption("url");
        var slotOpt = ev.getOption("slot");
        if (urlOpt == null || slotOpt == null) {
            ev.reply("`url` 과 `slot` 모두 필요합니다.").setEphemeral(true).queue();
            return;
        }
        String url = urlOpt.getAsString();
        int slot = (int) slotOpt.getAsLong();
        if (slot < 0 || slot > 4) {
            ev.reply("`slot` 은 0..4 만 가능합니다.").setEphemeral(true).queue();
            return;
        }
        String userId = ev.getUser().getId();

        ev.deferReply().setEphemeral(false).queue();
        InteractionHook hook = ev.getHook();

        exec.execute(() -> {
            try {
                hook.editOriginal("⏳ Downloading bplist...").queue();
                AtomicLong lastUpdateMs = new AtomicLong(0);
                AtomicInteger lastReported = new AtomicInteger(-1);

                BplistFetcher.Result r = fetcher.fetch(url, (mapped, total) -> {
                    long now = System.currentTimeMillis();
                    // mapped 가 변하거나 10곡 단위로만 갱신 (rate limit 회피)
                    if (now - lastUpdateMs.get() < PROGRESS_UPDATE_MIN_MS) return;
                    if (lastReported.get() == mapped) return;
                    lastReported.set(mapped);
                    lastUpdateMs.set(now);
                    hook.editOriginal(String.format("⏳ Mapping hashes: %d/%d", mapped, total)).queue(
                            ok -> {},
                            err -> log.debug("playlist add progress edit failed: {}", err.toString()));
                });

                if (!r.ok) {
                    hook.editOriginal("⚠️ bplist fetch failed: `" + r.error + "`").queue();
                    return;
                }
                if (r.songs.isEmpty()) {
                    hook.editOriginal("⚠️ bplist 에 곡이 없습니다.").queue();
                    return;
                }

                long nowSec = Instant.now().getEpochSecond();
                long playlistId = db.upsertPlaylistMeta(
                        url, r.title, r.author, r.description, r.imageB64,
                        r.songs.size(), nowSec);
                db.replacePlaylistSongs(playlistId, r.songs);
                db.setSlot(slot, playlistId, nowSec, userId);

                String titleDisp = r.title == null ? "(untitled)" : r.title;
                hook.editOriginal(String.format(
                        "✅ Playlist registered to **slot %d** — **%s** (%s)%n" +
                        "Total: %d songs · BSR mapped: %d · Source: <%s>",
                        slot, titleDisp,
                        r.author == null ? "no author" : r.author,
                        r.totalSongs, r.mappedBsr, url)).queue();
            } catch (Exception e) {
                log.error("playlist add failed", e);
                hook.editOriginal("⚠️ Internal error: " + e.getClass().getSimpleName() + " — " + e.getMessage()).queue();
            }
        });
    }

    private void handleRemove(SlashCommandInteractionEvent ev) {
        var slotOpt = ev.getOption("slot");
        if (slotOpt == null) { ev.reply("`slot` 필요").setEphemeral(true).queue(); return; }
        int slot = (int) slotOpt.getAsLong();
        if (slot < 0 || slot > 4) { ev.reply("`slot` 은 0..4").setEphemeral(true).queue(); return; }
        try {
            db.clearSlot(slot);
            ev.reply("🗑️ Slot " + slot + " 해제됨 (playlist 데이터 자체는 유지).").setEphemeral(true).queue();
        } catch (Exception e) {
            log.error("playlist remove failed", e);
            ev.reply("⚠️ " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleList(SlashCommandInteractionEvent ev) {
        try {
            List<UraSaberDatabase.SlotInfo> slots = db.listSlots();
            UraSaberDatabase.SlotInfo[] arr = new UraSaberDatabase.SlotInfo[5];
            for (UraSaberDatabase.SlotInfo s : slots) {
                if (s.slot() >= 0 && s.slot() < 5) arr[s.slot()] = s;
            }
            StringBuilder b = new StringBuilder("📋 **UraSaber Playlist Slots**\n");
            for (int i = 0; i < 5; i++) {
                UraSaberDatabase.SlotInfo s = arr[i];
                b.append("• **Slot ").append(i).append("**: ");
                if (s == null) {
                    b.append("_(empty)_\n");
                } else {
                    String title = s.title() == null || s.title().isBlank() ? "(untitled)" : s.title();
                    String author = s.author() == null || s.author().isBlank() ? "" : " — " + s.author();
                    b.append("**").append(title).append("**").append(author);
                    b.append(" · ").append(s.songCount()).append(" songs\n");
                }
            }
            ev.reply(b.toString()).setEphemeral(true).queue();
        } catch (Exception e) {
            log.error("playlist list failed", e);
            ev.reply("⚠️ " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleRefresh(SlashCommandInteractionEvent ev) {
        var slotOpt = ev.getOption("slot");
        if (slotOpt == null) { ev.reply("`slot` 필요").setEphemeral(true).queue(); return; }
        int slot = (int) slotOpt.getAsLong();
        if (slot < 0 || slot > 4) { ev.reply("`slot` 은 0..4").setEphemeral(true).queue(); return; }

        try {
            var slotInfo = db.getSlot(slot);
            if (slotInfo.isEmpty()) {
                ev.reply("⚠️ Slot " + slot + " 비어있음. 먼저 `/urasaber-playlist add` 하세요.").setEphemeral(true).queue();
                return;
            }
            var playlist = db.getPlaylistById(slotInfo.get().playlistId());
            if (playlist.isEmpty()) {
                ev.reply("⚠️ Playlist 데이터 누락.").setEphemeral(true).queue();
                return;
            }
            String syncUrl = playlist.get().syncUrl();
            String userId = ev.getUser().getId();

            ev.deferReply().setEphemeral(false).queue();
            InteractionHook hook = ev.getHook();

            exec.execute(() -> {
                try {
                    hook.editOriginal("⏳ Re-downloading slot " + slot + " from <" + syncUrl + ">...").queue();
                    AtomicLong lastUpdateMs = new AtomicLong(0);
                    AtomicInteger lastReported = new AtomicInteger(-1);
                    BplistFetcher.Result r = fetcher.fetch(syncUrl, (mapped, total) -> {
                        long now = System.currentTimeMillis();
                        if (now - lastUpdateMs.get() < PROGRESS_UPDATE_MIN_MS) return;
                        if (lastReported.get() == mapped) return;
                        lastReported.set(mapped);
                        lastUpdateMs.set(now);
                        hook.editOriginal(String.format("⏳ Mapping hashes: %d/%d", mapped, total)).queue(
                                ok -> {},
                                err -> log.debug("refresh progress edit failed: {}", err.toString()));
                    });

                    if (!r.ok) {
                        hook.editOriginal("⚠️ refresh failed: `" + r.error + "`").queue();
                        return;
                    }

                    long nowSec = Instant.now().getEpochSecond();
                    long playlistId = db.upsertPlaylistMeta(
                            syncUrl, r.title, r.author, r.description, r.imageB64,
                            r.songs.size(), nowSec);
                    db.replacePlaylistSongs(playlistId, r.songs);
                    db.setSlot(slot, playlistId, nowSec, userId); // slot 다시 set (set_at 갱신)

                    String titleDisp = r.title == null ? "(untitled)" : r.title;
                    hook.editOriginal(String.format(
                            "✅ Slot %d refreshed — **%s**%n" +
                            "Total: %d songs · BSR mapped: %d",
                            slot, titleDisp, r.totalSongs, r.mappedBsr)).queue();
                } catch (Exception e) {
                    log.error("playlist refresh failed", e);
                    hook.editOriginal("⚠️ Internal error: " + e.getMessage()).queue();
                }
            });
        } catch (Exception e) {
            log.error("playlist refresh dispatch failed", e);
            ev.reply("⚠️ " + e.getMessage()).setEphemeral(true).queue();
        }
    }

    private void handleSongs(SlashCommandInteractionEvent ev) {
        var slotOpt = ev.getOption("slot");
        if (slotOpt == null) { ev.reply("`slot` 필요").setEphemeral(true).queue(); return; }
        int slot = (int) slotOpt.getAsLong();
        if (slot < 0 || slot > 4) { ev.reply("`slot` 은 0..4").setEphemeral(true).queue(); return; }
        var pageOpt = ev.getOption("page");
        int page = pageOpt == null ? 0 : (int) pageOpt.getAsLong();
        if (page < 0) page = 0;

        int perPage = 10;
        try {
            var slotInfo = db.getSlot(slot);
            if (slotInfo.isEmpty()) { ev.reply("Slot " + slot + " 비어있음").setEphemeral(true).queue(); return; }
            var songs = db.getPlaylistSongsPage(slotInfo.get().playlistId(), page, perPage);
            if (songs.isEmpty()) { ev.reply("이 페이지에 곡 없음").setEphemeral(true).queue(); return; }

            int total = slotInfo.get().songCount();
            int totalPages = total == 0 ? 0 : ((total + perPage - 1) / perPage);
            String title = slotInfo.get().title() == null ? "(untitled)" : slotInfo.get().title();

            StringBuilder b = new StringBuilder("🎵 **Slot ").append(slot).append("** · ");
            b.append(title).append("  ·  page ").append(page + 1).append("/").append(totalPages).append("\n");
            int n = page * perPage;
            for (UraSaberDatabase.PlaylistSongRow s : songs) {
                n++;
                b.append('`').append(n).append("`. ");
                String name = s.songName() == null || s.songName().isBlank() ? "?" : s.songName();
                b.append("**").append(name).append("**");
                if (s.songAuthor() != null && !s.songAuthor().isBlank()) b.append(" — ").append(s.songAuthor());
                if (s.mapper() != null && !s.mapper().isBlank()) b.append(" _(map: ").append(s.mapper()).append(")_");
                if (s.mapId() != null && !s.mapId().isBlank()) {
                    b.append("  `!").append(s.mapId()).append("`");
                } else if (s.hash() != null) {
                    b.append("  `(no BSR — hash ").append(s.hash().substring(0, Math.min(8, s.hash().length()))).append(")`");
                }
                b.append('\n');
            }
            ev.reply(b.toString()).setEphemeral(true).queue();
        } catch (Exception e) {
            log.error("playlist songs failed", e);
            ev.reply("⚠️ " + e.getMessage()).setEphemeral(true).queue();
        }
    }
}
