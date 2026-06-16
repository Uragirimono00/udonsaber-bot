package com.udonsaber.bot.urasaber.api;

import java.util.Base64;

/**
 * "UraSaber Submission Payload v1" 디코더.
 *
 * 인월드 {@code BSReplayRecorder.EncodeSubmission()} 가 만든 바이트 레이아웃과 정확히 미러된다.
 * (월드는 URL-safe base64 로 인코딩 후 {@code submitUrlPrefix} 를 붙여 링크로 표시 → 사용자가
 *  VRCUrlInputField 에 붙여넣고 Submit → 월드가 VRCStringDownloader 로 GET → 봇 /submit?d= 도착.)
 *
 * <h3>바이트 레이아웃 (little-endian)</h3>
 * <pre>
 *  magic        : byte 0xB5, byte 0x53
 *  version      : byte
 *  name         : byte len, len × int16 (UTF-16 코드유닛)
 *  mapId        : byte len, len × byte (ASCII hex BeatSaver key)
 *  diff         : byte (0..4)
 *  charIdx      : byte (0=Std,1=Lawless,2=OneSaber,3=NoArrows,4=90,5=360,6=Lightshow)
 *  score        : int32
 *  accX100      : int16 (정확도 ×100)
 *  maxCombo     : int16
 *  good/bad/miss: int16 ×3
 *  fullCombo    : byte
 *  modifiers    : int32 (비트마스크)
 *  jumpDistance : int32 (×1000)
 *  heightMul    : int32 (×1000)
 *  originX/Y/Z  : int32 ×3 (×1000)
 *  storeHead    : byte (청크 경로는 1=머리 포함)
 *  frameStride  : byte
 *  compressed   : byte (1=v5 DPCM, 0=절대 int16)
 *  frameCount   : int32
 *  eventCount   : int32
 *  env          : byte len + len × byte (ASCII) — 플레이 환경 "njs|rt|jd|jdOn|approachOn|noteSize|gridW|gridH|hitOffset|playerH|saberLen|saberThick|modCode"
 *  frames[]     : compressed=0 → 매 프레임 { int16 timeUnits, [head]posRel+quat, L posRel+quat, R posRel+quat }
 *                 compressed=1(v5 DPCM) → i%30==0 키프레임(위와 동일 절대), 그 외 델타프레임
 *                   { int8 시간델타, [head]posDelta(int8×3)+quat, L posDelta+quat, R posDelta+quat }
 *                   posDelta: prev += d/127×1.2(POS_DELTA_RANGE). quat 은 항상 uint32(델타 안 함).
 *  events[]     : { int32 spawnTime(초×10000), uint16 packed, byte pre, byte post }  (8B)
 *                 packed bits: line(0..3) | layer<<2 | color<<4 | cutDir<<5(0..15) | kind<<9(0 good/1 bad/2 miss) | scoringType<<11(3..10)
 *                 spawnTime=노트 예정 시각(초)→ArcViewer 매칭(1ms). noteID=scoringType*10000+line*1000+layer*100+color*10+cutDir
 *                 scoringType: 3 Note/4 ArcHead/5 ArcTail/6 ChainHead/7 ChainLink
 * </pre>
 * posRel = int16×3, 값 = origin + i16/32767 × 16(POS_RANGE). quat = uint32 smallest-three.
 */
public final class ReplaySubmissionCodec {

    public static final int MAGIC0 = 0xB5;
    public static final int MAGIC1 = 0x53;

    private static final float POS_RANGE = 16f;
    private static final float QUAT_RANGE = 0.70710678f;
    private static final float FRAME_TIME_UNIT = 0.02f;
    private static final int SABER_KEYFRAME = 30;       // v5 DPCM 절대 키프레임 간격 (인월드와 일치)
    private static final float POS_DELTA_RANGE = 1.2f;  // int8 위치 델타 최대 (±1.2m/프레임, 인월드와 일치)

    public static final int KIND_GOOD = 0;
    public static final int KIND_BAD = 1;
    public static final int KIND_MISS = 2;

    public static final class Frame {
        public float time;
        public float hx, hy, hz, hqx, hqy, hqz, hqw;  // head (storeHead=false 면 0)
        public float lx, ly, lz, lqx, lqy, lqz, lqw;  // left saber
        public float rx, ry, rz, rqx, rqy, rqz, rqw;  // right saber
    }

