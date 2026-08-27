package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.ChatAttachment;
import com.simon.MindCrew.mapper.ChatAttachmentMapper;
import com.simon.MindCrew.service.knowledge.AudioTranscriber;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import com.simon.MindCrew.service.knowledge.VideoProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Executor;

/**
 * 问答页音视频附件的转写服务。
 *
 * 文档类附件（pdf/word/...）走同步提取（{@link com.simon.MindCrew.agent.MindCrewAgent} 内）；
 * 音视频转写慢（数十秒~数分钟），不能卡在一次问答里，故上传后异步转写、前端轮询。
 *
 * 复用既有能力：
 *   - 音频：{@link DocumentExtractor#transcribeAudio}（DashScope ASR）
 *   - 视频：{@link VideoProcessor#process}（FFmpeg 抽音轨/关键帧 + ASR + Qwen-VL）
 */
@Slf4j
@Service
public class ChatMediaService {

    private final ChatAttachmentMapper attachmentMapper;
    private final FileStorageService fileStorage;
    private final DocumentExtractor documentExtractor;
    private final VideoProcessor videoProcessor;
    private final Executor executor;

    public ChatMediaService(ChatAttachmentMapper attachmentMapper,
                            FileStorageService fileStorage,
                            DocumentExtractor documentExtractor,
                            VideoProcessor videoProcessor,
                            @Qualifier("docProcessExecutor") Executor executor) {
        this.attachmentMapper = attachmentMapper;
        this.fileStorage = fileStorage;
        this.documentExtractor = documentExtractor;
        this.videoProcessor = videoProcessor;
        this.executor = executor;
    }

    /** 转写文本注入上下文的字符上限（与文档附件口径一致，防止撑爆上下文） */
    private static final int TRANSCRIPT_CAP = 24000;

    /** 返回 "audio" / "video" / null（非音视频） */
    public static String mediaTypeOf(String ext) {
        if (ext == null) return null;
        String e = ext.toLowerCase();
        if (AudioTranscriber.supportedExtensions().contains(e)) return "audio";
        if (VideoProcessor.supportedExtensions().contains(e)) return "video";
        return null;
    }

    public static boolean isMedia(String ext) {
        return mediaTypeOf(ext) != null;
    }

    public ChatAttachment getByObjectName(String objectName) {
        if (StringUtils.isBlank(objectName)) return null;
        return attachmentMapper.selectOne(new LambdaQueryWrapper<ChatAttachment>()
                .eq(ChatAttachment::getObjectName, objectName).last("LIMIT 1"));
    }

    /** 取已就绪的转写文本；未就绪/失败返回 null */
    public String getReadyTranscript(String objectName) {
        ChatAttachment a = getByObjectName(objectName);
        if (a == null || !"ready".equals(a.getStatus())) return null;
        return a.getTranscript();
    }

    /**
     * 登记一条音视频附件并触发异步转写。立即返回（status=transcribing）。
     */
    public ChatAttachment registerAndTranscribe(String objectName, String originalName, String ext, Long userId) {
        String mediaType = mediaTypeOf(ext);
        if (mediaType == null) throw new IllegalArgumentException("非音视频附件，无需转写: " + ext);

        ChatAttachment a = new ChatAttachment();
        a.setObjectName(objectName);
        a.setOriginalName(originalName);
        a.setExt(ext.toLowerCase());
        a.setMediaType(mediaType);
        a.setStatus("transcribing");
        a.setChars(0);
        a.setOwnerUserId(userId);
        attachmentMapper.insert(a);

        final Long id = a.getId();
        executor.execute(() -> transcribe(id));
        return a;
    }

