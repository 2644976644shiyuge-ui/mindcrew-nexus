package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.KbGraphNode;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识图谱实体节点 Mapper
 * 位于 com.simon.MindCrew.mapper 包，已被 MindCrewApplication @MapperScan 覆盖。
 */
@Mapper
public interface KbGraphNodeMapper extends BaseMapper<KbGraphNode> {
}
