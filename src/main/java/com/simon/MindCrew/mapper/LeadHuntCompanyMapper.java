package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.LeadHuntCompany;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 全球获客 · 公司 Mapper
 */
@Mapper
public interface LeadHuntCompanyMapper extends BaseMapper<LeadHuntCompany> {

    /**
     * 历史去重：指定会话之外，该域名是否已在历史会话中出现。
     */
    @Select("SELECT COUNT(*) FROM lead_hunt_company c " +
            "JOIN lead_hunt_session s ON s.id = c.session_id " +
            "WHERE c.deleted = 0 AND s.deleted = 0 AND s.user_id = #{userId} " +
            "AND c.session_id <> #{sessionId} AND c.domain = #{domain}")
    long countHistoryByDomain(@Param("sessionId") Long sessionId,
                              @Param("userId") Long userId,
                              @Param("domain") String domain);

    /**
     * 地图模块：按国家聚合有效线索公司数（跨全部历史会话）。
     */
    @Select("<script>" +
            "SELECT c.country AS country, COUNT(*) AS cnt FROM lead_hunt_company c " +
            "JOIN lead_hunt_session s ON s.id = c.session_id " +
            "WHERE c.deleted = 0 AND s.deleted = 0 AND s.user_id = #{userId} " +
            "AND c.country IS NOT NULL AND c.country &lt;&gt; '' " +
            "<if test='sessionId != null'>AND c.session_id = #{sessionId} </if>" +
            "GROUP BY c.country ORDER BY cnt DESC" +
            "</script>")
    List<Map<String, Object>> countByCountry(@Param("userId") Long userId,
                                             @Param("sessionId") Long sessionId);
}
