package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.entity.dto.LoginDTO;
import com.simon.MindCrew.entity.dto.RegisterDTO;
import com.simon.MindCrew.entity.dto.UserUpdateDTO;
import com.simon.MindCrew.entity.vo.LoginVO;

/**
 * 用户服务接口
 */
public interface UserService {

    /**
     * 用户登录
     */
    LoginVO login(LoginDTO dto);

    /**
     * 用户注册
     */
    void register(RegisterDTO dto);

    /** 管理员主动创建用户（可设角色），返回新用户 ID */
    Long createUserByAdmin(com.simon.MindCrew.entity.dto.AdminCreateUserDTO dto);

    /**
     * 获取当前用户信息
     */
    SysUser getCurrentUser();

    /**
     * 更新用户信息
     */
    void updateUser(UserUpdateDTO dto);

    /**
     * 更新用户偏好
     */
    void updatePreference(String preference);

    /**
     * 分页查询用户列表 (管理员) · 支持按角色 / 来源 / 状态筛选
     */
    Page<SysUser> listUsers(Integer current, Integer size, String keyword,
                            String role, String source, Integer status);

    /** 设置账号到期时间（管理员）· expireTime 为 null 表示永久有效 */
    void updateUserExpireTime(Long userId, java.time.LocalDateTime expireTime);

    /**
     * 修改用户状态 (管理员)
     */
    void updateUserStatus(Long userId, Integer status);

    /**
     * 修改用户角色 (管理员)
     */
    void updateUserRole(Long userId, String role);

    /** 注销用户（管理员）· 逻辑删除 */
    void deleteUser(Long userId);

    /**
     * 任务 7 · 给用户分配部门 + 职位（管理员）
     * 任一参数为 null 表示清空对应字段
     */
    void updateUserOrg(Long userId, Long departmentId, Long positionId);

    /** 管理员直接重置某用户密码 */
    void adminResetPassword(Long userId, String newPassword);

    /**
     * 上传头像
     */
    String uploadAvatar(org.springframework.web.multipart.MultipartFile file);

    /**
     * 发送找回密码验证码（模拟短信，实际打印到日志）
     */
    void sendResetCode(String phone);

    /**
     * 校验验证码并重置密码
     */
    void resetPassword(com.simon.MindCrew.entity.dto.ResetPasswordDTO dto);

    /**
     * 登录态下修改当前用户密码（校验原密码）
     */
    void changePassword(com.simon.MindCrew.entity.dto.ChangePasswordDTO dto);

    /**
     * 获取当前登录用户ID
     */
    Long getCurrentUserId();
}
