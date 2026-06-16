package com.udonsaber.bot.urasaber.api;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * UraSaber 제출 페이로드({@link ReplaySubmissionCodec.Submission}) → BeatLeader {@code .bsor}
 * (ArcViewer 재생용, 고스트 품질).
 *
 * <p>고스트 품질이란:
 * <ul>
 *   <li>frames 블록(머리+양손 pos/rot)은 정확히 기록 → 아바타 동작 재생 OK.</li>
 *   <li>storeHead=false(세이버만 전송) 면 머리는 양손 중점+오프셋으로 합성.</li>
 *   <li>notes 블록은 비움(noteCount=0). ArcViewer 는 맵을 별도로 로드하므로 노트는 맵에서 나오고
 *       아바타가 그 위를 움직인다. 노트별 컷 판정 오버레이는 후속 작업(차트 noteIndex 정렬 필요).</li>
 * </ul>
 *
 * BSOR 포맷: magic 0x442d3d69, version 1, 블록 0(info) ~ 5(pauses). LE.
 */
public final class BsorWriter {

    private static final int MAGIC = 0x442d3d69;
    private static final byte VERSION = 1;

    /**
     * noteID = scoringType*10000 + line*1000 + layer*100 + color*10 + cutDir.
     * 일반 색노트의 scoringType. ArcViewer ScoringEvent.ScoringType.Note = 3 (소스 실측 확정 2026-06-16).
     * ObjectManager.CalculateObjectPosition 매칭이 noteID 동일해야 컷/미스 오버레이가 정확히 정렬됨.
     */
    private static final int SCORING_TYPE_NORMAL = 3;

    private static final String[] DIFF_NAMES = { "Easy", "Normal", "Hard", "Expert", "ExpertPlus" };
    private static final String[] MODE_NAMES = { "Standard", "Lawless", "OneSaber", "NoArrows", "90Degree", "360Degree", "Lightshow" };

    private final ByteArrayOutputStream o = new ByteArrayOutputStream(128 * 1024);

    private BsorWriter() {}

    public static String difficultyName(int diff) {
        return (diff >= 0 && diff < DIFF_NAMES.length) ? DIFF_NAMES[diff] : "ExpertPlus";
    }

    public static String modeName(int charIdx) {
        return (charIdx >= 0 && charIdx < MODE_NAMES.length) ? MODE_NAMES[charIdx] : "Standard";
    }

    /**
     * 제출 페이로드를 .bsor 바이트로 변환.
     *
     * @param sub          디코드된 제출
     * @param hash         맵 hash (SHA1) — info.hash 에 들어가 ArcViewer 가 맵 자동 로드. 모르면 "".
     * @param bsr          BeatSaver 코드 — userData(block 7)에 저장(식별용).
     * @param songName     곡명 (BeatSaver 메타)
     * @param mapper       매퍼명
     * @param modifiersStr 모디파이어 문자열 ("" 가능)
     * @param timestampSec 제출 epoch 초
     */
    public static byte[] toBsor(ReplaySubmissionCodec.Submission sub, String hash, String bsr,
                                String songName, String mapper, String modifiersStr,
                                long timestampSec) {
        BsorWriter w = new BsorWriter();
        w.build(sub, hash, bsr, songName, mapper, modifiersStr, timestampSec);
        return w.o.toByteArray();
    }

