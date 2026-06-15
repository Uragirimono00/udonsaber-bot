package com.udonsaber.bot.urasaber.api;

import com.udonsaber.bot.urasaber.db.UraSaberDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Hitbloq / BeatSaver / 기타 호스팅의 .bplist (JSON) 다운로드 → 곡 메타 파싱 →
 * 각 song hash 를 BeatSaver API ({@code /maps/hash/...}) 로 BSR (mapId) 매핑.
 * <p>
 * Hash → BSR 결과는 {@code ura_hash_map} 캐시 사용 — 같은 hash 가 다른 playlist 에도 나오면 cache hit.
 * 미캐시 hash 는 200ms throttle 로 BeatSaver API 호출 (rate limit 200req/5sec 회피).
 * <p>
 * Result 는 caller (예: Discord {@code /playlist add}) 에 반환 — caller 가 DB upsert 결정.
 */
public class BplistFetcher {
    private static final Logger log = LoggerFactory.getLogger(BplistFetcher.class);

    private static final int MAX_BPLIST_BYTES = 5 * 1024 * 1024;
    private static final long BEATSAVER_THROTTLE_MS = 250;
    /** BeatSaver /maps/hash/{hashes} 는 한 번에 최대 50개 hash 허용. */
    private static final int BEATSAVER_BATCH = 50;
    /** image_b64 가 너무 크면 DB 비대 — 64KB 초과시 잘라냄 (또는 null). */
    private static final int MAX_IMAGE_B64_CHARS = 64 * 1024;

    private final UraSaberDatabase db;
    private final HttpClient http;
    private volatile long lastBeatSaverCallMs = 0;

    public BplistFetcher(UraSaberDatabase db) {
        this.db = db;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public interface ProgressListener {
        /** mapped = 현재까지 매핑 성공한 곡 수, total = 전체 곡 수. */
        void onProgress(int mapped, int total);
    }

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final String title;
        public final String author;
        public final String description;
        public final String imageB64;
        public final List<UraSaberDatabase.PlaylistSongRow> songs; // 0-indexed ord, mapId null 가능
        public final int totalSongs;
        public final int mappedBsr;

        private Result(boolean ok, String error, String title, String author, String description,
                       String imageB64, List<UraSaberDatabase.PlaylistSongRow> songs,
                       int totalSongs, int mappedBsr) {
            this.ok = ok;
            this.error = error;
            this.title = title;
            this.author = author;
            this.description = description;
            this.imageB64 = imageB64;
            this.songs = songs;
            this.totalSongs = totalSongs;
            this.mappedBsr = mappedBsr;
        }

        static Result error(String err) {
            return new Result(false, err, null, null, null, null, List.of(), 0, 0);
        }
    }

