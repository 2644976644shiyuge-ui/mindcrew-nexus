package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.entity.dto.LoginDTO;
import com.simon.MindCrew.entity.dto.RegisterDTO;
import com.simon.MindCrew.entity.dto.UserUpdateDTO;
import com.simon.MindCrew.entity.vo.LoginVO;
import com.simon.MindCrew.entity.vo.PageVO;
import com.simon.MindCrew.service.UserService;
import com.simon.MindCrew.entity.dto.ResetPasswordDTO;
import com.simon.MindCrew.entity.dto.SendCodeDTO;
import com.simon.MindCrew.entity.dto.ChangePasswordDTO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 用户管理接口
 */
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final com.simon.MindCrew.service.KbAclService kbAclService;
    private final com.simon.MindCrew.security.SessionActivityService sessionActivityService;

    // ==================== 找回密码（无需登录）====================

    /**
     * 发送找回密码验证码
     */
    @PostMapping("/forgot-password/send-code")
    public Result<String> sendResetCode(@Valid @RequestBody SendCodeDTO dto) {
        userService.sendResetCode(dto.getUsername());
        return Result.success("验证码已发送");
    }

    /**
     * 验证码校验并重置密码
     */
    @PostMapping("/forgot-password/reset")
    public Result<String> resetPassword(@Valid @RequestBody ResetPasswordDTO dto) {
        userService.resetPassword(dto);
        return Result.success("密码重置成功");
    }

    // ==================== 登录注册 ====================

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return Result.success(userService.login(dto));
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterDTO dto) {
        userService.register(dto);
        return Result.success();
    }

    /**
     * 退出登录 —— 删除服务端会话活跃标记，立即吊销当前 token 的会话。
     */
    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            sessionActivityService.evict(authorization.substring(7));
        }
        return Result.success();
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/info")
    public Result<SysUser> getCurrentUser() {
        return Result.success(userService.getCurrentUser());
    }

    /**
     * 更新用户信息
     */
    @PutMapping("/info")
    public Result<Void> updateUser(@RequestBody UserUpdateDTO dto) {
        userService.updateUser(dto);
        return Result.success();
    }

    /**
     * 上传头像
     */
    @PostMapping("/avatar")
    public Result<String> uploadAvatar(@RequestParam("file") MultipartFile file) {
        String avatarUrl = userService.uploadAvatar(file);
        return Result.success("头像更新成功", avatarUrl);
    }

    /**
     * 更新用户偏好
     */
    @PutMapping("/preference")
    public Result<Void> updatePreference(@RequestBody String preference) {
        userService.updatePreference(preference);
        return Result.success();
    }

    /**
     * 修改密码（登录态，校验原密码）
     */
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        userService.changePassword(dto);
        return Result.success();
    }

    // ==================== 管理员接口 ====================

    /**
     * 管理员主动创建用户
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Long> createUser(@Valid @RequestBody com.simon.MindCrew.entity.dto.AdminCreateUserDTO dto) {
        return Result.success("创建成功", userService.createUserByAdmin(dto));
    }

    /**
     * 分页查询用户列表
     */
    @GetMapping("/list")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<PageVO<SysUser>> listUsers(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "size", defaultValue = "10") Integer size,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "role", required = false) String role,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "status", required = false) Integer status) {
        Page<SysUser> page = userService.listUsers(current, size, keyword, role, source, status);
        return Result.success(PageVO.of(page));
    }

    /**
     * 设置账号到期时间（管理员）· expireTime 传 null/空 表示永久有效
     */
    @PutMapping("/{userId}/expire-time")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateUserExpireTime(@PathVariable Long userId,
                                             @RequestBody ExpireTimeDTO dto) {
        userService.updateUserExpireTime(userId, dto.getExpireTime());
        return Result.success();
    }

    @lombok.Data
    public static class ExpireTimeDTO {
        @com.fasterxml.jackson.annotation.JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private java.time.LocalDateTime expireTime;
    }

    /**
     * 修改用户状态
     */
    @PutMapping("/{userId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateUserStatus(@PathVariable Long userId, @RequestParam("status") Integer status) {
        userService.updateUserStatus(userId, status);
        return Result.success();
    }

    /**
     * 注销用户（管理员）· 逻辑删除，禁止删除自己
     */
    @DeleteMapping("/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> deleteUser(@PathVariable Long userId) {
        if (userId != null && userId.equals(userService.getCurrentUserId())) {
            return Result.error("不能注销当前登录的管理员账号");
        }
        userService.deleteUser(userId);
        return Result.success();
    }

    /**
     * 修改用户角色
     */
    @PutMapping("/{userId}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateUserRole(@PathVariable Long userId, @RequestParam("role") String role) {
        userService.updateUserRole(userId, role);
        return Result.success();
    }

    /**
     * 任务 7 · 给用户分配部门 + 职位
     */
    @PutMapping("/{userId}/org")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateUserOrg(@PathVariable Long userId, @RequestBody OrgDTO dto) {
        userService.updateUserOrg(userId, dto.getDepartmentId(), dto.getPositionId());
        return Result.success();
    }

    /** 管理员直接重置某用户密码 */
    @PutMapping("/{userId}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> adminResetPassword(@PathVariable Long userId, @RequestBody ResetPwdDTO dto) {
        userService.adminResetPassword(userId, dto.getNewPassword());
        return Result.success();
    }

    /** 查某用户的知识库授权配置：模式 + 直接授权的 collection id 列表 */
    @GetMapping("/{userId}/collections")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<java.util.Map<String, Object>> listUserCollections(@PathVariable Long userId) {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("mode", kbAclService.getUserKbScopeMode(userId));
        out.put("collectionIds", kbAclService.listUserGrantedCollectionIds(userId));
        return Result.success(out);
    }

    /** 整体设置某用户的知识库授权：模式(inherit/override) + 直接可访问的知识库 */
    @PutMapping("/{userId}/collections")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> setUserCollections(@PathVariable Long userId, @RequestBody UserKbDTO dto) {
        Long me = userService.getCurrentUserId();
        kbAclService.setUserKbScope(userId, dto.getMode(), dto.getCollectionIds(), me);
        return Result.success();
    }

    @lombok.Data
    public static class OrgDTO {
        private Long departmentId;
        private Long positionId;
    }

    @lombok.Data
    public static class ResetPwdDTO {
        private String newPassword;
    }

    @lombok.Data
    public static class UserKbDTO {
        private String mode;   // inherit / override
        private java.util.List<Long> collectionIds;
    }
}
