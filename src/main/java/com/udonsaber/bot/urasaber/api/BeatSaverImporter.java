package com.udonsaber.bot.urasaber.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * BeatSaver 맵을 다운로드 → unzip → Info.dat + 난이도 .dat 파일 파싱 →
 * UraSaber 월드에서 쓰기 좋은 평면 JSON 으로 변환.
 *
 * <pre>
 *   1. GET https://api.beatsaver.com/maps/id/{bsr}                → 맵 메타
 *   2. zip URL 추출 (versions[0].downloadURL)
 *   3. zip 다운로드 후 메모리에서 unzip
 *   4. Info.dat 파싱 → BPM, 곡정보, 난이도 목록, songFilename
 *   5. 각 난이도 _beatmapFilename .dat 파싱 (v2/v3 분기) → notes/walls/chains/sliders
 *   6. beat → seconds 변환 (BPM 기준 상수 — v3 의 bpmEvents 는 무시, 대부분 맵이 단일 BPM)
 *   7. 결과 JSON 문자열 반환
 * </pre>
 *
 * 결과 페이로드는 BSSongData / BSDifficultyData 구조를 평면화한 JSON. 월드의 UdonSharp
 * JSON 파서가 동적 배열 생성이 어렵기 때문에 가능한 한 짧은 키와 평면 배열을 사용한다.
 *
 * 응답 캐시는 {@link #cache} 에 보관 (TTL {@link #CACHE_TTL_MS}). BSR 단위 메모리 캐시라
 * 같은 곡 반복 요청은 BeatSaver 재호출 안 함.
 */
public final class BeatSaverImporter {
    private static final Logger log = LoggerFactory.getLogger(BeatSaverImporter.class);

    /** BeatSaver 응답/zip 캐시 TTL (밀리초). 1시간. */
    private static final long CACHE_TTL_MS = 60L * 60_000L;

    /** 검색 응답 캐시 TTL — 같은 query+page 가 짧은 시간 안에 들어오면 캐시 hit. */
    private static final long SEARCH_CACHE_TTL_MS = 5L * 60_000L;

    /** zip 다운로드 최대 크기 (바이트). 비정상적으로 큰 zip 거부 — 50MB. */
    private static final long MAX_ZIP_BYTES = 50L * 1024L * 1024L;

    /** 단일 zip entry 최대 (info.dat / .dat 용 — 메모리에 보관). 16MB.
     *  랭크/이펙트 맵은 난이도 .dat 에 라이팅 커스텀데이터가 많아 8MB+ 도 흔함 → 4MB 면 못 읽어 no_difficulties_parsed. */
    private static final long MAX_SMALL_ENTRY_BYTES = 16L * 1024L * 1024L;

    /** audio entry 최대 (디스크로 스트림). 20MB. */
    private static final long MAX_AUDIO_BYTES = 20L * 1024L * 1024L;

    /** 난이도 1개당 노트 최대 (anti-OOM). */
    private static final int MAX_NOTES_PER_DIFF = 20_000;

    /** 전체 응답 JSON 크기 제한 (바이트). 8MB. */
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    /** 커버 이미지 최대 (BeatSaver 커버는 보통 <500KB 이지만 안전 마진). */
    private static final long MAX_COVER_BYTES = 4L * 1024L * 1024L;

    /** import 응답 캐시 최대 엔트리 수 (LRU). audio 는 디스크에 있으니 메모리 점유는 JSON/cover 정도. */
    private static final int MAX_CACHE_ENTRIES = 50;

    /** 동시 import 작업 최대. 초과 시 즉시 429 busy — OOM 으로 인한 서버 다운 방지. */
    private static final int MAX_CONCURRENT_IMPORTS = 2;

    /** in-flight future 대기 타임아웃. 같은 BSR 동시 호출 시 dedup 대기. */
    private static final long INFLIGHT_WAIT_SECONDS = 90;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 응답 캐시 — LRU (access-order) 로 MAX_CACHE_ENTRIES 초과 시 가장 오래 안 쓰인 entry evict.
     * evict 시 디스크 audio 파일도 같이 삭제 (removeEldestEntry).
     * synchronizedMap 으로 thread-safe.
     */
    private final Map<String, CachedEntry> cache = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedEntry>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest) {
                    if (size() > MAX_CACHE_ENTRIES) {
                        CachedEntry e = eldest.getValue();
                        if (e != null && e.audioPath != null) {
                            try { Files.deleteIfExists(e.audioPath); }
                            catch (Exception ignore) { /* best-effort */ }
                        }
                        return true;
                    }
                    return false;
                }
            });

    /** 동시 import 제한. fan-out 으로 인한 peak heap 폭증 방지. */
    private final Semaphore importSemaphore = new Semaphore(MAX_CONCURRENT_IMPORTS);

    /**
     * 같은 BSR 의 in-flight import 공유 — 동시 N명이 같은 곡 클릭하면 1회만 작업하고 결과 공유.
     * computeIfAbsent + CompletableFuture 패턴. 완료 시 remove.
     */
    private final ConcurrentHashMap<String, CompletableFuture<Result>> inFlight = new ConcurrentHashMap<>();

    /**
     * 디스크 audio 캐시 디렉토리. zip 의 .egg/.ogg entry 를 BeatSaver 다운로드 직후 곧바로 이리로 스트림 →
     * heap 에 audio bytes 보관 안 함. {@link #getAudioFile(String)} 가 요청 시 파일 경로를 반환.
     */
    private final Path audioCacheDir;

    /** 검색 캐시 — key = "q|page" (소문자). 짧은 TTL 로 단순 race 흡수. */
    private final Map<String, CachedSearch> searchCache = new ConcurrentHashMap<>();

    private static final class CachedSearch {
        final long timestamp;
        final String json;
        final List<String> bsrList;
        CachedSearch(long timestamp, String json, List<String> bsrList) {
            this.timestamp = timestamp;
            this.json = json;
            this.bsrList = bsrList;
        }
    }

    public BeatSaverImporter() {
        this(Paths.get("data", "beatsaver-audio"));
    }

    /** 테스트 / 다른 디렉토리 사용 케이스용 (디스크 audio 캐시 위치 override). */
    public BeatSaverImporter(Path audioCacheDir) {
        this.audioCacheDir = audioCacheDir;
        try {
            Files.createDirectories(audioCacheDir);
        } catch (IOException e) {
            log.warn("[BeatSaver] failed to create audio cache dir {}: {}", audioCacheDir, e.toString());
            // best-effort — importMap 에서 실제 쓰기 시 다시 시도하거나 실패 응답.
        }
    }

    /** BSR 코드 검증: BeatSaver ID 는 hex 4~6 자리 정도. 안전한 입력만 허용. */
    public static boolean isValidBsr(String bsr) {
        if (bsr == null) return false;
        int len = bsr.length();
        if (len < 1 || len > 12) return false;
        for (int i = 0; i < len; i++) {
            char c = bsr.charAt(i);
            boolean isHex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!isHex) return false;
        }
        return true;
    }

    /**
     * 캐시된 import JSON 반환 (없으면 BeatSaver 에서 fetch + 파싱).
     * <p>
     * 동시성 보호 3중 레이어:
     * <ol>
     *   <li>Cache hit (LRU) — TTL 내면 즉시 반환.</li>
     *   <li>In-flight dedup — 같은 BSR 의 import 가 진행 중이면 그 future 결과 공유.</li>
     *   <li>Semaphore — 시스템 전체 동시 import 수 제한. 초과 시 즉시 503 busy.</li>
     * </ol>
     * 모든 OOM/예외는 핸들러까지 안 새도록 {@link Throwable} 잡아서 Result.error 로 변환.
     */
    public Result importMap(String bsr) {
        if (!isValidBsr(bsr)) return Result.error(400, "bad_bsr");

        String key = bsr.toLowerCase();
        long now = System.currentTimeMillis();

        // 1. Cache hit
        CachedEntry cached = cache.get(key);
        if (cached != null && now - cached.timestamp < CACHE_TTL_MS) {
            return Result.ok(cached.json, cached.audioUrl, cached.meta);
        }

        // 2. In-flight dedup — 같은 BSR 이 처리 중이면 그 future 에 piggyback.
        //    computeIfAbsent 로 race-free 등록. 등록 성공한 caller 만 doImport 실행.
        boolean[] ownerHolder = new boolean[1];
        CompletableFuture<Result> future = inFlight.computeIfAbsent(key, k -> {
            ownerHolder[0] = true;
            return new CompletableFuture<>();
        });
        boolean owner = ownerHolder[0];

        if (!owner) {
            // 다른 스레드가 작업 중 — 결과 대기 (최대 INFLIGHT_WAIT_SECONDS).
            try {
                return future.get(INFLIGHT_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Result.error(503, "interrupted");
            } catch (Exception e) {
                log.warn("[BeatSaver bsr={}] in-flight wait failed: {}", bsr, e.toString());
                return Result.error(504, "in_flight_timeout");
            }
        }

        // 3. Semaphore — owner 도 시스템 동시 제한 통과해야 실제 작업 시작.
        boolean acquired = false;
        try {
            try {
                acquired = importSemaphore.tryAcquire(1, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                Result r = Result.error(503, "interrupted");
                future.complete(r);
                return r;
            }
            if (!acquired) {
                log.info("[BeatSaver bsr={}] busy — concurrent import limit reached", bsr);
                Result r = Result.error(503, "busy");
                future.complete(r);
                return r;
            }

            // 실제 작업. OOM (Error) 까지 잡아서 worker thread 죽지 않도록.
            Result r;
            try {
                r = doImport(key, bsr, now);
            } catch (OutOfMemoryError oom) {
                // 캐시 비워서 다음 import 가 회복 가능하게.
                log.error("[BeatSaver bsr={}] OOM during import — clearing cache", bsr, oom);
                clearCacheBestEffort();
                r = Result.error(503, "out_of_memory");
            } catch (Throwable t) {
                log.error("[BeatSaver bsr={}] import failed (throwable)", bsr, t);
                r = Result.error(500, "internal");
            }
            future.complete(r);
            return r;
        } finally {
            inFlight.remove(key);
            if (acquired) importSemaphore.release();
        }
    }

    /** 실제 import 로직 — owner 스레드만 실행. {@link #importMap}이 동시성/에러를 감싼다. */
    private Result doImport(String key, String bsr, long now) throws Exception {
        // 1. BeatSaver 메타 조회
        String mapJson = httpGet("https://api.beatsaver.com/maps/id/" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        if (mapJson == null) return Result.error(404, "beatsaver_not_found");

        // versions[0].downloadURL + versions[0].coverURL + versions[0].hash 추출
        String zipUrl = extractFirstString(mapJson, "\"downloadURL\"");
        String coverUrl = extractFirstString(mapJson, "\"coverURL\"");
        String mapHash = extractFirstString(mapJson, "\"hash\"");  // BSOR info.hash (자동 맵 매칭)
        if (zipUrl == null) return Result.error(502, "no_download_url");

        // 곡 메타 (메타 API 의 metadata 객체)
        String metaSongName = extractFirstString(mapJson, "\"songName\"");
        String metaSongSub = extractFirstString(mapJson, "\"songSubName\"");
        String metaSongAuthor = extractFirstString(mapJson, "\"songAuthorName\"");
        String metaLevelAuthor = extractFirstString(mapJson, "\"levelAuthorName\"");
        Double metaBpm = extractFirstDouble(mapJson, "\"bpm\"");
        Double metaDuration = extractFirstDouble(mapJson, "\"duration\"");

        // 2. zip 다운로드 → 임시 파일 (heap 에 50MB 통째 안 올림). 처리 후 삭제.
        Path zipTemp = null;
        ImportedFiles imp;
        try {
            zipTemp = httpStreamToTempFile(zipUrl, MAX_ZIP_BYTES);
            if (zipTemp == null) return Result.error(502, "zip_download_failed");

            // 3. zip 스트림 처리 — small entry (.dat) 만 메모리, audio 는 영구 디스크 캐시로 바로 스트림.
            imp = processZipStreaming(zipTemp, key);
        } finally {
            if (zipTemp != null) {
                try { Files.deleteIfExists(zipTemp); } catch (Exception ignore) { /* best-effort */ }
            }
        }

        byte[] infoBytes = findEntry(imp.smallFiles, "Info.dat");
        if (infoBytes == null) infoBytes = findEntry(imp.smallFiles, "info.dat");
        if (infoBytes == null) {
            if (imp.audioPath != null) {
                try { Files.deleteIfExists(imp.audioPath); } catch (Exception ignore) {}
            }
            return Result.error(422, "no_info_dat");
        }

        String infoDat = new String(infoBytes, StandardCharsets.UTF_8);

        // 4. Info.dat 파싱
        InfoParsed info = parseInfoDat(infoDat);
        if (info.bpm <= 0 && metaBpm != null) info.bpm = metaBpm;
        if (info.songName == null) info.songName = metaSongName;
        if (info.songAuthor == null) info.songAuthor = metaSongAuthor;
        if (info.songSubtitle == null) info.songSubtitle = metaSongSub;
        if (info.mapper == null) info.mapper = metaLevelAuthor;

        float beatPerSec = info.bpm > 0 ? (float) (info.bpm / 60.0) : 2.0f;  // bps. 60/bpm = sec/beat 의 역수.
        // beat → seconds : seconds = beat / beatPerSec.

        // 5. 각 난이도 .dat 파싱
        List<DiffParsed> diffs = new ArrayList<>();
        for (DiffMeta dm : info.diffs) {
            byte[] datBytes = findEntry(imp.smallFiles, dm.filename);
            if (datBytes == null) {
                log.warn("[BeatSaver bsr={}] missing difficulty file {}", bsr, dm.filename);
                continue;
            }
            String diffJson = new String(datBytes, StandardCharsets.UTF_8);
            DiffParsed dp = parseDifficultyDat(diffJson, beatPerSec);
            if (dp == null) continue;
            dp.meta = dm;
            diffs.add(dp);
        }
        if (diffs.isEmpty()) return Result.error(422, "no_difficulties_parsed");

        // 6. JSON 빌드
        String audioUrl = "/urasaber/api/import/audio?bsr=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        String json = buildResponseJson(key, info, coverUrl, audioUrl, metaDuration, diffs);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE_BYTES) {
            if (imp.audioPath != null) {
                try { Files.deleteIfExists(imp.audioPath); } catch (Exception ignore) {}
            }
            return Result.error(413, "response_too_large");
        }

        SongMeta meta = new SongMeta(key, info.songName, info.songAuthor, info.mapper,
                coverUrl, info.bpm, metaDuration == null ? 0.0 : metaDuration, mapHash);

        // 7. 커버 바이트 best-effort 다운로드 — 실패해도 import 자체는 성공으로 두고 cover 없이 진행.
        ImageBlob coverBlob = null;
        if (coverUrl != null && !coverUrl.isEmpty()) {
            try {
                coverBlob = downloadCover(coverUrl);
            } catch (Exception ce) {
                log.warn("[BeatSaver bsr={}] cover download failed: {}", bsr, ce.toString());
            }
        }

        // 8. diff 별 note count 캐싱 (standard index 0..4 매핑). 봇이 commit-score 에서 lookup.
        int[] noteCountByStd = new int[5];
        float[] njsByStd = new float[5];
        for (DiffParsed dp : diffs) {
            int std = rankToStdDiff(dp.meta.difficultyRank);
            if (std >= 0 && std < 5) {
                // 여러 characteristic 가 같은 std 에 매핑되면 최대값 (Standard 가 보통 가장 많음)
                if (dp.notes.size() > noteCountByStd[std]) noteCountByStd[std] = dp.notes.size();
                // NJS: Standard characteristic 우선 (없으면 첫 값). ArcViewer 의 _currentDifficulty.noteJumpSpeed 와 동일 소스.
                if (njsByStd[std] <= 0f || "Standard".equalsIgnoreCase(dp.meta.characteristic)) njsByStd[std] = dp.meta.njs;
            }
        }

        // 9. 기존 캐시 entry 가 있고 audio path 가 다르면 옛 파일 삭제 (zip 재다운으로 새 파일 만들어진 경우).
        CachedEntry prev = cache.get(key);
        if (prev != null && prev.audioPath != null
                && (imp.audioPath == null || !prev.audioPath.equals(imp.audioPath))) {
            try { Files.deleteIfExists(prev.audioPath); } catch (Exception ignore) {}
        }

        cache.put(key, new CachedEntry(now, json, audioUrl, imp.audioPath, imp.audioContentType,
                zipUrl, meta, coverBlob, noteCountByStd, njsByStd));
        return Result.ok(json, audioUrl, meta);
    }

    /** OOM 회복용 — 전체 캐시 비우고 디스크 audio 도 같이 정리 (best-effort). */
    private void clearCacheBestEffort() {
        try {
            synchronized (cache) {
                for (CachedEntry e : cache.values()) {
                    if (e != null && e.audioPath != null) {
                        try { Files.deleteIfExists(e.audioPath); } catch (Exception ignore) {}
                    }
                }
                cache.clear();
            }
        } catch (Throwable ignore) { /* nothing we can do */ }
    }

    /**
     * 캐시된 audio 가 있으면 반환 (없으면 null — 호출자가 import 트리거 또는 404).
     * 디스크 파일에서 lazy read — heap 점유는 호출 동안만.
     * Range/HTTP streaming 효율을 위해서는 {@link #getAudioFile(String)} 사용 권장.
     */
    public AudioBlob getAudio(String bsr) {
        if (!isValidBsr(bsr)) return null;
        CachedEntry e = cache.get(bsr.toLowerCase());
        if (e == null || e.audioPath == null) return null;
        try {
            if (!Files.exists(e.audioPath)) return null;
            byte[] bytes = Files.readAllBytes(e.audioPath);
            return new AudioBlob(bytes, e.audioContentType != null ? e.audioContentType : "audio/ogg");
        } catch (IOException ioe) {
            log.warn("[BeatSaver] audio read failed for {}: {}", bsr, ioe.toString());
            return null;
        }
    }

    /**
     * 캐시된 audio 의 디스크 경로. 핸들러가 streaming 으로 응답 본문에 복사할 때 사용
     * (전체 byte[] 를 heap 에 올리지 않음).
     */
    public AudioFile getAudioFile(String bsr) {
        if (!isValidBsr(bsr)) return null;
        CachedEntry e = cache.get(bsr.toLowerCase());
        if (e == null || e.audioPath == null) return null;
        if (!Files.exists(e.audioPath)) return null;
        return new AudioFile(e.audioPath, e.audioContentType != null ? e.audioContentType : "audio/ogg");
    }

    /**
     * 특정 BSR + standard diff (0..4) 의 note count 반환. 캐시 miss 면 importMap 트리거 후 재조회.
     * 봇의 commit-score 에서 BeatSaver lookup 으로 ura_play.note_count 채울 때 사용.
     */
    public Integer getDiffNoteCount(String bsr, int standardDiffIndex) {
        if (!isValidBsr(bsr)) return null;
        if (standardDiffIndex < 0 || standardDiffIndex >= 5) return null;
        CachedEntry e = cache.get(bsr.toLowerCase());
        if (e == null || e.noteCountByStdDiff == null) {
            importMap(bsr); // 캐시에 채워줌 (실패해도 OK)
            e = cache.get(bsr.toLowerCase());
            if (e == null || e.noteCountByStdDiff == null) return null;
        }
        int n = e.noteCountByStdDiff[standardDiffIndex];
        return n > 0 ? n : null;
    }

    /** 특정 BSR + standard diff(0..4)의 NJS(noteJumpSpeed). 캐시 miss 면 importMap 트리거. 없으면 null. */
    public Float getDiffNjs(String bsr, int standardDiffIndex) {
        if (!isValidBsr(bsr)) return null;
        if (standardDiffIndex < 0 || standardDiffIndex >= 5) return null;
        CachedEntry e = cache.get(bsr.toLowerCase());
        if (e == null || e.njsByStdDiff == null) {
            importMap(bsr);
            e = cache.get(bsr.toLowerCase());
            if (e == null || e.njsByStdDiff == null) return null;
        }
        float njs = e.njsByStdDiff[standardDiffIndex];
        return njs > 0f ? njs : null;
    }

    /** BeatSaver difficulty rank (1/3/5/7/9) → 표준 인덱스 (0..4). */
    private static int rankToStdDiff(int rank) {
        if (rank <= 1) return 0;
        if (rank <= 3) return 1;
        if (rank <= 5) return 2;
        if (rank <= 7) return 3;
        return 4;
    }

    /**
     * 캐시된 cover 이미지 바이트 반환. importMap 호출 시 자동으로 best-effort 다운로드되어 캐시됨.
     * 캐시 miss 시 호출자가 먼저 importMap 을 트리거.
     */
    public ImageBlob getCover(String bsr) {
        if (!isValidBsr(bsr)) return null;
        CachedEntry e = cache.get(bsr.toLowerCase());
        if (e == null) return null;
        return e.coverBlob;
    }

    /**
     * 캐시된 cover 이미지 URL (cdn.beatsaver.com/...jpg). 캐시 miss 면 importMap 트리거 후 재조회.
     * Discord 임베드 썸네일처럼 bytes 가 아닌 URL 이 필요할 때 사용.
     */
    public String getCoverUrl(String bsr) {
        if (!isValidBsr(bsr)) return null;
        CachedEntry e = cache.get(bsr.toLowerCase());
        if (e == null || e.meta == null || e.meta.coverUrl == null || e.meta.coverUrl.isEmpty()) {
            importMap(bsr); // 캐시에 채워줌 (실패해도 OK)
            e = cache.get(bsr.toLowerCase());
        }
        if (e == null || e.meta == null) return null;
        String url = e.meta.coverUrl;
        return (url == null || url.isEmpty()) ? null : url;
    }

    // ========================================
    // 경량 커버 (목록 썸네일 아틀라스용 — 전체 import 안 함)
    // ========================================

    /** bsr → coverURL 캐시 (빈 문자열 = negative). */
    private final Map<String, String> coverUrlCache = new ConcurrentHashMap<>();

    /** bsr → 난이도 인덱스 배열 문자열 "[0,3,4]" (없으면 "[]"). resolveCoverUrlsBatch 가 cover 와 함께 채움. */
    private final Map<String, String> diffsCache = new ConcurrentHashMap<>();

    /** bsr → cover 이미지 bytes LRU 캐시 (length 0 = negative). */
    private final Map<String, byte[]> atlasCoverCache = Collections.synchronizedMap(
            new LinkedHashMap<String, byte[]>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 256;
                }
            });

    /**
     * 전체 importMap 없이 BeatSaver /maps/id/{bsr} 만 호출해 coverURL 만 가볍게 획득 (+ 캐시).
     * 이미 풀 import 된 곡이면 캐시된 meta.coverUrl 재사용.
     * 실패(429/네트워크/404)는 캐시 안 함 — transient 실패가 영구 negative 가 되지 않도록(재시도 허용).
     * 다량 조회는 resolveCoverUrlsBatch 사용(개별 20연발 레이트리밋 회피).
     */
    public String resolveCoverUrl(String bsr) {
        if (!isValidBsr(bsr)) return null;
        String key = bsr.toLowerCase();
        String c = coverUrlCache.get(key);
        if (c != null) return c.isEmpty() ? null : c;

        CachedEntry e = cache.get(key);
        if (e != null && e.meta != null && e.meta.coverUrl != null && !e.meta.coverUrl.isEmpty()) {
            coverUrlCache.put(key, e.meta.coverUrl);
            return e.meta.coverUrl;
        }
        try {
            String mapJson = httpGet("https://api.beatsaver.com/maps/id/" + URLEncoder.encode(bsr, StandardCharsets.UTF_8));
            if (mapJson == null) return null; // 404/429/오류 — 캐시 안 함
            String coverUrl = extractFirstString(mapJson, "\"coverURL\"");
            if (coverUrl != null && !coverUrl.isEmpty()) { coverUrlCache.put(key, coverUrl); return coverUrl; }
            return null;
        } catch (Exception ex) {
            log.warn("[BeatSaver] resolveCoverUrl {} failed: {}", bsr, ex.toString());
            return null;
        }
    }

    /**
     * 여러 bsr 의 coverURL 을 BeatSaver /maps/ids 배치(최대 50개/요청)로 한 번에 해석 + 캐시.
     * 개별 /maps/id 20연발이 레이트리밋(429) 맞아 커버가 듬성듬성 누락되던 문제 해결.
     * 200 응답에 없는 id 만 genuine miss 로 negative 캐시. 전송 실패 chunk 는 캐시 안 함(재시도 허용).
     */
    public void resolveCoverUrlsBatch(java.util.List<String> bsrs) {
        if (bsrs == null || bsrs.isEmpty()) return;

        java.util.ArrayList<String> need = new java.util.ArrayList<>();
        for (String b : bsrs) {
            if (b == null || b.isBlank() || !isValidBsr(b)) continue;
            String key = b.toLowerCase();
            if (coverUrlCache.containsKey(key) && diffsCache.containsKey(key)) continue;
            if (!need.contains(b)) need.add(b);
        }
        if (need.isEmpty()) return;

        final int CHUNK = 50;
        for (int start = 0; start < need.size(); start += CHUNK) {
            int end = Math.min(start + CHUNK, need.size());
            java.util.List<String> chunk = need.subList(start, end);
            String ids = String.join(",", chunk); // bsr 은 hex 검증됨 — 콤마는 path 구분자라 인코딩 X
            String json;
            try {
                json = httpGet("https://api.beatsaver.com/maps/ids/" + ids);
            } catch (Exception ex) {
                log.warn("[BeatSaver] maps/ids batch failed ({} ids): {}", chunk.size(), ex.toString());
                json = null;
            }
            if (json == null) continue; // 전송 실패 — 캐시 안 함
            for (String b : chunk) {
                String key = b.toLowerCase();
                String obj = extractIdObject(json, b);
                if (obj == null) { // 200 인데 id 없음 → genuine miss
                    coverUrlCache.put(key, "");
                    diffsCache.put(key, "[]");
                    continue;
                }
                String coverUrl = extractFirstString(obj, "\"coverURL\"");
                coverUrlCache.put(key, coverUrl == null ? "" : coverUrl);
                diffsCache.put(key, diffsListToArrayStr(parseSearchDiffs(obj)));
            }
        }
    }

    /** 배치로 캐시된 난이도 인덱스 배열 문자열 "[0,3,4]" (없으면 "[]"). resolveCoverUrlsBatch 가 채움. */
    public String getDiffsIndexArray(String bsr) {
        if (bsr == null) return "[]";
        String v = diffsCache.get(bsr.toLowerCase());
        return v == null ? "[]" : v;
    }

    /** 배치 응답({"id":{map},...})에서 특정 id 의 map object substring 추출 (없으면 null). */
    private String extractIdObject(String batchJson, String bsr) {
        int k = batchJson.indexOf("\"" + bsr + "\"");
        if (k < 0) k = batchJson.toLowerCase().indexOf("\"" + bsr.toLowerCase() + "\"");
        if (k < 0) return null;
        int objStart = batchJson.indexOf('{', k);
        if (objStart < 0) return null;
        int objEnd = findObjectEnd(batchJson, objStart);
        if (objEnd < 0) return null;
        return batchJson.substring(objStart, objEnd + 1);
    }

    private static String diffsListToArrayStr(java.util.List<Integer> diffs) {
        if (diffs == null || diffs.isEmpty()) return "[]";
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < diffs.size(); i++) { if (i > 0) b.append(','); b.append(diffs.get(i)); }
        return b.append("]").toString();
    }

    /**
     * 목록 썸네일용 cover 바이트 — 전체 import 없이 획득.
     * 순서: atlas 캐시 → 풀 import cover → coverURL(캐시/해석) → cdn 다운로드.
     * 실패는 캐시하지 않음(positive-only) — transient 실패가 영구 검은칸이 되지 않도록.
     */
    public byte[] getCoverBytesLight(String bsr) {
        if (!isValidBsr(bsr)) return null;
        String key = bsr.toLowerCase();
        byte[] cached = atlasCoverCache.get(key);
        if (cached != null && cached.length > 0) return cached;

        ImageBlob blob = getCover(bsr);
        if (blob != null && blob.bytes != null && blob.bytes.length > 0) {
            atlasCoverCache.put(key, blob.bytes);
            return blob.bytes;
        }
        String url = resolveCoverUrl(bsr);
        if (url == null) return null;
        try {
            ImageBlob b = downloadCover(url);
            if (b != null && b.bytes != null && b.bytes.length > 0) {
                atlasCoverCache.put(key, b.bytes);
                return b.bytes;
            }
        } catch (Exception ex) {
            log.warn("[BeatSaver] getCoverBytesLight {} download failed: {}", bsr, ex.toString());
        }
        return null;
    }

    // ========================================
    // BeatSaver 검색
    // ========================================

    /**
     * BeatSaver /search/text/{page} 호출 후 월드 친화 평면 JSON 으로 변환.
     * 호출자 (HTTP handler) 가 per-IP slot 캐시 시드용으로 SearchResult.bsrList 사용.
     */
    public SearchResult search(String query, int page) {
        if (query == null || query.isBlank()) return SearchResult.error(400, "q_required");
        if (page < 0 || page > 50) return SearchResult.error(400, "bad_page");

        // 캐시 hit — 여러 클라이언트가 같은 query 로 동시에 검색해도 BeatSaver 한 번만 호출.
        String cacheKey = query.toLowerCase() + "|" + page;
        CachedSearch cached = searchCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.timestamp < SEARCH_CACHE_TTL_MS) {
            return SearchResult.ok(cached.json, cached.bsrList);
        }

        try {
            String url = "https://api.beatsaver.com/search/text/" + page
                    + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            String bsApi = httpGet(url);
            if (bsApi == null) return SearchResult.error(502, "search_upstream_failed");

            List<SearchHit> hits = parseSearchDocs(bsApi);

            // query 가 유효한 BSR 코드면 /maps/id 로 정확 조회해 최상단 고정 (page 0 한정).
            // 제목 검색으로는 안 잡히는 경우(또는 하위 노출)라도 코드로 곡이 존재하면 맨 앞에 노출.
            if (page == 0 && isValidBsr(query)) {
                String idUrl = "https://api.beatsaver.com/maps/id/"
                        + URLEncoder.encode(query.toLowerCase(), StandardCharsets.UTF_8);
                String idJson = httpGet(idUrl);
                if (idJson != null) {
                    SearchHit exact = parseSearchDoc(idJson);
                    if (exact != null && exact.bsr != null && !exact.bsr.isEmpty()) {
                        // 텍스트 결과에 이미 포함돼 있으면 제거 후 맨 앞에 삽입 (중복 방지)
                        hits.removeIf(h -> h.bsr != null && h.bsr.equalsIgnoreCase(exact.bsr));
                        hits.add(0, exact);
                    }
                }
            }

            StringBuilder b = new StringBuilder(16 * 1024);
            b.append("{\"ok\":true");
            b.append(",\"page\":").append(page);
            b.append(",\"count\":").append(hits.size());
            b.append(",\"results\":[");
            for (int i = 0; i < hits.size(); i++) {
                if (i > 0) b.append(',');
                appendSearchHitJson(b, hits.get(i));
            }
            b.append("]}");

            List<String> bsrList = new ArrayList<>(hits.size());
            for (SearchHit h : hits) bsrList.add(h.bsr);
            String responseJson = b.toString();
            searchCache.put(cacheKey, new CachedSearch(now, responseJson, bsrList));
            return SearchResult.ok(responseJson, bsrList);
        } catch (Exception e) {
            log.error("[BeatSaver] search failed q='{}' page={}", query, page, e);
            return SearchResult.error(500, "internal");
        }
    }

    private void appendSearchHitJson(StringBuilder b, SearchHit h) {
        b.append("{");
        b.append("\"bsr\":").append(jsonString(h.bsr));
        b.append(",\"name\":").append(jsonString(h.name));
        b.append(",\"subtitle\":").append(jsonString(h.subtitle));
        b.append(",\"author\":").append(jsonString(h.author));
        b.append(",\"mapper\":").append(jsonString(h.mapper));
        b.append(",\"bpm\":").append(formatNum(h.bpm));
        b.append(",\"duration\":").append(formatNum(h.duration));
        b.append(",\"diffs\":[");
        for (int i = 0; i < h.diffs.size(); i++) {
            if (i > 0) b.append(',');
            b.append(h.diffs.get(i));
        }
        b.append("]");
        b.append(",\"ranked\":").append(h.ranked ? "true" : "false");
        b.append(",\"blRanked\":").append(h.blRanked ? "true" : "false");
        b.append("}");
    }

    /** "docs":[ ... ] 를 walk 하여 각 map object 를 SearchHit 으로 변환. */
    private List<SearchHit> parseSearchDocs(String json) {
        List<SearchHit> out = new ArrayList<>();
        int docsKey = json.indexOf("\"docs\"");
        if (docsKey < 0) return out;
        int arrStart = json.indexOf('[', docsKey);
        int arrEnd = findArrayEnd(json, docsKey);
        if (arrStart < 0 || arrEnd < 0) return out;

        int cursor = arrStart + 1;
        while (cursor < arrEnd && out.size() < 50) {
            int objStart = json.indexOf('{', cursor);
            if (objStart < 0 || objStart >= arrEnd) break;
            int objEnd = findObjectEnd(json, objStart);
            if (objEnd < 0 || objEnd > arrEnd) break;

            SearchHit h = parseSearchDoc(json.substring(objStart, objEnd + 1));
            if (h != null) out.add(h);
            cursor = objEnd + 1;
        }
        return out;
    }

    /** docs[i] 객체 substring → SearchHit. metadata + versions[0].diffs + ranked flags 추출. */
    private SearchHit parseSearchDoc(String doc) {
        String id = extractFirstString(doc, "\"id\"");
        if (id == null || id.isEmpty()) return null;

        // metadata 객체 안에서 곡명/저자 등 추출 (metadata 밖에도 같은 키가 있을 수 있어 metadata 범위로 좁힘)
        String name = null, subtitle = null, author = null, mapper = null;
        double bpm = 0d, duration = 0d;
        int metaIdx = doc.indexOf("\"metadata\"");
        if (metaIdx >= 0) {
            int metaStart = doc.indexOf('{', metaIdx);
            int metaEnd = metaStart >= 0 ? findObjectEnd(doc, metaStart) : -1;
            if (metaStart >= 0 && metaEnd > metaStart) {
                String meta = doc.substring(metaStart, metaEnd + 1);
                name = extractFirstString(meta, "\"songName\"");
                subtitle = extractFirstString(meta, "\"songSubName\"");
                author = extractFirstString(meta, "\"songAuthorName\"");
                mapper = extractFirstString(meta, "\"levelAuthorName\"");
                Double b1 = extractFirstDouble(meta, "\"bpm\"");
                if (b1 != null) bpm = b1;
                Double d1 = extractFirstDouble(meta, "\"duration\"");
                if (d1 != null) duration = d1;
            }
        }

        boolean ranked = extractFirstBool(doc, "\"ranked\"");
        boolean blRanked = extractFirstBool(doc, "\"blRanked\"");

        List<Integer> diffs = parseSearchDiffs(doc);

        SearchHit h = new SearchHit();
        h.bsr = id;
        h.name = name == null ? "" : name;
        h.subtitle = subtitle == null ? "" : subtitle;
        h.author = author == null ? "" : author;
        h.mapper = mapper == null ? "" : mapper;
        h.bpm = bpm;
        h.duration = duration;
        h.ranked = ranked;
        h.blRanked = blRanked;
        h.diffs = diffs;
        return h;
    }

    /** versions[0].diffs[].difficulty 를 표준 인덱스 (0=Easy..4=ExpertPlus) 집합으로 변환. */
    private List<Integer> parseSearchDiffs(String doc) {
        List<Integer> out = new ArrayList<>();
        int verKey = doc.indexOf("\"versions\"");
        if (verKey < 0) return out;
        int vArrStart = doc.indexOf('[', verKey);
        int vArrEnd = findArrayEnd(doc, verKey);
        if (vArrStart < 0 || vArrEnd < 0) return out;

        // versions[0] 만 사용
        int verObjStart = doc.indexOf('{', vArrStart);
        if (verObjStart < 0 || verObjStart >= vArrEnd) return out;
        int verObjEnd = findObjectEnd(doc, verObjStart);
        if (verObjEnd < 0 || verObjEnd > vArrEnd) return out;
        String version = doc.substring(verObjStart, verObjEnd + 1);

        int diffsKey = version.indexOf("\"diffs\"");
        if (diffsKey < 0) return out;
        int dArrStart = version.indexOf('[', diffsKey);
        int dArrEnd = findArrayEnd(version, diffsKey);
        if (dArrStart < 0 || dArrEnd < 0) return out;

        boolean[] seen = new boolean[5];
        int cursor = dArrStart + 1;
        while (cursor < dArrEnd) {
            int dObjStart = version.indexOf('{', cursor);
            if (dObjStart < 0 || dObjStart >= dArrEnd) break;
            int dObjEnd = findObjectEnd(version, dObjStart);
            if (dObjEnd < 0 || dObjEnd > dArrEnd) break;

            String diffObj = version.substring(dObjStart, dObjEnd + 1);
            String diffName = extractFirstString(diffObj, "\"difficulty\"");
            Integer std = diffNameToStd(diffName);
            if (std != null && !seen[std]) {
                seen[std] = true;
                out.add(std);
            }
            cursor = dObjEnd + 1;
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static Integer diffNameToStd(String name) {
        if (name == null) return null;
        switch (name) {
            case "Easy": return 0;
            case "Normal": return 1;
            case "Hard": return 2;
            case "Expert": return 3;
            case "ExpertPlus": return 4;
            default: return null;
        }
    }

    /**
     * playlist DB 의 difficulties JSON ([{"characteristic":..,"name":"ExpertPlus"},..]) 안의 모든 name 을
     * 표준 인덱스(0=Easy..4=ExpertPlus) 집합으로 모아 JSON 배열 문자열 "[0,3,4]" 로 반환. 비어있으면 "[]".
     * 월드 row 난이도 인디케이터가 search 와 동일 포맷(인덱스 배열)으로 파싱.
     */
    public static String diffsJsonToIndexArray(String difficultiesJson) {
        if (difficultiesJson == null || difficultiesJson.isBlank()) return "[]";
        boolean[] seen = new boolean[5];
        int i = 0;
        while (true) {
            int k = difficultiesJson.indexOf("\"name\"", i);
            if (k < 0) break;
            String nm = extractFirstString(difficultiesJson.substring(k), "\"name\"");
            Integer std = diffNameToStd(nm);
            if (std != null) seen[std] = true;
            i = k + 6;
        }
        StringBuilder b = new StringBuilder("[");
        boolean first = true;
        for (int d = 0; d < 5; d++) {
            if (seen[d]) { if (!first) b.append(','); b.append(d); first = false; }
        }
        return b.append("]").toString();
    }

    private static boolean extractFirstBool(String s, String key) {
        int k = s.indexOf(key);
        if (k < 0) return false;
        int colon = s.indexOf(':', k);
        if (colon < 0) return false;
        int i = colon + 1;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i + 4 <= s.length() && "true".equals(s.substring(i, i + 4));
    }

    /** coverUrl (보통 cdn.beatsaver.com 의 .jpg) 다운로드. content-type 응답 헤더 그대로 보관. */
    private ImageBlob downloadCover(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "UraSaberBot/0.1 (+https://api.ura-s.org)")
                .header("Accept", "image/*")
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            log.warn("[BeatSaver] cover GET {} → {}", url, resp.statusCode());
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        try (InputStream in = resp.body()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_COVER_BYTES) {
                    log.warn("[BeatSaver] cover exceeded max bytes: {}", url);
                    return null;
                }
                out.write(buf, 0, n);
            }
        }
        // 응답 헤더에서 content-type 가져오기 — 없으면 URL 확장자로 추정
        String contentType = resp.headers().firstValue("Content-Type").orElse(null);
        if (contentType == null || contentType.isBlank()) {
            String lower = url.toLowerCase();
            if (lower.endsWith(".png")) contentType = "image/png";
            else if (lower.endsWith(".webp")) contentType = "image/webp";
            else contentType = "image/jpeg";
        }
        return new ImageBlob(out.toByteArray(), contentType);
    }

    // ========================================
    // HTTP helpers
    // ========================================

    private String httpGet(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "UraSaberBot/0.1 (+https://api.ura-s.org)")
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) return null;
        if (resp.statusCode() >= 400) {
            log.warn("[BeatSaver] GET {} → {}", url, resp.statusCode());
            return null;
        }
        return resp.body();
    }

    /**
     * BeatSaver zip 을 디스크 임시 파일로 스트림. heap 에 전체 byte[] 안 올림.
     * 반환 Path 는 caller 가 finally 에서 삭제 책임.
     */
    private Path httpStreamToTempFile(String url, long maxBytes) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "UraSaberBot/0.1 (+https://api.ura-s.org)")
                .GET()
                .build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            log.warn("[BeatSaver] GET stream {} → {}", url, resp.statusCode());
            try { resp.body().close(); } catch (Exception ignore) {}
            return null;
        }
        Path tmp = Files.createTempFile("urasaber-zip-", ".zip");
        boolean ok = false;
        try (InputStream in = resp.body();
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(tmp))) {
            byte[] buf = new byte[16 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > maxBytes) {
                    log.warn("[BeatSaver] zip exceeded max bytes: {} (limit={})", url, maxBytes);
                    return null;
                }
                out.write(buf, 0, n);
            }
            ok = true;
            return tmp;
        } finally {
            if (!ok) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            }
        }
    }

    /**
     * zip 을 단일 패스로 스트림 처리. small entry (.dat — info.dat + 난이도) 는 메모리에,
     * audio (.egg/.ogg) 는 영구 디스크 캐시로 직접 스트림 → heap 에 audio bytes 안 올림.
     * 다른 entry (cover.jpg 등) 는 스킵.
     */
    private ImportedFiles processZipStreaming(Path zipFile, String key) throws IOException {
        ImportedFiles out = new ImportedFiles();
        Files.createDirectories(audioCacheDir); // best-effort, idempotent

        try (ZipInputStream zis = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zipFile)))) {
            ZipEntry e;
            byte[] buf = new byte[16 * 1024];
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) { zis.closeEntry(); continue; }

                // 보안: 경로 traversal 방지 — 디렉토리 평탄화 (basename 만 사용)
                String name = e.getName();
                int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
                if (slash >= 0) name = name.substring(slash + 1);
                String lower = name.toLowerCase();

                if ((lower.endsWith(".egg") || lower.endsWith(".ogg")) && out.audioPath == null) {
                    // 첫 audio entry 를 디스크에 바로 스트림.
                    Path audioOut = audioCacheDir.resolve(key + ".ogg");
                    boolean audioOk = false;
                    try (OutputStream fos = new BufferedOutputStream(Files.newOutputStream(audioOut))) {
                        long total = 0;
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            total += n;
                            if (total > MAX_AUDIO_BYTES) {
                                log.warn("[BeatSaver key={}] audio entry exceeded max bytes ({}MB)",
                                        key, MAX_AUDIO_BYTES / (1024 * 1024));
                                break;
                            }
                            fos.write(buf, 0, n);
                        }
                        if (total > 0 && total <= MAX_AUDIO_BYTES) audioOk = true;
                    }
                    if (audioOk) {
                        out.audioPath = audioOut;
                        out.audioContentType = "audio/ogg";
                    } else {
                        try { Files.deleteIfExists(audioOut); } catch (Exception ignore) {}
                    }
                } else if (lower.endsWith(".dat")) {
                    // info.dat / 난이도 .dat — 메모리에 (보통 수십 KB ~ 수백 KB).
                    ByteArrayOutputStream baos = new ByteArrayOutputStream(8 * 1024);
                    long total = 0;
                    boolean tooBig = false;
                    int n;
                    while ((n = zis.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_SMALL_ENTRY_BYTES) { tooBig = true; break; }
                        baos.write(buf, 0, n);
                    }
                    if (!tooBig) {
                        out.smallFiles.put(name, baos.toByteArray());
                    } else {
                        log.warn("[BeatSaver key={}] .dat entry {} too large, skipped", key, name);
                    }
                }
                // else: jpg/png cover/license 등 — 무시 (cover 는 coverUrl 로 별도 다운로드).

                zis.closeEntry();
            }
        }
        return out;
    }

    /** zip 스트림 처리 결과 — small entry 메모리 맵 + audio 의 디스크 path. */
    private static final class ImportedFiles {
        final Map<String, byte[]> smallFiles = new HashMap<>();
        Path audioPath;            // nullable
        String audioContentType;   // nullable
    }

    private static byte[] findEntry(Map<String, byte[]> files, String name) {
        byte[] v = files.get(name);
        if (v != null) return v;
        // 대소문자 무시 fallback
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    // ========================================
    // Info.dat / .dat 파서 (정규식 기반 가벼운 구현 — 외부 의존성 없이)
    // ========================================

    private static class DiffMeta {
        String characteristic = "Standard";
        int difficultyRank = 1;
        String label = "Easy";
        String filename = "";
        float njs = 10f;
        float njsOffset = 0f;
    }

    private static class InfoParsed {
        String songName, songSubtitle, songAuthor, mapper, songFilename;
        double bpm;
        double previewStart, previewDuration;
        final List<DiffMeta> diffs = new ArrayList<>();
    }

    /**
     * Info.dat 의 핵심 필드 추출. v2 (_songName 등) 와 v4 (songName 등) 모두 흔히 보이는
     * BeatSaver 파일들을 위해 두 접두사 모두 시도.
     */
    private InfoParsed parseInfoDat(String s) {
        InfoParsed p = new InfoParsed();
        p.songName = firstNonNull(extractFirstString(s, "\"_songName\""), extractFirstString(s, "\"songName\""));
        p.songSubtitle = firstNonNull(extractFirstString(s, "\"_songSubName\""), extractFirstString(s, "\"songSubName\""));
        p.songAuthor = firstNonNull(extractFirstString(s, "\"_songAuthorName\""), extractFirstString(s, "\"songAuthorName\""));
        p.mapper = firstNonNull(extractFirstString(s, "\"_levelAuthorName\""), extractFirstString(s, "\"levelAuthorName\""));
        p.songFilename = firstNonNull(extractFirstString(s, "\"_songFilename\""), extractFirstString(s, "\"songFilename\""));
        Double bpm = firstNonNull(extractFirstDouble(s, "\"_beatsPerMinute\""), extractFirstDouble(s, "\"beatsPerMinute\""));
        if (bpm != null) p.bpm = bpm;
        Double ps = firstNonNull(extractFirstDouble(s, "\"_previewStartTime\""), extractFirstDouble(s, "\"previewStartTime\""));
        if (ps != null) p.previewStart = ps;
        Double pd = firstNonNull(extractFirstDouble(s, "\"_previewDuration\""), extractFirstDouble(s, "\"previewDuration\""));
        if (pd != null) p.previewDuration = pd;

        // _difficultyBeatmapSets[] / difficultyBeatmapSets[]: characteristic 별 난이도 목록
        // 정규식 대신 간단 토크나이저: "_difficulty": "Hard", "_difficultyRank": 5, "_beatmapFilename": "Hard.dat", ...
        // 거대한 정규식 1개로 처리: characteristic 그룹을 먼저 찾고, 그 내부에서 난이도 entry 를 차례로 추출.
        // BeatSaver Info.dat 는 사람이 만든 게 아니라 mapper 도구가 생성하므로 형식 일관성이 높음.
        int idx = 0;
        while (true) {
            // characteristic name 위치 찾기
            int chPos = indexOfKey(s, "\"_beatmapCharacteristicName\"", idx);
            if (chPos < 0) chPos = indexOfKey(s, "\"beatmapCharacteristicName\"", idx);
            if (chPos < 0) break;
            String ch = readStringValue(s, chPos);
            int setStart = chPos;
            // 다음 characteristic 위치를 끝으로 잡기
            int nextChPos = indexOfKey(s, "\"_beatmapCharacteristicName\"", chPos + 1);
            int nextChPos2 = indexOfKey(s, "\"beatmapCharacteristicName\"", chPos + 1);
            int nextEnd = nextChPos < 0 ? nextChPos2 : (nextChPos2 < 0 ? nextChPos : Math.min(nextChPos, nextChPos2));
            int setEnd = nextEnd < 0 ? s.length() : nextEnd;

            // 이 characteristic 구간 내에서 _difficultyBeatmaps 목록 파싱
            int diffIdx = setStart;
            while (true) {
                int dPos = indexOfKey(s, "\"_difficulty\"", diffIdx);
                if (dPos < 0) dPos = indexOfKey(s, "\"difficulty\"", diffIdx);
                if (dPos < 0 || dPos >= setEnd) break;
                DiffMeta d = new DiffMeta();
                d.characteristic = ch == null ? "Standard" : ch;
                d.label = readStringValue(s, dPos);
                Integer rank = readIntValueNearKey(s, "\"_difficultyRank\"", dPos, setEnd);
                if (rank == null) rank = readIntValueNearKey(s, "\"difficultyRank\"", dPos, setEnd);
                if (rank != null) d.difficultyRank = rank;
                String fn = readStringValueNearKey(s, "\"_beatmapFilename\"", dPos, setEnd);
                if (fn == null) fn = readStringValueNearKey(s, "\"beatmapFilename\"", dPos, setEnd);
                if (fn == null) fn = readStringValueNearKey(s, "\"beatmapDataFilename\"", dPos, setEnd);
                if (fn != null) d.filename = fn;
                Double njs = readDoubleValueNearKey(s, "\"_noteJumpMovementSpeed\"", dPos, setEnd);
                if (njs == null) njs = readDoubleValueNearKey(s, "\"noteJumpMovementSpeed\"", dPos, setEnd);
                if (njs != null) d.njs = njs.floatValue();
                Double off = readDoubleValueNearKey(s, "\"_noteJumpStartBeatOffset\"", dPos, setEnd);
                if (off == null) off = readDoubleValueNearKey(s, "\"noteJumpStartBeatOffset\"", dPos, setEnd);
                if (off != null) d.njsOffset = off.floatValue();

                if (!d.filename.isEmpty()) p.diffs.add(d);
                diffIdx = dPos + 1;
            }

            idx = setEnd;
        }
        return p;
    }

    private static class DiffParsed {
        DiffMeta meta;
        // notes: 각 노트 = [t, x, y, c, d] (t=초, x=line, y=layer, c=color/type, d=direction)
        final List<float[]> notes = new ArrayList<>();
        // bombs: type=3
        // walls: [t, x, y, dur, w, h]
        final List<float[]> walls = new ArrayList<>();
        // chains: [t, c, sx, sy, dir, tailT, ex, ey, segs, squish]
        final List<float[]> chains = new ArrayList<>();
        // arcs (sliders): [headT, c, hx, hy, hDir, hMu, tailT, tx, ty, tDir, tMu, midMode]
        final List<float[]> arcs = new ArrayList<>();
    }

    /**
     * 난이도 .dat 파싱. v2 (_notes 등) 와 v3 (colorNotes 등) 둘 다 자동 판별.
     * v4 도 colorNotes 패턴이 같아 통과되는 케이스가 많음.
     */
    private DiffParsed parseDifficultyDat(String s, float beatPerSec) {
        DiffParsed d = new DiffParsed();
        boolean isV3 = s.contains("\"colorNotes\"") || s.contains("\"version\":\"3");

        if (isV3) {
            parseV3Notes(s, beatPerSec, d);
            parseV3Bombs(s, beatPerSec, d);
            parseV3Obstacles(s, beatPerSec, d);
            parseV3BurstSliders(s, beatPerSec, d);
            parseV3Sliders(s, beatPerSec, d);
        } else {
            parseV2Notes(s, beatPerSec, d);
            parseV2Obstacles(s, beatPerSec, d);
        }
        // 노트는 파싱 순서가 시간순이 아닐 수 있음:
        //  - v2 .dat 은 정렬 보장 없음 (같은 위치 연타 구간 순서 뒤섞임)
        //  - v3 는 parseV3Bombs 가 colorNotes 뒤에 폭탄을 append → 항상 시간 역순 혼입
        // 월드 SpawnNotes 는 시간 오름차순 단일 커서 전제 → 출력 전 반드시 시간순 정렬.
        // (Collections.sort 는 안정 정렬이라 같은 비트 윈도우 페어 순서도 보존됨)
        java.util.Collections.sort(d.notes, (a, b) -> Float.compare(a[0], b[0]));
        return d;
    }

    // v2 _notes : "_time": ?, "_lineIndex": ?, "_lineLayer": ?, "_type": ?, "_cutDirection": ?
    private void parseV2Notes(String s, float bps, DiffParsed d) {
        int arrStart = indexOfKey(s, "\"_notes\"", 0);
        if (arrStart < 0) return;
        int arrEnd = findArrayEnd(s, arrStart);
        if (arrEnd < 0) return;
        int idx = arrStart;
        while (idx < arrEnd && d.notes.size() < MAX_NOTES_PER_DIFF) {
            int obj = s.indexOf('{', idx);
            if (obj < 0 || obj >= arrEnd) break;
            int objEnd = findObjectEnd(s, obj);
            if (objEnd < 0 || objEnd > arrEnd) break;
            Double t = readDoubleValueNearKey(s, "\"_time\"", obj, objEnd);
            Integer x = readIntValueNearKey(s, "\"_lineIndex\"", obj, objEnd);
            Integer y = readIntValueNearKey(s, "\"_lineLayer\"", obj, objEnd);
            Integer ty = readIntValueNearKey(s, "\"_type\"", obj, objEnd);
            Integer dir = readIntValueNearKey(s, "\"_cutDirection\"", obj, objEnd);
            if (t != null && x != null && y != null && ty != null && dir != null) {
                d.notes.add(new float[]{ t.floatValue(), x, y, ty, dir });
            }
            idx = objEnd + 1;
        }
    }

    private void parseV2Obstacles(String s, float bps, DiffParsed d) {
        int arrStart = indexOfKey(s, "\"_obstacles\"", 0);
        if (arrStart < 0) return;
        int arrEnd = findArrayEnd(s, arrStart);
        if (arrEnd < 0) return;
        int idx = arrStart;
        while (idx < arrEnd) {
            int obj = s.indexOf('{', idx);
            if (obj < 0 || obj >= arrEnd) break;
            int objEnd = findObjectEnd(s, obj);
            if (objEnd < 0 || objEnd > arrEnd) break;
            Double t = readDoubleValueNearKey(s, "\"_time\"", obj, objEnd);
            Integer x = readIntValueNearKey(s, "\"_lineIndex\"", obj, objEnd);
            Integer ty = readIntValueNearKey(s, "\"_type\"", obj, objEnd);
            Double dur = readDoubleValueNearKey(s, "\"_duration\"", obj, objEnd);
            Integer w = readIntValueNearKey(s, "\"_width\"", obj, objEnd);
            if (t != null && x != null && dur != null && w != null) {
                int yPos = (ty != null && ty == 1) ? 2 : 0;  // 1=Crouch (위쪽 벽), 0=Full
                int height = (ty != null && ty == 1) ? 3 : 5;
                d.walls.add(new float[]{
                        t.floatValue(), x, yPos, dur.floatValue(), w, height
                });
            }
            idx = objEnd + 1;
        }
    }

    // v3 colorNotes : "b": beat, "x": line, "y": layer, "c": color, "d": dir, "a": angleOffset
    private void parseV3Notes(String s, float bps, DiffParsed d) {
        int arrStart = indexOfKey(s, "\"colorNotes\"", 0);
        if (arrStart < 0) return;
        int arrEnd = findArrayEnd(s, arrStart);
        if (arrEnd < 0) return;
        int idx = arrStart;
        while (idx < arrEnd && d.notes.size() < MAX_NOTES_PER_DIFF) {
            int obj = s.indexOf('{', idx);
            if (obj < 0 || obj >= arrEnd) break;
            int objEnd = findObjectEnd(s, obj);
            if (objEnd < 0 || objEnd > arrEnd) break;
            Double t = readDoubleValueNearKey(s, "\"b\"", obj, objEnd);
            Integer x = readIntValueNearKey(s, "\"x\"", obj, objEnd);
            Integer y = readIntValueNearKey(s, "\"y\"", obj, objEnd);
            Integer c = readIntValueNearKey(s, "\"c\"", obj, objEnd);
            Integer dir = readIntValueNearKey(s, "\"d\"", obj, objEnd);
            if (t != null && x != null && y != null && c != null && dir != null) {
                d.notes.add(new float[]{ t.floatValue(), x, y, c, dir });
            }
            idx = objEnd + 1;
        }
    }

    private void parseV3Bombs(String s, float bps, DiffParsed d) {
        int arrStart = indexOfKey(s, "\"bombNotes\"", 0);
        if (arrStart < 0) return;
        int arrEnd = findArrayEnd(s, arrStart);
        if (arrEnd < 0) return;
        int idx = arrStart;
        while (idx < arrEnd && d.notes.size() < MAX_NOTES_PER_DIFF) {
            int obj = s.indexOf('{', idx);
            if (obj < 0 || obj >= arrEnd) break;
            int objEnd = findObjectEnd(s, obj);
            if (objEnd < 0 || objEnd > arrEnd) break;
            Double t = readDoubleValueNearKey(s, "\"b\"", obj, objEnd);
            Integer x = readIntValueNearKey(s, "\"x\"", obj, objEnd);
            Integer y = readIntValueNearKey(s, "\"y\"", obj, objEnd);
            if (t != null && x != null && y != null) {
                d.notes.add(new float[]{ t.floatValue(), x, y, 3 /*bomb*/, 8 /*any*/ });
            }
            idx = objEnd + 1;
        }
    }

    private void parseV3Obstacles(String s, float bps, DiffParsed d) {
        int arrStart = indexOfKey(s, "\"obstacles\"", 0);
        if (arrStart < 0) return;
        int arrEnd = findArrayEnd(s, arrStart);
        if (arrEnd < 0) return;
        int idx = arrStart;
        while (idx < arrEnd) {
            int obj = s.indexOf('{', idx);
            if (obj < 0 || obj >= arrEnd) break;
            int objEnd = findObjectEnd(s, obj);
            if (objEnd < 0 || objEnd > arrEnd) break;
            Double t = readDoubleValueNearKey(s, "\"b\"", obj, objEnd);
            Integer x = readIntValueNearKey(s, "\"x\"", obj, objEnd);
            Integer y = readIntValueNearKey(s, "\"y\"", obj, objEnd);
            Double dur = readDoubleValueNearKey(s, "\"d\"", obj, objEnd);
            Integer w = readIntValueNearKey(s, "\"w\"", obj, objEnd);
            Integer h = readIntValueNearKey(s, "\"h\"", obj, objEnd);
            if (t != null && x != null && y != null && dur != null && w != null && h != null) {
                d.walls.add(new float[]{ t.floatValue(), x, y, dur.floatValue(), w, h });
            }
            idx = objEnd + 1;
        }
    }

    private void parseV3BurstSliders(String s, float bps, DiffParsed d) {
        int arrStart = indexOfKey(s, "\"burstSliders\"", 0);
        if (arrStart < 0) return;
        int arrEnd = findArrayEnd(s, arrStart);
        if (arrEnd < 0) return;
        int idx = arrStart;
        while (idx < arrEnd) {
            int obj = s.indexOf('{', idx);
            if (obj < 0 || obj >= arrEnd) break;
            int objEnd = findObjectEnd(s, obj);
            if (objEnd < 0 || objEnd > arrEnd) break;
            Double t = readDoubleValueNearKey(s, "\"b\"", obj, objEnd);
            Integer c = readIntValueNearKey(s, "\"c\"", obj, objEnd);
            Integer sx = readIntValueNearKey(s, "\"x\"", obj, objEnd);
            Integer sy = readIntValueNearKey(s, "\"y\"", obj, objEnd);
            Integer dir = readIntValueNearKey(s, "\"d\"", obj, objEnd);
            Double tailT = readDoubleValueNearKey(s, "\"tb\"", obj, objEnd);
            Integer tx = readIntValueNearKey(s, "\"tx\"", obj, objEnd);
            Integer ty = readIntValueNearKey(s, "\"ty\"", obj, objEnd);
            Integer segs = readIntValueNearKey(s, "\"sc\"", obj, objEnd);
            Double squish = readDoubleValueNearKey(s, "\"s\"", obj, objEnd);
            if (t != null && c != null && sx != null && sy != null && dir != null
                    && tailT != null && tx != null && ty != null && segs != null) {
                d.chains.add(new float[]{
                        t.floatValue(), c, sx, sy, dir,
                        tailT.floatValue(), tx, ty, segs, squish == null ? 1f : squish.floatValue()
                });
            }
            idx = objEnd + 1;
        }
    }

    private void parseV3Sliders(String s, float bps, DiffParsed d) {
        int arrStart = indexOfKey(s, "\"sliders\"", 0);
        if (arrStart < 0) return;
        int arrEnd = findArrayEnd(s, arrStart);
        if (arrEnd < 0) return;
        int idx = arrStart;
        while (idx < arrEnd) {
            int obj = s.indexOf('{', idx);
            if (obj < 0 || obj >= arrEnd) break;
            int objEnd = findObjectEnd(s, obj);
            if (objEnd < 0 || objEnd > arrEnd) break;
            Double headT = readDoubleValueNearKey(s, "\"b\"", obj, objEnd);
            Integer c = readIntValueNearKey(s, "\"c\"", obj, objEnd);
            Integer hx = readIntValueNearKey(s, "\"x\"", obj, objEnd);
            Integer hy = readIntValueNearKey(s, "\"y\"", obj, objEnd);
            Integer hDir = readIntValueNearKey(s, "\"d\"", obj, objEnd);
            Double hMu = readDoubleValueNearKey(s, "\"mu\"", obj, objEnd);
            Double tailT = readDoubleValueNearKey(s, "\"tb\"", obj, objEnd);
            Integer tx = readIntValueNearKey(s, "\"tx\"", obj, objEnd);
            Integer ty = readIntValueNearKey(s, "\"ty\"", obj, objEnd);
            Integer tDir = readIntValueNearKey(s, "\"tc\"", obj, objEnd);
            Double tMu = readDoubleValueNearKey(s, "\"tmu\"", obj, objEnd);
            Integer midMode = readIntValueNearKey(s, "\"m\"", obj, objEnd);
            if (headT != null && c != null && hx != null && hy != null && hDir != null
                    && tailT != null && tx != null && ty != null && tDir != null) {
                d.arcs.add(new float[]{
                        headT.floatValue(), c, hx, hy, hDir, hMu == null ? 1f : hMu.floatValue(),
                        tailT.floatValue(), tx, ty, tDir, tMu == null ? 1f : tMu.floatValue(),
                        midMode == null ? 0 : midMode
                });
            }
            idx = objEnd + 1;
        }
    }

    // ========================================
    // Response JSON 빌더
    // ========================================

    private String buildResponseJson(String bsr, InfoParsed info, String coverUrl, String audioUrl,
                                     Double metaDuration, List<DiffParsed> diffs) {
        StringBuilder b = new StringBuilder(64 * 1024);
        b.append("{\"ok\":true");
        b.append(",\"bsr\":").append(jsonString(bsr));
        b.append(",\"song\":{");
        b.append("\"name\":").append(jsonString(info.songName));
        b.append(",\"subtitle\":").append(jsonString(info.songSubtitle));
        b.append(",\"author\":").append(jsonString(info.songAuthor));
        b.append(",\"mapper\":").append(jsonString(info.mapper));
        b.append(",\"bpm\":").append(formatNum(info.bpm));
        double durationSec = metaDuration != null ? metaDuration : 0.0;
        b.append(",\"duration\":").append(formatNum(durationSec));
        b.append(",\"previewStart\":").append(formatNum(info.previewStart));
        b.append(",\"previewDuration\":").append(formatNum(info.previewDuration));
        b.append(",\"coverUrl\":").append(jsonString(coverUrl));
        b.append(",\"audioUrl\":").append(jsonString(audioUrl));
        b.append("}");

        b.append(",\"difficulties\":[");
        for (int i = 0; i < diffs.size(); i++) {
            if (i > 0) b.append(',');
            appendDiffJson(b, diffs.get(i));
        }
        b.append("]}");
        return b.toString();
    }

    private void appendDiffJson(StringBuilder b, DiffParsed d) {
        b.append("{");
        b.append("\"characteristic\":").append(jsonString(d.meta.characteristic));
        b.append(",\"rank\":").append(d.meta.difficultyRank);
        b.append(",\"label\":").append(jsonString(d.meta.label));
        b.append(",\"njs\":").append(formatNum(d.meta.njs));
        b.append(",\"offset\":").append(formatNum(d.meta.njsOffset));
        b.append(",\"notes\":[");
        for (int i = 0; i < d.notes.size(); i++) {
            if (i > 0) b.append(',');
            appendFloatArr(b, d.notes.get(i));
        }
        b.append("],\"walls\":[");
        for (int i = 0; i < d.walls.size(); i++) {
            if (i > 0) b.append(',');
            appendFloatArr(b, d.walls.get(i));
        }
        b.append("],\"chains\":[");
        for (int i = 0; i < d.chains.size(); i++) {
            if (i > 0) b.append(',');
            appendFloatArr(b, d.chains.get(i));
        }
        b.append("],\"arcs\":[");
        for (int i = 0; i < d.arcs.size(); i++) {
            if (i > 0) b.append(',');
            appendFloatArr(b, d.arcs.get(i));
        }
        b.append("]}");
    }

    private static void appendFloatArr(StringBuilder b, float[] arr) {
        b.append('[');
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) b.append(',');
            float v = arr[i];
            if (v == (long) v) b.append((long) v);
            else b.append(formatNum(v));
        }
        b.append(']');
    }

    private static String formatNum(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        if (v == (long) v) return Long.toString((long) v);
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static String jsonString(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 8);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
        return b.toString();
    }

    // ========================================
    // 가벼운 JSON 추출 헬퍼 (외부 의존성 없음)
    // ========================================

    /** key 가 단순 위치 검색되는 첫번째 인덱스 (이스케이프 무시). */
    private static int indexOfKey(String s, String key, int from) {
        return s.indexOf(key, from);
    }

    /** key 가 처음 발견된 위치에서 그 값 (string) 을 추출. 없으면 null. */
    static String extractFirstString(String s, String key) {
        int k = s.indexOf(key);
        if (k < 0) return null;
        return readStringValue(s, k);
    }

    static Double extractFirstDouble(String s, String key) {
        int k = s.indexOf(key);
        if (k < 0) return null;
        return readDoubleValueNearKey(s, key, 0, s.length());
    }

    /** key 위치 다음의 ':' 뒤 첫 string 값 읽기. */
    private static String readStringValue(String s, int keyPos) {
        int colon = s.indexOf(':', keyPos);
        if (colon < 0) return null;
        int i = colon + 1;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length()) return null;
        if (s.charAt(i) == '"') {
            int end = i + 1;
            StringBuilder b = new StringBuilder();
            while (end < s.length()) {
                char c = s.charAt(end);
                if (c == '\\' && end + 1 < s.length()) {
                    char next = s.charAt(end + 1);
                    switch (next) {
                        case 'n': b.append('\n'); break;
                        case 'r': b.append('\r'); break;
                        case 't': b.append('\t'); break;
                        case '"': b.append('"');  break;
                        case '\\': b.append('\\'); break;
                        case '/':  b.append('/');  break;
                        default: b.append(next);
                    }
                    end += 2;
                    continue;
                }
                if (c == '"') break;
                b.append(c);
                end++;
            }
            return b.toString();
        }
        return null;
    }

    /** [from, to) 범위 안에서 key 와 그 값을 읽어 int 반환. */
    private static Integer readIntValueNearKey(String s, String key, int from, int to) {
        int k = s.indexOf(key, from);
        if (k < 0 || k >= to) return null;
        int colon = s.indexOf(':', k);
        if (colon < 0 || colon >= to) return null;
        int i = colon + 1;
        while (i < to && Character.isWhitespace(s.charAt(i))) i++;
        int start = i;
        if (start < to && (s.charAt(start) == '-' || s.charAt(start) == '+')) i++;
        while (i < to) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') break;
            i++;
        }
        if (i == start) return null;
        try {
            return Integer.parseInt(s.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double readDoubleValueNearKey(String s, String key, int from, int to) {
        int k = s.indexOf(key, from);
        if (k < 0 || k >= to) return null;
        int colon = s.indexOf(':', k);
        if (colon < 0 || colon >= to) return null;
        int i = colon + 1;
        while (i < to && Character.isWhitespace(s.charAt(i))) i++;
        int start = i;
        if (start < to && (s.charAt(start) == '-' || s.charAt(start) == '+')) i++;
        while (i < to) {
            char c = s.charAt(i);
            if ((c < '0' || c > '9') && c != '.' && c != 'e' && c != 'E' && c != '+' && c != '-') break;
            i++;
        }
        if (i == start) return null;
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readStringValueNearKey(String s, String key, int from, int to) {
        int k = s.indexOf(key, from);
        if (k < 0 || k >= to) return null;
        return readStringValue(s, k);
    }

    /** keyPos 위치 다음에 등장하는 첫 '[' 의 매칭 ']' 인덱스 (depth 추적). */
    private static int findArrayEnd(String s, int keyPos) {
        int open = s.indexOf('[', keyPos);
        if (open < 0) return -1;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) { esc = false; continue; }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /** objPos = '{' 위치의 매칭 '}' 인덱스. */
    private static int findObjectEnd(String s, int objPos) {
        if (s.charAt(objPos) != '{') return -1;
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = objPos; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) { esc = false; continue; }
                if (c == '\\') { esc = true; continue; }
                if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') { inStr = true; continue; }
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static <T> T firstNonNull(T a, T b) { return a != null ? a : b; }

    // ========================================
    // 결과/캐시 타입
    // ========================================

    public static final class Result {
        public final boolean ok;
        public final int httpStatus;
        public final String json;
        public final String error;
        public final String audioUrl;
        public final SongMeta meta; // nullable; populated when ok=true

        private Result(boolean ok, int status, String json, String error, String audioUrl, SongMeta meta) {
            this.ok = ok;
            this.httpStatus = status;
            this.json = json;
            this.error = error;
            this.audioUrl = audioUrl;
            this.meta = meta;
        }

        public static Result ok(String json, String audioUrl, SongMeta meta) {
            return new Result(true, 200, json, null, audioUrl, meta);
        }
        public static Result error(int httpStatus, String error) {
            String json = "{\"ok\":false,\"error\":" + jsonString(error) + "}";
            return new Result(false, httpStatus, json, error, null, null);
        }
    }

    /** Discord 알림 등 외부에 전달할 핵심 곡 메타. */
    public static final class SongMeta {
        public final String bsr;
        public final String songName;
        public final String songAuthor;
        public final String mapper;
        public final String coverUrl;
        public final double bpm;
        public final double duration;
        public final String hash;   // versions[0].hash (SHA1) — BSOR info.hash 자동 맵 매칭용. 없으면 "".

        public SongMeta(String bsr, String songName, String songAuthor, String mapper,
                        String coverUrl, double bpm, double duration, String hash) {
            this.bsr = bsr;
            this.songName = songName;
            this.songAuthor = songAuthor;
            this.mapper = mapper;
            this.coverUrl = coverUrl;
            this.bpm = bpm;
            this.duration = duration;
            this.hash = hash == null ? "" : hash;
        }
    }

    public static final class AudioBlob {
        public final byte[] bytes;
        public final String contentType;
        public AudioBlob(byte[] bytes, String contentType) { this.bytes = bytes; this.contentType = contentType; }
    }

    public static final class ImageBlob {
        public final byte[] bytes;
        public final String contentType;
        public ImageBlob(byte[] bytes, String contentType) { this.bytes = bytes; this.contentType = contentType; }
    }

    /** 한 곡 검색 결과 — 평면 JSON 으로 직렬화되어 월드로 보내짐. */
    public static final class SearchHit {
        public String bsr;
        public String name;
        public String subtitle;
        public String author;
        public String mapper;
        public double bpm;
        public double duration;
        public List<Integer> diffs;       // 표준 인덱스 (0=Easy..4=ExpertPlus)
        public boolean ranked;            // ScoreSaber ranked
        public boolean blRanked;          // BeatLeader ranked
    }

    /** 검색 응답 — JSON + per-IP slot 캐시 시드용 BSR 리스트. */
    public static final class SearchResult {
        public final boolean ok;
        public final int httpStatus;
        public final String json;
        public final String error;
        public final List<String> bsrList; // ok=true 일 때만 채워짐 (0..N-1 slot 매핑)

        private SearchResult(boolean ok, int status, String json, String error, List<String> bsrList) {
            this.ok = ok;
            this.httpStatus = status;
            this.json = json;
            this.error = error;
            this.bsrList = bsrList;
        }
        public static SearchResult ok(String json, List<String> bsrList) {
            return new SearchResult(true, 200, json, null, bsrList);
        }
        public static SearchResult error(int httpStatus, String error) {
            String json = "{\"ok\":false,\"error\":" + jsonString(error) + "}";
            return new SearchResult(false, httpStatus, json, error, java.util.Collections.emptyList());
        }
    }

    private static final class CachedEntry {
        final long timestamp;
        final String json;
        final String audioUrl;
        final Path audioPath;              // nullable — 디스크 캐시 경로
        final String audioContentType;     // nullable — 보통 audio/ogg
        final String externalZipUrl;
        final SongMeta meta;
        final ImageBlob coverBlob;         // nullable
        final int[] noteCountByStdDiff;    // size 5, 0..4 index → note count
        final float[] njsByStdDiff;        // size 5, 0..4 index → NJS (Standard 우선). ArcViewer RT 계산용.

        CachedEntry(long timestamp, String json, String audioUrl, Path audioPath, String audioContentType,
                    String externalZipUrl, SongMeta meta, ImageBlob coverBlob, int[] noteCountByStdDiff,
                    float[] njsByStdDiff) {
            this.timestamp = timestamp;
            this.json = json;
            this.audioUrl = audioUrl;
            this.audioPath = audioPath;
            this.audioContentType = audioContentType;
            this.externalZipUrl = externalZipUrl;
            this.meta = meta;
            this.coverBlob = coverBlob;
            this.noteCountByStdDiff = noteCountByStdDiff;
            this.njsByStdDiff = njsByStdDiff;
        }
    }

    /** 디스크 캐시된 audio 파일 + content-type. 핸들러가 stream 응답으로 사용. */
    public static final class AudioFile {
        public final Path path;
        public final String contentType;
        public AudioFile(Path path, String contentType) {
            this.path = path;
            this.contentType = contentType;
        }
    }
}
