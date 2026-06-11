package com.udonsaber.bot.urasaber.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BeatSaver /maps/id/{key} 메타 조회 — {@link BeatSaverImporter} 의 full import
 * (zip 다운로드 + .dat 파싱) 없이 Discord 임베드 표시에 필요한 곡 정보만 가볍게 가져온다.
 * 같은 곡 반복 신청 시 BeatSaver 재호출을 막기 위해 짧은 TTL 캐시 보관.
 */
public final class BeatSaverMapLookup {
    private static final Logger log = LoggerFactory.getLogger(BeatSaverMapLookup.class);

    private static final long CACHE_TTL_MS = 10L * 60_000L;
    private static final int MAX_CACHE_ENTRIES = 200;

    /** /maps/id 응답에서 임베드에 필요한 필드만. duration 은 초 단위. */
    public record MapInfo(String bsr, String name, String subName, String author, String mapper,
                          double bpm, double duration, String coverUrl, String downloadUrl) {}

    private record Cached(long timestamp, MapInfo info) {}

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** 맵 메타 조회. 404/네트워크 오류 시 null. */
    public MapInfo lookup(String bsr) {
        if (!BeatSaverImporter.isValidBsr(bsr)) return null;
        String key = bsr.toLowerCase();
        long now = System.currentTimeMillis();
        Cached c = cache.get(key);
        if (c != null && now - c.timestamp() < CACHE_TTL_MS) return c.info();

        String json;
        try {
            json = httpGet("https://api.beatsaver.com/maps/id/" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            log.warn("[BeatSaver] map lookup {} failed: {}", key, e.toString());
            return null;
        }
        if (json == null) return null;

        MapInfo info = new MapInfo(
                key,
                BeatSaverImporter.extractFirstString(json, "\"songName\""),
                BeatSaverImporter.extractFirstString(json, "\"songSubName\""),
                BeatSaverImporter.extractFirstString(json, "\"songAuthorName\""),
                BeatSaverImporter.extractFirstString(json, "\"levelAuthorName\""),
                orZero(BeatSaverImporter.extractFirstDouble(json, "\"bpm\"")),
                orZero(BeatSaverImporter.extractFirstDouble(json, "\"duration\"")),
                BeatSaverImporter.extractFirstString(json, "\"coverURL\""),
                BeatSaverImporter.extractFirstString(json, "\"downloadURL\""));

        if (cache.size() >= MAX_CACHE_ENTRIES) cache.clear(); // 단순 과적 방지 — TTL 짧아 전체 비움으로 충분
        cache.put(key, new Cached(now, info));
        return info;
    }

    private static double orZero(Double v) { return v == null ? 0 : v; }

    private String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "UraSaberBot/0.1 (+https://api.ura-s.org)")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            if (resp.statusCode() != 404) log.warn("[BeatSaver] GET {} → {}", url, resp.statusCode());
            return null;
        }
        return resp.body();
    }
}
