package com.simon.MindCrew.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求 DTO
 */
@Data
public class RegisterDTO {

    @NotBlank(message = "登录名不能为空")
    @Size(min = 3, max = 20, message = "登录名长度为3-20个字符")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度为6-20个字符")
    private String password;

    private String nickname;

    /** 邀请码（必填）：外部注册采用邀请码制度，校验通过才放行 */
    @NotBlank(message = "请填写邀请码")
    private String inviteCode;
}
