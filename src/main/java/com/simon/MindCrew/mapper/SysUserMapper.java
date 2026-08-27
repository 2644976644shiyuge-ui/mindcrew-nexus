package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户 Mapper
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * 在线人数：last_login 在 [since, now] 区间内的用户数（默认 5 分钟内视为在线）。
     * 真实"在线"需要 WebSocket/心跳；这里用最近活跃度近似。
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE deleted = 0 AND last_login IS NOT NULL AND last_login >= #{since}")
    long countOnlineSince(LocalDateTime since);

    /**
     * 最近活跃用户列表（按 last_login DESC）· 用于首页头像堆叠展示。
     */
    @Select("SELECT id, username, nickname, avatar, role, last_login FROM sys_user " +
            "WHERE deleted = 0 AND status = 1 AND last_login IS NOT NULL " +
            "ORDER BY last_login DESC LIMIT #{limit}")
    List<SysUser> findRecentActive(int limit);
}
