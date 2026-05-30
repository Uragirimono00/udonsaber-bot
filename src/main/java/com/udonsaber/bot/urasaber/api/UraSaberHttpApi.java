package com.udonsaber.bot.urasaber.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import com.udonsaber.bot.urasaber.discord.UraSaberNotifier;
import com.udonsaber.bot.urasaber.geo.IpCountryLookup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/**
 * UraSaber 전용 HTTP API. UdonSaber 봇 본체와 같은 프로세스/포트지만 라우트가 완전 분리됨.
 *
 * <pre>
 *   GET /urasaber/api/leaderboard?mapId=&amp;characteristic=&amp;difficulty=&amp;limit=
 *   GET /urasaber/api/player?nickname=
 *   GET /urasaber/api/song?mapId=
 *   GET /urasaber/api/songs?limit=&amp;offset=
 *   GET /urasaber/api/submit?nick=&amp;map=&amp;char=&amp;diff=&amp;score=&amp;acc=&amp;combo=&amp;maxCombo=&amp;total=&amp;rank=&amp;fc=&amp;ts=
 * </pre>
 *
 * 보안 모델 (HMAC 없음 — 친구 월드 수준):
 *  - ts 가 서버 시간과 ±5분 차이여야 함 (오래된 URL replay 완화)
 *  - 같은 (player, chart, score) 가 10초 안에 또 들어오면 dedup 거부
 *  - IP당 1분 60건 rate limit
 *  - 닉네임 처음 보면 자동 등록 (1st-come-first-served)
 *  - 서버 sanity: score≥0, 0≤acc≤100, diff 0..9
 *  - 부정 데이터는 봇 관리자가 수동 청소
 *
 * 두 가지 mount 방식 지원:
 *  - 독립 서버: {@link #startStandalone(int)} — 별도 포트
 *  - 기존 서버에 mount: {@link #attachTo(HttpServer)} — UdonSaber와 같은 포트
 */
public class UraSaberHttpApi {
    private static final Logger log = LoggerFactory.getLogger(UraSaberHttpApi.class);

    private static final int LEADERBOARD_DEFAULT_LIMIT = 10;
    private static final int LEADERBOARD_MAX_LIMIT = 100;
    private static final int PLAYER_PLAYS_LIMIT = 50;
    private static final int SONGS_DEFAULT_LIMIT = 50;
    private static final int SONGS_MAX_LIMIT = 200;

    /** submit ts 가 서버 시간과 이 만큼 이내여야 함 (초). */
    private static final long TS_WINDOW_SECONDS = 300;

    /** 같은 (player, chart, score) 가 이 초 이내에 들어오면 dedup. */
    private static final long DEDUP_WINDOW_SECONDS = 10;

    /** IP당 분당 허용 submit 건수. */
    private static final int RATE_LIMIT_PER_IP_PER_MIN = 60;

    /** 같은 BSR 콜이 이 초 이내에 다시 들어오면 Discord 알림을 한 번만 보낸다 (스팸 방지). */
    private static final long IMPORT_ANNOUNCE_DEDUP_SECONDS = 60;

    /** /search 응답으로 채워진 per-IP slot 캐시 TTL (밀리초). 1시간.
     *  resolveSlot 성공 시 timestamp 가 sliding 으로 갱신되므로, 활성 유저는 idle 1시간 후에만 evict 됨.
     *  10분이었을 때 유저가 popular/playlist 페이지 보고 한참 후 클릭하면 404 (slot_not_found) 였음. */
    private static final long SEARCH_SLOT_TTL_MS = 60L * 60_000L;

    /** Search slot 최대 — pre-baked URL 배열 크기와 일치해야 함. */
    private static final int SEARCH_SLOT_MAX = 20;

    // 목록 썸네일 아틀라스 — slot 캐시의 커버 N개를 격자 1장 JPEG 으로 합성. 월드/봇 동일 상수.
    private static final int ATLAS_COLS = 5;
    private static final int ATLAS_ROWS = 4;    // COLS*ROWS = SEARCH_SLOT_MAX (20)
    private static final int ATLAS_CELL = 128;  // 셀 한 변 px → 아틀라스 640x512
    private static final long ATLAS_CACHE_TTL_MS = 5L * 60_000L;
    private static final class AtlasCacheEntry {
        final long timestamp; final byte[] jpeg;
        AtlasCacheEntry(long timestamp, byte[] jpeg) { this.timestamp = timestamp; this.jpeg = jpeg; }
    }
    /** key = src|bsr0,bsr1,... → 합성된 JPEG (재합성 방지). */
    private final Map<String, AtlasCacheEntry> atlasCache = new ConcurrentHashMap<>();

    /** Multi-call submit 상태 TTL. 게임 한 판이 ~3분 → 5분 여유 */
    private static final long SUBMIT_STATE_TTL_MS = 5L * 60_000L;

    /** Per-IP "마지막 성공한 import BSR" TTL. SubmitState 보다 길게 — 긴 곡 + 점수 제출까지 커버.
     *  score submit step 0 가 /set-imported-map 발사 → 이 캐시에서 BSR 읽어 currentMap 에 복사.
     *  /import?slot=K&src=X URL 을 재발사하면 그 사이 popular/playlist/search 가 재시드돼서
     *  slot 이 다른 BSR 가리킬 수 있는 race 회피. */
    private static final long LAST_IMPORT_TTL_MS = 60L * 60_000L;

    /** Nick 등록 TTL. 긴 세션 대응 (4시간) — 오래 머무는 사용자도 같은 닉으로 매핑 유지. */
    private static final long NICK_CACHE_TTL_MS = 4L * 60L * 60_000L;

    /** Auto-name multi-call: UTF-16 char 단위 max 길이. Baker 와 동기화. */
    private static final int NAME_MAX_CHARS = 16;
    /** name-byte position 개수 — char 당 high/low 2 byte. */
    private static final int NAME_BYTE_POSITIONS = NAME_MAX_CHARS * 2;
    /** 진행 중인 NameState TTL. claim 흐름이 30분 이상 끌리면 폐기. */
    private static final long NAME_STATE_TTL_MS = 30L * 60_000L;

    /** Score bucket 개수 — 10000 (size=1000) → 0..9,999,000 (7자리, 정밀도 1k) */
    private static final int SCORE_BUCKETS = 10_000;
    private static final int SCORE_BUCKET_SIZE = 1000;
    /** Acc bucket 개수 — 1001 → 0.0..100.0% (정밀도 0.1%) */
    private static final int ACC_BUCKETS = 1001;
    /** maxCombo bucket — 10000 × 1 → 0..9999 (4자리, 정밀도 1) */
    private static final int COMBO_BUCKETS = 10_000;
    private static final int COMBO_BUCKET_SIZE = 1;
    /** FC bucket — 0=no, 1=yes (정밀한 fc 판정은 client 가 책임) */
    private static final int FC_BUCKETS = 2;
    /** Characteristic enum: 0=Standard,1=Lawless,2=OneSaber,3=NoArrows,4=90Degree,5=360Degree,6=Lightshow */
    private static final int CHAR_COUNT = 7;

    private final UraSaberDatabase db;
    private UraSaberNotifier notifier; // optional
    private final IpCountryLookup geo = new IpCountryLookup();
    private HttpServer ownServer; // standalone 모드에서만 사용

    // Rate limit: ip → (윈도우 시작 epoch sec, count)
    private final Map<String, long[]> rateLimits = new ConcurrentHashMap<>();

    /** BSR → 직전 announce epoch sec. {@link #IMPORT_ANNOUNCE_DEDUP_SECONDS} 만큼 같은 BSR 알림 억제. */
    private final Map<String, Long> lastAnnouncedBsr = new ConcurrentHashMap<>();

    /**
     * Per-IP slot 캐시 — 소스(검색/인기/플레이리스트)별 분리. 월드는 pre-baked URL
     * `/import?slot=N&src=search|popular|playlist` 로 호출 → 봇이 caller IP + src 기준으로
     * 해당 캐시에서 BSR 찾아 import. 분리 안 하면 search → popular 순으로 호출 시 popular cache 가
     * searchSlots 를 덮어써서 search row click 이 popular BSR 을 import 하는 버그가 생김.
     *
     * src 누락 (legacy 월드) 시에는 searchSlots 로 fallback.
     */
    private final Map<String, SearchSlotCache> searchSlots = new ConcurrentHashMap<>();
    private final Map<String, SearchSlotCache> popularSlots = new ConcurrentHashMap<>();
    private final Map<String, SearchSlotCache> playlistSlots = new ConcurrentHashMap<>();

    private static final class SearchSlotCache {
        final long timestamp;
        final String[] bsrs; // slot index → bsr (null 이면 빈 슬롯)
        SearchSlotCache(long timestamp, String[] bsrs) {
            this.timestamp = timestamp;
            this.bsrs = bsrs;
        }
    }

    /**
     * Multi-call lossy submit 의 per-IP 누적 상태.
     * 클라이언트가 /score-bucket, /acc-bucket, /diff 를 차례로 호출 → 각 호출이 한 필드 업데이트.
     * 마지막 /commit-score 가 도착하면 전체 상태 + nickCache + searchSlots(=last imported bsr) 로 ura_play 인서트.
     */
    private static final class SubmitState {
        volatile long timestamp;
        volatile int scoreBucket = -1;  // -1 = 미설정
        volatile int accBucket = -1;
        volatile int diff = -1;
        volatile String currentMap = null;   // 마지막으로 import / select-map 한 BSR (live, /import 가 덮어씀)
        // 점수 제출 step 0 (/select-map, /set-imported-map) 에서 lock 되는 BSR. commit 이 이걸 우선 읽음.
        // /import 는 절대 안 건드림 → 제출 진행 중(~1s, 8-step) 새 곡 import 해도 엉뚱한 곡으로 commit 안 됨.
        volatile String submissionMap = null;
        // Phase 2 (additional stats, optional)
        volatile int maxComboBucket = -1;  // maxCombo (1 단위)
        volatile int charIdx = -1;         // 0..6 enum
        volatile int fcBucket = -1;        // 0=no, 1=yes
        SubmitState(long timestamp) { this.timestamp = timestamp; }
    }
    private final Map<String, SubmitState> submitStates = new ConcurrentHashMap<>();

    /** Per-IP 마지막 성공 import 의 BSR — SubmitState 와 별도 lifecycle (LAST_IMPORT_TTL_MS).
     *  /set-imported-map 이 이 값을 읽어 SubmitState.currentMap 에 복사 — slot URL race 회피용. */
    private static final class LastImport {
        final long timestamp;
        final String bsr;
        LastImport(long timestamp, String bsr) { this.timestamp = timestamp; this.bsr = bsr; }
    }
    private final Map<String, LastImport> lastImports = new ConcurrentHashMap<>();

    /** Per-IP 등록된 displayName. /claim-nick 으로 등록. */
    private static final class NickEntry {
        final long timestamp;
        final String displayName;
        NickEntry(long timestamp, String displayName) { this.timestamp = timestamp; this.displayName = displayName; }
    }
    private final Map<String, NickEntry> nickCache = new ConcurrentHashMap<>();