    /**
     * syncUrl 의 .bplist 가져와 파싱 + hash 매핑.
     * progress 가 null 이 아니면 매 곡 마다 호출됨 — Discord 메시지 갱신 등에 사용.
     */
    public Result fetch(String syncUrl, ProgressListener progress) {
        // 1. .bplist 다운로드 (URL 검증 + 크기 제한은 download() 공용 헬퍼에서)
        Download dl = download(syncUrl);
        if (dl.error != null) return Result.error(dl.error);
        // Assume UTF-8 JSON (Hitbloq / BeatSaver / ScoreSaber playlists)
        String json = new String(dl.bytes, StandardCharsets.UTF_8);

        // 2. JSON parse (light-weight — 외부 lib 없이 substring 추출)
        String title = extractStringValue(json, "playlistTitle");
        String author = extractStringValue(json, "playlistAuthor");
        if (author == null) author = extractStringValue(json, "owner"); // hitbloq variant
        String desc = extractStringValue(json, "playlistDescription");
        String imageB64 = extractStringValue(json, "image");
        if (imageB64 != null && imageB64.length() > MAX_IMAGE_B64_CHARS) imageB64 = null;

        // 3. songs[] 추출 — 1차 패스: 각 entry 를 draft 로 파싱
        int songsKey = json.indexOf("\"songs\"");
        if (songsKey < 0) return Result.error("songs_key_missing");
        int arrStart = json.indexOf('[', songsKey);
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
        if (arrStart < 0 || arrEnd < 0) return Result.error("songs_array_parse_failed");

        List<SongDraft> drafts = new ArrayList<>();
        int cursor = arrStart + 1;
        int idx = 0;
        while (cursor < arrEnd) {
            int objStart = json.indexOf('{', cursor);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = findMatchingBracket(json, objStart, '{', '}');
            if (objEnd < 0 || objEnd > arrEnd) break;

            String obj = json.substring(objStart, objEnd + 1);
            String hash = normalizeHash(extractStringValue(obj, "hash"));
            // BeatSaver/일부 형식은 "key" 가 BSR (소문자 hex). 있으면 우선 사용.
            String mapId = normalizeBsr(extractStringValue(obj, "key"));
            String songName = extractStringValue(obj, "songName");
            String levelAuthor = extractStringValue(obj, "levelAuthorName");
            if (levelAuthor == null) levelAuthor = extractStringValue(obj, "mapper");
            // 작성자 (artist) — 일부 playlist 는 songSubName / songAuthorName 사용
            String songAuthor = extractStringValue(obj, "songAuthorName");
            if (songAuthor == null) songAuthor = extractStringValue(obj, "songAuthor");

            // difficulties JSON (있으면 그대로 보관) — UI 인디케이터용
            String diffs = extractObjectOrArrayValue(obj, "difficulties");

            drafts.add(new SongDraft(idx, hash, mapId, songName, songAuthor, levelAuthor, diffs));
            idx++;
            cursor = objEnd + 1;
        }

        // 4. 2차 패스: 캐시 → BeatSaver 배치 조회로 BSR + 곡 메타(아티스트/매퍼) 채움.
        //    BeatSaver 플레이리스트는 곡당 key+hash+songName 만 줘서 아티스트/매퍼가 비어있음 —
        //    그래서 mapId 가 이미 있어도 메타가 비었으면 hash 로 보강한다.
        long nowSec = Instant.now().getEpochSecond();
        enrichMeta(drafts, nowSec, progress);

        // 5. row 빌드
        List<UraSaberDatabase.PlaylistSongRow> songs = new ArrayList<>(drafts.size());
        int mapped = 0;
        for (SongDraft d : drafts) {
            songs.add(new UraSaberDatabase.PlaylistSongRow(
                    0L, d.ord, d.hash, d.mapId, d.songName, d.songAuthor, d.levelAuthor, d.diffs));
            if (d.mapId != null) mapped++;
        }
        return new Result(true, null, title, author, desc, imageB64, songs, drafts.size(), mapped);
    }

    // ========================================
    // 경량 fetchRaw — 원본 .bplist 바이트 + 헤더 메타만 (BeatSaver 보강 X)
    // ========================================

    /** {@link #fetchRaw} 결과 — 원본 .bplist 바이트 + 헤더 메타 + 곡 수. 실패 시 ok=false, error 코드. */
    public static final class RawResult {
        public final boolean ok;
        public final String error;
        public final String title;
        public final String author;
        public final String description;
        public final int songCount;
        public final byte[] bytes; // 원본 .bplist 바이트 — Discord 재첨부용

        private RawResult(boolean ok, String error, String title, String author,
                          String description, int songCount, byte[] bytes) {
            this.ok = ok;
            this.error = error;
            this.title = title;
            this.author = author;
            this.description = description;
            this.songCount = songCount;
            this.bytes = bytes;
        }

        static RawResult error(String err) {
            return new RawResult(false, err, null, null, null, 0, null);
        }
    }

    /**
     * .bplist 다운로드 후 헤더 메타(title/author/description) + 곡 수만 파싱하고 원본 바이트를 그대로 반환.
     * {@link #fetch} 와 달리 hash→BSR BeatSaver 보강을 하지 않아 빠르고 rate limit 부담이 없다 —
     * "채널에 playlist 링크 → 같은 .bplist 를 첨부로 돌려줘 전곡 다운로드" 흐름용.
     */
    public RawResult fetchRaw(String syncUrl) {
        Download dl = download(syncUrl);
        if (dl.error != null) return RawResult.error(dl.error);
        String json = new String(dl.bytes, StandardCharsets.UTF_8);

        String title = extractStringValue(json, "playlistTitle");
        String author = extractStringValue(json, "playlistAuthor");
        if (author == null) author = extractStringValue(json, "owner"); // hitbloq variant
        String desc = extractStringValue(json, "playlistDescription");
        int songCount = countSongs(json);
        return new RawResult(true, null, title, author, desc, songCount, dl.bytes);
    }

