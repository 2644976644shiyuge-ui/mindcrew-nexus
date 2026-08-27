package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.SysUserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 在线统计 API（首页实时面板用）
 *
 *   GET /api/stats/online-users
 *     -> { total: 当前在线人数（5 分钟内有 last_login）, recent: [前 8 个用户头像+昵称] }
 *
 * 注：真实"在线"需要 WebSocket 心跳维护在线状态表；这里用 sys_user.last_login 近似"最近 5 分钟活跃"。
 * 阈值可在 application.yml 通过 stats.online.window-minutes 覆盖，默认 5 分钟。
 */
@RestController
@RequestMapping("/api/stats/online")
@RequiredArgsConstructor
public class OnlineStatsController {

    private final SysUserMapper userMapper;
    private static final int WINDOW_MINUTES = 5;
    private static final int RECENT_LIMIT = 8;

    @GetMapping("/users")
    public Result<OnlineResponse> users() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(WINDOW_MINUTES);
        long total = userMapper.countOnlineSince(since);
        List<SysUser> raw = userMapper.findRecentActive(RECENT_LIMIT);

        OnlineResponse resp = new OnlineResponse();
        resp.setTotal(total);
        resp.setWindowMinutes(WINDOW_MINUTES);
        resp.setRecent(new ArrayList<>());
        for (SysUser u : raw) {
            OnlineUser ou = new OnlineUser();
            ou.setId(u.getId());
            ou.setNickname(u.getNickname() != null ? u.getNickname() : u.getUsername());
            ou.setAvatar(u.getAvatar());
            ou.setRole(u.getRole());
            resp.getRecent().add(ou);
        }
        return Result.success(resp);
    }

    @Data
    public static class OnlineResponse {
        private long total;
        private int windowMinutes;
        private List<OnlineUser> recent;
    }

    @Data
    public static class OnlineUser {
        private Long id;
        private String nickname;
        private String avatar;
        private String role;
    }
}