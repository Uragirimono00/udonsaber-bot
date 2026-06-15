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
     * 일반 색노트의 scoringType. ArcViewer 가 맵에서 계산하는 값과 일치해야 노트 오버레이가 정확히 매칭됨.
     * (불확실 — ArcViewer 실측 후 이 한 값만 튜닝하면 됨. 미스매치여도 아바타 재생/시간 폴백은 동작.)
     */
    private static final int SCORING_TYPE_NORMAL = 0;

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

        // ---- block 2: notes (비움) ----
        // ★ ArcViewer 는 노트 이벤트를 맵 노트와 (1) spawnTime ≈ TimeFromBeat(noteBeat) 1ms 오차 +
        //    (2) noteID 로 매칭한다(ObjectManager.cs). 우리 이벤트 time 은 "컷(히트) 시각을 20ms 양자화"한
        //    값이라 노트의 정확한 beat 시각과 안 맞아 전부 미스매치 → "Couldn't match N replay notes" +
        //    추정 위치로 이상한 컷이 그려졌다. 노트의 예정 시각(초, ms 정밀)을 인월드에서 정확히 캡처해
        //    보내기 전까지는 노트 블록을 비워 아바타 고스트만(맵 노트는 ArcViewer 가 맵에서 그대로 표시).
        wByte(2);
        wInt(0);

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