    public static final class Event {
        public float time;     // eventTime(초) = spawnTime (인디케이터 애니메이션 기준)
        public float spawnTime; // 노트 예정 시각(초) — ArcViewer 맵 노트 매칭 기준(CheckSameTime 1ms)
        public int line;       // 0..3
        public int layer;      // 0..2
        public int color;      // 0=red(left), 1=blue(right)
        public int cutDir;     // 0..8 (미스/미상=15)
        public int kind;       // 0 good / 1 bad / 2 miss
        public int scoringType; // ArcViewer scoringType: 3 Note/4 ArcHead/5 ArcTail/6 ChainHead/7 ChainLink
        public int pre;        // 0..255 (Beat Saber preSwing 점수 0..70)
        public int post;       // 0..255 (postSwing 점수 0..30)
        public boolean isGoodOrBad() { return kind == KIND_GOOD || kind == KIND_BAD; }
    }

    public static final class Submission {
        public int version;
        public String playerName = "";
        public String mapId = "";
        public int diff, charIdx;
        public int score, accX100, maxCombo, good, bad, miss;
        public boolean fullCombo;
        public int modifiers;
        public float jumpDistance, playerHeightMul;
        public float originX, originY, originZ;
        public boolean storeHead;
        public int frameStride;
        public boolean compressed;   // true=v5 DPCM(키프레임+int8 델타), false=절대 int16(v2 단일샷)
        public String env = "";      // 플레이 환경 문자열 (njs|rt|jd|jdOn|approachOn|noteSize|...|modCode) — 리더보드 표시용
        public int frameCount;   // 헤더가 선언한 프레임 수 (청크 조립 시 frames 채우기 전)
        public int eventCount;   // 헤더가 선언한 이벤트 수
        public Frame[] frames = new Frame[0];
        public Event[] events = new Event[0];

        public double accuracy() { return accX100 / 100.0; }
        public boolean hasFrames() { return frames != null && frames.length > 0; }
    }

    private final byte[] b;
    private int p;
    private float originX, originY, originZ;

    private ReplaySubmissionCodec(byte[] data) { this.b = data; this.p = 0; }

    // ---------------------------------------------------------------
    // 진입점
    // ---------------------------------------------------------------

