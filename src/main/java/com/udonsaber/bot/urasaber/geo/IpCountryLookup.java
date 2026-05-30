package com.udonsaber.bot.urasaber.geo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * IP → ISO 2자 국가코드 lookup. 무료 외부 서비스(ipapi.co) 사용 + 메모리 캐시.
 * 호출 실패해도 빈 문자열 반환 — 점수 저장 자체는 진행됨.
 *
 * 보수적 사용:
 *  - 로컬/사설 IP 는 lookup 안 함 (반환 "" / null)
 *  - 같은 IP 는 한 번만 호출하고 캐시 (TTL: 24h)
 *  - 외부 호출은 4초 타임아웃, 응답이 "?" 같은 비정상이면 빈 문자열
 *
 * 더 강한 정확성/오프라인이 필요하면 GeoLite2 country mmdb 를 임베드하면 됨 — 일단은 무료 API.
 */
public final class IpCountryLookup {
    private static final Logger log = LoggerFactory.getLogger(IpCountryLookup.class);

    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;
    private static final int TIMEOUT_MS = 4000;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService janitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "urasaber-geo-janitor");
        t.setDaemon(true);
        return t;
    });

    public IpCountryLookup() {
        janitor.scheduleAtFixedRate(this::cleanExpired, 1, 1, TimeUnit.HOURS);
    }

    /** IP → ISO2 국가코드. 실패/사설/private 이면 null. blocking 호출. */
    public String lookup(String ip) {
        if (ip == null || ip.isBlank()) return null;
        if (isPrivate(ip)) return null;

        CacheEntry hit = cache.get(ip);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.fetchedAt < TTL_MILLIS) {
            return hit.country.isEmpty() ? null : hit.country;
        }

        String country = fetch(ip);
        cache.put(ip, new CacheEntry(country == null ? "" : country, now));
        return country;
    }

    private String fetch(String ip) {
        try {
            URI uri = URI.create("https://ipapi.co/" + ip + "/country/");
            HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();
            con.setConnectTimeout(TIMEOUT_MS);
            con.setReadTimeout(TIMEOUT_MS);
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "udonsaber-bot/1.0");
            int code = con.getResponseCode();
            if (code != 200) return null;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
                String body = br.readLine();
                if (body == null) return null;
                body = body.trim();
                if (body.length() == 2 && body.chars().allMatch(c -> c >= 'A' && c <= 'Z')) return body;
                return null;
            }
        } catch (Exception e) {
            log.debug("country lookup failed for {}: {}", ip, e.getMessage());
            return null;
        }
    }

    private static boolean isPrivate(String ip) {
        if (ip.equals("127.0.0.1") || ip.equals("::1") || ip.equals("0.0.0.0") || ip.startsWith("169.254.")) return true;
        if (ip.startsWith("10.")) return true;
        if (ip.startsWith("192.168.")) return true;
        if (ip.startsWith("172.")) {
            int second = ip.indexOf('.', 4);
            if (second < 0) return false;
            try {
                int b = Integer.parseInt(ip.substring(4, second));
                if (b >= 16 && b <= 31) return true;
            } catch (NumberFormatException ignored) {}
        }
        if (ip.startsWith("fc") || ip.startsWith("fd")) return true; // ULA
        return false;
    }

    private void cleanExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(e -> now - e.getValue().fetchedAt >= TTL_MILLIS);
    }

    private record CacheEntry(String country, long fetchedAt) {}
}
