package com.simon.MindCrew.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.common.utils.JwtUtils;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.entity.dto.LoginDTO;
import com.simon.MindCrew.entity.dto.RegisterDTO;
import com.simon.MindCrew.entity.dto.UserUpdateDTO;
import com.simon.MindCrew.entity.vo.LoginVO;
import com.simon.MindCrew.mapper.SysUserMapper;
import com.simon.MindCrew.mapper.SysPositionMapper;
import com.simon.MindCrew.mapper.SysDepartmentMapper;
import com.simon.MindCrew.entity.SysPosition;
import com.simon.MindCrew.entity.SysDepartment;
import com.simon.MindCrew.common.FeatureCatalog;
import com.alibaba.fastjson2.JSON;
import com.simon.MindCrew.entity.dto.ResetPasswordDTO;
import com.simon.MindCrew.entity.dto.ChangePasswordDTO;
import com.simon.MindCrew.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import java.time.LocalDateTime;

/**
 * 用户服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final SysUserMapper sysUserMapper;
    private final SysPositionMapper sysPositionMapper;
    private final SysDepartmentMapper sysDepartmentMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;
    private final StringRedisTemplate stringRedisTemplate;
    private final com.simon.MindCrew.service.InviteCodeService inviteCodeService;
    private final com.simon.MindCrew.service.SettingService settingService;
    private final com.simon.MindCrew.security.SessionActivityService sessionActivityService;

    /** 外部注册用户归入的部门名（迁移脚本预置） */
    private static final String EXTERNAL_REGISTER_DEPT = "外部注册用户";

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /** Redis key 前缀，TTL 5 分钟 */
    private static final String RESET_CODE_PREFIX = "reset:code:";
    private static final long RESET_CODE_TTL = 5L;

    @Override
    @com.simon.MindCrew.common.audit.Audited(action = "user.login", label = "用户登录", targetType = "user", targetIdParam = "$arg0.username")
    public LoginVO login(LoginDTO dto) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, dto.getUsername())
                        .eq(SysUser::getDeleted, 0)
        );

        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BusinessException(ResultCode.USER_PASSWORD_ERROR);
        }

        if (user.getStatus() == 0) {
            throw new BusinessException(ResultCode.USER_DISABLED);
        }

        // 账号到期检查（外部注册用户默认 2 天，到期即禁登）
        if (user.getExpireTime() != null && user.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("账号已到期，请联系管理员");
        }

        // 更新最后登录时间
        user.setLastLogin(LocalDateTime.now());
        sysUserMapper.updateById(user);

        String token = jwtUtils.generateToken(user.getId(), user.getUsername(), user.getRole());

        // 写入会话活跃标记，启动闲置超时窗口（默认 1 小时无操作自动下线）
        sessionActivityService.touch(token);

        return LoginVO.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .role(user.getRole())
                .build();
    }

    @Override
    @Transactional
    public void register(RegisterDTO dto) {
        // 检查用户名是否存在
        long usernameCount = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername())
        );
        if (usernameCount > 0) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }

        // 邀请码必填校验并消费（无效则拒绝注册）
        inviteCodeService.validateAndConsume(dto.getInviteCode());

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.isNotBlank(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setRole("user");
        user.setStatus(1);
        user.setSource("register");
        // 默认有效期（天），到期禁登；管理员可在后台单独调整
        int days = settingService.getInt("register.default_expire_days", 2);
        user.setExpireTime(LocalDateTime.now().plusDays(days));
        // 外部注册用户统一归入「外部注册用户」部门，不带职位（仅能访问 public KB）
        user.setDepartmentId(resolveExternalRegisterDeptId());

        sysUserMapper.insert(user);
        log.info("新用户注册成功: {} (source=register, 到期={})", dto.getUsername(), user.getExpireTime());
    }

    /** 查「外部注册用户」部门 id（迁移脚本预置）；查不到返回 null（不阻断注册）。 */
    private Long resolveExternalRegisterDeptId() {
        try {
            SysDepartment dept = sysDepartmentMapper.selectOne(
                    new LambdaQueryWrapper<SysDepartment>()
                            .eq(SysDepartment::getName, EXTERNAL_REGISTER_DEPT)
                            .eq(SysDepartment::getDeleted, 0)
                            .last("LIMIT 1"));
            return dept == null ? null : dept.getId();
        } catch (Exception e) {
            log.warn("查找外部注册用户部门失败: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Long createUserByAdmin(com.simon.MindCrew.entity.dto.AdminCreateUserDTO dto) {
        long usernameCount = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, dto.getUsername()));
        if (usernameCount > 0) {
            throw new BusinessException(ResultCode.USER_ALREADY_EXISTS);
        }
        // 角色只允许 user / admin，其他一律降级为 user，防越权
        String role = "admin".equalsIgnoreCase(dto.getRole()) ? "admin" : "user";

        SysUser user = new SysUser();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.isNotBlank(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setRole(role);
        user.setStatus(1);
        user.setSource("admin");   // 管理员创建，默认永久有效（expireTime 留空）
        user.setDepartmentId(dto.getDepartmentId());
        user.setPositionId(dto.getPositionId());

        sysUserMapper.insert(user);
        log.info("管理员创建用户成功: {} (role={} deptId={} posId={})",
                dto.getUsername(), role, dto.getDepartmentId(), dto.getPositionId());
        return user.getId();
    }

    @Override
    public SysUser getCurrentUser() {
        Long userId = getCurrentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user != null) {
            user.setPermissions(resolvePermissions(user));
        }
        return user;
    }

    /**
     * #3 · 计算用户的有效可用功能点
     *   admin 系统角色 → 全部功能
     *   否则：职位(若配置) → 部门(若配置) → 默认集；并始终并入 ALWAYS_ON
     */
    private java.util.List<String> resolvePermissions(SysUser user) {
        if ("admin".equalsIgnoreCase(user.getRole())) {
            return FeatureCatalog.allKeys();
        }
        String json = null;
        if (user.getPositionId() != null) {
            SysPosition pos = sysPositionMapper.selectById(user.getPositionId());
            if (pos != null && pos.getPermissions() != null) json = pos.getPermissions();
        }
        if (json == null && user.getDepartmentId() != null) {
            SysDepartment dept = sysDepartmentMapper.selectById(user.getDepartmentId());
            if (dept != null && dept.getPermissions() != null) json = dept.getPermissions();
        }
        // 都没配置 → 用默认集（等同改造前体验）
        if (json == null) {
            return new java.util.ArrayList<>(FeatureCatalog.DEFAULT_FEATURES);
        }
        // 已配置 → 配置项 ∪ 永远开启项
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>(FeatureCatalog.ALWAYS_ON);
        try {
            java.util.List<String> list = JSON.parseArray(json, String.class);
            if (list != null) result.addAll(list);
        } catch (Exception ignore) { /* 脏数据忽略 */ }
        return new java.util.ArrayList<>(result);
    }

    @Override
    @Transactional
    public void updateUser(UserUpdateDTO dto) {
        Long userId = getCurrentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }

        if (StringUtils.isNotBlank(dto.getNickname())) {
            user.setNickname(dto.getNickname());
        }
        if (StringUtils.isNotBlank(dto.getAvatar())) {
            user.setAvatar(dto.getAvatar());
        }
        if (StringUtils.isNotBlank(dto.getPreference())) {
            user.setPreference(dto.getPreference());
        }

        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    public void updatePreference(String preference) {
        Long userId = getCurrentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        user.setPreference(preference);
        sysUserMapper.updateById(user);
    }

    @Override
    public Page<SysUser> listUsers(Integer current, Integer size, String keyword,
                                   String role, String source, Integer status) {
        Page<SysUser> page = new Page<>(current, size);
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, 0)
                .orderByDesc(SysUser::getCreateTime);

        if (StringUtils.isNotBlank(keyword)) {
            wrapper.and(w -> w
                    .like(SysUser::getUsername, keyword)
                    .or().like(SysUser::getNickname, keyword)
            );
        }
        if (StringUtils.isNotBlank(role)) {
            wrapper.eq(SysUser::getRole, role);
        }
        // 来源：register / admin；历史数据 source 为空时按 admin 处理
        if (StringUtils.isNotBlank(source)) {
            if ("admin".equals(source)) {
                wrapper.and(w -> w.eq(SysUser::getSource, "admin").or().isNull(SysUser::getSource));
            } else {
                wrapper.eq(SysUser::getSource, source);
            }
        }
        if (status != null) {
            wrapper.eq(SysUser::getStatus, status);
        }

        return sysUserMapper.selectPage(page, wrapper);
    }

    @Override
    public void updateUserExpireTime(Long userId, LocalDateTime expireTime) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // 用 UpdateWrapper 以便能把到期时间显式置为 null（永久有效）
        sysUserMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUser>()
                        .eq(SysUser::getId, userId)
                        .set(SysUser::getExpireTime, expireTime));
    }

    @Override
    @Transactional
    public void updateUserStatus(Long userId, Integer status) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        user.setStatus(status);
        sysUserMapper.updateById(user);
    }

    @Override
    @Transactional
    @com.simon.MindCrew.common.audit.Audited(action = "user.role.change", label = "调整用户角色", targetType = "user", targetIdParam = "$arg0")
    public void updateUserRole(Long userId, String role) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        user.setRole(role);
        sysUserMapper.updateById(user);
    }

    @Override
    public void deleteUser(Long userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        sysUserMapper.deleteById(userId);   // @TableLogic → 逻辑删除
        log.info("注销用户: id={} username={}", userId, user.getUsername());
    }

    @Override
    @Transactional
    public void adminResetPassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 20) {
            throw new BusinessException("新密码长度需为 6-20 个字符");
        }
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        SysUser patch = new SysUser();
        patch.setId(userId);
        patch.setPassword(passwordEncoder.encode(newPassword));
        sysUserMapper.updateById(patch);
        log.info("管理员重置用户密码: userId={}", userId);
    }

    @Override
    @Transactional
    public void updateUserOrg(Long userId, Long departmentId, Long positionId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        // null 表示清空（让 mybatis-plus 走 set 而非忽略）
        SysUser patch = new SysUser();
        patch.setId(userId);
        patch.setDepartmentId(departmentId);
        patch.setPositionId(positionId);
        // 用 LambdaUpdateWrapper 强制把 null 字段也写进 SQL（默认 updateById 不会更新 null）
        sysUserMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<SysUser>()
                        .set(SysUser::getDepartmentId, departmentId)
                        .set(SysUser::getPositionId, positionId)
                        .eq(SysUser::getId, userId));
    }

    @Override
    @Transactional
    public String uploadAvatar(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new BusinessException("只允许上传图片文件");
        }
        if (file.getSize() > 5 * 1024 * 1024) {
            throw new BusinessException("头像图片不能超过 5MB");
        }
        Long userId = getCurrentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) throw new BusinessException(ResultCode.USER_NOT_FOUND);

        try {
            String originalName = file.getOriginalFilename();
            String extension = (originalName != null && originalName.contains("."))
                    ? originalName.substring(originalName.lastIndexOf('.'))
                    : ".jpg";
            String filename = UUID.randomUUID() + extension;

            Path dir = Paths.get(uploadPath, "avatar");
            Files.createDirectories(dir);
            Path dest = dir.resolve(filename);
            file.transferTo(dest.toAbsolutePath().toFile());

            String avatarUrl = "/uploads/avatar/" + filename;
            user.setAvatar(avatarUrl);
            sysUserMapper.updateById(user);
            log.info("用户 {} 头像已保存到本地: {}", user.getUsername(), dest.toAbsolutePath());
            return avatarUrl;
        } catch (Exception e) {
            log.error("头像上传失败", e);
            throw new BusinessException("头像上传失败: " + e.getMessage());
        }
    }

    @Override
    public void sendResetCode(String username) {
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, username)
                        .eq(SysUser::getDeleted, 0)
        );
        if (user == null) {
            throw new BusinessException("该用户名未注册");
        }

        String key = RESET_CODE_PREFIX + username;
        Long ttl = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (ttl != null && ttl > (RESET_CODE_TTL * 60 - 60)) {
            throw new BusinessException("验证码已发送，请60秒后再试");
        }

        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(0, 1000000));
        stringRedisTemplate.opsForValue().set(key, code, RESET_CODE_TTL, TimeUnit.MINUTES);
        log.info("【模拟验证码】用户名: {} 验证码: {} (5分钟内有效)", username, code);
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordDTO dto) {
        String key = RESET_CODE_PREFIX + dto.getUsername();
        String savedCode = stringRedisTemplate.opsForValue().get(key);

        if (savedCode == null) {
            throw new BusinessException("验证码已过期，请重新获取");
        }
        if (!savedCode.equals(dto.getCode())) {
            throw new BusinessException("验证码错误，请重新输入");
        }

        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, dto.getUsername())
                        .eq(SysUser::getDeleted, 0)
        );
        if (user == null) {
            throw new BusinessException("用户不存在");
        }

        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        sysUserMapper.updateById(user);
        stringRedisTemplate.delete(key);
        log.info("用户 {} 密码重置成功", user.getUsername());
    }

    @Override
    @Transactional
    public void changePassword(ChangePasswordDTO dto) {
        Long userId = getCurrentUserId();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            throw new BusinessException("原密码不正确");
        }
        if (passwordEncoder.matches(dto.getNewPassword(), user.getPassword())) {
            throw new BusinessException("新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        sysUserMapper.updateById(user);
        log.info("用户 {} 修改密码成功", user.getUsername());
    }

    @Override
    public Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        String username = authentication.getName();
        SysUser user = sysUserMapper.selectOne(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username)
        );
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return user.getId();
    }
}