    /**
     * Per-IP 자동 닉 분할 누적 상태. /name-byte 가 각 byte position 채우고,
     * /name-len 이 char 길이를 set, /name-commit 이 모든 byte 를 UTF-16 char 로 복원해 ura_player upsert.
     * bytes[2i] = char[i] high byte, bytes[2i+1] = char[i] low byte. -1 = 아직 안 들어옴.
     */
    private static final class NameState {
        volatile long timestamp;
        final int[] bytes;
        volatile int charLen = -1;
        NameState(long ts, int size) {
            this.timestamp = ts;
            this.bytes = new int[size];
            for (int i = 0; i < size; i++) this.bytes[i] = -1;
        }
    }
    private final Map<String, NameState> nameStates = new ConcurrentHashMap<>();

    /** BeatSaver 곡 가져오기/파싱 캐시. 모든 import 요청이 공유. */
    private final BeatSaverImporter importer = new BeatSaverImporter();

    public UraSaberHttpApi(UraSaberDatabase db) {
        this.db = Objects.requireNonNull(db);
    }

    /** Discord 알림 활성화. null이면 알림 안 보냄. */
    public void setNotifier(UraSaberNotifier notifier) {
        this.notifier = notifier;
    }

    /** UdonSaber HttpApi와 같은 서버에 라우트만 mount. */
    public void attachTo(HttpServer server) {
        server.createContext("/urasaber/api/leaderboard", this::handleLeaderboard);
        server.createContext("/urasaber/api/player",      this::handlePlayer);
        server.createContext("/urasaber/api/song",        this::handleSong);
        server.createContext("/urasaber/api/songs",       this::handleSongs);
        server.createContext("/urasaber/api/submit",      this::handleSubmit);
        // BeatSaver 외부곡 import — 월드가 GET 으로 BSR 코드를 보내면 파싱된 JSON 반환.
        server.createContext("/urasaber/api/import",       this::handleImport);
        // 같은 BSR 의 오디오 (캐시된 .egg/.ogg 바이트). VRChat AVPro 가 직접 재생.
        server.createContext("/urasaber/api/import/audio", this::handleImportAudio);
        // BeatSaver 검색 — 월드가 q/page 보내면 평면 JSON 응답 + per-IP slot 캐시 시드.
        server.createContext("/urasaber/api/search",       this::handleSearch);
        // 인기 import 곡 랭킹 (1d / 7d / 30d 윈도우).
        server.createContext("/urasaber/api/popular",      this::handlePopular);
        // Multi-call lossy 점수 제출. 각각 baked URL pool 에서 호출.
        server.createContext("/urasaber/api/claim-nick",   this::handleClaimNick);
        server.createContext("/urasaber/api/score-bucket", this::handleScoreBucket);
        server.createContext("/urasaber/api/acc-bucket",   this::handleAccBucket);
        server.createContext("/urasaber/api/diff-bucket",  this::handleDiffBucket);
        server.createContext("/urasaber/api/select-map",   this::handleSelectMap);
        // import 곡 점수 제출용 — caller IP 의 lastImports 에 저장된 BSR 을 currentMap 에 복사.
        server.createContext("/urasaber/api/set-imported-map", this::handleSetImportedMap);
        server.createContext("/urasaber/api/maxcombo-bucket", this::handleMaxComboBucket);
        server.createContext("/urasaber/api/char-bucket",  this::handleCharBucket);
        server.createContext("/urasaber/api/fc-bucket",    this::handleFcBucket);
        server.createContext("/urasaber/api/commit-score", this::handleCommitScore);
        // 자동 닉 분할 전송 (VRChat displayName → 서버 nickCache).
        server.createContext("/urasaber/api/name-byte",    this::handleNameByte);
        server.createContext("/urasaber/api/name-len",     this::handleNameLen);
        server.createContext("/urasaber/api/name-commit",  this::handleNameCommit);
        // Playlist 5-slot 시스템 — Discord 봇이 등록한 bplist 의 page 단위 조회.
        server.createContext("/urasaber/api/playlist/slots", this::handlePlaylistSlots);
        server.createContext("/urasaber/api/playlist/slot",  this::handlePlaylistSlot);
        // 목록 썸네일 아틀라스 — per-IP slot 캐시(현재 페이지 커버)를 격자 1장 JPEG 으로.
        server.createContext("/urasaber/api/atlas",          this::handleAtlas);
        log.info("UraSaber routes mounted on existing server at /urasaber/api/*");
    }

