package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.LeadHuntContact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 全球获客 · 联系人 Mapper
 */
@Mapper
public interface LeadHuntContactMapper extends BaseMapper<LeadHuntContact> {

    /**
     * 历史去重：指定会话之外，该邮箱是否已在历史会话中出现。
     */
    @Select("SELECT COUNT(*) FROM lead_hunt_contact c " +
            "JOIN lead_hunt_session s ON s.id = c.session_id " +
            "WHERE c.deleted = 0 AND s.deleted = 0 AND s.user_id = #{userId} " +
            "AND c.session_id <> #{sessionId} AND c.email = #{email}")
    long countHistoryByEmail(@Param("sessionId") Long sessionId,
                             @Param("userId") Long userId,
                             @Param("email") String email);

    /**
     * 某会话下已有联系人的邮箱集合（同会话内去重用）。
     */
    @Select("SELECT email FROM lead_hunt_contact WHERE deleted = 0 AND session_id = #{sessionId} AND email IS NOT NULL")
    List<String> selectEmailsBySession(Long sessionId);
}
