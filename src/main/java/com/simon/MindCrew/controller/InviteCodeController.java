package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.InviteCode;
import com.simon.MindCrew.service.InviteCodeService;
import com.simon.MindCrew.service.SettingService;
import com.simon.MindCrew.service.UserService;
import com.fasterxml.jackson.annotation.JsonFormat;
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
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 邀请码管理（管理员）+ 注册二维码上传 / 公开获取。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class InviteCodeController {

    private static final String KEY_QR_URL = "register.invite_qr_url";
    private static final String KEY_EXPIRE_DAYS = "register.default_expire_days";

    private final InviteCodeService inviteCodeService;
    private final SettingService settingService;
    private final UserService userService;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    // ==================== 邀请码 CRUD（管理员）====================

    @PostMapping("/api/admin/invite-codes/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<InviteCode>> generate(@RequestBody GenerateDTO dto) {
        Long me = userService.getCurrentUserId();
        List<InviteCode> codes = inviteCodeService.generate(
                dto.getCount() == null ? 1 : dto.getCount(),
                dto.getMaxUses(), dto.getExpireTime(), dto.getRemark(), me);
        return Result.success(codes);
    }

    @GetMapping("/api/admin/invite-codes")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<InviteCode>> list() {
        return Result.success(inviteCodeService.list());
    }

    @PutMapping("/api/admin/invite-codes/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> setEnabled(@PathVariable Long id, @RequestParam("enabled") boolean enabled) {
        inviteCodeService.setEnabled(id, enabled);
        return Result.success();
    }

    @DeleteMapping("/api/admin/invite-codes/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        inviteCodeService.delete(id);
        return Result.success();
    }

    // ==================== 注册二维码 + 默认有效期（管理员）====================

    @PostMapping("/api/admin/register-qr")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<String> uploadQr(@RequestParam("file") MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("只允许上传图片文件");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException("二维码图片不能超过 5MB");
        }
        try {
            String originalName = file.getOriginalFilename();
            String ext = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf('.')) : ".png";
            String filename = "invite-qr-" + UUID.randomUUID() + ext;
            Path dir = Paths.get(uploadPath, "register-qr");
            Files.createDirectories(dir);
            Path dest = dir.resolve(filename);
            file.transferTo(dest.toAbsolutePath().toFile());
            String url = "/uploads/register-qr/" + filename;
            settingService.set(KEY_QR_URL, url);
            log.info("注册二维码已更新: {}", dest.toAbsolutePath());
            return Result.success(url);
        } catch (Exception e) {
            log.error("二维码上传失败", e);
            throw new BusinessException("二维码上传失败: " + e.getMessage());
        }
    }

    @GetMapping("/api/admin/register-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> getSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qrUrl", settingService.getString(KEY_QR_URL, null));
        m.put("defaultExpireDays", settingService.getInt(KEY_EXPIRE_DAYS, 2));
        return Result.success(m);
    }

    @PutMapping("/api/admin/register-settings")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateSettings(@RequestBody SettingsDTO dto) {
        if (dto.getDefaultExpireDays() != null && dto.getDefaultExpireDays() > 0) {
            settingService.set(KEY_EXPIRE_DAYS, String.valueOf(dto.getDefaultExpireDays()));
        }
        return Result.success();
    }

    // ==================== 注册页公开获取二维码（无需登录）====================

    @GetMapping("/api/user/register-qr")
    public Result<Map<String, Object>> publicQr() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qrUrl", settingService.getString(KEY_QR_URL, null));
        return Result.success(m);
    }

    @Data
    public static class GenerateDTO {
        private Integer count;
        private Integer maxUses;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime expireTime;
        private String remark;
    }

    @Data
    public static class SettingsDTO {
        private Integer defaultExpireDays;
    }
}
