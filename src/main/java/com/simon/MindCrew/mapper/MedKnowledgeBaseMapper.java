package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * 知识库 Mapper
 */
@Mapper
public interface MedKnowledgeBaseMapper extends BaseMapper<MedKnowledgeBase> {

    /** 全量索引维护锁是否已被任意数据库连接持有。 */
    @Select("SELECT IS_USED_LOCK('mindcrew_kb_rebuild') IS NOT NULL")
    boolean isKnowledgeRebuildLocked();
}
