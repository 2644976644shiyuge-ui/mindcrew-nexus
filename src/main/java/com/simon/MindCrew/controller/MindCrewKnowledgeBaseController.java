package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.entity.vo.PageVO;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.service.KnowledgeBaseService;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.service.KbAclService;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.stream.Collectors;

/**
 * MindCrew v2 知识库管理控制器（使用 KbKnowledgeBase 新实体）
 * 旧的 KnowledgeBaseController (/api/knowledge) 保持不变
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/kb")
@RequiredArgsConstructor
public class MindCrewKnowledgeBaseController {

    private final KbKnowledgeBaseMapper kbKnowledgeBaseMapper;
    private final KbChunkMapper kbChunkMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final UserService userService;
    private final FileStorageService fileStorage;
    private final KbAclService kbAclService;

    // ==================== 查询 ====================

    /**
     * 分页获取知识库列表
     * GET /api/v2/kb/list?page=1&size=10&category=xxx
     */
    @GetMapping("/list")
    public Result<PageVO<KbKnowledgeBase>> list(
            @RequestParam(value = "page", defaultValue = "1") Integer page,
            @RequestParam(value = "size", defaultValue = "10") Integer size,
            @RequestParam(value = "category", required = false) String category) {

        Page<KbKnowledgeBase> pageObj = new Page<>(page, size);
        List<Long> accessible = accessibleDocIds();
        if (accessible.isEmpty()) return Result.success(PageVO.of(pageObj));
        kbKnowledgeBaseMapper.selectPage(pageObj, new LambdaQueryWrapper<KbKnowledgeBase>()
                .in(KbKnowledgeBase::getId, accessible)
                .eq(KbKnowledgeBase::getDeleted, 0)
                .eq(StringUtils.isNotBlank(category), KbKnowledgeBase::getCategory, category)
                .orderByDesc(KbKnowledgeBase::getCreateTime));

        return Result.success(PageVO.of(pageObj));
    }

    /**
     * 获取知识库详情（含 chunk_count）
     * GET /api/v2/kb/{id}
     */
    @GetMapping("/{id}")
    public Result<KbDetailVO> getById(@PathVariable Long id) {
        KbKnowledgeBase kb = kbKnowledgeBaseMapper.selectById(id);
        if (kb == null || (kb.getDeleted() != null && kb.getDeleted() == 1) || !canRead(id)) {
            return Result.error("知识库不存在");
        }

        // 统计切片数量
        long chunkCount = kbChunkMapper.selectCount(
                new LambdaQueryWrapper<KbChunk>().eq(KbChunk::getKbId, id));

        KbDetailVO vo = new KbDetailVO();
        vo.setId(kb.getId());
        vo.setName(kb.getName());
        vo.setCategory(kb.getCategory());
        vo.setDescription(kb.getDescription());
        vo.setFileUrl(kb.getFileUrl());
        vo.setFileType(kb.getFileType());
        vo.setFileSize(kb.getFileSize());
        vo.setChunkCount((int) chunkCount);
        vo.setStatus(kb.getStatus());
        vo.setErrorMsg(kb.getErrorMsg());
        vo.setUserId(kb.getUserId());
        vo.setCreateTime(kb.getCreateTime() != null ? kb.getCreateTime().toString() : null);
        vo.setUpdateTime(kb.getUpdateTime() != null ? kb.getUpdateTime().toString() : null);

        return Result.success(vo);
    }

    /**
     * 删除知识库（软删除）
     * DELETE /api/v2/kb/{id}
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        KbKnowledgeBase kb = kbKnowledgeBaseMapper.selectById(id);
        if (kb == null || (kb.getDeleted() != null && kb.getDeleted() == 1)) {
            return Result.error("知识库不存在");
        }
        knowledgeBaseService.deleteById(id);
        log.info("[MindCrewKnowledgeBaseController] 知识库删除完成: id={}", id);
        return Result.success();
    }

    /**
     * 生成媒体文件预签名 URL（用于前端时间戳级溯源播放）
     * GET /api/v2/kb/media-url?objectName=asr-audio/uuid.mp3
     *
     * 返回有效期 7 天的 URL，前端 audio/video 标签直接拿来播放并 seek 到 startMs。
     */
    @GetMapping("/media-url")
    public Result<MediaUrlVO> mediaUrl(@RequestParam String objectName,
                                       @RequestParam(required = false) Long kbId) {
        if (StringUtils.isBlank(objectName) || !canReadMedia(kbId)) {
            return Result.error("文件不存在");
        }
        try {
            String url = fileStorage.getFileUrl(objectName);
            MediaUrlVO vo = new MediaUrlVO();
            vo.setUrl(url);
            vo.setExpireSeconds(7L * 24 * 3600);
            return Result.success(vo);
        } catch (Exception e) {
            log.error("生成 media-url 失败 objectName={}", objectName, e);
            return Result.error("生成 URL 失败: " + e.getMessage());
        }
    }

    /**
     * ⭐ 文件代理预览 · 任务 16
     * GET /api/v2/kb/proxy-fetch?objectName=xxx
     *
     * 用途：前端预览 markdown/txt/html 等文本文件时，绕过 OSS CORS。
     * 后端从存储拉文件，原样返回，Content-Type 让浏览器自己识别。
     *
     * 安全：需登录（Spring Security 配置已覆盖 /api/v2/**）
     * 限制：单次最大 20MB，避免拖死后端
     */
    @org.springframework.beans.factory.annotation.Value("${upload.path:uploads}")
    private String localUploadPath;

    @GetMapping("/proxy-fetch")
    public org.springframework.http.ResponseEntity<byte[]> proxyFetch(
            @RequestParam String objectName, @RequestParam Long kbId) {
        if (StringUtils.isBlank(objectName) || kbId == null || !canRead(kbId)) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        }
        // ⭐ 路径穿越防御：禁止 ../ 跳出
        if (objectName.contains("..") || objectName.startsWith("/")) {
            return org.springframework.http.ResponseEntity.badRequest().build();
        }
        final int MAX = 20 * 1024 * 1024;
        byte[] bytes = null;

        // 1) 优先尝试本地磁盘（普通文档 PDF/DOC/MD 都走 saveToLocal 存这里）
        try {
            java.nio.file.Path localPath = java.nio.file.Paths.get(localUploadPath, objectName).toAbsolutePath().normalize();
            // 路径必须在 uploads 目录内（防穿越）
            java.nio.file.Path uploadRoot = java.nio.file.Paths.get(localUploadPath).toAbsolutePath().normalize();
            if (localPath.startsWith(uploadRoot) && java.nio.file.Files.isRegularFile(localPath)) {
                long size = java.nio.file.Files.size(localPath);
                if (size > MAX) return org.springframework.http.ResponseEntity.status(413).build();
                bytes = java.nio.file.Files.readAllBytes(localPath);
                log.info("[proxy-fetch] 命中本地: {} ({} bytes)", localPath, size);
            }
        } catch (Exception ignored) { /* 继续走 OSS */ }

        // 2) 本地没有 → 走对象存储（音视频/图片走的是 OSS uploadLocalFile）
        if (bytes == null) {
            try (java.io.InputStream in = fileStorage.getFileStream(objectName)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n; int total = 0;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX) return org.springframework.http.ResponseEntity.status(413).build();
                    bos.write(buf, 0, n);
                }
                bytes = bos.toByteArray();
                log.info("[proxy-fetch] 命中对象存储: {} ({} bytes)", objectName, bytes.length);
            } catch (Exception e) {
                log.warn("[proxy-fetch] 文件不存在 · 本地+OSS 都查不到: {} · {}", objectName, e.getMessage());
                return org.springframework.http.ResponseEntity.status(404).build();
            }
        }

        if (bytes == null) {
            return org.springframework.http.ResponseEntity.status(404).build();
        }

        // 根据扩展名给 Content-Type · 浏览器决定怎么渲染
        String lower = objectName.toLowerCase();
        String contentType;
        if      (lower.endsWith(".md") || lower.endsWith(".markdown")) contentType = "text/plain; charset=utf-8";
        else if (lower.endsWith(".txt") || lower.endsWith(".log") || lower.endsWith(".csv")) contentType = "text/plain; charset=utf-8";
        else if (lower.endsWith(".html") || lower.endsWith(".htm"))    contentType = "text/html; charset=utf-8";
        else if (lower.endsWith(".json"))                              contentType = "application/json; charset=utf-8";
        else if (lower.endsWith(".xml"))                               contentType = "application/xml; charset=utf-8";
        else if (lower.endsWith(".pdf"))                               contentType = "application/pdf";
        else if (lower.endsWith(".png"))                               contentType = "image/png";
        else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))    contentType = "image/jpeg";
        else if (lower.endsWith(".webp"))                              contentType = "image/webp";
        else if (lower.endsWith(".gif"))                               contentType = "image/gif";
        else                                                           contentType = "application/octet-stream";

        org.springframework.http.HttpHeaders h = new org.springframework.http.HttpHeaders();
        h.set("Content-Type", contentType);
        h.set("Content-Length", String.valueOf(bytes.length));
        h.set("Cache-Control", "private, max-age=300");
        return new org.springframework.http.ResponseEntity<>(bytes, h, org.springframework.http.HttpStatus.OK);
    }

    /**
     * 获取所有分类列表
     * GET /api/v2/kb/categories
     */
    @GetMapping("/categories")
    public Result<List<String>> categories() {
        List<Long> accessible = accessibleDocIds();
        if (accessible.isEmpty()) return Result.success(List.of());
        List<String> categories = kbKnowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KbKnowledgeBase>()
                        .in(KbKnowledgeBase::getId, accessible)
                        .eq(KbKnowledgeBase::getDeleted, 0)
                        .select(KbKnowledgeBase::getCategory)
                        .groupBy(KbKnowledgeBase::getCategory)
        ).stream()
                .map(KbKnowledgeBase::getCategory)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .sorted()
                .collect(Collectors.toList());

        return Result.success(categories);
    }

    /**
     * 更新知识库信息
     * PUT /api/v2/kb/{id}
     * body: {name, description, category}
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> update(@PathVariable Long id, @RequestBody KbUpdateDTO dto) {
        KbKnowledgeBase kb = kbKnowledgeBaseMapper.selectById(id);
        if (kb == null || (kb.getDeleted() != null && kb.getDeleted() == 1)) {
            return Result.error("知识库不存在");
        }

        if (StringUtils.isNotBlank(dto.getName())) {
            kb.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            kb.setDescription(dto.getDescription());
        }
        if (StringUtils.isNotBlank(dto.getCategory())) {
            kb.setCategory(dto.getCategory());
        }

        kbKnowledgeBaseMapper.updateById(kb);
        log.info("[MindCrewKnowledgeBaseController] 知识库更新: id={}", id);
        return Result.success();
    }

    // ==================== 上传 ====================

    /**
     * 上传文件并创建知识库（新实体路径）
     * POST /api/v2/kb/upload
     * params: file, name, category, description
     */
    @PostMapping("/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam("category") String category,
            @RequestParam(value = "description", required = false) String description) {

        Long userId = userService.getCurrentUserId();
        String originalName = file.getOriginalFilename();
        String finalName = StringUtils.isNotBlank(name) ? name : originalName;
        Long kbId = knowledgeBaseService.uploadDocument(file, finalName, category, description, userId);

        log.info("[MindCrewKnowledgeBaseController] 知识库上传: kbId={}, name={}", kbId, finalName);
        return Result.success("上传成功，正在后台处理...", kbId);
    }

    // ==================== VO / DTO ====================

    @Data
    public static class KbDetailVO {
        private Long id;
        private String name;
        private String category;
        private String description;
        private String fileUrl;
        private String fileType;
        private Long fileSize;
        private Integer chunkCount;
        private String status;
        private String errorMsg;
        private Long userId;
        private String createTime;
        private String updateTime;
    }

    @Data
    public static class KbUpdateDTO {
        private String name;
        private String description;
        private String category;
    }

    @Data
    public static class MediaUrlVO {
        private String url;
        private Long expireSeconds;
    }

    private List<Long> accessibleDocIds() {
        return kbAclService.listAccessibleKbIds(userService.getCurrentUserId());
    }

    private boolean canRead(Long kbId) {
        return kbAclService.canAccess(userService.getCurrentUserId(), kbId, KbAclService.PERM_READ);
    }

    private boolean canReadMedia(Long kbId) {
        if (kbId != null) return canRead(kbId);
        com.simon.MindCrew.entity.SysUser user = userService.getCurrentUser();
        return user != null && "admin".equalsIgnoreCase(user.getRole());
    }
}
