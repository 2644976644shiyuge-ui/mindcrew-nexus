package com.simon.MindCrew.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 管理员主动创建用户 DTO
 * 与自助注册 RegisterDTO 的区别：允许设置 role（user/admin），无需邮箱验证。
 */
@Data
public class AdminCreateUserDTO {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 20, message = "用户名长度为3-20个字符")
    @Pattern(regexp = "^[A-Za-z0-9_.@-]+$", message = "用户名只能含字母、数字及 _ . @ - 符号")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度为6-20个字符")
    private String password;

    private String nickname;

    /** 系统角色：user（默认）/ admin */
    private String role;

    /** 可选：部门 ID */
    private Long departmentId;

    /** 可选：职位 ID */
    private Long positionId;
}
