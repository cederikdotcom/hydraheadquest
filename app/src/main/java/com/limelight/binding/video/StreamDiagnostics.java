package com.limelight.binding.video;

/**
 * One snapshot of the stream statistics, filled by MediaCodecDecoderRenderer
 * about once a second while the VR diagnostics panel is open. Plain fields,
 * built fresh for every update, handed to XrRenderer for display. Matches
 * the iPad app's in-stream diagnostics panel where Moonlight has the data.
 */
public class StreamDiagnostics {

    // From MoonBridge.getEstimatedRttInfo(). Zero when ENet has no
    // estimate yet; the panel shows placeholders then.
    public int rttMs;
    public int rttVarianceMs;

    public float incomingFps;
    public float renderedFps;

    // Percent of frames lost to the network over the last two windows
    public float netDropPercent;

    // Host processing latency in milliseconds, only when Sunshine reports
    // it (hasHostLatency false otherwise)
    public boolean hasHostLatency;
    public float hostLatencyAvgMs;
    public float hostLatencyMinMs;
    public float hostLatencyMaxMs;

    public float decodeTimeMs;

    // "H.264", "HEVC", or "AV1", plus the Android decoder behind it
    public String codec;
    public String decoderName;

    // The negotiated stream, plus the configured targets
    public int width;
    public int height;
    public int targetFps;
    public int bitrateKbps;

    // Hydra: which body and over which route ("mesh" or "lan"), from the
    // launch intent extras
    public String bodyHost;
    public String route;

    private static final String NA = "--";

    /**
     * The placeholder table shown before the first snapshot exists.
     * Lives here, not in XrRenderer: ART rejected two prior shapes of a
     * row-building method inside XrRenderer (VerifyError, copy1
     * type=Undefined cat=3), and a rejected method poisons its whole
     * class, which the video decoder must be able to load. Keeping every
     * row builder in this class isolates any future verifier trouble
     * away from the decoder path.
     */
    public static String[][] placeholderRows() {
        return new String[][] {
                { "Route", NA }, { "Body", NA }, { "Codec", NA },
                { "Stream", NA }, { "Bitrate", NA }, { "RTT", NA },
                { "RTT variance", NA }, { "FPS incoming", NA },
                { "FPS rendered", NA }, { "Net drops", NA },
                { "Host latency", NA }, { "Host min / max", NA },
                { "Decode time", NA },
        };
    }

    /**
     * One decimal without String.format: the format call's float-to-
     * double vararg promotion creates the wide-register copy that ART's
     * verifier rejected twice in this table's history. Integer math only.
     */
    private static String dec1(float v) {
        int tenths = Math.round(v * 10f);
        int whole = tenths / 10;
        int frac = tenths % 10;
        if (frac < 0) {
            frac = -frac;
        }
        return whole + "." + frac;
    }

    /** The live table. Plain statements, no conditional expressions. */
    public String[][] rows() {
        String route1 = NA;
        if (route != null) {
            route1 = route;
        }
        String body1 = NA;
        if (bodyHost != null) {
            body1 = bodyHost;
        }
        String codec1 = NA;
        if (codec != null) {
            codec1 = codec;
        }
        String stream1 = NA;
        if (width > 0) {
            stream1 = width + "x" + height + " @ " + targetFps;
        }
        String bitrate1 = NA;
        if (bitrateKbps > 0) {
            int tenthsMbps = Math.round(bitrateKbps / 100f);
            bitrate1 = (tenthsMbps / 10) + "." + (tenthsMbps % 10) + " Mbps";
        }
        String rtt1 = NA;
        String rttVar1 = NA;
        if (rttMs > 0 || rttVarianceMs > 0) {
            rtt1 = rttMs + " ms";
            rttVar1 = rttVarianceMs + " ms";
        }
        String fpsIn1 = dec1(incomingFps);
        String fpsOut1 = dec1(renderedFps);
        String drops1 = dec1(netDropPercent) + " %";
        String hostAvg1 = NA;
        String hostMinMax1 = NA;
        if (hasHostLatency) {
            hostAvg1 = dec1(hostLatencyAvgMs) + " ms";
            hostMinMax1 = dec1(hostLatencyMinMs) + " / " + dec1(hostLatencyMaxMs) + " ms";
        }
        String decode1 = NA;
        if (decodeTimeMs >= 0f) {
            decode1 = dec1(decodeTimeMs) + " ms";
        }
        return new String[][] {
                { "Route", route1 },
                { "Body", body1 },
                { "Codec", codec1 },
                { "Stream", stream1 },
                { "Bitrate", bitrate1 },
                { "RTT", rtt1 },
                { "RTT variance", rttVar1 },
                { "FPS incoming", fpsIn1 },
                { "FPS rendered", fpsOut1 },
                { "Net drops", drops1 },
                { "Host latency", hostAvg1 },
                { "Host min / max", hostMinMax1 },
                { "Decode time", decode1 },
        };
    }
}
