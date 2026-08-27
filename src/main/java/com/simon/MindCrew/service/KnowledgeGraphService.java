package com.simon.MindCrew.service;

import com.simon.MindCrew.entity.vo.GraphVO;

/**
 * 知识图谱服务：抽取建图 + 聚合查询 + 清理。
 */
public interface KnowledgeGraphService {

    /**
     * 为单篇文档抽取实体/关系并落库（覆盖该文档旧图谱）。
     * 容错：内部异常不外抛，保证不影响文档主流程。
     */
    void buildForKb(Long kbId);

    /** 按知识库(集合)聚合查询图谱 · 同名实体跨文档归并 */
    GraphVO getByCollection(Long collectionId);

    /** 按单文档查询图谱 */
    GraphVO getByKb(Long kbId);

    /** 删除某文档的全部图谱数据（文档删除/重处理时调用） */
    void deleteByKb(Long kbId);
}
