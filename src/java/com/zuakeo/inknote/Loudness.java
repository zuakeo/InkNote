package com.zuakeo.inknote;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 判断一段录音「到底有没有人说话」。
 *
 * 为什么不用峰值：环境噪声的瞬时峰值很容易过 500，但 100ms 的 RMS 只有几十。
 * 电脑端 inkvoice.is_silent 用的是「100ms 窗口 RMS ≥ 500，且至少 5 个窗口达标」，
 * 这里照同一口径实现，两端行为一致。
 *
 * 约定：解码失败 → 返回 UNKNOWN(-1)，调用方**不要拦**。
 * 宁可留一条空录音，也不能把用户说的话误删。
 */
public class Loudness {

    public static final int UNKNOWN = -1;
    /** 与电脑端一致：16bit 满量程 32767，500 约 -36dB。 */
    public static final int RMS_THRESHOLD = 500;
    /** 至少这么多个 100ms 窗口达标，才算「有人说话」。 */
    public static final int MIN_LOUD_WINDOWS = 5;

    /**
     * @return 1 有人说话；0 静音；UNKNOWN(-1) 判不出来
     */
    public static int voiced(File f, int maxMs) {
        MediaExtractor ex = new MediaExtractor();
        MediaCodec codec = null;
        try {
            ex.setDataSource(f.getAbsolutePath());
            int track = -1;
            MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                MediaFormat m = ex.getTrackFormat(i);
                String mime = m.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    fmt = m;
                    break;
                }
            }
            if (track < 0 || fmt == null) return UNKNOWN;
            ex.selectTrack(track);
            codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME));
            codec.configure(fmt, null, null, 0);
            codec.start();

            int sampleRate = 44100;
            try {
                if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                }
            } catch (Throwable ignored) { }
            int window = Math.max(1, sampleRate / 10);   // 100ms

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long endUs = maxMs * 1000L;
            boolean inputDone = false;
            int guard = 0;
            long winSum = 0;
            int winCount = 0;
            int loud = 0;

            while (guard++ < 6000) {
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10000);
                    if (in >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(in);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long t = ex.getSampleTime();
                            codec.queueInputBuffer(in, 0, n, t, 0);
                            ex.advance();
                            if (t > endUs) inputDone = true;
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10000);
                if (out >= 0) {
                    if (info.size > 0) {
                        ByteBuffer b = codec.getOutputBuffer(out);
                        if (b != null) {
                            b.order(ByteOrder.LITTLE_ENDIAN);
                            int end = info.offset + info.size;
                            for (int i = info.offset; i + 1 < end; i += 2) {
                                int s = b.getShort(i);
                                winSum += (long) s * s;
                                if (++winCount >= window) {
                                    double rms = Math.sqrt((double) winSum / winCount);
                                    if (rms >= RMS_THRESHOLD && ++loud >= MIN_LOUD_WINDOWS) {
                                        return 1;              // 已经够「有声」，不用再解
                                    }
                                    winSum = 0;
                                    winCount = 0;
                                }
                            }
                        }
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(out, false);
                    if (eos) break;
                } else if (out == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    break;
                }
            }
            return 0;   // 没有一个窗口达标 → 静音
        } catch (Throwable t) {
            return UNKNOWN;
        } finally {
            try { if (codec != null) codec.stop(); } catch (Throwable ignored) { }
            try { if (codec != null) codec.release(); } catch (Throwable ignored) { }
            try { ex.release(); } catch (Throwable ignored) { }
        }
    }
}