    /** URL-safe base64 → 바이트. 실패 시 null. (월드는 +→-, /→_, = 제거로 인코딩) */
    public static byte[] decodeUrlSafeBase64(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String b64 = s.replace('-', '+').replace('_', '/');
            int pad = (4 - (b64.length() % 4)) % 4;
            if (pad > 0) b64 += "=".repeat(pad);
            return Base64.getDecoder().decode(b64);
        } catch (Exception e) {
            return null;
        }
    }

    /** 디코드된 바이트가 제출 페이로드(매직 0xB5 0x53)인지. */
    public static boolean isBinaryPayload(byte[] raw) {
        return raw != null && raw.length >= 3
                && (raw[0] & 0xFF) == MAGIC0 && (raw[1] & 0xFF) == MAGIC1;
    }

    /** 바이트 → Submission. 형식 오류/범위 초과 시 null. */
    public static Submission decode(byte[] raw) {
        if (!isBinaryPayload(raw)) return null;
        try {
            return new ReplaySubmissionCodec(raw).read();
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------
    // 저수준 reader
    // ---------------------------------------------------------------

    private int rByte() { return b[p++] & 0xFF; }

    private int rI16() {
        int lo = rByte(), hi = rByte();
        int u = lo | (hi << 8);
        if (u >= 32768) u -= 65536;
        return u;
    }

    private int rU16() {
        int lo = rByte(), hi = rByte();
        return lo | (hi << 8);
    }

    private int rI32() {
        int v = (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
        p += 4;
        return v;
    }

    private float rF() { return rI32() / 1000f; }

    private boolean rBool() { return rByte() != 0; }

    private String rStrAscii() {
        int len = rByte();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) rByte());
        return sb.toString();
    }

    private String rStrUtf16() {
        int len = rByte();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append((char) rU16());
        return sb.toString();
    }

    /** WPosRel 미러: origin + i16/32767 × POS_RANGE. */
    private void readPosRel(float[] out) {
        out[0] = originX + rI16() / 32767f * POS_RANGE;
        out[1] = originY + rI16() / 32767f * POS_RANGE;
        out[2] = originZ + rI16() / 32767f * POS_RANGE;
    }

    /** WPosDelta 미러: prev 에 int8 델타(d/127×POS_DELTA_RANGE) 누적(제자리 갱신). */
    private void readPosDelta(float[] prev) {
        for (int k = 0; k < 3; k++) {
            int d = rByte(); if (d >= 128) d -= 256;
            prev[k] += d / 127f * POS_DELTA_RANGE;
        }
    }

    /** PackQuat(smallest-three) 미러 → [x,y,z,w] (정규화됨). */
    private void readQuat(float[] out) {
        long packed = rI32() & 0xFFFFFFFFL;
        int idx = (int) ((packed >> 30) & 3);
        int shift = 20;
        float sumsq = 0f;
        for (int k = 0; k < 4; k++) {
            if (k == idx) continue;
            int u = (int) ((packed >> shift) & 1023);
            float v = (u / 1023f * 2f - 1f) * QUAT_RANGE;
            out[k] = v;
            sumsq += v * v;
            shift -= 10;
        }
        out[idx] = (float) Math.sqrt(Math.max(0f, 1f - sumsq));
        float n = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]);
        if (n > 1e-6f) { out[0] /= n; out[1] /= n; out[2] /= n; out[3] /= n; }
    }

    // ---------------------------------------------------------------
    // 본문
    // ---------------------------------------------------------------

    private Submission read() {
        Submission s = new Submission();
        rByte(); rByte();              // magic
        s.version = rByte();
        readHeaderFields(s);           // name..eventCount (origin 세팅 포함)
        readFramesInto(s);
        readEventsInto(s);
        return s;
    }

    /** 스코어 헤더(name..eventCount) 읽기. magic/version 은 호출 측이 이미 소비한 상태에서 호출. */
    private void readHeaderFields(Submission s) {
        s.playerName = rStrUtf16();
        s.mapId = rStrAscii();
        s.diff = rByte();
        s.charIdx = rByte();
        s.score = rI32();
        s.accX100 = rU16();
        s.maxCombo = rU16();
        s.good = rU16();
        s.bad = rU16();
        s.miss = rU16();
        s.fullCombo = rBool();
        s.modifiers = rI32();
        s.jumpDistance = rF();
        s.playerHeightMul = rF();
        s.originX = rF(); s.originY = rF(); s.originZ = rF();
        originX = s.originX; originY = s.originY; originZ = s.originZ;
        s.storeHead = rByte() != 0;
        s.frameStride = rByte();
        s.compressed = rByte() != 0;   // v5 DPCM 여부
        s.frameCount = rI32();
        s.eventCount = rI32();
        s.env = rStrAscii();   // 플레이 환경 문자열 (njs|rt|jd|... 13필드) — 리더보드 detail 표시용
        if (s.frameCount < 0 || s.frameCount > 5_000_000) throw new IllegalStateException("bad frameCount " + s.frameCount);
        if (s.eventCount < 0 || s.eventCount > 1_000_000) throw new IllegalStateException("bad eventCount " + s.eventCount);
    }

    private void readFramesInto(Submission s) {
        float[] pos = new float[3];
        float[] quat = new float[4];
        float[] prevH = new float[3], prevL = new float[3], prevR = new float[3];
        int decUnits = 0;
        Frame[] frames = new Frame[s.frameCount];
        for (int i = 0; i < s.frameCount; i++) {
            Frame f = new Frame();
            // 절대 프레임 여부: 비압축은 매 프레임 절대, 압축(v5)은 SABER_KEYFRAME 마다 절대·그 외 int8 델타.
            boolean absolute = !s.compressed || (i % SABER_KEYFRAME == 0);

            if (absolute) { decUnits = rI16(); f.time = decUnits * FRAME_TIME_UNIT; }
            else { int du = rByte(); if (du >= 128) du -= 256; decUnits += du; f.time = decUnits * FRAME_TIME_UNIT; }

            if (s.storeHead) {
                if (absolute) { readPosRel(pos); prevH[0]=pos[0]; prevH[1]=pos[1]; prevH[2]=pos[2]; }
                else { readPosDelta(prevH); pos[0]=prevH[0]; pos[1]=prevH[1]; pos[2]=prevH[2]; }
                f.hx = pos[0]; f.hy = pos[1]; f.hz = pos[2];
                readQuat(quat); f.hqx = quat[0]; f.hqy = quat[1]; f.hqz = quat[2]; f.hqw = quat[3];
            }
            if (absolute) { readPosRel(pos); prevL[0]=pos[0]; prevL[1]=pos[1]; prevL[2]=pos[2]; }
            else { readPosDelta(prevL); pos[0]=prevL[0]; pos[1]=prevL[1]; pos[2]=prevL[2]; }
            f.lx = pos[0]; f.ly = pos[1]; f.lz = pos[2];
            readQuat(quat); f.lqx = quat[0]; f.lqy = quat[1]; f.lqz = quat[2]; f.lqw = quat[3];

            if (absolute) { readPosRel(pos); prevR[0]=pos[0]; prevR[1]=pos[1]; prevR[2]=pos[2]; }
            else { readPosDelta(prevR); pos[0]=prevR[0]; pos[1]=prevR[1]; pos[2]=prevR[2]; }
            f.rx = pos[0]; f.ry = pos[1]; f.rz = pos[2];
            readQuat(quat); f.rqx = quat[0]; f.rqy = quat[1]; f.rqz = quat[2]; f.rqw = quat[3];

            frames[i] = f;
        }
        s.frames = frames;
    }

    private void readEventsInto(Submission s) {
        // v3 이벤트(8B): spawnTime(int32, 초×10000) + packed(uint16) + pre + post.
        // packed = line | layer<<2 | color<<4 | cutDir<<5 | kind<<9.
        Event[] events = new Event[s.eventCount];
        for (int j = 0; j < s.eventCount; j++) {
            Event e = new Event();
            e.spawnTime = rI32() / 10000f;   // 예정 시각(초) — 매칭 기준
            e.time = e.spawnTime;            // eventTime = spawnTime
            int packed = rU16();
            e.line = packed & 0x3;
            e.layer = (packed >> 2) & 0x3;
            e.color = (packed >> 4) & 0x1;
            e.cutDir = (packed >> 5) & 0xF;
            e.kind = (packed >> 9) & 0x3;
            e.scoringType = (packed >> 11) & 0xF;   // 3 Note/4 ArcHead/5 ArcTail/6 ChainHead/7 ChainLink
            if (e.scoringType < 3 || e.scoringType > 10) e.scoringType = 3;
            e.pre = rByte();
            e.post = rByte();
            events[j] = e;
        }
        s.events = events;
    }

    // ---------------------------------------------------------------
    // 청크(v3) 지원: 헤더(chunk0 body)와 프레임 스트림(여러 청크 합본) 분리 디코드
    // ---------------------------------------------------------------

    /** chunk0 body(매직/봉투 제거된, name 부터 시작) → 헤더 파싱 결과. */
    public static final class HeaderResult {
        public final Submission header;
        public final int frameStreamOffset;   // chunk0 body 에서 프레임 스트림이 시작하는 오프셋
        HeaderResult(Submission h, int off) { this.header = h; this.frameStreamOffset = off; }
    }

    /** chunk0 body 의 헤더만 파싱(스코어 즉시 등록용). 프레임 시작 오프셋도 반환. 실패 시 null. */
    public static HeaderResult parseChunk0Header(byte[] body) {
        try {
            ReplaySubmissionCodec c = new ReplaySubmissionCodec(body);
            Submission s = new Submission();
            c.readHeaderFields(s);
            return new HeaderResult(s, c.p);
        } catch (Exception e) {
            return null;
        }
    }

    /** 합쳐진 프레임 스트림 → header.frames(+이벤트) 채움. 스트림 = [프레임 전부][이벤트 전부] 순서.
     *  header 의 frameCount/eventCount/origin/storeHead 사용. 성공 true. */
    public static boolean decodeFramesInto(Submission header, byte[] frameStream) {
        try {
            ReplaySubmissionCodec c = new ReplaySubmissionCodec(frameStream);
            c.originX = header.originX; c.originY = header.originY; c.originZ = header.originZ;
            c.readFramesInto(header);
            c.readEventsInto(header);   // 프레임 뒤에 이어붙은 노트 컷/미스 이벤트(ArcViewer 오버레이)
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