    private void build(ReplaySubmissionCodec.Submission sub, String hash, String bsr,
                       String songName, String mapper, String modifiersStr, long timestampSec) {
        wInt(MAGIC);
        wByte(VERSION);

        // ---- block 0: info ----
        wByte(0);
        wString("UraSaber");                                  // version (replay app)
        wString("UraSaber");                                  // gameVersion
        wString(Long.toString(timestampSec));                 // timestamp
        wString("");                                          // playerID
        wString(sub.playerName == null ? "" : sub.playerName);// playerName
        wString("vrchat");                                    // platform
        wString("UraSaber");                                  // trackingSystem
        wString("Unknown");                                   // hmd
        wString("Unknown");                                   // controller
        wString(hash == null ? "" : hash);                    // hash
        wString(songName == null ? "" : songName);            // songName
        wString(mapper == null ? "" : mapper);                // mapper
        wString(difficultyName(sub.diff));                    // difficulty
        wInt(sub.score);                                      // score
        wString(modeName(sub.charIdx));                       // mode
        wString("DefaultEnvironment");                        // environment
        wString(modifiersStr == null ? "" : modifiersStr);    // modifiers
        wFloat(sub.jumpDistance);                             // jumpDistance
        wBool(false);                                         // leftHanded
        wFloat(sub.playerHeightMul > 0f ? sub.playerHeightMul * 1.7f : 1.7f); // height (근사)
        wFloat(0f);                                           // startTime
        wFloat(0f);                                           // failTime
        wFloat(0f);                                           // speed (0 = normal)

        // ---- block 1: frames ----
        wByte(1);
        ReplaySubmissionCodec.Frame[] frames = sub.frames != null ? sub.frames : new ReplaySubmissionCodec.Frame[0];
        wInt(frames.length);
        for (int i = 0; i < frames.length; i++) {
            ReplaySubmissionCodec.Frame f = frames[i];
            wFloat(f.time);
            wInt(estimateFps(frames, i));

            if (sub.storeHead) {
                wVec(f.hx, f.hy, f.hz);
                wQuat(f.hqx, f.hqy, f.hqz, f.hqw);
            } else {
                // 머리 합성: 양손 중점 + 위로 0.45m, 회전 identity.
                wVec((f.lx + f.rx) * 0.5f, (f.ly + f.ry) * 0.5f + 0.45f, (f.lz + f.rz) * 0.5f);
                wQuat(0f, 0f, 0f, 1f);
            }
            wVec(f.lx, f.ly, f.lz);
            wQuat(f.lqx, f.lqy, f.lqz, f.lqw);
            wVec(f.rx, f.ry, f.rz);
            wQuat(f.rqx, f.rqy, f.rqz, f.rqw);
        }

        // ---- block 2: notes (컷/미스 오버레이) ----
        // ArcViewer(ObjectManager) 는 맵 노트와 (1) spawnTime ≈ TimeFromBeat(noteBeat) 1ms(CheckSameTime) +
        // (2) noteID(=3*10000+line*1000+layer*100+color*10+cutDir) 로 매칭한다. 인월드가 이제 노트 예정
        // 시각(spawnTime, 0.1ms 정밀)을 보내므로 그 값으로 매칭 가능 → 컷 마커/점수/미스 X 가 노트 위치에 그려짐.
        // 일반 색노트만(good/bad/miss). 체인/아크/봄은 미포함(추후).
        wByte(2);
        ReplaySubmissionCodec.Event[] events = sub.events != null ? sub.events : new ReplaySubmissionCodec.Event[0];
        wInt(events.length);
        for (ReplaySubmissionCodec.Event e : events) {
            int noteID = SCORING_TYPE_NORMAL * 10000 + e.line * 1000 + e.layer * 100 + e.color * 10 + e.cutDir;
            wInt(noteID);
            wFloat(e.spawnTime);   // eventTime (인디케이터) = spawnTime
            wFloat(e.spawnTime);   // spawnTime (매칭 기준)
            int eventType = e.kind;  // 0 good / 1 bad / 2 miss
            wInt(eventType);
            if (eventType == 0 || eventType == 1) {
                // NoteCutInfo (good/bad 만 — Replay.cs DecodeCutInfo 순서와 일치)
                float beforeRating = clamp01(e.pre / 70f);   // preSwing 0..70 → 0..1
                float afterRating = clamp01(e.post / 30f);   // postSwing 0..30 → 0..1
                wBool(true);    // speedOK
                wBool(true);    // directionOK
                wBool(true);    // saberTypeOK
                wBool(false);   // wasCutTooSoon
                wFloat(0f);     // saberSpeed
                wVec(0f, 0f, 0f); // saberDir
                wInt(e.color);  // saberType (0=left/red, 1=right/blue)
                wFloat(0f);     // timeDeviation
                wFloat(0f);     // cutDirDeviation
                wVec(0f, 0f, 0f); // cutPoint
                wVec(0f, 0f, 0f); // cutNormal
                wFloat(0f);     // cutDistanceToCenter (0 = 센터 만점)
                wFloat(0f);     // cutAngle
                wFloat(beforeRating); // beforeCutRating
                wFloat(afterRating);  // afterCutRating
            }
        }

        // ---- block 3: walls ----
        wByte(3);
        wInt(0);

        // ---- block 4: heights ----
        wByte(4);
        wInt(0);

        // ---- block 5: pauses ----
        wByte(5);
        wInt(0);

        // ---- block 7: user data (BeatSaver 코드 저장 — 식별/재조회용) ----
        wByte(7);
        String userJson = "{\"bsr\":\"" + (bsr == null ? "" : bsr) + "\",\"src\":\"UraSaber\"}";
        byte[] userBytes = userJson.getBytes(StandardCharsets.UTF_8);
        wInt(userBytes.length);
        o.writeBytes(userBytes);
    }

    /** 프레임 간격으로 fps 추정 (정보용). 불가하면 90. */
    private static int estimateFps(ReplaySubmissionCodec.Frame[] frames, int i) {
        float dt = 0f;
        if (i + 1 < frames.length) dt = frames[i + 1].time - frames[i].time;
        else if (i > 0) dt = frames[i].time - frames[i - 1].time;
        if (dt <= 0.0001f) return 90;
        int fps = Math.round(1f / dt);
        if (fps < 1) fps = 1;
        if (fps > 240) fps = 240;
        return fps;
    }

    // ---------------------------------------------------------------
    // 저수준 writer (little-endian)
    // ---------------------------------------------------------------

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    private void wByte(int v) { o.write(v & 0xFF); }

    private void wInt(int v) {
        o.write(v & 0xFF);
        o.write((v >> 8) & 0xFF);
        o.write((v >> 16) & 0xFF);
        o.write((v >> 24) & 0xFF);
    }

    private void wFloat(float f) { wInt(Float.floatToIntBits(f)); }

    private void wBool(boolean b) { o.write(b ? 1 : 0); }

    private void wString(String s) {
        byte[] bytes = (s == null) ? new byte[0] : s.getBytes(StandardCharsets.UTF_8);
        wInt(bytes.length);
        o.writeBytes(bytes);
    }

    private void wVec(float x, float y, float z) { wFloat(x); wFloat(y); wFloat(z); }

    private void wQuat(float x, float y, float z, float w) { wFloat(x); wFloat(y); wFloat(z); wFloat(w); }
}