    /** 별도 포트로 띄움. */
    public void startStandalone(int port) throws IOException {
        ownServer = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        ownServer.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "urasaber-http");
            t.setDaemon(true);
            return t;
        }));
        attachTo(ownServer);
        ownServer.start();
        log.info("UraSaber HTTP API listening on 0.0.0.0:{}", port);
    }

    public void stop() {
        if (ownServer != null) ownServer.stop(0);
    }

    // ========================================
    // Read handlers
    // ========================================

    private void handleLeaderboard(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            String mapId = nullIfBlank(q.get("mapId"));
            String character = q.getOrDefault("characteristic", "Standard");
            Integer diff = parseIntBoxed(q.get("difficulty"));
            // mapId 없고 src=imported 면 caller IP 의 마지막 import BSR 로 해석.
            // 월드는 런타임 new VRCUrl(string) 이 막혀 import 곡의 동적 mapId 를 URL 에 못 박음 →
            // src=imported 로 baked URL 을 쏘고 봇이 IP 의 lastImports(=import 슬롯에 올라간 곡)로 해석.
            if (mapId == null && "imported".equals(nullIfBlank(q.get("src")))) {
                String ip = extractClientIp(ex);
                LastImport li = lastImports.get(ip);
                long now = System.currentTimeMillis();
                if (li != null && now - li.timestamp <= LAST_IMPORT_TTL_MS) {
                    mapId = li.bsr;
                    lastImports.put(ip, new LastImport(now, li.bsr)); // sliding TTL
                } else if (li != null) {
                    lastImports.remove(ip);
                }
            }
            if (diff == null) { respond(ex, 400, "text/plain", "difficulty is required"); return; }
            // src=imported 인데 아직 import 기록이 없으면(다른 플레이어/만료) 빈 리더보드 반환 — 에러 대신 정상 200.
            if (mapId == null) {
                respond(ex, 200, "application/json; charset=utf-8",
                        "{\"mapId\":null,\"characteristic\":" + jsonString(character)
                        + ",\"difficulty\":" + diff + ",\"song\":null,\"chart\":null,\"entries\":[]}");
                return;
            }
            int limit = clamp(parseInt(q.get("limit"), LEADERBOARD_DEFAULT_LIMIT), 1, LEADERBOARD_MAX_LIMIT);

            var song = db.getSong(mapId).orElse(null);
            var charts = db.listCharts(mapId);
            UraSaberDatabase.ChartRow chart = null;
            for (var c : charts) {
                if (c.characteristic().equalsIgnoreCase(character) && c.difficulty() == diff) { chart = c; break; }
            }
            var entries = db.getChartLeaderboard(mapId, character, diff, limit);

            StringBuilder json = new StringBuilder(512);
            json.append("{\"mapId\":").append(jsonString(mapId));
            json.append(",\"characteristic\":").append(jsonString(character));
            json.append(",\"difficulty\":").append(diff);
            json.append(",\"song\":").append(song == null ? "null" : songJson(song));
            json.append(",\"chart\":").append(chart == null ? "null" : chartJson(chart));
            json.append(",\"entries\":[");
            for (int i = 0; i < entries.size(); i++) {
                if (i > 0) json.append(',');
                json.append(leaderboardEntryJson(entries.get(i)));
            }
            json.append("]}");
            respond(ex, 200, "application/json; charset=utf-8", json.toString());
        } catch (Exception e) {
            log.error("/urasaber/api/leaderboard failed", e);
            respond(ex, 500, "text/plain", "Internal Server Error");
        }
    }

    private void handlePlayer(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String nickname = nullIfBlank(parseQuery(ex.getRequestURI().getRawQuery()).get("nickname"));
            if (nickname == null) { respond(ex, 400, "text/plain", "nickname is required"); return; }

            int totalPlays = db.countPlayerPlays(nickname);
            var plays = db.getPlayerRecentPlays(nickname, PLAYER_PLAYS_LIMIT);

            StringBuilder json = new StringBuilder(512);
            json.append("{\"nickname\":").append(jsonString(nickname));
            json.append(",\"totalPlays\":").append(totalPlays);
            json.append(",\"plays\":[");
            for (int i = 0; i < plays.size(); i++) {
                if (i > 0) json.append(',');
                json.append(playerPlayJson(plays.get(i)));
            }
            json.append("]}");
            respond(ex, 200, "application/json; charset=utf-8", json.toString());
        } catch (Exception e) {
            log.error("/urasaber/api/player failed", e);
            respond(ex, 500, "text/plain", "Internal Server Error");
        }
    }

    private void handleSong(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String mapId = nullIfBlank(parseQuery(ex.getRequestURI().getRawQuery()).get("mapId"));
            if (mapId == null) { respond(ex, 400, "text/plain", "mapId is required"); return; }

            var song = db.getSong(mapId).orElse(null);
            if (song == null) { respond(ex, 404, "application/json; charset=utf-8", "{\"error\":\"song not found\"}"); return; }
            var charts = db.listCharts(mapId);

            StringBuilder json = new StringBuilder(512);
            json.append("{\"song\":").append(songJson(song));
            json.append(",\"charts\":[");
            for (int i = 0; i < charts.size(); i++) {
                if (i > 0) json.append(',');
                json.append(chartJson(charts.get(i)));
            }
            json.append("]}");
            respond(ex, 200, "application/json; charset=utf-8", json.toString());
        } catch (Exception e) {
            log.error("/urasaber/api/song failed", e);
            respond(ex, 500, "text/plain", "Internal Server Error");
        }
    }

    private void handleSongs(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            int limit = clamp(parseInt(q.get("limit"), SONGS_DEFAULT_LIMIT), 1, SONGS_MAX_LIMIT);
            int offset = Math.max(0, parseInt(q.get("offset"), 0));

            int total = db.countSongs();
            var songs = db.listSongs(limit, offset);

            StringBuilder json = new StringBuilder(512);
            json.append("{\"total\":").append(total);
            json.append(",\"limit\":").append(limit);
            json.append(",\"offset\":").append(offset);
            json.append(",\"songs\":[");
            for (int i = 0; i < songs.size(); i++) {
                if (i > 0) json.append(',');
                json.append(songJson(songs.get(i)));
            }
            json.append("]}");
            respond(ex, 200, "application/json; charset=utf-8", json.toString());
        } catch (Exception e) {
            log.error("/urasaber/api/songs failed", e);
            respond(ex, 500, "text/plain", "Internal Server Error");
        }
    }

    // ========================================
    // BeatSaver import (외부 곡 변환)
    // ========================================

    private void handleImport(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }

            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            // 해석 우선순위: 명시 bsr > src=playlist 결정론적(n,page,row) DB 조회 > per-IP slot 캐시(legacy).
            String bsr = resolveBsrFromRequest(ip, qs);
            if (bsr == null) {
                boolean hadLookup = nullIfBlank(qs.get("slot")) != null || nullIfBlank(qs.get("n")) != null;
                respond(ex, hadLookup ? 404 : 400, "application/json; charset=utf-8",
                        hadLookup ? "{\"ok\":false,\"error\":\"slot_not_found\"}" : "{\"ok\":false,\"error\":\"bsr_required\"}");
                return;
            }
            if (!BeatSaverImporter.isValidBsr(bsr)) { respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_bsr\"}"); return; }

            // 호출자가 미디어 플레이어 (AVPro 등) 면 audio 바이트, 이미지 다운로더면 cover 바이트 응답.
            // VRCStringDownloader / 브라우저 면 JSON 메타 응답. 같은 URL 을 세 갈래로 활용 → 월드 측은 URL 1개만 입력받으면 됨.
            if (isMediaPlayerRequest(ex))
            {
                handleImportAudio(ex);
                return;
            }
            if (isImageRequest(ex))
            {
                handleImportCover(ex, bsr);
                return;
            }

            BeatSaverImporter.Result r = importer.importMap(bsr);
            if (r.ok && r.meta != null && notifier != null) {
                maybeAnnounceImport(r.meta);
            }
            // Submit 흐름 시 commit 핸들러가 이 IP 의 "마지막 import 한 BSR" 을 mapId 로 쓸 수 있게 캐시.
            if (r.ok) {
                SubmitState s = getOrCreateSubmitState(ip);
                s.currentMap = bsr;
                // SubmitState 와 별도 — score submit 단계에서 /set-imported-map 이 다시 currentMap 으로 복사.
                // SubmitState TTL (5분) 보다 긴 곡/지연도 커버하기 위해 별도 lifecycle.
                lastImports.put(ip, new LastImport(System.currentTimeMillis(), bsr));
            }
            respond(ex, r.httpStatus, "application/json; charset=utf-8", r.json);
        } catch (Exception e) {
            log.error("/urasaber/api/import failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /**
     * 요청이 미디어 플레이어 (AVPro / Windows Media Foundation / VLC 등) 에서 온 것인지 추정.
     * VRCStringDownloader (Unity UnityWebRequest) 가 미디어로 오인 분류되지 않는 게 핵심.
     *  - Range 헤더 → 미디어 플레이어 부분 요청 시그니처 (가장 신뢰 가능)
     *  - Accept 에 audio/* 또는 video/* 명시적 포함 → 미디어
     *  - User-Agent 의 좁은 키워드 (Unity/libcurl 등 광범위 keyword 는 제외)
     * 모호하면 false → JSON 응답 (safe default; AVPro 가 못 받으면 시그니처 키워드 추가).
     */
    private boolean isMediaPlayerRequest(HttpExchange ex) {
        var headers = ex.getRequestHeaders();
        String range = headers.getFirst("Range");
        if (range != null && !range.isEmpty()) {
            log.info("[import] media-detected via Range: {}", range);
            return true;
        }

        String accept = headers.getFirst("Accept");
        if (accept != null) {
            String a = accept.toLowerCase();
            // 명시적 audio/video accept (단 audio/* 가 명확히 있어야 함)
            if (a.contains("audio/") || a.contains("video/")) {
                log.info("[import] media-detected via Accept: {}", accept);
                return true;
            }
        }

        String ua = headers.getFirst("User-Agent");
        if (ua != null) {
            String u = ua.toLowerCase();
            // 미디어 플레이어 시그니처만 (Unity UnityWebRequest 와 겹치지 않게 libcurl 등 제외).
            if (u.contains("avpro") || u.contains("mfmediaengine") || u.contains("mfplat")
                    || u.contains("nsplayer") || u.contains("vlc") || u.contains("gstreamer")
                    || u.contains("mediaengine") || u.contains("quicktime")
                    || u.contains("windows-media-player"))
            {
                log.info("[import] media-detected via User-Agent: {}", ua);
                return true;
            }
        }
        log.info("[import] non-media request (UA='{}', Accept='{}', Range='{}') → JSON",
                ua, accept, range);
        return false;
    }

    // ========================================
    // BeatSaver 검색 (월드 search UI 가 사용)
    // ========================================

    /**
     * /urasaber/api/search?q=...&page=0 → 검색 결과 평면 JSON.
     * 응답 시 caller IP 의 slot 캐시에 BSR 리스트 시드 → 같은 IP 가 /import?slot=N 으로 호출하면 매핑됨.
     */
    private void handleSearch(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }

            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            String q = nullIfBlank(qs.get("q"));
            if (q == null) { respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"q_required\"}"); return; }

            int page = 0;
            String pageStr = qs.get("page");
            if (pageStr != null) {
                try { page = Integer.parseInt(pageStr); } catch (NumberFormatException e) { /* default 0 */ }
            }

            BeatSaverImporter.SearchResult r = importer.search(q, page);
            if (r.ok) {
                // Per-IP search slot 캐시 시드 — /import?slot=K&src=search 가 이 캐시에서 BSR 조회.
                int n = Math.min(SEARCH_SLOT_MAX, r.bsrList.size());
                String[] arr = new String[n];
                for (int i = 0; i < n; i++) arr[i] = r.bsrList.get(i);
                seedSlotCache(searchSlots, ip, arr);
                log.info("[search] ip={} q='{}' page={} slots seeded={}", ip, q, page, n);
            }
            respond(ex, r.httpStatus, "application/json; charset=utf-8", r.json);
        } catch (Exception e) {
            log.error("/urasaber/api/search failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    // ========================================
    // 인기 import 곡 랭킹
    // ========================================

    /** 인기 랭킹 응답 캐시 — window 키당 60초 TTL. DB hammering 방지. */
    private static final long POPULAR_CACHE_TTL_MS = 60_000L;
    private final Map<String, PopularCache> popularCache = new ConcurrentHashMap<>();

    // ========================================
    // Playlist (5-slot bplist sync)
    // ========================================

    /** /playlist/slots — 5개 slot 메타. 월드의 탭 라벨 채우는 용도. */
    private void handlePlaylistSlots(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            List<UraSaberDatabase.SlotInfo> slots = db.listSlots();
            // 0..4 모든 slot 표시 — 미설정 slot 은 empty placeholder
            UraSaberDatabase.SlotInfo[] arr = new UraSaberDatabase.SlotInfo[5];
            for (UraSaberDatabase.SlotInfo s : slots) {
                if (s.slot() >= 0 && s.slot() < 5) arr[s.slot()] = s;
            }

            StringBuilder b = new StringBuilder(512);
            b.append("{\"ok\":true,\"slots\":[");
            for (int i = 0; i < 5; i++) {
                if (i > 0) b.append(',');
                UraSaberDatabase.SlotInfo s = arr[i];
                if (s == null) {
                    b.append("{\"slot\":").append(i).append(",\"playlistId\":0");
                    b.append(",\"title\":null,\"author\":null,\"songCount\":0}");
                } else {
                    b.append("{\"slot\":").append(s.slot());
                    b.append(",\"playlistId\":").append(s.playlistId());
                    b.append(",\"title\":").append(jsonString(s.title()));
                    b.append(",\"author\":").append(jsonString(s.author()));
                    b.append(",\"songCount\":").append(s.songCount());
                    b.append("}");
                }
            }
            b.append("]}");
            respond(ex, 200, "application/json; charset=utf-8", b.toString());
        } catch (Exception e) {
            log.error("/urasaber/api/playlist/slots failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /**
     * /playlist/slot?n=0..4&page=0..N&perPage=20 — 해당 slot 의 page 의 곡 목록.
     * 응답 시 popular 와 같이 caller IP 의 slot 캐시에 BSR 시드 → row click 의 /import?slot=K 가 그 BSR import.
     */
    private void handlePlaylistSlot(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            Integer slot = parseIntBoxed(qs.get("n"));
            if (slot == null || slot < 0 || slot >= 5) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_slot\"}"); return;
            }
            int page = Math.max(0, parseInt(qs.get("page"), 0));
            int perPage = clamp(parseInt(qs.get("perPage"), 20), 1, SEARCH_SLOT_MAX);

            var slotOpt = db.getSlot(slot);
            if (slotOpt.isEmpty()) {
                respond(ex, 200, "application/json; charset=utf-8",
                        "{\"ok\":false,\"error\":\"slot_empty\",\"slot\":" + slot + "}");
                return;
            }
            UraSaberDatabase.SlotInfo s = slotOpt.get();
            List<UraSaberDatabase.PlaylistSongRow> songs = db.getPlaylistSongsPage(s.playlistId(), page, perPage);
            int totalPages = s.songCount() == 0 ? 0 : ((s.songCount() + perPage - 1) / perPage);

            // DB difficulties 가 비어있을 수 있어(난이도 없이 동기화된 bplist) BeatSaver 에서 난이도(+커버) 배치 해석.
            // 캐시되며 atlas 커버도 pre-warm 됨.
            java.util.ArrayList<String> pageBsrs = new java.util.ArrayList<>();
            for (UraSaberDatabase.PlaylistSongRow ps : songs) if (ps.mapId() != null && !ps.mapId().isBlank()) pageBsrs.add(ps.mapId());
            importer.resolveCoverUrlsBatch(pageBsrs);

            // BSR slot 시드 — popular 와 동일. 매핑 실패 곡 (mapId == null) 은 slot 에 null 들어감.
            String[] bsrs = new String[SEARCH_SLOT_MAX];
            StringBuilder b = new StringBuilder(4 * 1024);
            b.append("{\"ok\":true");
            b.append(",\"slot\":").append(slot);
            b.append(",\"playlistId\":").append(s.playlistId());
            b.append(",\"title\":").append(jsonString(s.title()));
            b.append(",\"author\":").append(jsonString(s.author()));
            b.append(",\"songCount\":").append(s.songCount());
            b.append(",\"page\":").append(page);
            b.append(",\"perPage\":").append(perPage);
            b.append(",\"totalPages\":").append(totalPages);
            int count = 0;
            b.append(",\"results\":[");
            for (UraSaberDatabase.PlaylistSongRow song : songs) {
                if (count >= SEARCH_SLOT_MAX) break;
                if (count > 0) b.append(',');
                bsrs[count] = song.mapId(); // null OK — UI 에서 빌트인 매칭 실패 + import 도 불가 표시
                b.append("{");
                b.append("\"bsr\":").append(jsonString(song.mapId() == null ? "" : song.mapId()));
                b.append(",\"hash\":").append(jsonString(song.hash() == null ? "" : song.hash()));
                b.append(",\"name\":").append(jsonString(song.songName() == null ? "" : song.songName()));
                b.append(",\"subtitle\":\"\"");
                b.append(",\"author\":").append(jsonString(song.songAuthor() == null ? "" : song.songAuthor()));
                b.append(",\"mapper\":").append(jsonString(song.mapper() == null ? "" : song.mapper()));
                String dbDiffs = BeatSaverImporter.diffsJsonToIndexArray(song.difficulties());
                b.append(",\"diffs\":").append("[]".equals(dbDiffs) ? importer.getDiffsIndexArray(song.mapId()) : dbDiffs);
                b.append(",\"ranked\":false,\"blRanked\":false");
                b.append("}");
                count++;
            }
            b.append("]");
            b.append(",\"count\":").append(count);
            b.append("}");

            seedSlotCache(playlistSlots, ip, bsrs);
            log.info("[playlist/slot] ip={} slot={} page={} count={}", ip, slot, page, count);
            respond(ex, 200, "application/json; charset=utf-8", b.toString());
        } catch (Exception e) {
            log.error("/urasaber/api/playlist/slot failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /** popular/search/playlist 공통 slot 시드 헬퍼. bsrs 의 인덱스 = slot 인덱스. /import?slot=K&src=X 가 caller IP 의 BSR 찾음. */
    private void seedSlotCache(Map<String, SearchSlotCache> cache, String ip, String[] bsrs) {
        if (bsrs == null) return;
        String[] slots = new String[SEARCH_SLOT_MAX];
        int n = Math.min(SEARCH_SLOT_MAX, bsrs.length);
        for (int i = 0; i < n; i++) slots[i] = bsrs[i];
        cache.put(ip, new SearchSlotCache(System.currentTimeMillis(), slots));
    }

    private static final class PopularCache {
        final long timestamp;
        final String json;
        final String[] bsrs; // slot 시드용. 캐시 hit 시 매번 다시 시드.
        PopularCache(long timestamp, String json, String[] bsrs) {
            this.timestamp = timestamp; this.json = json; this.bsrs = bsrs;
        }
    }

    /**
     * /urasaber/api/popular?window=d|w|m&limit=20 → 최근 N일간 가장 많이 플레이된 BSR import 곡 top N.
     * 응답 JSON 은 /search 와 동일 구조 + plays 필드 추가.
     */
    private void handlePopular(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }

            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            String window = qs.getOrDefault("window", "d");
            int windowDays;
            switch (window) {
                case "d": windowDays = 1; break;
                case "w": windowDays = 7; break;
                case "m": windowDays = 30; break;
                default:
                    respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_window\"}");
                    return;
            }
            int limit = 20;
            String limitStr = qs.get("limit");
            if (limitStr != null) {
                try { limit = Math.max(1, Math.min(50, Integer.parseInt(limitStr))); } catch (NumberFormatException e) { /* default */ }
            }

            // 캐시 hit
            String cacheKey = window + "|" + limit;
            PopularCache cached = popularCache.get(cacheKey);
            long now = System.currentTimeMillis();
            if (cached != null && now - cached.timestamp < POPULAR_CACHE_TTL_MS) {
                // 캐시 응답이라도 slot 시드는 매번 — popular row click 후 /import?slot=K&src=popular 가 이 IP 캐시에서 BSR 찾음.
                seedSlotCache(popularSlots, ip, cached.bsrs);
                respond(ex, 200, "application/json; charset=utf-8", cached.json);
                return;
            }

            long sinceEpochSec = (now / 1000L) - ((long) windowDays * 86400L);
            // BSR 패턴 (hex 1~12) 필터링을 적용하려면 raw 결과를 더 많이 가져와야 함.
            int fetchLimit = Math.max(50, limit * 4);
            List<UraSaberDatabase.PopularSongRow> rows = db.getPopularMaps(sinceEpochSec, fetchLimit);

            // popular BSR 들을 slot 시드용 배열에 동시에 모음 — popular row click → /import?slot=K 가 이걸로 BSR 찾음.
            String[] popularBsrs = new String[SEARCH_SLOT_MAX];
            StringBuilder b = new StringBuilder(8 * 1024);
            b.append("{\"ok\":true");
            b.append(",\"window\":\"").append(window).append("\"");
            b.append(",\"days\":").append(windowDays);
            int count = 0;
            b.append(",\"results\":[");
            for (UraSaberDatabase.PopularSongRow row : rows) {
                if (count >= limit) break;
                if (row.mapId() == null) continue;
                if (!BeatSaverImporter.isValidBsr(row.mapId())) continue;  // BSR import 만
                if (count > 0) b.append(',');
                if (count < SEARCH_SLOT_MAX) popularBsrs[count] = row.mapId();
                b.append("{");
                b.append("\"bsr\":").append(jsonString(row.mapId()));
                b.append(",\"plays\":").append(row.playCount());
                b.append(",\"name\":").append(jsonString(row.songName() == null ? "" : row.songName()));
                b.append(",\"subtitle\":").append(jsonString(row.songSubtitle() == null ? "" : row.songSubtitle()));
                b.append(",\"author\":").append(jsonString(row.songAuthor() == null ? "" : row.songAuthor()));
                b.append(",\"mapper\":").append(jsonString(row.mapper() == null ? "" : row.mapper()));
                b.append(",\"bpm\":").append(row.bpm() == null ? "0" : String.format(java.util.Locale.ROOT, "%.1f", row.bpm()));
                b.append(",\"duration\":").append(row.duration() == null ? "0" : String.format(java.util.Locale.ROOT, "%.1f", row.duration()));
                b.append(",\"diffs\":[]");          // 메타 없으면 빈 배열
                b.append(",\"ranked\":false");
                b.append(",\"blRanked\":false");
                b.append("}");
                count++;
            }
            b.append("]");
            b.append(",\"count\":").append(count);
            b.append("}");

            String responseJson = b.toString();
            popularCache.put(cacheKey, new PopularCache(now, responseJson, popularBsrs));
            // Per-IP popular slot 캐시 시드 — /import?slot=K&src=popular 가 이 캐시에서 BSR 조회.
            seedSlotCache(popularSlots, ip, popularBsrs);
            log.info("[popular] ip={} window={} count={} slots seeded={}", ip, window, count, Math.min(count, SEARCH_SLOT_MAX));
            respond(ex, 200, "application/json; charset=utf-8", responseJson);
        } catch (Exception e) {
            log.error("/urasaber/api/popular failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /**
     * /submit?d=BASE64 형식 디코딩. URL-safe base64 (- → +, _ → /) 복원 + 패딩 추가 후 디코드.
     * payload = "nick|map|char|diff|score|acc|combo|maxCombo|good|bad|miss|total|noteCount|fc|rank|ts"
     * 16개 필드 미만이면 null 반환 (잘못된 형식). 각 필드를 q map 으로 변환.
     */
    private Map<String, String> unpackBase64Payload(String dataParam) {
        try {
            // URL-safe base64 → standard base64
            String b64 = dataParam.replace('-', '+').replace('_', '/');
            // 패딩 복원
            int pad = (4 - (b64.length() % 4)) % 4;
            if (pad > 0) b64 += "=".repeat(pad);
            byte[] decoded = java.util.Base64.getDecoder().decode(b64);
            String payload = new String(decoded, StandardCharsets.UTF_8);
            String[] f = payload.split("\\|", -1); // -1 = empty trailing fields 유지
            if (f.length < 16) {
                log.warn("[submit] bad payload field count: {} (expected 16)", f.length);
                return null;
            }
            Map<String, String> m = new HashMap<>();
            m.put("nick",      f[0]);
            m.put("map",       f[1]);
            m.put("char",      f[2]);
            m.put("diff",      f[3]);
            m.put("score",     f[4]);
            m.put("acc",       f[5]);
            m.put("combo",     f[6]);
            m.put("maxCombo",  f[7]);
            m.put("good",      f[8]);
            m.put("bad",       f[9]);
            m.put("miss",      f[10]);
            m.put("total",     f[11]);
            m.put("noteCount", f[12]);
            m.put("fc",        f[13]);
            m.put("rank",      f[14]);
            m.put("ts",        f[15]);
            return m;
        } catch (Exception e) {
            log.warn("[submit] base64 unpack failed: {}", e.toString());
            return null;
        }
    }

    // ========================================
    // Multi-call lossy 점수 제출
    // ========================================

    /** 호출자 IP 의 SubmitState 를 가져오거나 새로 생성 (TTL 만료된 건 갈아엎음). */
    private SubmitState getOrCreateSubmitState(String ip) {
        long now = System.currentTimeMillis();
        SubmitState s = submitStates.get(ip);
        if (s == null || now - s.timestamp > SUBMIT_STATE_TTL_MS) {
            s = new SubmitState(now);
            submitStates.put(ip, s);
        }
        s.timestamp = now;
        return s;
    }

    /** /urasaber/api/claim-nick?nick=DISPLAY_NAME — VRChat displayName 을 IP 에 매핑. */
    private void handleClaimNick(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            String nick = nullIfBlank(qs.get("nick"));
            if (nick == null) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"nick_required\"}"); return;
            }
            // 닉 길이 제한 (안전망)
            if (nick.length() > 64) nick = nick.substring(0, 64);
            nickCache.put(ip, new NickEntry(System.currentTimeMillis(), nick));
            log.info("[claim-nick] ip={} nick='{}'", ip, nick);
            respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        } catch (Exception e) {
            log.error("/urasaber/api/claim-nick failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    // ========================================
    // 자동 닉 분할 전송 (multi-call)
    // ========================================

    /** caller IP 의 NameState 를 가져오거나 새로 만들기. TTL 만료된 건 갈아엎음. */
    private NameState getOrCreateNameState(String ip) {
        long now = System.currentTimeMillis();
        NameState s = nameStates.get(ip);
        if (s == null || now - s.timestamp > NAME_STATE_TTL_MS) {
            s = new NameState(now, NAME_BYTE_POSITIONS);
            nameStates.put(ip, s);
        }
        s.timestamp = now;
        return s;
    }

    /** /urasaber/api/name-byte?p=POS&b=BYTE — UTF-16 char 의 high/low byte 누적. */
    private void handleNameByte(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            Integer p = parseIntBoxed(qs.get("p"));
            Integer b = parseIntBoxed(qs.get("b"));
            if (p == null || p < 0 || p >= NAME_BYTE_POSITIONS) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_p\"}"); return;
            }
            if (b == null || b < 0 || b > 255) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_b\"}"); return;
            }
            NameState s = getOrCreateNameState(ip);
            s.bytes[p] = b;
            respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        } catch (Exception e) {
            log.error("/urasaber/api/name-byte failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /** /urasaber/api/name-len?n=N — UTF-16 char 길이 set (0..NAME_MAX_CHARS). */
    private void handleNameLen(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            Integer n = parseIntBoxed(qs.get("n"));
            if (n == null || n < 0 || n > NAME_MAX_CHARS) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_n\"}"); return;
            }
            NameState s = getOrCreateNameState(ip);
            s.charLen = n;
            respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        } catch (Exception e) {
            log.error("/urasaber/api/name-len failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /**
     * /urasaber/api/name-commit — 누적된 byte 를 UTF-16 char 로 복원해 displayName 추출.
     * ura_player upsert + nickCache 갱신 (commit-score 에서 자동으로 닉으로 인서트되게).
     */
    private void handleNameCommit(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            NameState s = nameStates.get(ip);
            if (s == null || System.currentTimeMillis() - s.timestamp > NAME_STATE_TTL_MS) {
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"no_state\"}"); return;
            }
            int len = s.charLen;
            if (len < 1 || len > NAME_MAX_CHARS) {
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_len\"}"); return;
            }

            // char[i] = (high << 8) | low. 미전송 byte 는 0 으로 처리 (sentinel -1 → 0).
            char[] chars = new char[len];
            for (int i = 0; i < len; i++) {
                int hi = s.bytes[2 * i];
                int lo = s.bytes[2 * i + 1];
                if (hi < 0) hi = 0;
                if (lo < 0) lo = 0;
                chars[i] = (char) ((hi << 8) | lo);
            }

            // control char (0x00-0x1F, 0x7F) 제거 — VRChat displayName 은 보통 printable.
            StringBuilder cleaned = new StringBuilder(len);
            for (char c : chars) {
                if (c < 0x20 || c == 0x7F) continue;
                cleaned.append(c);
            }
            String name = cleaned.toString().trim();
            if (name.isBlank()) {
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"empty\"}"); return;
            }
            if (name.length() > 64) name = name.substring(0, 64);

            long nowSec = Instant.now().getEpochSecond();
            long playerId = db.upsertPlayer(name, nowSec);
            nickCache.put(ip, new NickEntry(System.currentTimeMillis(), name));
            nameStates.remove(ip);
            log.info("[name-commit] ip={} name='{}' playerId={}", ip, name, playerId);
            respond(ex, 200, "application/json; charset=utf-8",
                    "{\"ok\":true,\"playerId\":" + playerId + ",\"name\":" + jsonString(name) + "}");
        } catch (Exception e) {
            log.error("/urasaber/api/name-commit failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    // ========================================
    // Multi-call lossy 점수 제출 (bucket 핸들러)
    // ========================================

    /** /urasaber/api/score-bucket?sb=N — 점수 bucket 누적. N * (1_000_000 / SCORE_BUCKETS) 으로 환산. */
    private void handleScoreBucket(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            Integer sb = parseIntBoxed(qs.get("sb"));
            if (sb == null || sb < 0 || sb >= SCORE_BUCKETS) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_sb\"}"); return;
            }
            SubmitState s = getOrCreateSubmitState(ip);
            s.scoreBucket = sb;
            respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        } catch (Exception e) {
            log.error("/urasaber/api/score-bucket failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /** /urasaber/api/acc-bucket?ab=N — 정확도 bucket 누적. N % (0..100). */
    private void handleAccBucket(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            Integer ab = parseIntBoxed(qs.get("ab"));
            if (ab == null || ab < 0 || ab >= ACC_BUCKETS) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_ab\"}"); return;
            }
            SubmitState s = getOrCreateSubmitState(ip);
            s.accBucket = ab;
            respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        } catch (Exception e) {
            log.error("/urasaber/api/acc-bucket failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /**
     * /urasaber/api/select-map?bsr=X — submit 직전 mapId 명시 set. 빌드된 (built-in) 곡 지원용.
     * BSR import 곡은 이미 /import?bsr=X 가 currentMap 을 set 하니까 이 호출 불필요지만, 양쪽 다 호환됨.
     */
    private void handleSelectMap(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            String bsr = nullIfBlank(qs.get("bsr"));
            if (bsr == null || !BeatSaverImporter.isValidBsr(bsr)) {
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_bsr\"}"); return;
            }
            SubmitState s = getOrCreateSubmitState(ip);
            s.currentMap = bsr;
            s.submissionMap = bsr;  // 제출 step 0 — 이 곡으로 lock (commit 까지 /import 가 못 바꿈)
            log.info("[select-map] ip={} bsr={}", ip, bsr);
            respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        } catch (Exception e) {
            log.error("/urasaber/api/select-map failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /**
     * /urasaber/api/set-imported-map — caller IP 의 lastImports BSR 을 currentMap 에 복사.
     * score submit step 0 가 import 곡일 때 발사. /import?slot=K&src=X 를 재발사하면
     * 그 사이 popular/playlist/search 가 재시드돼서 slot 이 다른 BSR 로 resolve 될 수 있는 race 회피.
     * lastImports 가 없거나 TTL 만료면 soft-fail (200 + ok:false) — 월드 흐름은 계속 진행됨.
     */
    private void handleSetImportedMap(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            LastImport li = lastImports.get(ip);
            long now = System.currentTimeMillis();
            if (li == null || now - li.timestamp > LAST_IMPORT_TTL_MS) {
                if (li != null) lastImports.remove(ip);
                log.warn("[set-imported-map] no_import for ip={}", ip);
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"no_import\"}");
                return;
            }
            SubmitState s = getOrCreateSubmitState(ip);
            s.currentMap = li.bsr;
            s.submissionMap = li.bsr;  // 제출 step 0 — 이 곡으로 lock (commit 까지 /import 가 못 바꿈)
            log.info("[set-imported-map] ip={} bsr={}", ip, li.bsr);
            respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true,\"bsr\":" + jsonString(li.bsr) + "}");
        } catch (Exception e) {
            log.error("/urasaber/api/set-imported-map failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /** 공통 bucket 누적 핸들러 — query param 명, 최대 범위, 필드 setter 로 generalize. */
    private void handleBucketCommon(HttpExchange ex, String paramName, int maxValue, int field) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
        String ip = extractClientIp(ex);
        if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

        Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
        Integer v = parseIntBoxed(qs.get(paramName));
        if (v == null || v < 0 || v >= maxValue) {
            respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_" + paramName + "\"}"); return;
        }
        SubmitState s = getOrCreateSubmitState(ip);
        switch (field) {
            case 1: s.maxComboBucket = v; break;
            case 5: s.charIdx = v; break;
            case 6: s.fcBucket = v; break;
        }
        respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
    }

    private void handleMaxComboBucket(HttpExchange ex) throws IOException { try { handleBucketCommon(ex, "mcb", COMBO_BUCKETS, 1); } catch (Exception e) { log.error("/maxcombo-bucket", e); respond(ex, 500, "text/plain", "internal"); } }
    private void handleCharBucket(HttpExchange ex) throws IOException { try { handleBucketCommon(ex, "ch", CHAR_COUNT, 5); } catch (Exception e) { log.error("/char-bucket", e); respond(ex, 500, "text/plain", "internal"); } }
    private void handleFcBucket(HttpExchange ex) throws IOException { try { handleBucketCommon(ex, "fb", FC_BUCKETS, 6); } catch (Exception e) { log.error("/fc-bucket", e); respond(ex, 500, "text/plain", "internal"); } }

    private static final String[] CHARACTERISTICS = { "Standard", "Lawless", "OneSaber", "NoArrows", "90Degree", "360Degree", "Lightshow" };

    /**
     * Beat Saber 기본 게임 등급 (max score 대비 정확도 %). BeatLeader 표시도 동일.
     * 기준: SS≥90, S≥80, A≥65, B≥50, C≥35, D≥20, 나머지 E. (SSS는 기본 게임에 없음)
     */
    private static String rankFromAcc(double acc) {
        if (acc >= 90.0) return "SS";
        if (acc >= 80.0) return "S";
        if (acc >= 65.0) return "A";
        if (acc >= 50.0) return "B";
        if (acc >= 35.0) return "C";
        if (acc >= 20.0) return "D";
        return "E";
    }

    /** /urasaber/api/diff-bucket?d=N — 난이도 (0..9) 누적. */
    private void handleDiffBucket(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            Integer d = parseIntBoxed(qs.get("d"));
            if (d == null || d < 0 || d > 9) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_d\"}"); return;
            }
            SubmitState s = getOrCreateSubmitState(ip);
            s.diff = d;
            respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true}");
        } catch (Exception e) {
            log.error("/urasaber/api/diff-bucket failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /**
     * /urasaber/api/commit-score — 누적 상태로 ura_play 인서트.
     * 필요한 최소 필드: score, acc, diff. map 은 마지막 import 의 BSR 사용 (searchSlots 또는 직전 /import?bsr=).
     * nick 은 nickCache 또는 "Anonymous-IP".
     */
    private void handleCommitScore(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            SubmitState s = submitStates.get(ip);
            if (s == null || System.currentTimeMillis() - s.timestamp > SUBMIT_STATE_TTL_MS) {
                log.warn("[commit-score] no_state for ip={}", ip);
                // 200 으로 반환 — VRChat 이 4xx 응답 본문 안 잡아줘서 U# 에서 에러 확인 못 함. soft-error 는 200+error 패턴 사용.
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"no_state\"}"); return;
            }
            if (s.scoreBucket < 0 || s.accBucket < 0 || s.diff < 0) {
                log.warn("[commit-score] incomplete_state for ip={} scoreBucket={} accBucket={} diff={}",
                        ip, s.scoreBucket, s.accBucket, s.diff);
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"incomplete_state\"}"); return;
            }

            // map id — query 우선, 없으면 SubmitState.currentMap, 없으면 searchSlots[0] (가장 최근에 시드된 결과의 첫 항목, 사용자가 가장 최근 클릭한 항목과 일치 가능성)
            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            String mapId = nullIfBlank(qs.get("map"));
            // submissionMap(step 0 에서 lock, /import 가 안 건드림) 우선 → 제출 중 새 곡 import 해도 엉뚱한 곡 commit 방지.
            if (mapId == null) mapId = s.submissionMap;
            if (mapId == null) mapId = s.currentMap;
            if (mapId == null) {
                SearchSlotCache cache = searchSlots.get(ip);
                if (cache != null && cache.bsrs != null && cache.bsrs.length > 0) {
                    mapId = cache.bsrs[0];
                }
            }
            if (mapId == null) {
                log.warn("[commit-score] no_map for ip={} (s.currentMap=null, searchSlots empty/null). " +
                        "User must do /import?bsr=X first OR include ?map=X in commit URL.", ip);
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"no_map\"}"); return;
            }

            // nickname
            NickEntry nickEntry = nickCache.get(ip);
            String nick;
            if (nickEntry != null && System.currentTimeMillis() - nickEntry.timestamp < NICK_CACHE_TTL_MS) {
                nick = nickEntry.displayName;
            } else {
                nick = "Anon-" + ip.replace('.', '_').replace(':', '_');
                if (nick.length() > 32) nick = nick.substring(0, 32);
            }

            int score = s.scoreBucket * SCORE_BUCKET_SIZE;
            double accuracy = s.accBucket / 10.0;  // 0..1000 → 0.0..100.0
            int diff = s.diff;
            String character = (s.charIdx >= 0 && s.charIdx < CHARACTERISTICS.length) ? CHARACTERISTICS[s.charIdx] : "Standard";
            long nowSec = Instant.now().getEpochSecond();

            // Phase 2 — maxCombo 만 받음 (combo/good/bad/miss 제거됨).
            Integer maxCombo = (s.maxComboBucket >= 0) ? s.maxComboBucket * COMBO_BUCKET_SIZE : null;

            // 인서트 — 누락된 필드는 null/default
            long playerId = db.upsertPlayer(nick, nowSec);
            // dedup: 같은 IP 가 짧은 시간에 같은 점수 보내면 무시
            if (db.isDuplicateRecent(playerId, mapId, character, diff, score, nowSec - DEDUP_WINDOW_SECONDS)) {
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true,\"duplicate\":true}"); return;
            }

            // BeatSaver lookup — ura_song 메타 채우기 + note count 가져오기.
            Integer noteCount = null;
            try {
                BeatSaverImporter.Result r = importer.importMap(mapId);
                if (r != null && r.ok && r.meta != null) {
                    // ura_song 에 곡 메타 upsert (이미 있어도 갱신)
                    BeatSaverImporter.SongMeta meta = r.meta;
                    try {
                        db.upsertSong(mapId, meta.songName, null, meta.songAuthor, meta.mapper,
                                meta.bpm > 0 ? meta.bpm : null, meta.duration > 0 ? meta.duration : null, nowSec);
                    } catch (Exception e) {
                        log.warn("[commit-score] upsertSong failed for {}: {}", mapId, e.toString());
                    }
                }
                noteCount = importer.getDiffNoteCount(mapId, diff <= 4 ? diff : 4);
            } catch (Exception e) {
                log.warn("[commit-score] BeatSaver lookup failed for {}: {}", mapId, e.toString());
            }

            // hit/total — combo/good/bad/miss 안 받으니 acc 기반 추정.
            Integer hit = (noteCount != null) ? (int) Math.round(noteCount * (accuracy / 100.0)) : null;
            if (maxCombo == null) maxCombo = noteCount;

            // FC — client 가 명시적으로 전송. 미설정이면 false (안전).
            boolean fc = (s.fcBucket == 1);
            // Rank derive from acc
            String rank = rankFromAcc(accuracy);

            String country = null;
            try {
                country = db.getPlayerCountry(playerId);
                if (country == null || country.isBlank()) {
                    country = geo.lookup(ip);
                    if (country != null) db.updatePlayerCountry(playerId, country);
                }
            } catch (Exception ignored) {}

            // 개인 최고점 갱신 여부 — 알림에 표시.
            boolean personalBest;
            int prevBest = previousBestScore(playerId, mapId, character, diff);
            personalBest = (prevBest < 0) || (score > prevBest);

            // multi-call: score, acc, diff, char, maxCombo, fc 만 받음.
            // combo, good, bad, miss → null (안 받음). hit/total → acc 추정.
            db.insertPlay(playerId, mapId, character, diff, score,
                    accuracy, null, maxCombo, hit, hit, rank, fc, null,
                    nowSec, nowSec, ip, null, null, null, noteCount, country);

            // 상태 클리어 — 동일 sessionId 로 또 commit 들어오는 거 방지
            submitStates.remove(ip);

            log.info("[commit-score] ip={} nick={} map={} diff={} score={} acc={}", ip, nick, mapId, diff, score, accuracy);

            // Discord 알림 (있을 때만). combo/good/bad/miss 제거됨 → null 전달.
            if (notifier != null) {
                pushNotification(nick, mapId, null, null, character, diff,
                        score, accuracy, null, maxCombo, null, null, null, noteCount,
                        fc, rank, personalBest, country, nowSec);
            }

            respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true,\"score\":" + score + "}");
        } catch (Exception e) {
            log.error("/urasaber/api/commit-score failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    /**
     * /import 와 /import/audio 양쪽에서 쓰는 slot → bsr 변환.
     * src 로 popular/playlist/search 캐시 중 하나 선택. src 누락 시 legacy searchSlots fallback
     * (구버전 월드 호환 — src 박힌 URL 베이커 돌리기 전까지).
     */
    /**
     * import 계열 엔드포인트(/import, /import/audio, cover) 공통 bsr 해석.
     * 우선순위: 명시 ?bsr > src=playlist 결정론적(n,page,row) DB 직접 조회 > per-IP slot 캐시(legacy).
     * 결정론적 경로는 per-IP 캐시 desync(엉뚱한 곡 import) 버그를 회피함.
     */
    private String resolveBsrFromRequest(String ip, Map<String, String> qs) throws java.sql.SQLException {
        String bsr = nullIfBlank(qs.get("bsr"));
        if (bsr != null) return bsr;
        String src = nullIfBlank(qs.get("src"));
        if ("playlist".equals(src) && nullIfBlank(qs.get("n")) != null && nullIfBlank(qs.get("row")) != null) {
            int n = parseInt(qs.get("n"), -1);
            int page = Math.max(0, parseInt(qs.get("page"), 0));
            int row = parseInt(qs.get("row"), -1);
            int perPage = clamp(parseInt(qs.get("perPage"), 20), 1, SEARCH_SLOT_MAX);
            return resolvePlaylistRow(n, page, row, perPage);
        }
        if ("popular".equals(src) && nullIfBlank(qs.get("window")) != null && nullIfBlank(qs.get("row")) != null) {
            String window = qs.get("window");
            int row = parseInt(qs.get("row"), -1);
            int limit = clamp(parseInt(qs.get("limit"), 20), 1, SEARCH_SLOT_MAX);
            return resolvePopularRow(window, row, limit);
        }
        String slotStr = nullIfBlank(qs.get("slot"));
        if (slotStr != null) return resolveSlot(ip, slotStr, src);
        return null;
    }

    /**
     * (popular 윈도우 window=d|w|m, row) → bsr. 전역 popularCache(모든 IP 공유, window|limit 키)에서 조회 —
     * cache miss/만료면 DB에서 재계산. per-IP 상태 없이 결정론적 — 월드 표시 행과 항상 일치.
     */
    private String resolvePopularRow(String window, int row, int limit) throws java.sql.SQLException {
        if (row < 0 || row >= SEARCH_SLOT_MAX) return null;
        String[] bsrs = computePopularBsrs(window, limit);
        if (bsrs == null || row >= bsrs.length) return null;
        return nullIfBlank(bsrs[row]);
    }

    /**
     * popular 윈도우의 정렬된 valid-bsr 배열(최대 SEARCH_SLOT_MAX). handlePopular 와 동일한 선택 로직
     * (windowDays, isValidBsr 필터, limit). 전역 popularCache 우선 — handlePopular 가 채운 것 재사용.
     */
    private String[] computePopularBsrs(String window, int limit) throws java.sql.SQLException {
        int windowDays;
        switch (window) {
            case "d": windowDays = 1; break;
            case "w": windowDays = 7; break;
            case "m": windowDays = 30; break;
            default: return null;
        }
        // handlePopular 가 limit=20 으로 캐시함 — 같은 키면 재사용 (cache.bsrs 는 시드용 정렬 배열).
        PopularCache cached = popularCache.get(window + "|" + limit);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.timestamp < POPULAR_CACHE_TTL_MS) return cached.bsrs;

        long sinceEpochSec = (now / 1000L) - ((long) windowDays * 86400L);
        int fetchLimit = Math.max(50, limit * 4);
        List<UraSaberDatabase.PopularSongRow> rows = db.getPopularMaps(sinceEpochSec, fetchLimit);
        String[] bsrs = new String[SEARCH_SLOT_MAX];
        int count = 0;
        for (UraSaberDatabase.PopularSongRow r : rows) {
            if (count >= limit || count >= SEARCH_SLOT_MAX) break;
            if (r.mapId() == null || !BeatSaverImporter.isValidBsr(r.mapId())) continue;
            bsrs[count++] = r.mapId();
        }
        return bsrs;
    }

    /**
     * (playlist 탭 n, page, row) → bsr. ura_playlist_song.position 정렬 + LIMIT/OFFSET DB 직접 조회라
     * per-IP 상태 없이 결정론적 — 월드 표시 행과 항상 일치. row 는 페이지 내 인덱스(0..perPage-1).
     */
    private String resolvePlaylistRow(int n, int page, int row, int perPage) throws java.sql.SQLException {
        if (n < 0 || n >= 5 || row < 0 || row >= perPage) return null;
        var slotOpt = db.getSlot(n);
        if (slotOpt.isEmpty()) return null;
        List<UraSaberDatabase.PlaylistSongRow> songs = db.getPlaylistSongsPage(slotOpt.get().playlistId(), page, perPage);
        if (row >= songs.size()) return null;
        return nullIfBlank(songs.get(row).mapId());
    }

    private String resolveSlot(String ip, String slotStr, String src) {
        int slot;
        try { slot = Integer.parseInt(slotStr); } catch (NumberFormatException e) { return null; }
        if (slot < 0 || slot >= SEARCH_SLOT_MAX) return null;
        Map<String, SearchSlotCache> cache = pickSlotCache(src);
        SearchSlotCache c = cache.get(ip);
        if (c == null) return null;
        long now = System.currentTimeMillis();
        if (now - c.timestamp > SEARCH_SLOT_TTL_MS) {
            cache.remove(ip);
            return null;
        }
        if (slot >= c.bsrs.length) return null;
        String bsr = c.bsrs[slot]; // null 이면 빈 슬롯
        // sliding TTL: 유효 접근마다 timestamp 갱신 → 활성 유저는 idle TTL 안에만 다시 클릭하면 안 끊김.
        if (bsr != null) cache.put(ip, new SearchSlotCache(now, c.bsrs));
        return bsr;
    }

    /** src 쿼리 파라미터 → 해당 slot 캐시. unknown/null 은 legacy searchSlots fallback. */
    private Map<String, SearchSlotCache> pickSlotCache(String src) {
        if ("popular".equals(src)) return popularSlots;
        if ("playlist".equals(src)) return playlistSlots;
        return searchSlots;
    }

    /**
     * 요청이 이미지 다운로더 (VRCImageDownloader 등) 에서 온 것인지 판정.
     * VRCImageDownloader 는 Accept: image/* (또는 image/png, image/jpeg) 를 보냄.
     */
    private boolean isImageRequest(HttpExchange ex) {
        String accept = ex.getRequestHeaders().getFirst("Accept");
        if (accept == null) return false;
        String a = accept.toLowerCase();
        // application/json 명시한 경우는 메타 JSON 우선
        if (a.contains("application/json")) return false;
        return a.contains("image/");
    }

    /** BSR 의 커버 이미지 바이트 전송. importMap 이 best-effort 로 미리 캐시. */
    private void handleImportCover(HttpExchange ex, String bsr) throws IOException {
        boolean headersSent = false;
        try {
            BeatSaverImporter.ImageBlob blob = importer.getCover(bsr);
            if (blob == null) {
                // 메타 import 가 선행되지 않았으면 강제로 한 번 fetch.
                importer.importMap(bsr);
                blob = importer.getCover(bsr);
            }
            if (blob == null || blob.bytes == null || blob.bytes.length == 0) {
                respond(ex, 404, "text/plain", "cover_not_found");
                return;
            }

            ex.getResponseHeaders().add("Content-Type", blob.contentType);
            ex.getResponseHeaders().add("Cache-Control", "public, max-age=86400");
            ex.sendResponseHeaders(200, blob.bytes.length);
            headersSent = true;
            try (OutputStream os = ex.getResponseBody()) { os.write(blob.bytes); }
        } catch (Exception e) {
            if (headersSent) {
                log.debug("/urasaber/api/import cover client disconnected mid-stream: {}", e.toString());
            } else {
                log.error("/urasaber/api/import cover failed", e);
                try { respond(ex, 500, "text/plain", "internal"); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * 목록 썸네일 아틀라스. /atlas?src=search|popular|playlist (+ window/n/page/v 캐시버스터, 내용 결정엔 미사용).
     * caller-IP 의 해당 slot 캐시(현재 페이지 bsr 최대 20개)를 읽어 커버를 격자 1장 JPEG 으로 합성.
     * 전체 importMap 없이 BeatSaver /maps/id 로 coverURL 만 가볍게 해석(getCoverBytesLight). 결과 TTL 캐시.
     */
    private void handleAtlas(HttpExchange ex) throws IOException {
        boolean headersSent = false;
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }
            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            String src = nullIfBlank(qs.get("src"));
            SearchSlotCache c = pickSlotCache(src).get(ip);
            if (c == null || System.currentTimeMillis() - c.timestamp > SEARCH_SLOT_TTL_MS) {
                respond(ex, 404, "text/plain", "no_slots");
                return;
            }

            // 캐시 키: src + 현재 페이지 bsr 목록 (동일 페이지 재요청 시 재합성 안 함).
            StringBuilder kb = new StringBuilder(src == null ? "search" : src).append('|');
            for (int i = 0; i < c.bsrs.length; i++) { if (i > 0) kb.append(','); kb.append(c.bsrs[i] == null ? "" : c.bsrs[i]); }
            String key = kb.toString();

            long now = System.currentTimeMillis();
            AtlasCacheEntry hit = atlasCache.get(key);
            byte[] jpeg;
            if (hit != null && now - hit.timestamp < ATLAS_CACHE_TTL_MS) {
                jpeg = hit.jpeg;
            } else {
                jpeg = buildAtlas(c.bsrs);
                if (jpeg != null) atlasCache.put(key, new AtlasCacheEntry(now, jpeg));
            }
            if (jpeg == null || jpeg.length == 0) { respond(ex, 404, "text/plain", "atlas_empty"); return; }

            ex.getResponseHeaders().add("Content-Type", "image/jpeg");
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().add("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, jpeg.length);
            headersSent = true;
            try (OutputStream os = ex.getResponseBody()) { os.write(jpeg); }
        } catch (Exception e) {
            if (headersSent) {
                log.debug("/urasaber/api/atlas client disconnected mid-stream: {}", e.toString());
            } else {
                log.error("/urasaber/api/atlas failed", e);
                try { respond(ex, 500, "text/plain", "internal"); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * slot 캐시의 bsr 배열(최대 SEARCH_SLOT_MAX)을 ATLAS_COLS×ATLAS_ROWS 격자 JPEG 으로 합성.
     * 셀 i: col=i%COLS, rowTop=i/COLS (좌상단 row-major). 커버 없는/디코드 실패(webp 등) 칸은 어두운 배경.
     * 커버 바이트 fetch 는 병렬(common ForkJoinPool), 드로잉은 단일 Graphics2D.
     */
    private byte[] buildAtlas(String[] bsrs) throws IOException {
        int cells = Math.min(ATLAS_COLS * ATLAS_ROWS, bsrs.length);

        // coverURL 들을 BeatSaver /maps/ids 배치로 1회 해석 (개별 maps/id 20연발 레이트리밋 회피).
        java.util.ArrayList<String> bsrList = new java.util.ArrayList<>();
        for (int i = 0; i < cells; i++) if (bsrs[i] != null && !bsrs[i].isBlank()) bsrList.add(bsrs[i]);
        importer.resolveCoverUrlsBatch(bsrList);

        // 커버 바이트는 CDN(eu.cdn.beatsaver.com)에서 병렬 다운로드 — CDN 은 레이트리밋 관대.
        final byte[][] covers = new byte[cells][];
        java.util.stream.IntStream.range(0, cells).parallel().forEach(i -> {
            String bsr = bsrs[i];
            if (bsr != null && !bsr.isBlank()) {
                try { covers[i] = importer.getCoverBytesLight(bsr); } catch (Exception ignore) { /* 빈 칸 */ }
            }
        });

        int w = ATLAS_COLS * ATLAS_CELL;
        int h = ATLAS_ROWS * ATLAS_CELL;
        BufferedImage atlas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = atlas.createGraphics();
        boolean any = false;
        try {
            g.setColor(new Color(0x14, 0x14, 0x1a));
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            for (int i = 0; i < cells; i++) {
                if (covers[i] == null) continue;
                BufferedImage img;
                try { img = ImageIO.read(new ByteArrayInputStream(covers[i])); }
                catch (Exception ex) { img = null; }
                if (img == null) continue; // 미지원 포맷(webp 등) → 빈 칸
                int col = i % ATLAS_COLS;
                int rowTop = i / ATLAS_COLS;
                g.drawImage(img, col * ATLAS_CELL, rowTop * ATLAS_CELL, ATLAS_CELL, ATLAS_CELL, null);
                any = true;
            }
        } finally {
            g.dispose();
        }
        if (!any) return null;

        ByteArrayOutputStream baos = new ByteArrayOutputStream(64 * 1024);
        ImageIO.write(atlas, "jpg", baos);
        return baos.toByteArray();
    }

    /**
     * BSR 의 오디오 (.egg/.ogg) 바이트 전송. 월드 VRChat AVPro 가 이 URL 을 그대로 재생.
     * 캐시 miss 시 import 를 먼저 트리거해서 zip 을 받아오도록 한다.
     * headersSent 플래그로 mid-stream client disconnect 시 secondary respond 시도 방지.
     */
    private void handleImportAudio(HttpExchange ex) throws IOException {
        boolean headersSent = false;
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }

            Map<String, String> qs = parseQuery(ex.getRequestURI().getRawQuery());
            // import 메타와 동일 해석 — AVPro 가 같은 URL(n,page,row 또는 slot) 로 오디오 요청.
            String bsr = resolveBsrFromRequest(extractClientIp(ex), qs);
            if (bsr == null || !BeatSaverImporter.isValidBsr(bsr)) { respond(ex, 400, "text/plain", "bad_bsr"); return; }

            BeatSaverImporter.AudioFile audio = importer.getAudioFile(bsr);
            if (audio == null) {
                // 메타 import 가 선행되지 않았으면 강제로 한 번 fetch.
                importer.importMap(bsr);
                audio = importer.getAudioFile(bsr);
            }
            if (audio == null) { respond(ex, 404, "text/plain", "audio_not_found"); return; }

            // 디스크 → 응답 본문으로 스트림 (heap 에 audio 전체 안 올림 — AVPro Range 요청 동시 다발에 안전).
            long size = Files.size(audio.path);
            ex.getResponseHeaders().add("Content-Type", audio.contentType);
            ex.getResponseHeaders().add("Cache-Control", "public, max-age=3600");
            ex.sendResponseHeaders(200, size);
            headersSent = true;
            try (InputStream in = Files.newInputStream(audio.path);
                 OutputStream os = ex.getResponseBody()) {
                in.transferTo(os);
            }
        } catch (Exception e) {
            if (headersSent) {
                // AVPro 가 head probe 끝나면 첫 connection 을 끊는 정상 동작 → write 중 IOException.
                // 헤더 이미 보낸 상태라 추가 respond 못 함. debug 레벨로만 기록.
                log.debug("/urasaber/api/import/audio client disconnected mid-stream: {}", e.toString());
            } else {
                log.error("/urasaber/api/import/audio failed", e);
                try { respond(ex, 500, "text/plain", "internal"); } catch (Exception ignore) {}
            }
        }
    }

    // ========================================
    // Submit handler (HMAC-protected GET)
    // ========================================

    private void handleSubmit(HttpExchange ex) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) { respond(ex, 405, "text/plain", "Method Not Allowed"); return; }

            String ip = extractClientIp(ex);
            if (!checkRateLimit(ip)) { respond(ex, 429, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"rate_limit\"}"); return; }

            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());

            // 새 형식: ?d=BASE64(pipe|delimited|payload). URL 길이 단축 + 특수문자 안전.
            // payload 필드 순서 (U# 와 정확히 일치): nick|map|char|diff|score|acc|combo|maxCombo|good|bad|miss|total|noteCount|fc|rank|ts
            String dataParam = nullIfBlank(q.get("d"));
            if (dataParam != null) {
                Map<String, String> unpacked = unpackBase64Payload(dataParam);
                if (unpacked == null) {
                    respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_d_param\"}");
                    return;
                }
                // 풀어진 필드를 q 에 merge — 이후 로직은 기존 individual param 흐름과 동일.
                for (Map.Entry<String, String> e : unpacked.entrySet()) {
                    q.putIfAbsent(e.getKey(), e.getValue());
                }
            }

            String nick = nullIfBlank(q.get("nick"));
            String mapId = nullIfBlank(q.get("map"));
            String character = q.getOrDefault("char", "Standard");
            Integer diff = parseIntBoxed(q.get("diff"));
            Integer score = parseIntBoxed(q.get("score"));
            String tsRaw = q.get("ts");

            if (nick == null || mapId == null || diff == null || score == null || tsRaw == null) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"missing_params\"}"); return;
            }
            long ts;
            try { ts = Long.parseLong(tsRaw); } catch (NumberFormatException nfe) {
                respond(ex, 400, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"bad_ts\"}"); return;
            }

            long nowSec = Instant.now().getEpochSecond();
            if (Math.abs(nowSec - ts) > TS_WINDOW_SECONDS) {
                respond(ex, 401, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"ts_drift\"}"); return;
            }

            // Sanity
            Double accuracy = parseDoubleBoxed(q.get("acc"));
            Integer combo = parseIntBoxed(q.get("combo"));
            Integer maxCombo = parseIntBoxed(q.get("maxCombo"));
            Integer hit = parseIntBoxed(q.get("total"));   // Udon은 'total' = notesHit
            Integer noteCount = parseIntBoxed(q.get("noteCount")); // 차트의 총 노트 수
            Integer goodCut = parseIntBoxed(q.get("good"));
            Integer badCut  = parseIntBoxed(q.get("bad"));
            Integer miss    = parseIntBoxed(q.get("miss"));
            String rank = nullIfBlank(q.get("rank"));
            boolean fc = "1".equals(q.get("fc")) || "true".equalsIgnoreCase(q.get("fc"));
            String modifiers = nullIfBlank(q.get("mods"));

            // 곡 메타 (옵션, 첫 제출 시 ura_song upsert 용)
            String songName     = nullIfBlank(q.get("name"));
            String songSubtitle = nullIfBlank(q.get("sub"));
            String songAuthor   = nullIfBlank(q.get("artist"));
            String mapper       = nullIfBlank(q.get("mapper"));

            if (score < 0)                    { respond(ex, 422, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"score_negative\"}"); return; }
            if (accuracy != null && (accuracy < 0 || accuracy > 100)) { respond(ex, 422, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"acc_oob\"}"); return; }
            if (hit != null && noteCount != null && hit > noteCount + 10) { // 약간 여유
                respond(ex, 422, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"hit_gt_noteCount\"}"); return;
            }
            if (diff < 0 || diff > 9)         { respond(ex, 422, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"diff_oob\"}"); return; }

            long playerId = db.upsertPlayer(nick, nowSec);
            if (db.isDuplicateRecent(playerId, mapId, character, diff, score, nowSec - DEDUP_WINDOW_SECONDS)) {
                respond(ex, 200, "application/json; charset=utf-8", "{\"ok\":true,\"duplicate\":true}"); return;
            }

            // 첫 제출에 따라 ura_song upsert (메타 데이터 자동 등록)
            if (songName != null) {
                try {
                    db.upsertSong(mapId, songName, songSubtitle, songAuthor, mapper, null, null, nowSec);
                } catch (Exception e) {
                    log.warn("upsertSong failed for {}: {}", mapId, e.getMessage());
                }
            }

            // Country lookup (cached). 비ASYNC라 미세하게 첫 호출은 느릴 수 있음 (~수백ms)
            String country = null;
            try {
                country = db.getPlayerCountry(playerId);
                if (country == null || country.isBlank()) {
                    country = geo.lookup(ip);
                    if (country != null) {
                        db.updatePlayerCountry(playerId, country);
                    }
                }
            } catch (Exception e) {
                log.warn("country lookup failed for {}: {}", ip, e.getMessage());
            }

            int prevBest = previousBestScore(playerId, mapId, character, diff);
            boolean personalBest = score > prevBest;

            long playId = db.insertPlay(playerId, mapId, character, diff,
                    score, accuracy, combo, maxCombo, hit, hit, rank, fc, modifiers,
                    ts, nowSec, ip,
                    goodCut, badCut, miss, noteCount, country);

            // Discord 알림 (있을 때만, 비동기 — HTTP 응답을 막지 않음)
            if (notifier != null) {
                pushNotification(nick, mapId, songName, songAuthor, character, diff,
                        score, accuracy, combo, maxCombo, goodCut, badCut, miss, noteCount,
                        fc, rank, personalBest, country, ts);
            }

            respond(ex, 200, "application/json; charset=utf-8",
                    "{\"ok\":true,\"playId\":" + playId + ",\"personalBest\":" + personalBest + "}");
        } catch (Exception e) {
            log.error("/urasaber/api/submit failed", e);
            respond(ex, 500, "application/json; charset=utf-8", "{\"ok\":false,\"error\":\"server\"}");
        }
    }

    /** 이 (player, chart) 조합의 직전까지의 best score. 없으면 -1. */
    private int previousBestScore(long playerId, String mapId, String character, int diff) {
        try {
            return db.getPlayerBestScore(playerId, mapId, character, diff);
        } catch (Exception e) {
            log.warn("previousBestScore failed: {}", e.getMessage());
            return -1;
        }
    }

    /** Submit 후 Discord 채널들에 알림 푸시. song 메타는 submit param 우선, 없으면 DB lookup. */
    private void pushNotification(String nick, String mapId, String songName, String songAuthor,
                                  String character, int diff,
                                  int score, Double accuracy, Integer combo, Integer maxCombo,
                                  Integer goodCut, Integer badCut, Integer miss, Integer noteCount,
                                  boolean fc, String rank, boolean personalBest, String country,
                                  long playedAt) {
        try {
            if (songName == null || songAuthor == null) {
                var songOpt = db.getSong(mapId);
                if (songName == null) songName = songOpt.map(UraSaberDatabase.SongRow::songName).orElse(mapId);
                if (songAuthor == null) songAuthor = songOpt.map(UraSaberDatabase.SongRow::songAuthor).orElse(null);
            }

            // 썸네일 + 난이도 노트 수: DB 우선, 없으면 BeatSaver (mapId == BSR) 폴백 후 DB write-back.
            FeedMeta fm = resolveFeedMeta(mapId, character, diff, noteCount);

            notifier.announce(new UraSaberNotifier.ScoreEvent(
                    nick, mapId, songName, songAuthor,
                    character, diff, score, accuracy,
                    combo, maxCombo, goodCut, badCut, miss, noteCount,
                    fc, rank, personalBest, country, playedAt,
                    fm.coverUrl(), fm.noteCount()));
        } catch (Exception e) {
            log.warn("urasaber notify push failed: {}", e.getMessage());
        }
    }

    private record FeedMeta(String coverUrl, Integer noteCount) {}

    /**
     * 피드용 cover URL + 난이도 노트 수 해석.
     * DB(ura_song.cover_url / ura_chart.note_count) 우선 → 없으면 BeatSaver (mapId 가 BSR 일 때) →
     * DB 에 없던 값은 write-back 해서 다음엔 DB 히트. 전부 best-effort (실패해도 피드는 나감).
     */
    private FeedMeta resolveFeedMeta(String mapId, String character, int diff, Integer submittedNoteCount) {
        String dbCover = null;
        Integer dbNotes = null;
        try {
            dbCover = db.getSongCoverUrl(mapId);
            dbNotes = db.getChartNoteCount(mapId, character, diff);
        } catch (Exception e) {
            log.warn("feed meta DB lookup failed for {}: {}", mapId, e.getMessage());
        }

        String coverUrl = dbCover;
        Integer noteCount = (dbNotes != null) ? dbNotes : submittedNoteCount;

        boolean isBsr = BeatSaverImporter.isValidBsr(mapId);
        if (isBsr && (coverUrl == null || coverUrl.isBlank())) {
            try { coverUrl = importer.getCoverUrl(mapId); } catch (Exception ignore) {}
        }
        if (isBsr && noteCount == null && "Standard".equalsIgnoreCase(character) && diff >= 0 && diff <= 4) {
            try { noteCount = importer.getDiffNoteCount(mapId, diff); } catch (Exception ignore) {}
        }

        // DB 에 없던 것만 write-back.
        if (coverUrl != null && !coverUrl.isBlank() && (dbCover == null || dbCover.isBlank())) {
            try { db.updateSongCover(mapId, coverUrl); } catch (Exception ignore) {}
        }
        if (noteCount != null && dbNotes == null) {
            try { db.updateChartNoteCount(mapId, character, diff, noteCount); } catch (Exception ignore) {}
        }
        return new FeedMeta(coverUrl, noteCount);
    }

    /** 같은 BSR 이 짧은 시간 내 반복 호출돼도 Discord 알림은 한 번만. 비동기 fire-and-forget. */
    private void maybeAnnounceImport(BeatSaverImporter.SongMeta meta) {
        long now = Instant.now().getEpochSecond();
        Long prev = lastAnnouncedBsr.get(meta.bsr);
        if (prev != null && now - prev < IMPORT_ANNOUNCE_DEDUP_SECONDS) return;
        lastAnnouncedBsr.put(meta.bsr, now);

        try {
            notifier.announceImport(new UraSaberNotifier.ImportEvent(
                    meta.bsr, meta.songName, meta.songAuthor, meta.mapper,
                    meta.coverUrl, meta.bpm, meta.duration, now));
        } catch (Exception e) {
            log.warn("import notify push failed for bsr={}: {}", meta.bsr, e.getMessage());
        }
    }

    // ========================================
    // Rate limit
    // ========================================

    private boolean checkRateLimit(String ip) {
        long nowMin = Instant.now().getEpochSecond() / 60;
        long[] cur = rateLimits.compute(ip, (k, v) -> {
            if (v == null || v[0] != nowMin) return new long[] { nowMin, 1 };
            v[1]++;
            return v;
        });
        return cur[1] <= RATE_LIMIT_PER_IP_PER_MIN;
    }

    private static String extractClientIp(HttpExchange ex) {
        String fwd = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        if (fwd != null && !fwd.isBlank()) {
            int comma = fwd.indexOf(',');
            return (comma > 0 ? fwd.substring(0, comma) : fwd).trim();
        }
        InetSocketAddress remote = ex.getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    // ========================================
    // JSON serialization
    // ========================================

    private static String songJson(UraSaberDatabase.SongRow s) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("{\"mapId\":").append(jsonString(s.mapId()));
        sb.append(",\"songName\":").append(jsonString(s.songName()));
        sb.append(",\"songSubtitle\":").append(jsonString(s.songSubtitle()));
        sb.append(",\"songAuthor\":").append(jsonString(s.songAuthor()));
        sb.append(",\"mapper\":").append(jsonString(s.mapper()));
        sb.append(",\"bpm\":").append(s.bpm() == null ? "null" : String.format(Locale.ROOT, "%.2f", s.bpm()));
        sb.append(",\"duration\":").append(s.duration() == null ? "null" : String.format(Locale.ROOT, "%.2f", s.duration()));
        sb.append('}');
        return sb.toString();
    }

    private static String chartJson(UraSaberDatabase.ChartRow c) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"characteristic\":").append(jsonString(c.characteristic()));
        sb.append(",\"difficulty\":").append(c.difficulty());
        sb.append(",\"stars\":").append(c.stars() == null ? "null" : String.format(Locale.ROOT, "%.4f", c.stars()));
        sb.append(",\"accRating\":").append(c.accRating() == null ? "null" : String.format(Locale.ROOT, "%.4f", c.accRating()));
        sb.append(",\"passRating\":").append(c.passRating() == null ? "null" : String.format(Locale.ROOT, "%.4f", c.passRating()));
        sb.append(",\"techRating\":").append(c.techRating() == null ? "null" : String.format(Locale.ROOT, "%.4f", c.techRating()));
        sb.append(",\"noteCount\":").append(c.noteCount() == null ? "null" : c.noteCount());
        sb.append('}');
        return sb.toString();
    }

    private static String leaderboardEntryJson(UraSaberDatabase.LeaderboardEntry e) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"rank\":").append(e.rank());
        sb.append(",\"nickname\":").append(jsonString(e.nickname()));
        sb.append(",\"score\":").append(e.score());
        sb.append(",\"accuracy\":").append(e.accuracy() == null ? "null" : String.format(Locale.ROOT, "%.2f", e.accuracy()));
        sb.append(",\"rankLetter\":").append(jsonString(e.rankLetter()));
        sb.append(",\"fullCombo\":").append(e.fullCombo());
        sb.append(",\"playedAt\":").append(e.playedAt());
        sb.append(",\"playCount\":").append(e.playCount());
        sb.append('}');
        return sb.toString();
    }

    private static String playerPlayJson(UraSaberDatabase.PlayerPlay p) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"mapId\":").append(jsonString(p.mapId()));
        sb.append(",\"songName\":").append(jsonString(p.songName()));
        sb.append(",\"songAuthor\":").append(jsonString(p.songAuthor()));
        sb.append(",\"characteristic\":").append(jsonString(p.characteristic()));
        sb.append(",\"difficulty\":").append(p.difficulty());
        sb.append(",\"score\":").append(p.score());
        sb.append(",\"accuracy\":").append(p.accuracy() == null ? "null" : String.format(Locale.ROOT, "%.2f", p.accuracy()));
        sb.append(",\"rankLetter\":").append(jsonString(p.rankLetter()));
        sb.append(",\"fullCombo\":").append(p.fullCombo());
        sb.append(",\"playedAt\":").append(p.playedAt());
        sb.append('}');
        return sb.toString();
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ========================================
    // Param parsing helpers
    // ========================================

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String p : raw.split("&")) {
            int eq = p.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? p : p.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
            out.put(k, v);
        }
        return out;
    }

    private static String nullIfBlank(String s) { return (s == null || s.isBlank()) ? null : s; }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static Integer parseIntBoxed(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    private static Double parseDoubleBoxed(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void respond(HttpExchange ex, int code, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }
}
