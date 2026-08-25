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
}
