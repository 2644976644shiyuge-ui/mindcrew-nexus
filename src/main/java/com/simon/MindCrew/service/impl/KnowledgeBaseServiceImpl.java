package com.simon.MindCrew.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbParentChunk;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbParentChunkMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.service.KnowledgeBaseService;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.MilvusService;
import com.simon.MindCrew.service.knowledge.TextChunker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 知识库服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor(onConstructor_ = @org.springframework.beans.factory.annotation.Autowired)
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    private final MedKnowledgeBaseMapper knowledgeBaseMapper;
    private final KbChunkMapper kbChunkMapper;
    private final MilvusService milvusService;
    private final DocumentExtractor documentExtractor;
    private final TextChunker textChunker;
    private final DocumentProcessTask documentProcessTask;
    private final com.simon.MindCrew.service.knowledge.FileStorageService fileStorage;
    // 任务 7 · 职位独立 KB ACL 服务（可为 null 兼容旧测试构造器）
    private final com.simon.MindCrew.service.KbAclService kbAclService;
    private final com.simon.MindCrew.service.UserService userService;
    private final com.simon.MindCrew.service.KnowledgeCollectionService collectionService;
    private final com.simon.MindCrew.service.KnowledgeGraphService knowledgeGraphService;

    /** 可选注入以兼容旧测试构造器；正式环境存在父子切片表时用于清理父段。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private KbParentChunkMapper kbParentChunkMapper;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /** 测试用辅助构造器 · ACL/User 服务传 null，测试场景跳过权限校验 */
    KnowledgeBaseServiceImpl(MedKnowledgeBaseMapper knowledgeBaseMapper,
                             MilvusService milvusService,
                             DocumentExtractor documentExtractor,
                             TextChunker textChunker,
                             DocumentProcessTask documentProcessTask) {
        this(knowledgeBaseMapper, null, milvusService, documentExtractor, textChunker, documentProcessTask, null, null, null, null, null);
    }

    Long uploadDocument(MultipartFile file, String category, String description) {
        return uploadDocument(file, file != null ? file.getOriginalFilename() : null, category, description, 0L);
    }

    @Override
    public Long uploadDocument(MultipartFile file, String name, String category, String description, Long userId) {
        return uploadDocument(file, name, category, description, userId, null);
    }

    @Override
    @Transactional
    @com.simon.MindCrew.common.audit.Audited(action = "kb.upload", label = "上传知识库文档", targetType = "kb", targetIdParam = "$return")
    public Long uploadDocument(MultipartFile file, String name, String category, String description, Long userId, Long collectionId) {
        // 1. 校验文件类型
        String originalName = file.getOriginalFilename();
        String fileType = getFileExtension(originalName);
        String ft = fileType.toLowerCase();
        boolean isAudio = com.simon.MindCrew.service.knowledge.AudioTranscriber.supportedExtensions().contains(ft);
        boolean isVideo = com.simon.MindCrew.service.knowledge.VideoProcessor.supportedExtensions().contains(ft);
        if (!DocumentExtractor.supportedExtensions().contains(ft) && !isAudio && !isVideo) {
            throw new BusinessException("不支持的文件格式，当前支持: 文档(" +
                    String.join(", ", DocumentExtractor.supportedExtensions()) + ")、音频(" +
                    String.join(", ", com.simon.MindCrew.service.knowledge.AudioTranscriber.supportedExtensions()) +
                    ")、视频(" + String.join(", ", com.simon.MindCrew.service.knowledge.VideoProcessor.supportedExtensions()) + ")");
        }
        if (userId == null) {
            throw new BusinessException("上传用户不能为空");
        }

        String finalName = StringUtils.isNotBlank(name) ? name : originalName;

        // 2. 保存文件到本地磁盘
        String fileUrl = saveToLocal(file, originalName);

        // 3. 创建知识库记录
        MedKnowledgeBase kb = new MedKnowledgeBase();
        kb.setName(finalName);
        kb.setDescription(description);
        kb.setCategory(category);
        kb.setCollectionId(collectionId);   // 任务 15：归属知识库
        kb.setFileUrl(fileUrl);
        kb.setFileType(fileType);
        kb.setFileSize(file.getSize());
        kb.setStatus("uploading");
        kb.setChunkCount(0);
        kb.setUserId(userId);
        knowledgeBaseMapper.insert(kb);

        // 任务 15：上传时带了 collectionId → 刷新知识库文档计数器
        if (collectionId != null && collectionService != null) {
            collectionService.refreshCounters(collectionId);
        }

        // 4. 事务提交后再触发异步任务，避免异步线程读到未提交数据导致状态卡在 uploading
        final Long kbId = kb.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                documentProcessTask.process(kbId);
            }
        });

        log.info("文档上传成功: id={}, name={}, userId={}", kb.getId(), finalName, userId);
        return kb.getId();
    }

    @Override
    @Transactional
    @com.simon.MindCrew.common.audit.Audited(action = "kb.upload-from-chat", label = "聊天附件加入知识库", targetType = "kb", targetIdParam = "$return")
    public Long uploadDocumentFromObject(String objectName, String name, String category, String description, Long userId, Long collectionId) {
        if (StringUtils.isBlank(objectName)) throw new BusinessException("objectName 不能为空");
        if (userId == null) throw new BusinessException("上传用户不能为空");

        String fileType = getFileExtension(objectName);
        String ft = fileType.toLowerCase();
        boolean isAudio = com.simon.MindCrew.service.knowledge.AudioTranscriber.supportedExtensions().contains(ft);
        boolean isVideo = com.simon.MindCrew.service.knowledge.VideoProcessor.supportedExtensions().contains(ft);
        if (!DocumentExtractor.supportedExtensions().contains(ft) && !isAudio && !isVideo) {
            throw new BusinessException("不支持的文件格式: " + ft);
        }

        // 1. 把对象下载到本地 knowledge 目录，供 DocumentProcessTask 解析/转写
        String filename = UUID.randomUUID() + "." + ft;
        long fileSize;
        try {
            Path dir = Paths.get(uploadPath, "knowledge");
            Files.createDirectories(dir);
            Path dest = dir.resolve(filename);
            try (java.io.InputStream in = fileStorage.getFileStream(objectName)) {
                Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            fileSize = Files.size(dest);
        } catch (IOException e) {
            throw new BusinessException("从对象存储读取失败: " + e.getMessage());
        }
        String fileUrl = "knowledge/" + filename;

        // 2. 创建知识库记录（与 uploadDocument 同口径）
        String finalName = StringUtils.isNotBlank(name) ? name : objectName.substring(objectName.lastIndexOf('/') + 1);
        MedKnowledgeBase kb = new MedKnowledgeBase();
        kb.setName(finalName);
        kb.setDescription(description);
        kb.setCategory(category);
        kb.setCollectionId(collectionId);
        kb.setFileUrl(fileUrl);
        kb.setFileType(fileType);
        kb.setFileSize(fileSize);
        kb.setStatus("uploading");
        kb.setChunkCount(0);
        kb.setUserId(userId);
        knowledgeBaseMapper.insert(kb);

        if (collectionId != null && collectionService != null) {
            collectionService.refreshCounters(collectionId);
        }

        // 3. 事务提交后触发异步处理（音视频在此重新转写、文档解析后切块向量化）
        final Long kbId = kb.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                documentProcessTask.process(kbId);
            }
        });

        log.info("聊天附件加入知识库: id={}, name={}, objectName={}, userId={}", kbId, finalName, objectName, userId);
        return kbId;
    }

    @Override
    public Page<MedKnowledgeBase> listKnowledge(Integer current, Integer size,
                                                   String category, String status) {
        Page<MedKnowledgeBase> page = new Page<>(current, size);

        // 任务 7 · 接口层 ACL 过滤
        List<Long> accessibleIds = null;
        if (userService != null && kbAclService != null) {
            Long uid;
            try { uid = userService.getCurrentUserId(); } catch (Exception e) { uid = null; }
            accessibleIds = (uid == null) ? List.of() : kbAclService.listAccessibleKbIds(uid);
        }

        LambdaQueryWrapper<MedKnowledgeBase> wrapper = new LambdaQueryWrapper<MedKnowledgeBase>()
                .eq(MedKnowledgeBase::getDeleted, 0)
                .eq(StringUtils.isNotBlank(category), MedKnowledgeBase::getCategory, category)
                .eq(StringUtils.isNotBlank(status), MedKnowledgeBase::getStatus, status)
                // 仅当 accessibleIds 不为 null 时启用过滤；空列表用 -1L 占位（保证查不到）
                .in(accessibleIds != null,
                        MedKnowledgeBase::getId,
                        accessibleIds != null && accessibleIds.isEmpty() ? List.of(-1L) : accessibleIds)
                .orderByDesc(MedKnowledgeBase::getCreateTime);
        return knowledgeBaseMapper.selectPage(page, wrapper);
    }

    @Override
    public java.util.Map<String, Object> statsKnowledge() {
        // 复用 list 的 ACL 作用域，保证统计口径与列表一致
        List<Long> accessibleIds = null;
        if (userService != null && kbAclService != null) {
            Long uid;
            try { uid = userService.getCurrentUserId(); } catch (Exception e) { uid = null; }
            accessibleIds = (uid == null) ? List.of() : kbAclService.listAccessibleKbIds(uid);
        }

        // 仅取 status + chunk_count 两列，轻量；在 Java 侧聚合（文档量级小，安全）
        LambdaQueryWrapper<MedKnowledgeBase> wrapper = new LambdaQueryWrapper<MedKnowledgeBase>()
                .select(MedKnowledgeBase::getStatus, MedKnowledgeBase::getChunkCount)
                .eq(MedKnowledgeBase::getDeleted, 0)
                .in(accessibleIds != null,
                        MedKnowledgeBase::getId,
                        accessibleIds != null && accessibleIds.isEmpty() ? List.of(-1L) : accessibleIds);
        List<MedKnowledgeBase> rows = knowledgeBaseMapper.selectList(wrapper);

        long total = rows.size(), ready = 0, processing = 0, failed = 0, totalChunks = 0;
        for (MedKnowledgeBase r : rows) {
            String st = r.getStatus();
            if ("ready".equals(st)) ready++;
            else if ("processing".equals(st) || "uploading".equals(st)
                    || "rebuild_queued".equals(st) || "rebuilding".equals(st)) processing++;
            else if ("failed".equals(st) || "rebuild_failed".equals(st)) failed++;
            if (r.getChunkCount() != null) totalChunks += r.getChunkCount();
        }
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        m.put("total", total);
        m.put("ready", ready);
        m.put("processing", processing);
        m.put("failed", failed);
        m.put("totalChunks", totalChunks);
        return m;
    }

    @Override
    public String getDownloadUrl(Long id) {
        MedKnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null || (kb.getDeleted() != null && kb.getDeleted() == 1)) {
            throw new BusinessException(ResultCode.KNOWLEDGE_NOT_FOUND);
        }
        // 优先 OSS 原件预签名直链（任意大小、不占后端内存）
        if (kb.getOssObjectName() != null && !kb.getOssObjectName().isBlank()) {
            return fileStorage.getFileUrl(kb.getOssObjectName());
        }
        // 回退：老文件仅本地有 → 走代理下载（≤20MB）
        if (kb.getFileUrl() != null && !kb.getFileUrl().isBlank()) {
            return "/api/v2/kb/proxy-fetch?objectName="
                    + java.net.URLEncoder.encode(kb.getFileUrl(), java.nio.charset.StandardCharsets.UTF_8)
                    + "&kbId=" + id;
        }
        return null;
    }

    @Override
    public MedKnowledgeBase getById(Long id) {
        MedKnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null || kb.getDeleted() == 1) {
            throw new BusinessException(ResultCode.KNOWLEDGE_NOT_FOUND);
        }
        // 任务 7 · 权限校验 read
        if (userService != null && kbAclService != null) {
            Long me;
            try { me = userService.getCurrentUserId(); } catch (Exception e) { me = null; }
            if (me != null && !kbAclService.canAccess(me, id, com.simon.MindCrew.service.KbAclService.PERM_READ)) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权访问该知识库");
            }
        }
        return kb;
    }

    @Override
    @Transactional
    @com.simon.MindCrew.common.audit.Audited(action = "kb.delete", label = "删除知识库", targetType = "kb", targetIdParam = "$arg0")
    public void deleteById(Long id) {
        MedKnowledgeBase kb = getById(id);
        // 删除需要 admin 权限
        if (userService != null && kbAclService != null) {
            Long me;
            try { me = userService.getCurrentUserId(); } catch (Exception e) { me = null; }
            if (me != null && !kbAclService.canAccess(me, id, com.simon.MindCrew.service.KbAclService.PERM_ADMIN)) {
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权删除该知识库（需要 admin 权限）");
            }
        }
        // 删除 Milvus 向量（失败不影响主流程，向量丢失可接受）
        milvusService.deleteByKnowledgeBaseId(id);
        kbChunkMapper.delete(new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getKbId, id));
        deleteParentChunks(id);
        // 删除知识图谱数据（节点/边）
        if (knowledgeGraphService != null) knowledgeGraphService.deleteByKb(id);
        // 删除本地文件
        deleteLocalFile(kb.getFileUrl());
        // 删除 OSS 原件（防止删库后 OSS 残留累积）
        if (kb.getOssObjectName() != null && !kb.getOssObjectName().isBlank()) {
            try { fileStorage.deleteObject(kb.getOssObjectName()); }
            catch (Exception e) { log.warn("删除 OSS 原件失败（不影响删库）: {} err={}", kb.getOssObjectName(), e.getMessage()); }
        }
        // 逻辑删除：必须用 deleteById 而非 updateById
        // @TableLogic 字段会被 updateById 的 SET 子句忽略，直接 setDeleted(1)+updateById 无效
        knowledgeBaseMapper.deleteById(id);
        log.info("知识库删除完成: id={}", id);
    }

    @Override
    public List<String> listCategories() {
        return knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<MedKnowledgeBase>()
                        .eq(MedKnowledgeBase::getDeleted, 0)
                        .select(MedKnowledgeBase::getCategory)
                        .groupBy(MedKnowledgeBase::getCategory)
        ).stream().map(MedKnowledgeBase::getCategory).distinct().collect(Collectors.toList());
    }

    @Override
    public void reprocess(Long id) {
        if (knowledgeBaseMapper.isKnowledgeRebuildLocked()) {
            throw new BusinessException("全量索引维护正在进行，请等待完成后再手动重建文档");
        }
        reprocessInternal(id);
    }

    @Override
    public void reprocessForMaintenance(Long id) {
        reprocessInternal(id);
    }

    private void reprocessInternal(Long id) {
        MedKnowledgeBase kb = getById(id);
        if (!java.util.Set.of("failed", "ready", "rebuild_failed").contains(kb.getStatus())) {
            throw new BusinessException("只有处理失败或已就绪的文档才能重新处理");
        }
        String originalStatus = kb.getStatus();
        boolean rebuildReadyIndex = "ready".equals(kb.getStatus())
                || "rebuild_failed".equals(kb.getStatus());
        String queuedStatus = rebuildReadyIndex ? "rebuild_queued" : "uploading";
        boolean hasOssOriginal = StringUtils.isNotBlank(kb.getOssObjectName());
        boolean hasLocalOriginal = !hasOssOriginal
                && StringUtils.isNotBlank(uploadPath)
                && StringUtils.isNotBlank(kb.getFileUrl())
                && Files.exists(Paths.get(uploadPath, kb.getFileUrl()));
        if (!hasOssOriginal && !hasLocalOriginal) {
            throw new BusinessException("原始文件不存在，无法安全重建索引；请重新上传文档");
        }

        // 先用状态 CAS 抢占该文档，避免管理员双击/多个维护进程同时重建同一 KB。
        int claimed = knowledgeBaseMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                        .set(MedKnowledgeBase::getStatus, queuedStatus)
                        .set(MedKnowledgeBase::getErrorMsg, null)
                        .eq(MedKnowledgeBase::getId, id)
                        .eq(MedKnowledgeBase::getStatus, originalStatus));
        if (claimed != 1) {
            throw new BusinessException("该文档已被其他任务接管，请等待当前处理完成");
        }

        // 只负责持久化排队状态并投递。旧索引由 worker 抢占成功后再删除，避免任务尚未启动
        // 就让线上检索失去这份文档；worker 也能据此安全处理重启恢复。
        try {
            if (rebuildReadyIndex) {
                documentProcessTask.rebuildIndex(id);
            } else {
                documentProcessTask.process(id);
            }
        } catch (RuntimeException ex) {
            knowledgeBaseMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                            .set(MedKnowledgeBase::getStatus, originalStatus)
                            .set(MedKnowledgeBase::getErrorMsg, kb.getErrorMsg())
                            .eq(MedKnowledgeBase::getId, id)
                            .eq(MedKnowledgeBase::getStatus, queuedStatus));
            throw ex;
        }
    }

    private void deleteParentChunks(Long kbId) {
        if (kbParentChunkMapper == null) return;
        try {
            kbParentChunkMapper.delete(
                    new LambdaQueryWrapper<KbParentChunk>().eq(KbParentChunk::getKbId, kbId));
        } catch (Exception e) {
            log.debug("清理父切片跳过: {}", e.getMessage());
        }
    }

    @Override
    public void updateVisibility(Long id, String visibility) {
        if (visibility == null || !java.util.Set.of("public", "scoped", "private").contains(visibility)) {
            throw new BusinessException("visibility 必须是 public / scoped / private 之一");
        }
        MedKnowledgeBase kb = knowledgeBaseMapper.selectById(id);
        if (kb == null || kb.getDeleted() == 1) {
            throw new BusinessException(ResultCode.KNOWLEDGE_NOT_FOUND);
        }
        // 用 LambdaUpdateWrapper 显式 set，避免 mybatis-plus null 字段被忽略导致旧值留存
        knowledgeBaseMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                        .set(MedKnowledgeBase::getVisibility, visibility)
                        .eq(MedKnowledgeBase::getId, id));
        log.info("[KB] 可见性切换 id={} → {}", id, visibility);
    }

    // ==================== 私有方法 ====================

    private void updateStatus(MedKnowledgeBase kb, String status, String errorMsg) {
        kb.setStatus(status);
        kb.setErrorMsg(errorMsg);
        knowledgeBaseMapper.updateById(kb);
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) return "txt";
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
    }

    /**
     * 保存文件到本地磁盘，返回相对路径（作为 fileUrl 存入数据库）
     */
    private String saveToLocal(MultipartFile file, String originalName) {
        try {
            String ext = getFileExtension(originalName);
            String filename = UUID.randomUUID() + "." + ext;
            Path dir = Paths.get(uploadPath, "knowledge");
            Files.createDirectories(dir);
            Path dest = dir.resolve(filename);
            file.transferTo(dest.toAbsolutePath().toFile());
            // 返回相对路径，供 DocumentProcessTask 读取
            String relativePath = "knowledge/" + filename;
            log.info("文档已保存到本地: {}", dest.toAbsolutePath());
            return relativePath;
        } catch (IOException e) {
            throw new BusinessException("文件保存失败: " + e.getMessage());
        }
    }

    /**
     * 删除本地文件
     */
    private void deleteLocalFile(String fileUrl) {
        try {
            Path path = Paths.get(uploadPath, fileUrl);
            Files.deleteIfExists(path);
            log.info("本地文件已删除: {}", path.toAbsolutePath());
        } catch (IOException e) {
            log.warn("本地文件删除失败: {}", e.getMessage());
        }
    }
}
