package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.VoicePersona;
import com.simon.MindCrew.mapper.VoicePersonaMapper;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import com.simon.MindCrew.service.knowledge.VideoProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;

/**
 * 音色管理 · 任务 14
 *
 * 当前阶段：仅查询预置音色 + 默认音色解析。
 * 自定义音色复刻（14.1）后续接入：写入时 owner_user_id 必填。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoicePersonaService {

    private final VoicePersonaMapper mapper;
    private final FileStorageService fileStorage;
    private final DashScopeVoiceCloneService cloneService;
    private final VideoProcessor videoProcessor;

    /** 所有可用音色（用户可见：预置 + 自己拥有的） */
    public List<VoicePersona> listAccessible(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<VoicePersona>()
                .eq(VoicePersona::getEnabled, 1)
                .and(w -> w.isNull(VoicePersona::getOwnerUserId)
                          .or().eq(userId != null, VoicePersona::getOwnerUserId, userId))
                .orderByAsc(VoicePersona::getSortOrder));
    }

    /** 用 ID 取音色，找不到回退到默认 */
    public VoicePersona getOrDefault(Long voiceId) {
        if (voiceId != null) {
            VoicePersona p = mapper.selectById(voiceId);
            if (p != null && Integer.valueOf(1).equals(p.getEnabled())) return p;
        }
        return getDefault();
    }

    public VoicePersona getDefault() {
        VoicePersona p = mapper.selectOne(new LambdaQueryWrapper<VoicePersona>()
                .eq(VoicePersona::getIsDefault, 1)
                .eq(VoicePersona::getEnabled, 1)
                .last("LIMIT 1"));
        if (p != null) return p;
        // 兜底：返回任意一个 enabled 音色
        return mapper.selectOne(new LambdaQueryWrapper<VoicePersona>()
                .eq(VoicePersona::getEnabled, 1)
                .orderByAsc(VoicePersona::getSortOrder)
                .last("LIMIT 1"));
    }

    public VoicePersona getById(Long id) {
        return mapper.selectById(id);
    }

    // ═════════════════════════════════════════════════════
    // 任务 14.1 · 自定义音色复刻
    // ═════════════════════════════════════════════════════

    /** 用户自定义音色列表（仅自己拥有的） */
    public List<VoicePersona> listMyVoices(Long userId) {
        if (userId == null) return List.of();
        return mapper.selectList(new LambdaQueryWrapper<VoicePersona>()
                .eq(VoicePersona::getOwnerUserId, userId)
                .orderByDesc(VoicePersona::getCreateTime));
    }

    /**
     * 个人中心「我的音色」管理列表 · 预置（原始）音色 + 自己的自定义音色。
     * 与 listAccessible 不同：这里<b>不过滤 enabled</b>，以便展示并切换「是否在电话列表显示」。
     */
    public List<VoicePersona> listManageable(Long userId) {
        return mapper.selectList(new LambdaQueryWrapper<VoicePersona>()
                .and(w -> w.isNull(VoicePersona::getOwnerUserId)
                          .or().eq(userId != null, VoicePersona::getOwnerUserId, userId))
                .orderByAsc(VoicePersona::getOwnerUserId)   // 预置（null）在前
                .orderByAsc(VoicePersona::getSortOrder)
                .orderByDesc(VoicePersona::getCreateTime));
    }

    /**
     * 切换音色「是否在电话/朗读列表显示」（即 enabled）。
     * 预置音色（owner_user_id=null）仅管理员可改；自定义音色仅本人可改。
     */
    @Transactional
    public void setEnabled(Long userId, boolean isAdmin, Long voiceId, boolean enabled) {
        if (voiceId == null) throw new IllegalArgumentException("参数不全");
        VoicePersona vp = mapper.selectById(voiceId);
        if (vp == null) throw new IllegalArgumentException("音色不存在");
        boolean isPreset = vp.getOwnerUserId() == null;
        if (isPreset) {
            if (!isAdmin) throw new IllegalStateException("仅管理员可修改原始音色");
        } else if (!Objects.equals(vp.getOwnerUserId(), userId)) {
            throw new IllegalStateException("只能修改自己的音色");
        }
        VoicePersona patch = new VoicePersona();
        patch.setId(voiceId);
        patch.setEnabled(enabled ? 1 : 0);
        mapper.updateById(patch);
        log.info("[Voice] 显示开关 · user={} voiceId={} enabled={}", userId, voiceId, enabled);
    }

    /**
     * 把个人自定义音色「转为系统预设」（owner_user_id 置 null，全员可见）。仅管理员可操作。
     * 用于迁移管理员早前创建的个人音色 → 系统预设。
     */
    @Transactional
    public void promoteToPreset(Long userId, boolean isAdmin, Long voiceId) {
        if (!isAdmin) throw new IllegalStateException("仅管理员可转为系统预设");
        if (voiceId == null) throw new IllegalArgumentException("参数不全");
        VoicePersona vp = mapper.selectById(voiceId);
        if (vp == null) throw new IllegalArgumentException("音色不存在");
        if (vp.getOwnerUserId() == null) {
            return;   // 已是预设，幂等返回
        }
        // MyBatis-Plus updateById 不会把字段更新成 null，这里用条件构造器显式置 null
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<VoicePersona> uw =
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<>();
        uw.eq(VoicePersona::getId, voiceId)
          .set(VoicePersona::getOwnerUserId, null)
          .set(VoicePersona::getEnabled, 1)
          .set(VoicePersona::getDescription, "系统预设音色")
          .set(VoicePersona::getSortOrder, 100);
        mapper.update(null, uw);
        log.info("[Voice] 个人音色转为系统预设 · admin={} voiceId={}", userId, voiceId);
    }

    /**
     * 上传声音样本 → 调 DashScope 复刻 → 写库
     *
     * @param userId   当前登录用户
     * @param sample   样本音频（wav/mp3，建议 10-30 秒）
     * @param name     音色展示名（如「老板的声音」）
     * @param gender   male / female / neutral
     * @param asPreset true=作为系统预设音色（owner_user_id=null，全员可见，管理员添加）；
     *                 false=作为个人自定义音色（owner_user_id=当前用户，仅本人可用，普通用户）
     * @return 新建的 VoicePersona（status=ready 或 failed）
     */
    @Transactional
    public VoicePersona cloneFromSample(Long userId, MultipartFile sample,
                                         String name, String gender, boolean asPreset) {
        if (userId == null) throw new IllegalStateException("未登录");
        if (sample == null || sample.isEmpty()) throw new IllegalArgumentException("样本音频不能为空");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("音色名称不能为空");

        // 自定义音色数量不限制

        // 1) 上传样本到对象存储拿公网 URL
        //    视频样本：先用 ffmpeg 抽音轨（24kHz 单声道 mp3），再上传音频（DashScope 复刻只吃音频）
        String origName = sample.getOriginalFilename();
        String ext = (origName != null && origName.contains("."))
                ? origName.substring(origName.lastIndexOf('.') + 1).toLowerCase() : "";
        boolean isVideo = VideoProcessor.supportedExtensions().contains(ext);
        String objectName;
        try {
            if (isVideo) {
                Path tmpIn = Files.createTempFile("clone-src-", "." + ext);
                Path audio = null;
                try {
                    try (var in = sample.getInputStream()) {
                        Files.copy(in, tmpIn, StandardCopyOption.REPLACE_EXISTING);
                    }
                    audio = videoProcessor.extractAudioForClone(tmpIn);
                    if (audio == null) {
                        throw new RuntimeException("从视频抽取音轨失败，请确认视频含清晰人声，或改用音频文件");
                    }
                    objectName = fileStorage.uploadLocalFile(audio, "voice-samples/user-" + userId, "audio/mpeg");
                } finally {
                    try { Files.deleteIfExists(tmpIn); } catch (Exception ignored) {}
                    if (audio != null) { try { Files.deleteIfExists(audio); } catch (Exception ignored) {} }
                }
            } else {
                objectName = fileStorage.uploadFile(sample, "voice-samples/user-" + userId);
            }
        } catch (RuntimeException re) {
            throw re;
        } catch (Exception e) {
            throw new RuntimeException("样本上传失败: " + e.getMessage(), e);
        }
        String publicUrl;
        try {
            publicUrl = fileStorage.getFileUrl(objectName);
        } catch (Exception e) {
            throw new RuntimeException("获取样本 URL 失败: " + e.getMessage(), e);
        }
        if (publicUrl == null || publicUrl.isBlank()) {
            throw new RuntimeException("样本 URL 为空，无法发起复刻");
        }
        // 本地 MinIO localhost 直接拒绝（DashScope 拉不到）
        if (publicUrl.contains("localhost") || publicUrl.contains("127.0.0.1")) {
            throw new RuntimeException("音色复刻需要公网可访问的存储，请部署阿里云 OSS。本地 MinIO 不支持。");
        }

        // 2) 先写一条 cloning 记录（万一 DashScope 慢 / 失败也有痕迹）
        VoicePersona vp = new VoicePersona();
        vp.setName(name.trim());
        vp.setProvider("cosyvoice");
        vp.setModel("cosyvoice-v2");
        vp.setGender(normalizeGender(gender));
        vp.setLanguage("zh-CN");
        vp.setSampleRate(22050);
        // 管理员添加 → 系统预设（owner=null，全员可见）；普通用户 → 个人音色（owner=自己）
        vp.setOwnerUserId(asPreset ? null : userId);
        vp.setStatus("cloning");
        vp.setSampleObjectName(objectName);
        vp.setIsDefault(0);
        vp.setEnabled(0);   // 暂不启用，复刻成功后才启用
        vp.setSortOrder(asPreset ? 100 : 200);   // 预设排在原始预置后、个人音色前
        // voice_id 必填（非空约束），先占位（unique key 是 provider+voice_id）
        vp.setVoiceId("pending-u" + userId + "-" + System.currentTimeMillis());
        mapper.insert(vp);

        // 3) 调 DashScope
        try {
            String voiceId = cloneService.createVoice(publicUrl, "u" + userId);
            VoicePersona patch = new VoicePersona();
            patch.setId(vp.getId());
            patch.setVoiceId(voiceId);
            patch.setStatus("ready");
            patch.setEnabled(1);
            patch.setDescription(asPreset ? "系统预设音色" : "我的自定义音色");
            mapper.updateById(patch);
            log.info("[VoiceClone] 复刻成功 · user={} name={} voiceId={}", userId, name, voiceId);
            return mapper.selectById(vp.getId());
        } catch (Exception e) {
            log.error("[VoiceClone] 复刻失败 · user={} err={}", userId, e.getMessage());
            VoicePersona patch = new VoicePersona();
            patch.setId(vp.getId());
            patch.setStatus("failed");
            patch.setErrorMessage(truncate(e.getMessage(), 480));
            mapper.updateById(patch);
            throw new RuntimeException("音色复刻失败: " + e.getMessage(), e);
        }
    }

    /** 删除自己的自定义音色 · 同步删 DashScope */
    @Transactional
    public void deleteMyVoice(Long userId, Long voiceId) {
        deleteVoice(userId, false, voiceId);
    }

    /**
     * 删除音色 · 同步删 DashScope。
     * 预置（原始）音色 owner_user_id=null 仅管理员可删；自定义音色仅本人可删。
     */
    @Transactional
    public void deleteVoice(Long userId, boolean isAdmin, Long voiceId) {
        if (userId == null || voiceId == null) throw new IllegalArgumentException("参数不全");
        VoicePersona vp = mapper.selectById(voiceId);
        if (vp == null) return;
        boolean isPreset = vp.getOwnerUserId() == null;
        if (isPreset) {
            if (!isAdmin) throw new IllegalStateException("仅管理员可删除原始音色");
        } else if (!Objects.equals(vp.getOwnerUserId(), userId)) {
            throw new IllegalStateException("只能删除自己的音色");
        }
        // 先删 DashScope（失败也继续）· 仅自定义复刻音色才同步删除；预置官方音色不调 DashScope
        if (!isPreset && vp.getVoiceId() != null && !vp.getVoiceId().startsWith("pending-")) {
            try {
                cloneService.deleteVoice(vp.getVoiceId());
            } catch (Exception ignored) {}
        }
        // 删 OSS 样本文件
        if (vp.getSampleObjectName() != null) {
            try { fileStorage.deleteObject(vp.getSampleObjectName()); } catch (Exception ignored) {}
        }
        mapper.deleteById(voiceId);
        log.info("[VoiceClone] 已删除 · user={} voicePersonaId={} dashscopeVoiceId={}",
                userId, voiceId, vp.getVoiceId());
    }

    private String normalizeGender(String g) {
        if (g == null) return "neutral";
        String v = g.trim().toLowerCase();
        return switch (v) {
            case "male", "m", "男" -> "male";
            case "female", "f", "女" -> "female";
            default -> "neutral";
        };
    }

    private String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n) + "...");
    }
}