    /** 异步转写主体：下载对象 → 转写 → 回写 transcript/status。 */
    private void transcribe(Long attachmentId) {
        ChatAttachment a = attachmentMapper.selectById(attachmentId);
        if (a == null) return;
        Path temp = null;
        try {
            // 下载对象到本地临时文件（视频处理需要本地路径走 FFmpeg）
            temp = Files.createTempFile("chat-media-", "." + a.getExt());
            try (InputStream in = fileStorage.getFileStream(a.getObjectName())) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }

            String text = "audio".equals(a.getMediaType())
                    ? transcribeAudio(temp, a.getExt())
                    : transcribeVideo(temp, a.getObjectName());

            text = text == null ? "" : text.trim();
            if (text.isEmpty()) throw new RuntimeException("转写结果为空");
            if (text.length() > TRANSCRIPT_CAP) text = text.substring(0, TRANSCRIPT_CAP);

            ChatAttachment patch = new ChatAttachment();
            patch.setId(attachmentId);
            patch.setTranscript(text);
            patch.setChars(text.length());
            patch.setStatus("ready");
            attachmentMapper.updateById(patch);
            log.info("[ChatMedia] 转写完成 id={} type={} chars={}", attachmentId, a.getMediaType(), text.length());
        } catch (Exception e) {
            log.error("[ChatMedia] 转写失败 id={}: {}", attachmentId, e.getMessage(), e);
            ChatAttachment patch = new ChatAttachment();
            patch.setId(attachmentId);
            patch.setStatus("failed");
            patch.setErrorMsg(StringUtils.abbreviate(e.getMessage(), 1000));
            attachmentMapper.updateById(patch);
        } finally {
            if (temp != null) {
                try { Files.deleteIfExists(temp); } catch (Exception ignored) {}
            }
        }
    }

    /** 音频：归一化（失败退回原文件）→ 上传 ASR 通道 → DashScope ASR → 拼成纯文本。 */
    private String transcribeAudio(Path localFile, String ext) {
        Path normalized = videoProcessor.normalizeAudioForAsr(localFile);
        Path asrSource = normalized != null ? normalized : localFile;
        String contentType = normalized != null ? "audio/mpeg" : guessAudioContentType(ext);
        String objectName = null;
        try {
            objectName = fileStorage.uploadLocalFile(asrSource, "asr-audio", contentType);
            String url = fileStorage.getFileUrl(objectName);
            AudioTranscriber.TranscriptionResult r = documentExtractor.transcribeAudio(url);
            if (!r.success()) throw new RuntimeException("ASR 失败: " + r.errorMsg());
            StringBuilder sb = new StringBuilder();
            for (AudioTranscriber.Sentence s : r.sentences()) {
                if (StringUtils.isBlank(s.text())) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(s.text().trim());
            }
            return sb.toString();
        } finally {
            if (objectName != null) {
                try { fileStorage.deleteObject(objectName); } catch (Exception ignored) {}
            }
            if (normalized != null) {
                try { Files.deleteIfExists(normalized); } catch (Exception ignored) {}
            }
        }
    }

    /** 视频：FFmpeg 抽音轨/关键帧 + ASR + Qwen-VL → 音频句子 + 视觉描述拼成纯文本。 */
    private String transcribeVideo(Path localFile, String videoObjectName) {
        VideoProcessor.VideoParseResult r = videoProcessor.process(localFile, videoObjectName);
        if (!r.success()) {
            throw new RuntimeException("视频处理失败: " + r.errorMsg() + " · " + r.diagnosticsText());
        }
        StringBuilder sb = new StringBuilder();
        for (VideoProcessor.AudioSegment seg : r.audioSegments()) {
            if (StringUtils.isBlank(seg.text())) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(seg.text().trim());
        }
        for (VideoProcessor.KeyframeSegment k : r.keyframes()) {
            String t = k.toIndexedText();
            if (StringUtils.isBlank(t)) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t.trim());
        }
        return sb.toString();
    }

    private String guessAudioContentType(String ext) {
        return switch (ext == null ? "" : ext.toLowerCase()) {
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "m4a", "aac" -> "audio/aac";
            case "flac" -> "audio/flac";
            case "opus" -> "audio/opus";
            case "ogg" -> "audio/ogg";
            case "amr" -> "audio/amr";
            default -> "application/octet-stream";
        };
    }
}
