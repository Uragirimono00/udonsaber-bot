package com.udonsaber.bot.urasaber.db;

import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * UraSaber 전용 SQLite 저장소. UdonSaber와 완전 분리된 별도 DB 파일.
 *
 * 테이블 prefix는 {@code ura_}. 모두 cross-instance leaderboard용.
 *
 * <pre>
 *   ura_player   : 플레이어 (VRChat displayName 기준)
 *   ura_song     : 곡 메타 (BeatSaver mapId가 PK)
 *   ura_chart    : 곡 × characteristic × difficulty 단위 (별 등급 포함)
 *   ura_play     : 매번 제출되는 점수 (history)
 * </pre>
 *
 * Best score는 별도 테이블 없이 {@code SELECT MAX(score)}로 계산. 인덱스 있어서 빠름.
 */
public class UraSaberDatabase implements AutoCloseable {

    private final Connection conn;

    public UraSaberDatabase(Path dbPath) throws SQLException, IOException {
        if (dbPath.getParent() != null) Files.createDirectories(dbPath.getParent());
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dbPath.toAbsolutePath());
        this.conn = ds.getConnection();
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA journal_mode = WAL");
        }
        initSchema();
    }

    private void initSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ura_player (
                    id              INTEGER PRIMARY KEY AUTOINCREMENT,
                    vrchat_nickname TEXT NOT NULL UNIQUE COLLATE NOCASE,
                    discord_user_id TEXT,
                    first_seen_at   INTEGER NOT NULL,
                    last_seen_at    INTEGER NOT NULL
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ura_song (
                    map_id        TEXT PRIMARY KEY,
                    song_name     TEXT NOT NULL,
                    song_subtitle TEXT,
                    song_author   TEXT,
                    mapper        TEXT,
                    bpm           REAL,
                    duration      REAL,
                    cover_url     TEXT,
                    updated_at    INTEGER NOT NULL
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ura_chart (
                    map_id         TEXT NOT NULL,
                    characteristic TEXT NOT NULL,
                    difficulty     INTEGER NOT NULL,
                    stars          REAL,
                    acc_rating     REAL,
                    pass_rating    REAL,
                    tech_rating    REAL,
                    note_count     INTEGER,
                    PRIMARY KEY (map_id, characteristic, difficulty),
                    FOREIGN KEY (map_id) REFERENCES ura_song(map_id) ON DELETE CASCADE
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ura_play (
                    id             INTEGER PRIMARY KEY AUTOINCREMENT,
                    player_id      INTEGER NOT NULL,
                    map_id         TEXT NOT NULL,
                    characteristic TEXT NOT NULL,
                    difficulty     INTEGER NOT NULL,
                    score          INTEGER NOT NULL,
                    accuracy       REAL,
                    combo          INTEGER,
                    max_combo      INTEGER,
                    hit            INTEGER,
                    total          INTEGER,
                    rank_letter    TEXT,
                    full_combo     INTEGER NOT NULL DEFAULT 0,
                    modifiers      TEXT,
                    played_at      INTEGER NOT NULL,
                    submitted_at   INTEGER NOT NULL,
                    source_ip      TEXT,
                    good_cut       INTEGER,
                    bad_cut        INTEGER,
                    miss           INTEGER,
                    note_count     INTEGER,
                    country        TEXT,
                    FOREIGN KEY (player_id) REFERENCES ura_player(id) ON DELETE CASCADE
                )
            """);

            // 이미 옛 스키마로 만든 DB 들은 ALTER TABLE 로 컬럼 추가 (없으면 무시).
            migrateAddColumnIfMissing(st, "ura_play", "good_cut", "INTEGER");
            migrateAddColumnIfMissing(st, "ura_play", "bad_cut",  "INTEGER");
            migrateAddColumnIfMissing(st, "ura_play", "miss",     "INTEGER");
            migrateAddColumnIfMissing(st, "ura_play", "note_count", "INTEGER");
            migrateAddColumnIfMissing(st, "ura_play", "country",  "TEXT");
            migrateAddColumnIfMissing(st, "ura_player", "country", "TEXT");
            migrateAddColumnIfMissing(st, "ura_song", "cover_url", "TEXT");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ura_play_chart ON ura_play(map_id, characteristic, difficulty, score DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ura_play_player ON ura_play(player_id, played_at DESC)");
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ura_play_dedup ON ura_play(player_id, map_id, characteristic, difficulty, score, submitted_at)");

            // Discord 알림 대상 채널 — notify_type 별로 분리 ('score', 'import').
            // 신규 환경은 바로 새 스키마. 옛 (guild_id PK only) 스키마는 아래 migrateNotifyChannelSchema 가 변환.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ura_notify_channel (
                    guild_id    TEXT NOT NULL,
                    notify_type TEXT NOT NULL,
                    channel_id  TEXT NOT NULL,
                    updated_at  INTEGER NOT NULL,
                    PRIMARY KEY (guild_id, notify_type)
                )
            """);
            migrateNotifyChannelSchema(st);

            // ========================================
            // Playlist 기능 (bplist sync + 5 slot)
            // ========================================
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ura_playlist (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    sync_url    TEXT NOT NULL UNIQUE,
                    title       TEXT,
                    author      TEXT,
                    description TEXT,
                    image_b64   TEXT,
                    song_count  INTEGER NOT NULL DEFAULT 0,
                    fetched_at  INTEGER NOT NULL,
                    created_at  INTEGER NOT NULL
                )
            """);
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ura_playlist_song (
                    playlist_id   INTEGER NOT NULL,
                    ord           INTEGER NOT NULL,
                    hash          TEXT,
                    map_id        TEXT,
                    song_name     TEXT,
                    song_author   TEXT,
                    mapper        TEXT,
                    difficulties  TEXT,
                    PRIMARY KEY (playlist_id, ord),
                    FOREIGN KEY (playlist_id) REFERENCES ura_playlist(id) ON DELETE CASCADE
                )
            """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_ura_playlist_song_mapid ON ura_playlist_song(map_id)");
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ura_playlist_slot (
                    slot           INTEGER PRIMARY KEY,
                    playlist_id    INTEGER NOT NULL,
                    set_at         INTEGER NOT NULL,
                    set_by_user_id TEXT,
                    FOREIGN KEY (playlist_id) REFERENCES ura_playlist(id) ON DELETE CASCADE
                )
            """);
            // hash → BSR 매핑 캐시 — BeatSaver API hammering 방지.
            // map_id NULL 은 negative cache (BeatSaver 에 그 hash 곡 없음).
            // song_name/song_author/mapper 도 같이 저장 — .bplist 가 hash 만 있으면 이 캐시로 row 메타 채움.
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS ura_hash_map (
                    hash        TEXT PRIMARY KEY COLLATE NOCASE,
                    map_id      TEXT,
                    song_name   TEXT,
                    song_author TEXT,
                    mapper      TEXT,
                    fetched_at  INTEGER NOT NULL
                )
            """);
            // 기존 환경 마이그레이션 — 컬럼 없으면 추가.
            migrateAddColumnIfMissing(st, "ura_hash_map", "song_name", "TEXT");
            migrateAddColumnIfMissing(st, "ura_hash_map", "song_author", "TEXT");
            migrateAddColumnIfMissing(st, "ura_hash_map", "mapper", "TEXT");
        }
    }

    /**
     * 옛 스키마 (guild_id PRIMARY KEY 만 있는 단일 채널) → 신 스키마 (guild_id, notify_type 복합 PK) 마이그레이션.
     * 옛 row 는 score + import 양쪽으로 복제해서 분리 변경 전까지 현재 동작 그대로 유지.
     * SQLite 는 PK 변경 불가 → CREATE NEW + COPY + DROP + RENAME 패턴.
     */
    private void migrateNotifyChannelSchema(Statement st) throws SQLException {
        String createSql = null;
        try (ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='ura_notify_channel'")) {
            if (rs.next()) createSql = rs.getString(1);
        }
        // 옛 스키마 시그니처: guild_id 가 단독 PRIMARY KEY. notify_type 컬럼 없음.
        if (createSql == null) return;
        if (createSql.contains("notify_type")) return; // 이미 신 스키마

        org.slf4j.LoggerFactory.getLogger(UraSaberDatabase.class).info(
                "Migrating ura_notify_channel to (guild_id, notify_type) schema");

        st.executeUpdate("ALTER TABLE ura_notify_channel RENAME TO ura_notify_channel_old");
        st.executeUpdate("""
            CREATE TABLE ura_notify_channel (
                guild_id    TEXT NOT NULL,
                notify_type TEXT NOT NULL,
                channel_id  TEXT NOT NULL,
                updated_at  INTEGER NOT NULL,
                PRIMARY KEY (guild_id, notify_type)
            )
        """);
        // 옛 row → score + import 양쪽으로 복제 (마이그레이션 직후 현재 동작 그대로).
        st.executeUpdate("""
            INSERT INTO ura_notify_channel (guild_id, notify_type, channel_id, updated_at)
            SELECT guild_id, 'score',  channel_id, updated_at FROM ura_notify_channel_old
        """);
        st.executeUpdate("""
            INSERT INTO ura_notify_channel (guild_id, notify_type, channel_id, updated_at)
            SELECT guild_id, 'import', channel_id, updated_at FROM ura_notify_channel_old
        """);
        st.executeUpdate("DROP TABLE ura_notify_channel_old");
    }

    private static void migrateAddColumnIfMissing(Statement st, String table, String column, String type) {
        try {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException ignored) {
            // 이미 컬럼이 존재하면 SQLite가 에러 던짐 — 무시 OK
        }
    }

    // ========================================
    // Notify channels
    // ========================================

    /** notify_type 상수. 새 타입 추가 시 여기 + Notifier 분기 + Command type choice 셋 다 갱신. */
    public static final String NOTIFY_TYPE_SCORE = "score";
    public static final String NOTIFY_TYPE_IMPORT = "import";
    /** 곡 신청 채널 — 알림 발송용이 아니라 SongRequestCommand 가 BSR 메시지를 감시하는 채널. */
    public static final String NOTIFY_TYPE_REQUEST = "request";

    /** (guild_id, notify_type) 쌍에 채널 등록 / 갱신. */
    public void setNotifyChannel(String guildId, String notifyType, String channelId, long nowEpochSec) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ura_notify_channel (guild_id, notify_type, channel_id, updated_at) VALUES (?, ?, ?, ?)
                ON CONFLICT(guild_id, notify_type) DO UPDATE SET channel_id = excluded.channel_id, updated_at = excluded.updated_at
                """)) {
            ps.setString(1, guildId);
            ps.setString(2, notifyType);
            ps.setString(3, channelId);
            ps.setLong(4, nowEpochSec);
            ps.executeUpdate();
        }
    }

    /** notifyType null 이면 길드의 모든 타입 해제. */
    public void clearNotifyChannel(String guildId, String notifyType) throws SQLException {
        if (notifyType == null) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ura_notify_channel WHERE guild_id = ?")) {
                ps.setString(1, guildId);
                ps.executeUpdate();
            }
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ura_notify_channel WHERE guild_id = ? AND notify_type = ?")) {
            ps.setString(1, guildId);
            ps.setString(2, notifyType);
            ps.executeUpdate();
        }
    }

    /** 해당 notify_type 으로 등록된 모든 길드/채널. */
    public List<NotifyChannel> listNotifyChannels(String notifyType) throws SQLException {
        List<NotifyChannel> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT guild_id, channel_id FROM ura_notify_channel WHERE notify_type = ?")) {
            ps.setString(1, notifyType);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new NotifyChannel(rs.getString(1), notifyType, rs.getString(2)));
            }
        }
        return out;
    }

    /** 길드의 모든 알림 등록 상태 조회 (커맨드 응답용). */
    public List<NotifyChannel> getGuildNotifyChannels(String guildId) throws SQLException {
        List<NotifyChannel> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT notify_type, channel_id FROM ura_notify_channel WHERE guild_id = ? ORDER BY notify_type")) {
            ps.setString(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new NotifyChannel(guildId, rs.getString(1), rs.getString(2)));
            }
        }
        return out;
    }

    public record NotifyChannel(String guildId, String notifyType, String channelId) {}

    // ========================================
    // Players
    // ========================================

    /** Returns the player id, creating a row if none exists for this nickname (case-insensitive). */
    public long upsertPlayer(String nickname, long nowEpochSec) throws SQLException {
        // Try insert
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO ura_player (vrchat_nickname, first_seen_at, last_seen_at) VALUES (?, ?, ?)")) {
            ps.setString(1, nickname);
            ps.setLong(2, nowEpochSec);
            ps.setLong(3, nowEpochSec);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ura_player SET last_seen_at = ? WHERE vrchat_nickname = ? COLLATE NOCASE")) {
            ps.setLong(1, nowEpochSec);
            ps.setString(2, nickname);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM ura_player WHERE vrchat_nickname = ? COLLATE NOCASE")) {
            ps.setString(1, nickname);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("upsertPlayer failed for nickname=" + nickname);
    }

    public Optional<Long> findPlayerId(String nickname) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM ura_player WHERE vrchat_nickname = ? COLLATE NOCASE")) {
            ps.setString(1, nickname);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getLong(1));
            }
        }
        return Optional.empty();
    }

    // ========================================
    // Songs / Charts
    // ========================================

    public void upsertSong(String mapId, String songName, String songSubtitle, String songAuthor,
                           String mapper, Double bpm, Double duration, long nowEpochSec) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ura_song (map_id, song_name, song_subtitle, song_author, mapper, bpm, duration, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(map_id) DO UPDATE SET
                    song_name = excluded.song_name,
                    song_subtitle = excluded.song_subtitle,
                    song_author = excluded.song_author,
                    mapper = excluded.mapper,
                    bpm = excluded.bpm,
                    duration = excluded.duration,
                    updated_at = excluded.updated_at
                """)) {
            ps.setString(1, mapId);
            ps.setString(2, songName);
            if (songSubtitle == null) ps.setNull(3, java.sql.Types.VARCHAR); else ps.setString(3, songSubtitle);
            if (songAuthor == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, songAuthor);
            if (mapper == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, mapper);
            if (bpm == null) ps.setNull(6, java.sql.Types.DOUBLE); else ps.setDouble(6, bpm);
            if (duration == null) ps.setNull(7, java.sql.Types.DOUBLE); else ps.setDouble(7, duration);
            ps.setLong(8, nowEpochSec);
            ps.executeUpdate();
        }
    }

    public void upsertChart(String mapId, String characteristic, int difficulty,
                            Double stars, Double accRating, Double passRating, Double techRating,
                            Integer noteCount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ura_chart (map_id, characteristic, difficulty, stars, acc_rating, pass_rating, tech_rating, note_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(map_id, characteristic, difficulty) DO UPDATE SET
                    stars = excluded.stars,
                    acc_rating = excluded.acc_rating,
                    pass_rating = excluded.pass_rating,
                    tech_rating = excluded.tech_rating,
                    note_count = excluded.note_count
                """)) {
            ps.setString(1, mapId);
            ps.setString(2, characteristic);
            ps.setInt(3, difficulty);
            if (stars == null) ps.setNull(4, java.sql.Types.DOUBLE); else ps.setDouble(4, stars);
            if (accRating == null) ps.setNull(5, java.sql.Types.DOUBLE); else ps.setDouble(5, accRating);
            if (passRating == null) ps.setNull(6, java.sql.Types.DOUBLE); else ps.setDouble(6, passRating);
            if (techRating == null) ps.setNull(7, java.sql.Types.DOUBLE); else ps.setDouble(7, techRating);
            if (noteCount == null) ps.setNull(8, java.sql.Types.INTEGER); else ps.setInt(8, noteCount);
            ps.executeUpdate();
        }
    }

    public Optional<SongRow> getSong(String mapId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT map_id, song_name, song_subtitle, song_author, mapper, bpm, duration FROM ura_song WHERE map_id = ?")) {
            ps.setString(1, mapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(new SongRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), (Double) rs.getObject(6), (Double) rs.getObject(7)));
            }
        }
        return Optional.empty();
    }

    public List<ChartRow> listCharts(String mapId) throws SQLException {
        List<ChartRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT characteristic, difficulty, stars, acc_rating, pass_rating, tech_rating, note_count
                FROM ura_chart WHERE map_id = ?
                ORDER BY characteristic, difficulty
                """)) {
            ps.setString(1, mapId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new ChartRow(mapId,
                        rs.getString(1), rs.getInt(2),
                        (Double) rs.getObject(3), (Double) rs.getObject(4),
                        (Double) rs.getObject(5), (Double) rs.getObject(6),
                        (Integer) rs.getObject(7)));
            }
        }
        return out;
    }

    /** 캐시된 cover URL (cdn.beatsaver.com/...jpg). 없으면 null. */
    public String getSongCoverUrl(String mapId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT cover_url FROM ura_song WHERE map_id = ?")) {
            ps.setString(1, mapId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    /** ura_song.cover_url 만 갱신 (다른 메타 보존). 행이 없으면 아무 일도 안 함. */
    public void updateSongCover(String mapId, String coverUrl) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ura_song SET cover_url = ? WHERE map_id = ?")) {
            if (coverUrl == null) ps.setNull(1, java.sql.Types.VARCHAR); else ps.setString(1, coverUrl);
            ps.setString(2, mapId);
            ps.executeUpdate();
        }
    }

    /** (map, characteristic, difficulty) 의 note_count. 없으면 null. */
    public Integer getChartNoteCount(String mapId, String characteristic, int difficulty) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT note_count FROM ura_chart WHERE map_id = ? AND characteristic = ? AND difficulty = ?")) {
            ps.setString(1, mapId);
            ps.setString(2, characteristic);
            ps.setInt(3, difficulty);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return (Integer) rs.getObject(1);
            }
        }
        return null;
    }

    /** ura_chart.note_count 만 upsert (stars/rating 은 보존). */
    public void updateChartNoteCount(String mapId, String characteristic, int difficulty,
                                     Integer noteCount) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ura_chart (map_id, characteristic, difficulty, note_count)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(map_id, characteristic, difficulty) DO UPDATE SET
                    note_count = excluded.note_count
                """)) {
            ps.setString(1, mapId);
            ps.setString(2, characteristic);
            ps.setInt(3, difficulty);
            if (noteCount == null) ps.setNull(4, java.sql.Types.INTEGER); else ps.setInt(4, noteCount);
            ps.executeUpdate();
        }
    }

    public List<SongRow> listSongs(int limit, int offset) throws SQLException {
        List<SongRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT map_id, song_name, song_subtitle, song_author, mapper, bpm, duration
                FROM ura_song ORDER BY song_name COLLATE NOCASE
                LIMIT ? OFFSET ?
                """)) {
            ps.setInt(1, limit);
            ps.setInt(2, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new SongRow(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), (Double) rs.getObject(6), (Double) rs.getObject(7)));
            }
        }
        return out;
    }

    public int countSongs() throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ura_song")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    /**
     * 시간 윈도우 안의 plays 를 map_id 단위로 집계해서 가장 많이 플레이된 top N 곡 반환.
     * 호출자가 BSR 패턴 (hex 4~12자) 필터링을 추가로 적용.
     * ura_song 에 메타가 없는 곡은 song_name 등이 null 로 반환됨.
     */
    public List<PopularSongRow> getPopularMaps(long sinceEpochSec, int fetchLimit) throws SQLException {
        List<PopularSongRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT pl.map_id, COUNT(*) AS plays,
                       s.song_name, s.song_subtitle, s.song_author, s.mapper, s.bpm, s.duration
                FROM ura_play pl
                LEFT JOIN ura_song s ON s.map_id = pl.map_id
                WHERE pl.played_at >= ?
                GROUP BY pl.map_id
                ORDER BY plays DESC, pl.map_id
                LIMIT ?
                """)) {
            ps.setLong(1, sinceEpochSec);
            ps.setInt(2, fetchLimit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PopularSongRow(
                            rs.getString(1), rs.getInt(2),
                            rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                            (Double) rs.getObject(7), (Double) rs.getObject(8)));
                }
            }
        }
        return out;
    }

    // ========================================
    // Plays
    // ========================================

    /**
     * 같은 player/chart/score 가 지난 N초 안에 들어왔는지 — 멱등성 보장 (Udon이 재전송해도 안전).
     */
    public boolean isDuplicateRecent(long playerId, String mapId, String characteristic, int difficulty,
                                     int score, long sinceEpochSec) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1 FROM ura_play
                WHERE player_id = ? AND map_id = ? AND characteristic = ? AND difficulty = ?
                  AND score = ? AND submitted_at >= ?
                LIMIT 1
                """)) {
            ps.setLong(1, playerId);
            ps.setString(2, mapId);
            ps.setString(3, characteristic);
            ps.setInt(4, difficulty);
            ps.setInt(5, score);
            ps.setLong(6, sinceEpochSec);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public long insertPlay(long playerId, String mapId, String characteristic, int difficulty,
                           int score, Double accuracy, Integer combo, Integer maxCombo,
                           Integer hit, Integer total, String rankLetter, boolean fullCombo,
                           String modifiers, long playedAt, long submittedAt, String sourceIp,
                           Integer goodCut, Integer badCut, Integer miss, Integer noteCount,
                           String country) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ura_play
                    (player_id, map_id, characteristic, difficulty, score, accuracy, combo, max_combo,
                     hit, total, rank_letter, full_combo, modifiers, played_at, submitted_at, source_ip,
                     good_cut, bad_cut, miss, note_count, country)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, playerId);
            ps.setString(2, mapId);
            ps.setString(3, characteristic);
            ps.setInt(4, difficulty);
            ps.setInt(5, score);
            if (accuracy == null) ps.setNull(6, java.sql.Types.DOUBLE); else ps.setDouble(6, accuracy);
            if (combo == null) ps.setNull(7, java.sql.Types.INTEGER); else ps.setInt(7, combo);
            if (maxCombo == null) ps.setNull(8, java.sql.Types.INTEGER); else ps.setInt(8, maxCombo);
            if (hit == null) ps.setNull(9, java.sql.Types.INTEGER); else ps.setInt(9, hit);
            if (total == null) ps.setNull(10, java.sql.Types.INTEGER); else ps.setInt(10, total);
            if (rankLetter == null) ps.setNull(11, java.sql.Types.VARCHAR); else ps.setString(11, rankLetter);
            ps.setInt(12, fullCombo ? 1 : 0);
            if (modifiers == null) ps.setNull(13, java.sql.Types.VARCHAR); else ps.setString(13, modifiers);
            ps.setLong(14, playedAt);
            ps.setLong(15, submittedAt);
            if (sourceIp == null) ps.setNull(16, java.sql.Types.VARCHAR); else ps.setString(16, sourceIp);
            if (goodCut == null) ps.setNull(17, java.sql.Types.INTEGER); else ps.setInt(17, goodCut);
            if (badCut == null)  ps.setNull(18, java.sql.Types.INTEGER); else ps.setInt(18, badCut);
            if (miss == null)    ps.setNull(19, java.sql.Types.INTEGER); else ps.setInt(19, miss);
            if (noteCount == null) ps.setNull(20, java.sql.Types.INTEGER); else ps.setInt(20, noteCount);
            if (country == null) ps.setNull(21, java.sql.Types.VARCHAR); else ps.setString(21, country);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        return -1;
    }

    /** 플레이어의 country 코드 업데이트 (geo-lookup 결과 캐싱). */
    public void updatePlayerCountry(long playerId, String country) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE ura_player SET country = ? WHERE id = ?")) {
            if (country == null) ps.setNull(1, java.sql.Types.VARCHAR); else ps.setString(1, country);
            ps.setLong(2, playerId);
            ps.executeUpdate();
        }
    }

    public String getPlayerCountry(long playerId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT country FROM ura_player WHERE id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        return null;
    }

    /**
     * 차트 leaderboard. 각 플레이어의 BEST score만 반환 (DENSE_RANK 흉내).
     */
    public List<LeaderboardEntry> getChartLeaderboard(String mapId, String characteristic, int difficulty, int limit) throws SQLException {
        List<LeaderboardEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT p.vrchat_nickname,
                       MAX(pl.score) AS best_score,
                       (SELECT accuracy   FROM ura_play WHERE player_id = p.id AND map_id = ? AND characteristic = ? AND difficulty = ? ORDER BY score DESC, submitted_at DESC LIMIT 1) AS best_acc,
                       (SELECT rank_letter FROM ura_play WHERE player_id = p.id AND map_id = ? AND characteristic = ? AND difficulty = ? ORDER BY score DESC, submitted_at DESC LIMIT 1) AS best_rank,
                       (SELECT full_combo  FROM ura_play WHERE player_id = p.id AND map_id = ? AND characteristic = ? AND difficulty = ? ORDER BY score DESC, submitted_at DESC LIMIT 1) AS best_fc,
                       (SELECT played_at   FROM ura_play WHERE player_id = p.id AND map_id = ? AND characteristic = ? AND difficulty = ? ORDER BY score DESC, submitted_at DESC LIMIT 1) AS best_played_at,
                       COUNT(*) AS play_count
                FROM ura_play pl
                JOIN ura_player p ON p.id = pl.player_id
                WHERE pl.map_id = ? AND pl.characteristic = ? AND pl.difficulty = ?
                GROUP BY p.id
                ORDER BY best_score DESC
                LIMIT ?
                """)) {
            int i = 1;
            for (int k = 0; k < 4; k++) {
                ps.setString(i++, mapId);
                ps.setString(i++, characteristic);
                ps.setInt(i++, difficulty);
            }
            ps.setString(i++, mapId);
            ps.setString(i++, characteristic);
            ps.setInt(i++, difficulty);
            ps.setInt(i, limit);
            try (ResultSet rs = ps.executeQuery()) {
                int rank = 0;
                while (rs.next()) {
                    rank++;
                    out.add(new LeaderboardEntry(
                            rank,
                            rs.getString(1),
                            rs.getInt(2),
                            (Double) rs.getObject(3),
                            rs.getString(4),
                            rs.getInt(5) != 0,
                            rs.getLong(6),
                            rs.getInt(7)));
                }
            }
        }
        return out;
    }

    public List<PlayerPlay> getPlayerRecentPlays(String nickname, int limit) throws SQLException {
        List<PlayerPlay> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT pl.map_id, s.song_name, s.song_author, pl.characteristic, pl.difficulty,
                       pl.score, pl.accuracy, pl.rank_letter, pl.full_combo, pl.played_at
                FROM ura_play pl
                JOIN ura_player p ON p.id = pl.player_id
                LEFT JOIN ura_song s ON s.map_id = pl.map_id
                WHERE p.vrchat_nickname = ? COLLATE NOCASE
                ORDER BY pl.played_at DESC
                LIMIT ?
                """)) {
            ps.setString(1, nickname);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PlayerPlay(
                            rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getInt(5),
                            rs.getInt(6), (Double) rs.getObject(7),
                            rs.getString(8), rs.getInt(9) != 0, rs.getLong(10)));
                }
            }
        }
        return out;
    }

    /** 이 (player, chart) 조합의 best score. 기록 없으면 -1. */
    public int getPlayerBestScore(long playerId, String mapId, String characteristic, int difficulty) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT MAX(score) FROM ura_play
                WHERE player_id = ? AND map_id = ? AND characteristic = ? AND difficulty = ?
                """)) {
            ps.setLong(1, playerId);
            ps.setString(2, mapId);
            ps.setString(3, characteristic);
            ps.setInt(4, difficulty);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Object v = rs.getObject(1);
                    if (v == null) return -1;
                    return ((Number) v).intValue();
                }
            }
        }
        return -1;
    }

    public int countPlayerPlays(String nickname) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM ura_play pl JOIN ura_player p ON p.id = pl.player_id WHERE p.vrchat_nickname = ? COLLATE NOCASE")) {
            ps.setString(1, nickname);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    @Override
    public void close() throws SQLException {
        if (conn != null && !conn.isClosed()) conn.close();
    }

    // ========================================
    // Playlist
    // ========================================

    /** sync_url 기준 in-place upsert. 기존 row 면 메타 + fetched_at 갱신, 곡 리스트는 별도 replacePlaylistSongs 로 교체. */
    public long upsertPlaylistMeta(String syncUrl, String title, String author, String description,
                                   String imageB64, int songCount, long nowEpochSec) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ura_playlist (sync_url, title, author, description, image_b64, song_count, fetched_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(sync_url) DO UPDATE SET
                    title       = excluded.title,
                    author      = excluded.author,
                    description = excluded.description,
                    image_b64   = excluded.image_b64,
                    song_count  = excluded.song_count,
                    fetched_at  = excluded.fetched_at
                """)) {
            ps.setString(1, syncUrl);
            if (title == null) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, title);
            if (author == null) ps.setNull(3, java.sql.Types.VARCHAR); else ps.setString(3, author);
            if (description == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, description);
            if (imageB64 == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, imageB64);
            ps.setInt(6, songCount);
            ps.setLong(7, nowEpochSec);
            ps.setLong(8, nowEpochSec);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM ura_playlist WHERE sync_url = ?")) {
            ps.setString(1, syncUrl);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        throw new SQLException("upsertPlaylistMeta failed for syncUrl=" + syncUrl);
    }

    /** playlist 의 모든 곡 삭제 후 새로 삽입 (트랜잭션). */
    public void replacePlaylistSongs(long playlistId, List<PlaylistSongRow> songs) throws SQLException {
        boolean prevAutoCommit = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            try (PreparedStatement del = conn.prepareStatement("DELETE FROM ura_playlist_song WHERE playlist_id = ?")) {
                del.setLong(1, playlistId);
                del.executeUpdate();
            }
            try (PreparedStatement ins = conn.prepareStatement("""
                    INSERT INTO ura_playlist_song (playlist_id, ord, hash, map_id, song_name, song_author, mapper, difficulties)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                int ord = 0;
                for (PlaylistSongRow s : songs) {
                    ins.setLong(1, playlistId);
                    ins.setInt(2, ord++);
                    if (s.hash() == null) ins.setNull(3, java.sql.Types.VARCHAR); else ins.setString(3, s.hash());
                    if (s.mapId() == null) ins.setNull(4, java.sql.Types.VARCHAR); else ins.setString(4, s.mapId());
                    if (s.songName() == null) ins.setNull(5, java.sql.Types.VARCHAR); else ins.setString(5, s.songName());
                    if (s.songAuthor() == null) ins.setNull(6, java.sql.Types.VARCHAR); else ins.setString(6, s.songAuthor());
                    if (s.mapper() == null) ins.setNull(7, java.sql.Types.VARCHAR); else ins.setString(7, s.mapper());
                    if (s.difficulties() == null) ins.setNull(8, java.sql.Types.VARCHAR); else ins.setString(8, s.difficulties());
                    ins.executeUpdate();
                }
            }
            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(prevAutoCommit);
        }
    }

    public java.util.Optional<PlaylistRow> getPlaylistById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, sync_url, title, author, description, image_b64, song_count, fetched_at, created_at
                FROM ura_playlist WHERE id = ?
                """)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return java.util.Optional.of(readPlaylist(rs));
            }
        }
        return java.util.Optional.empty();
    }

    public java.util.Optional<PlaylistRow> getPlaylistBySyncUrl(String syncUrl) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT id, sync_url, title, author, description, image_b64, song_count, fetched_at, created_at
                FROM ura_playlist WHERE sync_url = ?
                """)) {
            ps.setString(1, syncUrl);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return java.util.Optional.of(readPlaylist(rs));
            }
        }
        return java.util.Optional.empty();
    }

    private static PlaylistRow readPlaylist(ResultSet rs) throws SQLException {
        return new PlaylistRow(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getLong(8), rs.getLong(9));
    }

    /** playlist 곡 page 조회. 0-indexed page, songsPerPage 개수. */
    public List<PlaylistSongRow> getPlaylistSongsPage(long playlistId, int page, int songsPerPage) throws SQLException {
        List<PlaylistSongRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT playlist_id, ord, hash, map_id, song_name, song_author, mapper, difficulties
                FROM ura_playlist_song
                WHERE playlist_id = ?
                ORDER BY ord
                LIMIT ? OFFSET ?
                """)) {
            ps.setLong(1, playlistId);
            ps.setInt(2, songsPerPage);
            ps.setInt(3, page * songsPerPage);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new PlaylistSongRow(
                        rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)));
            }
        }
        return out;
    }

    public void deletePlaylistById(long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ura_playlist WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    // ----- Slot (0..4) -----

    public void setSlot(int slot, long playlistId, long nowEpochSec, String setByUserId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ura_playlist_slot (slot, playlist_id, set_at, set_by_user_id) VALUES (?, ?, ?, ?)
                ON CONFLICT(slot) DO UPDATE SET
                    playlist_id    = excluded.playlist_id,
                    set_at         = excluded.set_at,
                    set_by_user_id = excluded.set_by_user_id
                """)) {
            ps.setInt(1, slot);
            ps.setLong(2, playlistId);
            ps.setLong(3, nowEpochSec);
            if (setByUserId == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, setByUserId);
            ps.executeUpdate();
        }
    }

    public void clearSlot(int slot) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ura_playlist_slot WHERE slot = ?")) {
            ps.setInt(1, slot);
            ps.executeUpdate();
        }
    }

    /** 5 slot 전체 상태 (0..4 순서, 없는 slot 은 null playlistId). */
    public List<SlotInfo> listSlots() throws SQLException {
        List<SlotInfo> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT s.slot, s.playlist_id, p.title, p.author, p.song_count, s.set_at
                FROM ura_playlist_slot s
                LEFT JOIN ura_playlist p ON p.id = s.playlist_id
                ORDER BY s.slot
                """)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new SlotInfo(
                        rs.getInt(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getLong(6)));
            }
        }
        return out;
    }

    public java.util.Optional<SlotInfo> getSlot(int slot) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT s.slot, s.playlist_id, p.title, p.author, p.song_count, s.set_at
                FROM ura_playlist_slot s
                LEFT JOIN ura_playlist p ON p.id = s.playlist_id
                WHERE s.slot = ?
                """)) {
            ps.setInt(1, slot);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return java.util.Optional.of(new SlotInfo(
                        rs.getInt(1), rs.getLong(2),
                        rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getLong(6)));
            }
        }
        return java.util.Optional.empty();
    }

    // ----- Hash → BSR cache (+ song meta) -----

    /** hash 의 BSR 캐시 조회. Optional.empty() = 미캐시, mapId == null = negative cache. */
    public java.util.Optional<HashMapEntry> getHashMap(String hash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT hash, map_id, song_name, song_author, mapper, fetched_at FROM ura_hash_map WHERE hash = ? COLLATE NOCASE")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return java.util.Optional.of(new HashMapEntry(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(6)));
            }
        }
        return java.util.Optional.empty();
    }

    /** mapId == null 도 OK — negative cache. meta 도 nullable. */
    public void putHashMap(String hash, String mapId, String songName, String songAuthor, String mapper,
                           long nowEpochSec) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                INSERT INTO ura_hash_map (hash, map_id, song_name, song_author, mapper, fetched_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(hash) DO UPDATE SET
                    map_id      = excluded.map_id,
                    song_name   = excluded.song_name,
                    song_author = excluded.song_author,
                    mapper      = excluded.mapper,
                    fetched_at  = excluded.fetched_at
                """)) {
            ps.setString(1, hash);
            if (mapId == null) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, mapId);
            if (songName == null) ps.setNull(3, java.sql.Types.VARCHAR); else ps.setString(3, songName);
            if (songAuthor == null) ps.setNull(4, java.sql.Types.VARCHAR); else ps.setString(4, songAuthor);
            if (mapper == null) ps.setNull(5, java.sql.Types.VARCHAR); else ps.setString(5, mapper);
            ps.setLong(6, nowEpochSec);
            ps.executeUpdate();
        }
    }

    // ========================================
    // Records
    // ========================================

    public record SongRow(String mapId, String songName, String songSubtitle, String songAuthor,
                          String mapper, Double bpm, Double duration) {}

    public record ChartRow(String mapId, String characteristic, int difficulty,
                           Double stars, Double accRating, Double passRating, Double techRating,
                           Integer noteCount) {}

    public record LeaderboardEntry(int rank, String nickname, int score, Double accuracy,
                                   String rankLetter, boolean fullCombo, long playedAt, int playCount) {}

    public record PlayerPlay(String mapId, String songName, String songAuthor,
                             String characteristic, int difficulty,
                             int score, Double accuracy, String rankLetter, boolean fullCombo, long playedAt) {}

    /** 인기 곡 row — getPopularMaps 결과. ura_song 에 메타 없으면 song* 필드는 null. */
    public record PopularSongRow(String mapId, int playCount,
                                 String songName, String songSubtitle, String songAuthor, String mapper,
                                 Double bpm, Double duration) {}

    public record PlaylistRow(long id, String syncUrl, String title, String author, String description,
                              String imageB64, int songCount, long fetchedAt, long createdAt) {}

    /** difficulties = JSON string (예: [{"characteristic":"Standard","name":"ExpertPlus"}]). */
    public record PlaylistSongRow(long playlistId, int ord, String hash, String mapId,
                                  String songName, String songAuthor, String mapper, String difficulties) {}

    /** Slot 상태 응답용. playlistId == 0 이면 빈 slot. song_count 도 함께. */
    public record SlotInfo(int slot, long playlistId, String title, String author, int songCount, long setAt) {}

    public record HashMapEntry(String hash, String mapId, String songName, String songAuthor, String mapper, long fetchedAt) {}
}
