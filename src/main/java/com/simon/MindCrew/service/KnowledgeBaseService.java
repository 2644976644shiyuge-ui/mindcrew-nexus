package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库服务接口
 */
public interface KnowledgeBaseService {

    /**
     * 上传并解析文档（异步）
     * @return 知识库记录ID
     */
    Long uploadDocument(MultipartFile file, String name, String category, String description, Long userId);

    /** 任务 15：带 collectionId 的上传 · collectionId=null 视为散文档 */
    Long uploadDocument(MultipartFile file, String name, String category, String description, Long userId, Long collectionId);

    /**
     * 从对象存储里已有的对象创建知识库文档（异步解析/转写）。
     * 用于「问答页附件 → 加入知识库」：复用已上传的音视频/文档原件，走完整入库管线。
     * @param objectName 对象存储 objectName（如 chat-attachment/uuid.mp4）
     * @return 知识库记录ID
     */
    Long uploadDocumentFromObject(String objectName, String name, String category, String description, Long userId, Long collectionId);

    /**
     * 分页查询知识库列表
     */
    Page<MedKnowledgeBase> listKnowledge(Integer current, Integer size, String category, String status);

    /**
     * 文档全量统计（顶部统计卡片用）· 复用 list 的 ACL 作用域
     * 返回: total / ready / processing / failed / totalChunks
     */
    java.util.Map<String, Object> statsKnowledge();

    /**
     * 获取知识库详情
     */
    MedKnowledgeBase getById(Long id);

    /** 取原文件下载直链（OSS 预签名优先，本地回退代理 URL）· 找不到返回 null */
    String getDownloadUrl(Long id);

    /**
     * 删除知识库（同步删除 Milvus 向量）
     */
    void deleteById(Long id);

    /**
     * 获取所有分类
     */
    java.util.List<String> listCategories();

    /**
     * 重新处理文档（处理失败时重试）
     */
    void reprocess(Long id);

    /** 仅供持有数据库全局维护锁的本地协调器调用，不暴露为 HTTP 接口。 */
    void reprocessForMaintenance(Long id);

    /**
     * 任务 7 · 切换 KB 可见性
     */
    void updateVisibility(Long id, String visibility);
}
