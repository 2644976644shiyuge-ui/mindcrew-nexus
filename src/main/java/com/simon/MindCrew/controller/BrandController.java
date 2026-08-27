package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.service.SettingService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequiredArgsConstructor
public class BrandController {

    private static final String KEY_SYSTEM_NAME = "brand.system_name";
    private static final String KEY_LOGO_URL = "brand.logo_url";
    private static final String DEFAULT_SYSTEM_NAME = "MindCrew";

    private final SettingService settingService;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    @GetMapping("/api/system/brand")
    public Result<Map<String, Object>> publicBrand() {
        return Result.success(brandPayload());
    }

    @GetMapping("/api/admin/brand-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> getSettings() {
        return Result.success(brandPayload());
    }

    @PutMapping("/api/admin/brand-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> updateSettings(@RequestBody BrandSettingsDTO dto) {
        String systemName = dto.getSystemName() == null ? "" : dto.getSystemName().trim();
        if (systemName.isBlank()) {
            throw new BusinessException("系统名称不能为空");
        }
        if (systemName.length() > 40) {
            throw new BusinessException("系统名称不能超过 40 个字符");
        }
        settingService.set(KEY_SYSTEM_NAME, systemName);

        if (dto.getLogoUrl() != null) {
            String logoUrl = dto.getLogoUrl().trim();
            settingService.set(KEY_LOGO_URL, logoUrl.isBlank() ? null : logoUrl);
        }
        return Result.success(brandPayload());
    }

    @PostMapping("/api/admin/brand-logo")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> uploadLogo(@RequestParam("file") MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("只允许上传图片文件");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            throw new BusinessException("Logo 图片不能超过 2MB");
        }
        try {
            String originalName = file.getOriginalFilename();
            String ext = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf('.')) : ".png";
            String filename = "brand-logo-" + UUID.randomUUID() + ext;
            Path dir = Paths.get(uploadPath, "brand");
            Files.createDirectories(dir);
            Path dest = dir.resolve(filename);
            file.transferTo(dest.toAbsolutePath().toFile());
            String url = "/uploads/brand/" + filename;
            log.info("品牌 Logo 已上传: {}", dest.toAbsolutePath());
            return Result.success(url);
        } catch (Exception e) {
            log.error("品牌 Logo 上传失败", e);
            throw new BusinessException("Logo 上传失败: " + e.getMessage());
        }
    }

    private Map<String, Object> brandPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("systemName", settingService.getString(KEY_SYSTEM_NAME, DEFAULT_SYSTEM_NAME));
        payload.put("logoUrl", settingService.getString(KEY_LOGO_URL, null));
        return payload;
    }

    @Data
    public static class BrandSettingsDTO {
        private String systemName;
        private String logoUrl;
    }
}
