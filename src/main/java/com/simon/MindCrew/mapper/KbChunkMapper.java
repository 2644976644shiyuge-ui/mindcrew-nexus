package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.KbChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 文档切片 Mapper
 * 对应数据表: kb_chunk
 */
@Mapper
public interface KbChunkMapper extends BaseMapper<KbChunk> {

    /**
     * 性能优化 · 一次 SQL 批量统计多个 KB 的 chunk 数量
     * 替代 for(kbId) { selectCount(...) } 这种 N+1 写法
     *
     * @return List of Map{kbId: Long, cnt: Long}
     */
    @Select("<script>" +
            "SELECT kb_id AS kbId, COUNT(*) AS cnt FROM kb_chunk WHERE kb_id IN " +
            "<foreach collection='kbIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>" +
            " GROUP BY kb_id" +
            "</script>")
    List<Map<String, Object>> countByKbIds(@Param("kbIds") List<Long> kbIds);
}
