package com.videotolive.app;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * GPU 加速视频转码 (MediaCodec Buffer 模式)
 *
 * 解码(Buffer) → 编码(Buffer) → Muxer
 * 兼容性优先，避免了 Surface 直连的格式匹配问题
 */
public class GpuConverter {

    private static final String TAG = "GpuConverter";
    private static final String MIME_OUT = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static int calcBitrate(int w, int h) {
        int pixels = w * h;
        if (pixels > 3840 * 2160) return 25_000_000;  // 4K+ 高运动
        else if (pixels > 1920 * 1080) return 15_000_000; // >1080p
        else return 8_000_000; // ≤1080p
    }
    private static final int IFRAME_INTERVAL = 1;

    public interface LogListener {
        void onLog(String msg);
    }

    public static boolean transcode(String inputPath, String outputPath,
                                     double startSec, double durationSec,
                                     int maxW, int maxH, int bitrate,
                                     LogListener logL) {
        MediaExtractor extractor = null;
        MediaCodec decoder = null, encoder = null;
        MediaMuxer muxer = null;
        LogListener L = msg -> { Log.d(TAG, msg); if (logL != null) logL.onLog(msg); };

        try {
            // ── 1. 提取器 ──
            extractor = new MediaExtractor();
            extractor.setDataSource(inputPath);

            int videoTrack = -1;
            MediaFormat decFmt = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                L.onLog("轨道" + i + ": " + mime);
                if (mime != null && mime.startsWith("video/") && !mime.contains("dolby")) {
                    if (videoTrack < 0 || mime.contains("hevc") || mime.contains("avc")) {
                        videoTrack = i;
                        decFmt = f;
                    }
                }
            }
            if (videoTrack < 0) { L.onLog("GPU: 无可用视频轨道"); return false; }
            L.onLog(String.format("视频: %s %dx%d",
                decFmt.getString(MediaFormat.KEY_MIME),
                decFmt.getInteger(MediaFormat.KEY_WIDTH),
                decFmt.getInteger(MediaFormat.KEY_HEIGHT)));

            // ── 2. 输出分辨率：GPU Buffer 模式不缩放，码率控制体积 ──
            int inW = decFmt.getInteger(MediaFormat.KEY_WIDTH);
            int inH = decFmt.getInteger(MediaFormat.KEY_HEIGHT);
            int outW = inW, outH = inH;
            L.onLog(String.format("GPU: %dx%d @ %.1fMbps", outW, outH, bitrate / 1_000_000.0));

            // ── 3. 编码器 (Buffer 输入) ──
            MediaFormat encFmt = MediaFormat.createVideoFormat(MIME_OUT, outW, outH);
            encFmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            encFmt.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            encFmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL);
            encFmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);

            encoder = MediaCodec.createEncoderByType(MIME_OUT);
            L.onLog("编码器: " + encoder.getName());
            encoder.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            // ── 4. 解码器 (Buffer 输出) ──
            extractor.selectTrack(videoTrack);
            decoder = MediaCodec.createDecoderByType(decFmt.getString(MediaFormat.KEY_MIME));
            decoder.configure(decFmt, null, null, 0);
            decoder.start();
            L.onLog("解码器: " + decoder.getName());

            // ── 5. Muxer ──
            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxTrack = -1;
            boolean muxStarted = false;

            // ── 6. 帧处理循环 ──
            long startUs = (long) (startSec * 1_000_000);
            long endUs = startUs + (long) (durationSec * 1_000_000);
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean decDone = false, encDone = false;
            long timeOffset = -1;
            int inCount = 0, outCount = 0, encCount = 0;
            long t0 = System.currentTimeMillis();

            while (!encDone) {
                // 喂输入给解码器
                if (!decDone) {
                    int inIdx = decoder.dequeueInputBuffer(5000);
                    if (inIdx >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(inIdx);
                        if (buf != null) {
                            int size = extractor.readSampleData(buf, 0);
                            long pts = extractor.getSampleTime();
                            if (size > 0 && pts < endUs) {
                                if (timeOffset < 0) timeOffset = pts;
                                decoder.queueInputBuffer(inIdx, 0, size, pts, 0);
                                extractor.advance();
                                inCount++;
                            } else {
                                decoder.queueInputBuffer(inIdx, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                decDone = true;
                            }
                        }
                    }
                }

                // 取解码器输出 → 喂编码器
                int decIdx = decoder.dequeueOutputBuffer(info, 5000);
                if (decIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer decBuf = decoder.getOutputBuffer(decIdx);
                        // 喂到编码器
                        int encInIdx = encoder.dequeueInputBuffer(5000);
                        if (encInIdx >= 0) {
                            ByteBuffer encInBuf = encoder.getInputBuffer(encInIdx);
                            if (encInBuf != null && decBuf != null) {
                                encInBuf.clear();
                                encInBuf.put(decBuf);
                                encInBuf.flip();
                                encoder.queueInputBuffer(encInIdx, 0, info.size,
                                    info.presentationTimeUs, 0);
                            }
                        }
                        outCount++;
                    }
                    decoder.releaseOutputBuffer(decIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // 解码结束 → 发 EOS 给编码器
                        int eosIdx = encoder.dequeueInputBuffer(5000);
                        if (eosIdx >= 0) {
                            encoder.queueInputBuffer(eosIdx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        }
                    }
                }

                // 取编码器输出 → Muxer
                int encIdx = encoder.dequeueOutputBuffer(info, 5000);
                if (encIdx >= 0) {
                    ByteBuffer buf = encoder.getOutputBuffer(encIdx);
                    if (buf != null && info.size > 0) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            if (muxTrack < 0) {
                                muxTrack = muxer.addTrack(encoder.getOutputFormat());
                                muxer.start();
                                muxStarted = true;
                            }
                        } else if (muxTrack >= 0 && muxStarted) {
                            if (info.presentationTimeUs >= timeOffset)
                                info.presentationTimeUs -= timeOffset;
                            muxer.writeSampleData(muxTrack, buf, info);
                            encCount++;
                        }
                    }
                    encoder.releaseOutputBuffer(encIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        encDone = true;
                }

                if (System.currentTimeMillis() - t0 > 300_000) {
                    L.onLog("GPU: 超时"); return false;
                }
            }

            long elapsed = System.currentTimeMillis() - t0;
            L.onLog(String.format("GPU完成: in=%d out=%d enc=%d 耗时%.1fs",
                inCount, outCount, encCount, elapsed / 1000.0));
            return encCount > 0;

        } catch (Exception e) {
            L.onLog("GPU异常: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            return false;
        } finally {
            try { encoder.stop(); encoder.release(); } catch (Exception ig) {}
            try { decoder.stop(); decoder.release(); } catch (Exception ig) {}
            try { muxer.stop(); muxer.release(); } catch (Exception ig) {}
            try { extractor.release(); } catch (Exception ig) {}
        }
    }
}
