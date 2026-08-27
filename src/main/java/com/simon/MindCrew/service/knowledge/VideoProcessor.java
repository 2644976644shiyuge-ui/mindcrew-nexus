package com.simon.MindCrew.service.knowledge;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 视频处理器。
 *
 * 处理链路：
 *   1. FFmpeg 抽音轨 → 保存为本地 mp3
 *   2. 音轨上传到 MinIO/OSS → 调 AudioTranscriber 走 ASR 拿带时间戳的句子
 *   3. FFmpeg 按间隔抽关键帧 → 每帧调 VisionRecognizer 拿 OCR + 描述
 *   4. 字幕文件（同目录的 .srt / .vtt）也合并解析（如有）
 *   5. 返回结构化结果（音频句子 + 关键帧描述，都带时间戳）
 *
 * 配置 application.yml:
 *   video:
 *     ffmpeg-path: ffmpeg              # Mac: /opt/homebrew/bin/ffmpeg
 *     ffprobe-path: ffprobe
 *     keyframe-interval: 30            # 每 N 秒抽 1 帧
 *     keyframe-max-count: 200          # 最多抽多少帧（避免长视频爆炸）
 *     ocr-min-text-length: 4           # 关键帧 OCR 出来的文字少于这个长度就只保留描述
 *     audio-format: mp3                # 抽音轨编码
 *
 * 部署前置: brew install ffmpeg / apt-get install ffmpeg
 * 验证: ffmpeg -version
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VideoProcessor {

    private final FileStorageService fileStorage;
    private final AudioTranscriber audioTranscriber;
    private final VisionRecognizer visionRecognizer;
    private final QwenVideoUnderstandingService qwenVideoUnderstanding;
    private final com.simon.MindCrew.config.AiConfigHolder aiConfigHolder;

    /**
     * 处理模式（yml 默认值，实际以「AI 配置 → 解析模型 → 视频理解方式」为准·可视化切换）：
     *   qwen-vl  · 原生 Qwen-VL 视频理解（准确率高 · 成本高）
     *   legacy   · FFmpeg 抽音轨 ASR + 抽帧 VL（口播/访谈类性价比高 · 失败回退）
     */
    @Value("${video.understanding.mode:qwen-vl}")
    private String understandingMode;

    /** 运行时取处理模式：优先后台 DB 配置 video.mode，回退 yml 默认 */
    private String activeMode() {
        return aiConfigHolder.getStringOrDefault("video.mode", understandingMode);
    }

    /** Qwen-VL 单次调用最长视频秒数；超过此长度按此切片 */
    @Value("${video.understanding.max-seconds-per-call:60}")
    private int maxSecondsPerCall;

    /** Qwen-VL 多切片并行度 · 长视频切多段时并行调用，3 并发兼顾提速与限流 */
    @Value("${video.understanding.parallelism:3}")
    private int understandingParallelism;

    @Value("${video.ffmpeg-path:ffmpeg}")
    private String ffmpegPath;

    @Value("${video.ffprobe-path:ffprobe}")
    private String ffprobePath;

    @Value("${video.keyframe-interval:30}")
    private int keyframeInterval;

    @Value("${video.keyframe-max-count:200}")
    private int keyframeMaxCount;

    @Value("${video.ocr-min-text-length:4}")
    private int ocrMinTextLength;

    @Value("${video.audio-format:mp3}")
    private String audioFormat;

    @Value("${video.ffmpeg-timeout-seconds:600}")
    private int ffmpegTimeoutSeconds;

    @Value("${storage.type:minio}")
    private String storageType;

    @Value("${minio.endpoint:http://localhost:9000}")
    private String minioEndpoint;

    /**
     * 启动时自动探测 ffmpeg/ffprobe 真实路径。
     * 如果 yml 配的是默认 "ffmpeg" 但 PATH 找不到，尝试 Homebrew/系统常见路径。
     */
    @PostConstruct
    public void autoDetectBinary() {
        ffmpegPath  = probeBinary(ffmpegPath, "ffmpeg",
                "/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg");
        ffprobePath = probeBinary(ffprobePath, "ffprobe",
                "/opt/homebrew/bin/ffprobe", "/usr/local/bin/ffprobe", "/usr/bin/ffprobe");
        log.info("[Video] 二进制路径已确认 · ffmpeg={} · ffprobe={}", ffmpegPath, ffprobePath);
    }

    private String probeBinary(String configured, String name, String... fallbacks) {
        // 先试配置值
        if (tryRun(configured)) return configured;
        log.warn("[Video] 配置的 {} 不可用: {} · 自动探测中…", name, configured);
        // 再依次试常见路径
        for (String p : fallbacks) {
            if (Files.exists(Paths.get(p)) && tryRun(p)) {
                log.info("[Video] {} 自动定位到: {}", name, p);
                return p;
            }
        }
        log.error("[Video] !!! 找不到 {} !!! 请 `brew install ffmpeg` 或在 yml 配 video.{}-path 绝对路径", name, name);
        return configured;   // 保留原值，让真正调用时报错带 PATH
    }

    private boolean tryRun(String bin) {
        try {
            ProcessBuilder pb = new ProcessBuilder(bin, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) { p.destroyForcibly(); return false; }
            return p.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 本地 MinIO 公网不可达 → DashScope ASR 调用必败 */
    private boolean isAsrUnreachable() {
        if (storageType == null || !"minio".equalsIgnoreCase(storageType)) return false;
        return minioEndpoint != null
                && (minioEndpoint.contains("localhost") || minioEndpoint.contains("127.0.0.1"));
    }

    // ─────────────────────────────────────────────
    // 数据结构
    // ─────────────────────────────────────────────

    /** 视频中的一个音频句子（来自 ASR） */
    public record AudioSegment(
            int index,
            String text,
            long startMs,
            long endMs,
            String speakerId
    ) {}

    /**
     * 视频中的一个视觉片段
     *   - legacy 抽帧管线：timeMs = 帧时间点，endMs 留 0（DocumentProcessTask 默认补 30s 窗口）
     *   - Qwen-VL 管线：timeMs = startMs，endMs = 片段真实结束时间
     */
    public record KeyframeSegment(
            int index,
            long timeMs,             // 起始时间（兼容旧名）
            long endMs,              // 结束时间 · 任务 1.8 v3 新增 · 0 表示按 30s 默认窗口推算
            String ocrText,          // OCR 出的文字（可能为空）
            String description       // VL 描述
    ) {
        /** 旧 4 参构造（legacy 抽帧管线兼容用 · endMs=0） */
        public KeyframeSegment(int index, long timeMs, String ocrText, String description) {
            this(index, timeMs, 0L, ocrText, description);
        }
        public String toIndexedText() {
            StringBuilder sb = new StringBuilder();
            if (description != null && !description.isBlank()) {
                sb.append("【画面描述】").append(description);
            }
            if (ocrText != null && !ocrText.isBlank()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("【画面文字】").append(ocrText);
            }
            return sb.toString().trim();
        }
    }

    /** 完整结果 */
    public record VideoParseResult(
            boolean success,
            String errorMsg,
            long durationMs,
            String videoObjectName,
            List<AudioSegment> audioSegments,
            List<KeyframeSegment> keyframes,
            List<String> diagnostics,     // 每步失败的具体原因，按时间顺序
            long totalTokens              // Qwen-VL 真实 token 用量（准确记账），未知=0
    ) {
        /** 兼容旧的不带 token 的构造（token 默认 0） */
        public VideoParseResult(boolean success, String errorMsg, long durationMs, String videoObjectName,
                                List<AudioSegment> audioSegments, List<KeyframeSegment> keyframes,
                                List<String> diagnostics) {
            this(success, errorMsg, durationMs, videoObjectName, audioSegments, keyframes, diagnostics, 0L);
        }
        public static VideoParseResult fail(String msg) {
            return new VideoParseResult(false, msg, 0L, null, List.of(), List.of(), List.of());
        }
        public boolean hasContent() {
            return !audioSegments.isEmpty() || !keyframes.isEmpty();
        }
        public String diagnosticsText() {
            return diagnostics == null || diagnostics.isEmpty()
                    ? "（无诊断信息）"
                    : String.join(" | ", diagnostics);
        }
    }

    // ─────────────────────────────────────────────
    // 主入口
    // ─────────────────────────────────────────────
    public VideoParseResult process(Path localVideoFile, String videoObjectName) {
        long t0 = System.currentTimeMillis();
        log.info("[Video] 开始处理: {}", localVideoFile);

        // 每步失败/警告原因都往这里塞，最终回传给调用方
        List<String> diags = new ArrayList<>();

        // 0. 前置体检：ffmpeg 是否可用
        String ffmpegCheck = checkFfmpegAvailable();
        if (ffmpegCheck != null) {
            diags.add(ffmpegCheck);
            return new VideoParseResult(false, "FFmpeg 不可用: " + ffmpegCheck, 0L,
                    videoObjectName, List.of(), List.of(), diags);
        }

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("video-proc-");

            // 1. 先探测时长
            long durationMs = probeDuration(localVideoFile);
            log.info("[Video] 时长 = {}ms ({} 秒) · 模式 = {}",
                    durationMs, durationMs / 1000, understandingMode);

            // 2. 选择处理模式（后台可视化切换）
            if ("qwen-vl".equalsIgnoreCase(activeMode())) {
                // 新管线：Qwen-VL 视频原生理解
                VideoParseResult r = processWithQwenVL(localVideoFile, workDir, videoObjectName, durationMs, diags);
                if (r != null) {
                    log.info("[Video] Qwen-VL 处理完成 用时={}ms 段数={} 诊断={}",
                            System.currentTimeMillis() - t0,
                            r.audioSegments().size() + r.keyframes().size(), diags);
                    return r;
                }
                // 失败 → 回退到 legacy
                diags.add("Qwen-VL 处理失败，自动回退 legacy ASR+VL 管线");
                log.warn("[Video] Qwen-VL 失败，回退 legacy 管线");
            }

            // legacy: 抽音轨 + ASR + 抽帧 + VL（兜底/旧管线）
            List<AudioSegment> audioSegments;
            if (isAsrUnreachable()) {
                diags.add("已跳过 ASR · 本地 MinIO (" + minioEndpoint + ") 公网不可达，DashScope 拉不到音轨。"
                        + "切到 OSS (storage.type=oss) 即可启用 ASR");
                log.info("[Video] 跳过 ASR · 走 VL-only 模式（本地 MinIO）");
                audioSegments = List.of();
            } else {
                Path audioFile = extractAudio(localVideoFile, workDir, diags);
                audioSegments = audioFile != null ? transcribeAudio(audioFile, diags) : List.of();
            }
            List<KeyframeSegment> keyframes = extractAndRecognizeKeyframes(localVideoFile, workDir, durationMs, diags);

            log.info("[Video] legacy 处理完成 用时={}ms 音频句={} 关键帧={} 诊断={}",
                    System.currentTimeMillis() - t0, audioSegments.size(), keyframes.size(), diags);

            return new VideoParseResult(true, null, durationMs,
                    videoObjectName, audioSegments, keyframes, diags);
        } catch (Exception e) {
            log.error("[Video] 处理失败", e);
            diags.add("处理流程异常: " + e.getMessage());
            return new VideoParseResult(false, "视频处理失败: " + e.getMessage(),
                    0L, videoObjectName, List.of(), List.of(), diags);
        } finally {
            cleanupSilently(workDir);
        }
    }

    /** 启动前体检 ffmpeg 是否在 PATH 上 */
    private String checkFfmpegAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(ffmpegPath, "-version");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(8, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "ffmpeg -version 超时（PATH: " + ffmpegPath + "）";
            }
            if (p.exitValue() != 0) {
                return "ffmpeg -version 退出码非 0（PATH: " + ffmpegPath + "）";
            }
            return null;
        } catch (Exception e) {
            return "无法执行 ffmpeg（PATH: " + ffmpegPath + "）: " + e.getMessage()
                    + " · 解决: brew install ffmpeg 或 在 yml 配 video.ffmpeg-path 为绝对路径";
        }
    }

    // ─────────────────────────────────────────────
    // 1. 抽音轨
    // ─────────────────────────────────────────────
    private Path extractAudio(Path video, Path workDir, List<String> diags) {
        Path audioOut = workDir.resolve("audio." + audioFormat);
        // -vn 不要视频，-ar 16000 ASR 友好采样率，-ac 1 单声道
        List<String> cmd = List.of(
                ffmpegPath, "-y", "-i", video.toString(),
                "-vn", "-ar", "16000", "-ac", "1",
                audioOut.toString()
        );
        FfmpegOutcome r = runFfmpegWithStderr(cmd, "抽音轨");
        if (!r.ok || !Files.exists(audioOut)) {
            String reason = r.errorTail == null || r.errorTail.isBlank()
                    ? "ffmpeg 抽音轨失败（exit=" + r.exitCode + "）"
                    : "ffmpeg 抽音轨失败: " + truncate(r.errorTail, 200);
            diags.add(reason);
            log.warn("[Video] {}", reason);
            return null;
        }
        try {
            long size = Files.size(audioOut);
            log.info("[Video] 音轨已抽出 {} KB", size / 1024);
            if (size == 0) {
                diags.add("音轨抽出 0 字节（视频无音频流？）");
                return null;
            }
        } catch (IOException ignored) {}
        return audioOut;
    }

    /**
     * 把任意音频归一化为 DashScope ASR 友好格式（16kHz 单声道 mp3）。
     * 用途：纯音频上传前先转码，避免 48kHz/奇怪编码触发 DECODE_ERROR；
     *       ffmpeg 重编码会跳过坏帧重新封装，能抢救部分轻度损坏的文件。
     * @return 转码后的临时文件路径（调用方用完需删除）；失败返回 null。
     */
    public Path normalizeAudioForAsr(Path input) {
        try {
            Path out = Files.createTempFile("asr-norm-", ".mp3");
            List<String> cmd = List.of(
                    ffmpegPath, "-y",
                    "-err_detect", "ignore_err",      // 容忍坏帧，尽量重封装
                    "-i", input.toString(),
                    "-vn", "-ar", "16000", "-ac", "1", "-b:a", "64k",
                    out.toString());
            FfmpegOutcome r = runFfmpegWithStderr(cmd, "音频归一化(ASR)");
            if (r.ok && Files.exists(out) && Files.size(out) > 0) {
                log.info("[Video] 音频已归一化为 16kHz 单声道: {} -> {} ({} KB)",
                        input.getFileName(), out.getFileName(), Files.size(out) / 1024);
                return out;
            }
            Files.deleteIfExists(out);
            log.warn("[Video] 音频归一化失败（exit={}）· 退回原文件", r.exitCode);
            return null;
        } catch (Exception e) {
            log.warn("[Video] 音频归一化异常: {} · 退回原文件", e.getMessage());
            return null;
        }
    }

    /**
     * 从视频/音频里抽取「克隆用」音轨：24kHz 单声道 128k mp3。
     * 比 ASR 归一化(16kHz/64k) 保真度高，适合音色复刻；失败返回 null。
     */
    public Path extractAudioForClone(Path input) {
        try {
            Path out = Files.createTempFile("clone-audio-", ".mp3");
            List<String> cmd = List.of(
                    ffmpegPath, "-y",
                    "-err_detect", "ignore_err",
                    "-i", input.toString(),
                    "-vn", "-ar", "24000", "-ac", "1", "-b:a", "128k",
                    out.toString());
            FfmpegOutcome r = runFfmpegWithStderr(cmd, "音频抽取(克隆)");
            if (r.ok && Files.exists(out) && Files.size(out) > 0) {
                log.info("[Video] 已抽取克隆音轨: {} -> {} ({} KB)",
                        input.getFileName(), out.getFileName(), Files.size(out) / 1024);
                return out;
            }
            Files.deleteIfExists(out);
            log.warn("[Video] 克隆音轨抽取失败（exit={}）", r.exitCode);
            return null;
        } catch (Exception e) {
            log.warn("[Video] 克隆音轨抽取异常: {}", e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────
    // 2. 音轨 ASR
    // ─────────────────────────────────────────────
    private List<AudioSegment> transcribeAudio(Path audioFile, List<String> diags) {
        String objectName = null;
        try {
            // 把音轨上传到 storage，拿预签名 URL 调 ASR
            objectName = fileStorage.uploadLocalFile(
                    audioFile, "video-audio", "audio/" + audioFormat);
            String url = fileStorage.getFileUrl(objectName);

            // 重要：DashScope ASR 要求音轨 URL 公网可访问
            // 本地 MinIO 的 localhost URL 它访问不到，会失败
            if (url != null && (url.contains("localhost") || url.contains("127.0.0.1"))) {
                diags.add("⚠ MinIO 音轨 URL 包含 localhost，DashScope 无法访问（"
                        + url + "）· 解决: 切到 OSS (storage.type=oss) 或本地用 ngrok 暴露 MinIO");
            }

            AudioTranscriber.TranscriptionResult r = audioTranscriber.transcribe(url);

            if (!r.success()) {
                String reason = "ASR 失败: " + truncate(r.errorMsg(), 250);
                diags.add(reason);
                log.warn("[Video] {}", reason);
                return List.of();
            }
            List<AudioSegment> out = new ArrayList<>();
            for (AudioTranscriber.Sentence s : r.sentences()) {
                if (s.text() == null || s.text().isBlank()) continue;
                out.add(new AudioSegment(s.index(), s.text(), s.startMs(), s.endMs(), s.speakerId()));
            }
            if (out.isEmpty()) {
                // ASR 成功但无语音内容（SUCCESS_WITH_NO_VALID_FRAGMENT）
                // 这是预期内的情况（静音/纯音乐/无人声视频），不是系统故障
                String note = (r.errorMsg() != null && !r.errorMsg().isBlank())
                        ? "ASR: " + truncate(r.errorMsg(), 200)
                        : "ASR 成功但识别结果为空（音频可能没有人声）";
                diags.add(note);
                log.warn("[Video] {}", note);
            }
            return out;
        } catch (Exception e) {
            String reason = "ASR 阶段异常: " + e.getClass().getSimpleName() + " · " + e.getMessage();
            diags.add(reason);
            log.warn("[Video] {}", reason);
            return List.of();
        } finally {
            // ASR 完成后清理 storage 上的临时音轨
            if (objectName != null) {
                try { fileStorage.deleteObject(objectName); } catch (Exception ignored) {}
            }
        }
    }

    // ─────────────────────────────────────────────
    // 3. 抽关键帧 + VL 识别
    //
    // 帧采样策略（自适应，保证短视频也能正确识别）：
    //   - 计算目标帧数 = clamp(duration / interval, MIN_FRAMES, MAX_FRAMES)
    //   - 在视频时长内等距取 N 个时间点（避免取到 0 秒和末尾）
    //   - 对每个时间点用 ffmpeg -ss 精确 seek 抽 1 张图（比 fps 滤镜更可控）
    //
    // 示例：
    //   4 秒视频 → 取 3 帧（1s, 2s, 3s）
    //   60 秒视频 → 取 3 帧（15s, 30s, 45s）
    //   600 秒视频 → 取 20 帧（每 30 秒一帧）
    //   3600 秒视频 → 取 120 帧（被 max 限制为 200 内）
    // ─────────────────────────────────────────────
    private static final int MIN_KEYFRAMES = 3;

    private List<KeyframeSegment> extractAndRecognizeKeyframes(Path video, Path workDir, long durationMs, List<String> diags) {
        Path framesDir = workDir.resolve("frames");
        try {
            Files.createDirectories(framesDir);
        } catch (IOException e) {
            String reason = "关键帧目录创建失败: " + e.getMessage();
            diags.add(reason);
            log.warn("[Video] {}", reason);
            return List.of();
        }

        // 计算采样时间点
        long durSec = Math.max(1, durationMs / 1000);
        int targetFrames = (int) Math.min(keyframeMaxCount,
                Math.max(MIN_KEYFRAMES, durSec / keyframeInterval));
        // 等距分布 · 避开首尾（开头可能黑屏、结尾可能淡出）
        double[] tsSeconds = new double[targetFrames];
        if (targetFrames == 1) {
            tsSeconds[0] = durSec / 2.0;
        } else {
            double step = (double) durSec / (targetFrames + 1);
            for (int i = 0; i < targetFrames; i++) {
                tsSeconds[i] = step * (i + 1);
            }
        }

        log.info("[Video] 计划抽 {} 帧 (时长 {}s, 间隔 {}s) · 时间点 = {}",
                targetFrames, durSec, keyframeInterval,
                Arrays.stream(tsSeconds).mapToObj(t -> String.format("%.1fs", t)).toList());

        // 逐帧抽取
        List<Path> extractedFrames = new ArrayList<>();
        for (int i = 0; i < tsSeconds.length; i++) {
            double ts = tsSeconds[i];
            Path outFile = framesDir.resolve(String.format("frame_%04d.jpg", i + 1));
            // -ss 在 -i 前：快速 seek（关键帧定位，速度快但可能不精准）
            // -ss 在 -i 后：精确 seek（解码到时间点，慢但精准）
            // 这里用前置 -ss 的快速版，对短视频影响小，效率优先
            List<String> cmd = List.of(
                    ffmpegPath, "-y",
                    "-ss", String.format("%.3f", ts),
                    "-i", video.toString(),
                    "-frames:v", "1",
                    "-vf", "scale='min(1280,iw)':-2,format=yuvj420p",
                    "-q:v", "2",
                    "-pix_fmt", "yuvj420p",
                    outFile.toString()
            );
            FfmpegOutcome r = runFfmpegWithStderr(cmd, "抽帧@" + String.format("%.1fs", ts));
            if (r.ok && Files.exists(outFile)) {
                try {
                    if (Files.size(outFile) > 0) extractedFrames.add(outFile);
                } catch (IOException ignored) {}
            } else if (!r.ok) {
                // 单帧失败不致命，继续抽下一帧（可能是该时间点恰好是损坏帧）
                log.warn("[Video] 抽帧 @{}s 失败 (exit={}): {}", ts, r.exitCode,
                        truncate(r.errorTail, 150));
            }
        }

        if (extractedFrames.isEmpty()) {
            diags.add("所有时间点均未成功抽帧（视频可能损坏或编码格式不支持）");
            return List.of();
        }

        log.info("[Video] 实际抽出 {} 帧，开始 VL 识别", extractedFrames.size());

        // VL 识别
        List<KeyframeSegment> result = new ArrayList<>();
        int vlFailCount = 0;
        String firstVlError = null;
        for (int i = 0; i < extractedFrames.size(); i++) {
            Path frame = extractedFrames.get(i);
            long timeMs = (long) (tsSeconds[i] * 1000);
            try {
                byte[] data = Files.readAllBytes(frame);
                VisionRecognizer.VisionResult vr = visionRecognizer.recognize(data, "image/jpeg");
                String ocr = (vr.ocrText() != null && vr.ocrText().length() >= ocrMinTextLength)
                        ? vr.ocrText() : "";
                String desc = vr.description() == null ? "" : vr.description();
                if (ocr.isBlank() && desc.isBlank()) continue;
                result.add(new KeyframeSegment(i + 1, timeMs, ocr, desc));
            } catch (Exception e) {
                vlFailCount++;
                if (firstVlError == null) firstVlError = e.getMessage();
                log.warn("[Video] 第 {} 帧 VL 失败: {}", i + 1, e.getMessage());
            }
        }

        if (result.isEmpty()) {
            diags.add(vlFailCount > 0
                    ? "VL 识别全部失败（" + vlFailCount + "帧）· 首个错误: " + truncate(firstVlError, 180)
                    : "VL 识别返回内容均为空（视频画面可能为纯黑/纯色/无意义内容）");
        }
        return result;
    }

    // ─────────────────────────────────────────────
    // 4. 探测视频时长
    // ─────────────────────────────────────────────
    private long probeDuration(Path video) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffprobePath, "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "csv=p=0", video.toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean ok = p.waitFor(30, TimeUnit.SECONDS);
            if (!ok) { p.destroyForcibly(); return 0L; }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return (long) (Double.parseDouble(out) * 1000);
        } catch (Exception e) {
            log.warn("[Video] 时长探测失败: {}", e.getMessage());
            return 0L;
        }
    }

    // ─────────────────────────────────────────────
    // FFmpeg 调用 · 带 stderr 回传
    // ─────────────────────────────────────────────
    private record FfmpegOutcome(boolean ok, int exitCode, String errorTail) {}

    private FfmpegOutcome runFfmpegWithStderr(List<String> cmd, String stepName) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // 提前读输出，避免管道写满 hung
            String output;
            try (var in = p.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            boolean finished = p.waitFor(ffmpegTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                log.warn("[Video] {} 超时 (>{}s)", stepName, ffmpegTimeoutSeconds);
                return new FfmpegOutcome(false, -1, "超时 >" + ffmpegTimeoutSeconds + "s");
            }
            int exit = p.exitValue();
            if (exit != 0) {
                String tail = output.length() > 500 ? output.substring(output.length() - 500) : output;
                log.warn("[Video] {} 失败 exit={}: {}", stepName, exit, tail);
                return new FfmpegOutcome(false, exit, tail);
            }
            return new FfmpegOutcome(true, 0, null);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new FfmpegOutcome(false, -1, "线程被中断");
        } catch (Exception e) {
            log.warn("[Video] {} 异常: {}", stepName, e.getMessage());
            return new FfmpegOutcome(false, -1, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private void cleanupSilently(Path dir) {
        if (dir == null) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (Exception ignored) {}
    }

    /** 当前支持的视频扩展名 */
    public static List<String> supportedExtensions() {
        return List.of("mp4", "mov", "mkv", "avi", "flv", "webm", "m4v");
    }

    // ═════════════════════════════════════════════════════════════════════
    // Qwen-VL 视频原生理解（任务 1.8 v3 · 替代旧 ASR+VL 双管线）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * 用 Qwen-VL 一次性理解视频（画面 + 音频）。
     * 长视频先 ffmpeg 切片，每段独立调用，最后合并时间轴。
     *
     * @return VideoParseResult · 失败返回 null 让上层回退到 legacy 管线
     */
    private VideoParseResult processWithQwenVL(Path localVideoFile, Path workDir, String videoObjectName,
                                                long durationMs, List<String> diags) {
        // 必须公网可达；本地 MinIO 直接不行
        if (isAsrUnreachable()) {
            diags.add("Qwen-VL 需要公网可达 URL，本地 MinIO (" + minioEndpoint + ") 不支持。请部署 OSS 或回退 legacy。");
            log.warn("[Video.QwenVL] 本地 MinIO 不可达，跳过 Qwen-VL");
            return null;
        }

        try {
            // 1) 决定要不要切片
            long maxMs = (long) maxSecondsPerCall * 1000;
            List<VideoSlice> slices = new ArrayList<>();
            if (durationMs <= maxMs) {
                // 短视频直传
                slices.add(new VideoSlice(localVideoFile, videoObjectName, 0L));
            } else {
                // 切片
                long sliceCount = (durationMs + maxMs - 1) / maxMs;
                log.info("[Video.QwenVL] 视频 {}ms > {}s，切 {} 段", durationMs, maxSecondsPerCall, sliceCount);
                for (long i = 0; i < sliceCount; i++) {
                    long offsetMs = i * maxMs;
                    long sliceDurMs = Math.min(maxMs, durationMs - offsetMs);
                    Path sliceFile = workDir.resolve("slice-" + i + ".mp4");
                    try {
                        sliceVideo(localVideoFile, sliceFile, offsetMs, sliceDurMs);
                    } catch (Exception e) {
                        diags.add("切片失败 #" + i + ": " + e.getMessage());
                        log.warn("[Video.QwenVL] 切片失败 idx={} err={}", i, e.getMessage());
                        continue;
                    }
                    // 上传切片到 OSS 拿公网 URL
                    String sliceObjectName = uploadSlice(sliceFile, videoObjectName, i);
                    if (sliceObjectName == null) {
                        diags.add("切片上传失败 #" + i);
                        continue;
                    }
                    slices.add(new VideoSlice(sliceFile, sliceObjectName, offsetMs));
                }
            }

            if (slices.isEmpty()) {
                diags.add("无可用切片，Qwen-VL 处理终止");
                return null;
            }

            // 2) 逐段调 Qwen-VL · 短视频 1 段直接调；长视频多段并行（理解调用每段 20-60s，是耗时大头）
            //    每段结果按切片下标占位，最后按时间轴顺序合并
            List<List<QwenVideoUnderstandingService.VideoSegment>> perSlice =
                    new ArrayList<>(java.util.Collections.nCopies(slices.size(), null));
            List<String> safeDiags = java.util.Collections.synchronizedList(diags);
            java.util.concurrent.atomic.AtomicLong totalTokens = new java.util.concurrent.atomic.AtomicLong(0);
            int parallelism = Math.max(1, Math.min(understandingParallelism, slices.size()));
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                    parallelism, r -> { Thread t = new Thread(r, "qwenvl-slice"); t.setDaemon(true); return t; });
            try {
                List<java.util.concurrent.CompletableFuture<Void>> tasks = new ArrayList<>(slices.size());
                for (int si = 0; si < slices.size(); si++) {
                    final int idx = si;
                    final VideoSlice slice = slices.get(si);
                    tasks.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                        String url = fileStorage.getFileUrl(slice.objectName);
                        if (url == null || url.isBlank()) {
                            safeDiags.add("无法获取切片 URL: " + slice.objectName);
                            perSlice.set(idx, List.of());
                            return;
                        }
                        try {
                            QwenVideoUnderstandingService.VlResult vl =
                                    qwenVideoUnderstanding.understand(url, slice.offsetMs);
                            totalTokens.addAndGet(vl.totalTokens());
                            if (vl.segments().isEmpty()) safeDiags.add("Qwen-VL 返回 0 段 · offset=" + slice.offsetMs);
                            perSlice.set(idx, vl.segments());
                        } catch (Exception e) {
                            safeDiags.add("Qwen-VL 调用失败 offset=" + slice.offsetMs + "ms: " + e.getMessage());
                            log.warn("[Video.QwenVL] understand 失败 offset={} err={}", slice.offsetMs, e.getMessage());
                            perSlice.set(idx, List.of());
                        }
                    }, pool));
                }
                try {
                    java.util.concurrent.CompletableFuture.allOf(
                                    tasks.toArray(new java.util.concurrent.CompletableFuture[0]))
                            .get(30, java.util.concurrent.TimeUnit.MINUTES);
                } catch (Exception e) {
                    diags.add("Qwen-VL 并行处理超时/异常: " + e.getMessage());
                    log.warn("[Video.QwenVL] 并行等待异常: {}", e.getMessage());
                }
            } finally {
                pool.shutdownNow();
            }

            // 按切片顺序合并，保持时间轴递增 · 映射回 AudioSegment + KeyframeSegment（保持上游兼容）
            List<AudioSegment> audioSegments = new ArrayList<>();
            List<KeyframeSegment> keyframes = new ArrayList<>();
            int audioIdx = 0;
            int frameIdx = 0;
            for (List<QwenVideoUnderstandingService.VideoSegment> segs : perSlice) {
                if (segs == null) continue;
                for (QwenVideoUnderstandingService.VideoSegment s : segs) {
                    if (s.getSpeech() != null && !s.getSpeech().isBlank()) {
                        audioSegments.add(new AudioSegment(
                                audioIdx++, s.getSpeech(), s.getStartMs(), s.getEndMs(), null));
                    }
                    if ((s.getVisual() != null && !s.getVisual().isBlank())
                            || (s.getKeywords() != null && !s.getKeywords().isEmpty())) {
                        // visual 描述 + keywords 拼成 description；ocrText 留空（Qwen-VL 没拆开）
                        StringBuilder desc = new StringBuilder();
                        if (s.getVisual() != null && !s.getVisual().isBlank()) {
                            desc.append(s.getVisual());
                        }
                        if (s.getKeywords() != null && !s.getKeywords().isEmpty()) {
                            if (desc.length() > 0) desc.append(" | ");
                            desc.append("关键词: ").append(String.join(", ", s.getKeywords()));
                        }
                        // Qwen-VL 段：传完整 startMs + endMs，避免被上游用默认 30s 窗口覆盖
                        keyframes.add(new KeyframeSegment(
                                frameIdx++, s.getStartMs(), s.getEndMs(), "", desc.toString()));
                    }
                }
            }

            if (audioSegments.isEmpty() && keyframes.isEmpty()) {
                diags.add("Qwen-VL 处理结果为空（所有切片均失败/无内容）");
                return null;
            }

            return new VideoParseResult(true, null, durationMs,
                    videoObjectName, audioSegments, keyframes, diags, totalTokens.get());
        } catch (Exception e) {
            log.error("[Video.QwenVL] 处理异常", e);
            diags.add("Qwen-VL 处理异常: " + e.getMessage());
            return null;
        }
    }

    /** ffmpeg 切片 · 不重编码，复制原编码 */
    private void sliceVideo(Path source, Path target, long offsetMs, long durationMs) throws Exception {
        double offsetSec = offsetMs / 1000.0;
        double durSec = durationMs / 1000.0;
        ProcessBuilder pb = new ProcessBuilder(
                ffmpegPath,
                "-y",
                "-ss", String.format(java.util.Locale.US, "%.3f", offsetSec),
                "-i", source.toAbsolutePath().toString(),
                "-t", String.format(java.util.Locale.US, "%.3f", durSec),
                "-c", "copy",
                "-avoid_negative_ts", "make_zero",
                target.toAbsolutePath().toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        if (!p.waitFor(120, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new RuntimeException("ffmpeg 切片超时");
        }
        if (p.exitValue() != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            throw new RuntimeException("ffmpeg 切片失败 exit=" + p.exitValue() + " · " + out);
        }
    }

    /** 上传切片到对象存储，返回对象名；失败返回 null */
    private String uploadSlice(Path sliceFile, String originalObjectName, long idx) {
        try {
            // 同目录下放 slices 子目录，方便日后清理
            String dir = "video-slices/" + originalObjectName.replace('/', '_');
            return fileStorage.uploadLocalFile(sliceFile, dir, "video/mp4");
        } catch (Exception e) {
            log.warn("[Video.QwenVL] 切片上传失败 idx={} err={}", idx, e.getMessage());
            return null;
        }
    }

    /** 内部 · 单段切片信息 */
    private record VideoSlice(Path localFile, String objectName, long offsetMs) {}
}