    /** songs[] 배열의 곡(객체) 개수 — 가벼운 substring 워크. 파싱 실패 시 0. */
    private static int countSongs(String json) {
        int songsKey = json.indexOf("\"songs\"");
        if (songsKey < 0) return 0;
        int arrStart = json.indexOf('[', songsKey);
        int arrEnd = findMatchingBracket(json, arrStart, '[', ']');
        if (arrStart < 0 || arrEnd < 0) return 0;
        int count = 0;
        int cursor = arrStart + 1;
        while (cursor < arrEnd) {
            int objStart = json.indexOf('{', cursor);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = findMatchingBracket(json, objStart, '{', '}');
            if (objEnd < 0 || objEnd > arrEnd) break;
            count++;
            cursor = objEnd + 1;
        }
        return count;
    }

    /** download() 결과 holder — 성공 시 bytes(error=null), 실패 시 error 코드(bytes=null). */
    private static final class Download {
        final byte[] bytes;
        final String error;
        private Download(byte[] bytes, String error) { this.bytes = bytes; this.error = error; }
        static Download ok(byte[] b) { return new Download(b, null); }
        static Download err(String e) { return new Download(null, e); }
    }

    /** syncUrl 의 .bplist 바이트 다운로드 (URL 검증 + 크기 제한). {@link #fetch}/{@link #fetchRaw} 공용. */
    private Download download(String syncUrl) {
        if (syncUrl == null || syncUrl.isBlank()) return Download.err("sync_url_empty");
        if (!(syncUrl.startsWith("http://") || syncUrl.startsWith("https://"))) {
            return Download.err("sync_url_not_http");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(syncUrl))
                    .header("User-Agent", "UraSaberBot/1.0 (+playlist sync)")
                    .timeout(Duration.ofSeconds(20))
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                return Download.err("download_failed_http_" + resp.statusCode());
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) return Download.err("empty_response");
            if (body.length > MAX_BPLIST_BYTES) return Download.err("bplist_too_large");
            return Download.ok(body);
        } catch (Exception e) {
            log.warn("bplist download failed url={} err={}", syncUrl, e.toString());
            return Download.err("download_exception_" + safeMsg(e));
        }
    }

    /** 파싱 중간 단계의 가변 곡 holder — 캐시/BeatSaver 보강 후 PlaylistSongRow 로 변환. */
    private static final class SongDraft {
        final int ord;
        final String hash;
        final String diffs;
        String mapId;
        String songName;
        String songAuthor;
        String levelAuthor;

        SongDraft(int ord, String hash, String mapId, String songName,
                  String songAuthor, String levelAuthor, String diffs) {
            this.ord = ord; this.hash = hash; this.mapId = mapId;
            this.songName = songName; this.songAuthor = songAuthor;
            this.levelAuthor = levelAuthor; this.diffs = diffs;
        }

        boolean hasValidHash() { return hash != null && hash.length() == 40; }

        /** BSR 또는 메타(곡명/아티스트/매퍼) 중 하나라도 비었으면 BeatSaver 보강 필요. */
        boolean needsMeta() {
            return mapId == null || isBlank(songName) || isBlank(songAuthor) || isBlank(levelAuthor);
        }

        /** 빈 필드만 채움 — playlist 원본 값(songName 등) 보존, negative cache 의 null 은 무시. */
        void fill(String mapId, String songName, String songAuthor, String mapper) {
            if (this.mapId == null && mapId != null) this.mapId = mapId;
            if (isBlank(this.songName) && !isBlank(songName)) this.songName = songName;
            if (isBlank(this.songAuthor) && !isBlank(songAuthor)) this.songAuthor = songAuthor;
            if (isBlank(this.levelAuthor) && !isBlank(mapper)) this.levelAuthor = mapper;
        }
    }

    /**
     * drafts 의 빈 메타를 채운다. 먼저 hash 캐시(ura_hash_map)로 채우고,
     * 남은 hash 는 BeatSaver 배치 엔드포인트(/maps/hash/{h1,...,h50}, 최대 50개)로 묶어 조회.
     * 358곡을 1곡씩이 아닌 ~8회 호출로 처리 → 빠르고 rate limit 안전.
     */
    private void enrichMeta(List<SongDraft> drafts, long nowSec, ProgressListener progress) {
        // 4a. 캐시 우선
        List<SongDraft> pending = new ArrayList<>();
        for (SongDraft d : drafts) {
            if (!d.hasValidHash() || !d.needsMeta()) continue;
            try {
                var cached = db.getHashMap(d.hash);
                if (cached.isPresent()) {
                    var c = cached.get();
                    // 옛 캐시 보강: mapId 있는데 song_name 비어있으면 마이그레이션 전 row — 재조회.
                    boolean staleMeta = c.mapId() != null && isBlank(c.songName());
                    if (!staleMeta) {
                        d.fill(c.mapId(), c.songName(), c.songAuthor(), c.mapper());
                        continue; // negative cache(mapId==null) 여도 신뢰 — BeatSaver 재조회 안 함
                    }
                }
            } catch (Exception e) {
                log.warn("hash cache lookup failed: {}", e.toString());
            }
            pending.add(d);
        }
        report(drafts, progress);

        // 4b. 미캐시 hash 배치 조회
        for (int i = 0; i < pending.size(); i += BEATSAVER_BATCH) {
            List<SongDraft> batch = pending.subList(i, Math.min(i + BEATSAVER_BATCH, pending.size()));
            batchLookup(batch, nowSec);
            report(drafts, progress);
        }
    }

    /** batch(최대 50곡) 의 hash 를 BeatSaver 로 한 번에 조회 → 메타 채움 + 캐시 저장. */
    private void batchLookup(List<SongDraft> batch, long nowSec) {
        throttle();
        StringBuilder hashes = new StringBuilder();
        for (SongDraft d : batch) {
            if (hashes.length() > 0) hashes.append(',');
            hashes.append(d.hash);
        }

        String body;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.beatsaver.com/maps/hash/" + hashes))
                    .header("User-Agent", "UraSaberBot/1.0 (+playlist sync)")
                    .timeout(Duration.ofSeconds(15))
                    .GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("BeatSaver batch hash lookup HTTP {} ({} hashes)", resp.statusCode(), batch.size());
                return; // 메타 없이 진행 — playlist 의 key 는 이미 mapId 로 보존됨
            }
            body = resp.body();
        } catch (Exception e) {
            log.warn("BeatSaver batch hash lookup err={}", e.toString());
            return;
        }

        // 응답: 다중 hash → { "<hash>": {MapDetail}, ... } / 단일 hash → {MapDetail} (flat).
        boolean keyed = batch.size() > 1;
        for (SongDraft d : batch) {
            String detail = keyed ? extractObjectOrArrayValue(body, d.hash) : body;
            String mapId = null, songName = null, songAuthor = null, mapper = null;
            if (detail != null) {
                // MapDetail: { "id", "name", "metadata": { songName, songAuthorName, levelAuthorName }, "uploader": {...} }
                mapId = normalizeBsr(extractStringValue(detail, "id"));
                String metadata = extractObjectOrArrayValue(detail, "metadata");
                if (metadata != null) {
                    songName = extractStringValue(metadata, "songName");
                    songAuthor = extractStringValue(metadata, "songAuthorName");
                    mapper = extractStringValue(metadata, "levelAuthorName");
                }
                if (isBlank(songName)) songName = extractStringValue(detail, "name");
                if (isBlank(mapper)) {
                    String uploader = extractObjectOrArrayValue(detail, "uploader");
                    if (uploader != null) mapper = extractStringValue(uploader, "name");
                }
            }
            d.fill(mapId, songName, songAuthor, mapper);
            try {
                // detail 없으면(BeatSaver 미보유) negative cache 로 저장 — 재조회 방지.
                db.putHashMap(d.hash, mapId, songName, songAuthor, mapper, nowSec);
            } catch (Exception e) {
                log.warn("hash cache put failed: {}", e.toString());
            }
        }
    }

    /** BeatSaver 호출 간 throttle (rate limit 200req/5sec 회피). */
    private void throttle() {
        long sinceLast = System.currentTimeMillis() - lastBeatSaverCallMs;
        if (sinceLast < BEATSAVER_THROTTLE_MS) {
            try { Thread.sleep(BEATSAVER_THROTTLE_MS - sinceLast); } catch (InterruptedException ignored) {}
        }
        lastBeatSaverCallMs = System.currentTimeMillis();
    }

    private static void report(List<SongDraft> drafts, ProgressListener progress) {
        if (progress == null) return;
        int mapped = 0;
        for (SongDraft d : drafts) if (d.mapId != null) mapped++;
        progress.onProgress(mapped, drafts.size());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ========================================
    // JSON 파싱 헬퍼 (외부 lib 없이 substring)
    // ========================================

    private static String extractStringValue(String s, String keyNoQuotes) {
        String key = "\"" + keyNoQuotes + "\"";
        int hit = s.indexOf(key);
        if (hit < 0) return null;
        int after = hit + key.length();
        while (after < s.length() && (s.charAt(after) == ' ' || s.charAt(after) == '\t')) after++;
        if (after >= s.length() || s.charAt(after) != ':') return null;
        int p = after + 1;
        while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
        if (p >= s.length() || s.charAt(p) != '"') return null;
        StringBuilder sb = new StringBuilder();
        for (int i = p + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '"') { sb.append('"'); i++; }
                else if (n == '\\') { sb.append('\\'); i++; }
                else if (n == '/') { sb.append('/'); i++; }
                else if (n == 'n') { sb.append('\n'); i++; }
                else if (n == 't') { sb.append('\t'); i++; }
                else if (n == 'r') { sb.append('\r'); i++; }
                else if (n == 'u' && i + 5 < s.length()) {
                    try {
                        int cp = Integer.parseInt(s.substring(i + 2, i + 6), 16);
                        sb.append((char) cp);
                        i += 5;
                    } catch (NumberFormatException e) { sb.append(c); }
                } else { sb.append(c); }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    /** difficulties 같은 nested array/object 를 원본 raw JSON substring 으로 추출. */
    private static String extractObjectOrArrayValue(String s, String keyNoQuotes) {
        String key = "\"" + keyNoQuotes + "\"";
        int hit = s.indexOf(key);
        if (hit < 0) return null;
        int after = hit + key.length();
        while (after < s.length() && (s.charAt(after) == ' ' || s.charAt(after) == '\t')) after++;
        if (after >= s.length() || s.charAt(after) != ':') return null;
        int p = after + 1;
        while (p < s.length() && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
        if (p >= s.length()) return null;
        char open = s.charAt(p);
        if (open == '[' || open == '{') {
            char close = (open == '[') ? ']' : '}';
            int end = findMatchingBracket(s, p, open, close);
            if (end > p) return s.substring(p, end + 1);
        }
        return null;
    }

    private static int findMatchingBracket(String s, int openIdx, char open, char close) {
        if (openIdx < 0 || openIdx >= s.length() || s.charAt(openIdx) != open) return -1;
        int depth = 0;
        boolean inStr = false;
        boolean escape = false;
        for (int i = openIdx; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String normalizeHash(String h) {
        if (h == null) return null;
        h = h.trim().toLowerCase();
        return h.isEmpty() ? null : h;
    }

    private static String normalizeBsr(String b) {
        if (b == null) return null;
        b = b.trim().toLowerCase();
        if (b.isEmpty()) return null;
        // BSR 은 hex 1..12 자
        if (b.length() > 12) return null;
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return null;
        }
        return b;
    }

    private static String safeMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        m = m.replace(' ', '_');
        if (m.length() > 60) m = m.substring(0, 60);
        return m;
    }
}
