package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KnowledgeCollection;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.mapper.KnowledgeCollectionMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库（集合）核心服务 · 任务 15
 *
 * 职责：
 *   - CRUD 知识库
 *   - 把文档移入/移出知识库
 *   - 维护 doc_count / total_chunks 缓存
 *   - 获取当前用户可访问的知识库集合（结合 visibility + ownerUserId）
 *
 * 设计取舍：
 *   - 删除知识库时 **不级联删文档** · 文档 collection_id 置 NULL，变成"散文档"
 *     这样误删一个库不会丢数据；用户可以重新归类。
 *   - 创建/更新都走管理员或 owner · 权限拦在 controller 层
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeCollectionService {

    private final KnowledgeCollectionMapper collectionMapper;
    private final MedKnowledgeBaseMapper kbMapper;
    private final CoachRuleService coachRuleService;
    private final KbAclService kbAclService;

    // ─── CRUD ────────────────────────────────────────────

    public KnowledgeCollection create(KnowledgeCollection req, Long ownerUserId) {
        if (req.getName() == null || req.getName().isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "知识库名称必填");
        }
        // 重名检查 · 同一用户不能建同名
        Long dup = collectionMapper.selectCount(new LambdaQueryWrapper<KnowledgeCollection>()
                .eq(KnowledgeCollection::getOwnerUserId, ownerUserId)
                .eq(KnowledgeCollection::getName, req.getName().trim()));
        if (dup != null && dup > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "你已有同名知识库");
        }
        KnowledgeCollection c = new KnowledgeCollection();
        c.setName(req.getName().trim());
        c.setDescription(req.getDescription());
        c.setIcon(req.getIcon() == null || req.getIcon().isBlank() ? "FolderOpened" : req.getIcon());
        c.setColor(req.getColor() == null || req.getColor().isBlank() ? "#7C3AED" : req.getColor());
        c.setCategoryCode(req.getCategoryCode());
        c.setVisibility(req.getVisibility() == null ? "public" : req.getVisibility());
        // 人格绑定：>0 才设，0/null 表示用全局默认
        c.setPersonaId(req.getPersonaId() != null && req.getPersonaId() > 0 ? req.getPersonaId() : null);
        c.setOwnerUserId(ownerUserId);
        c.setDocCount(0);
        c.setTotalChunks(0);
        c.setIsSystem(0);
        collectionMapper.insert(c);
        log.info("[Collection] 新建 · id={} name={} owner={}", c.getId(), c.getName(), ownerUserId);
        return c;
    }

    public KnowledgeCollection update(Long id, KnowledgeCollection patch, Long currentUserId, boolean isAdmin) {
        KnowledgeCollection c = mustGet(id);
        if (!isAdmin && (c.getOwnerUserId() == null || !c.getOwnerUserId().equals(currentUserId))) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权修改他人的知识库");
        }
        if (patch.getName() != null && !patch.getName().isBlank())            c.setName(patch.getName().trim());
        if (patch.getDescription() != null)                                    c.setDescription(patch.getDescription());
        if (patch.getIcon() != null && !patch.getIcon().isBlank())             c.setIcon(patch.getIcon());
        if (patch.getColor() != null && !patch.getColor().isBlank())           c.setColor(patch.getColor());
        if (patch.getCategoryCode() != null)                                   c.setCategoryCode(patch.getCategoryCode());
        if (patch.getVisibility() != null && !patch.getVisibility().isBlank()) c.setVisibility(patch.getVisibility());
        // 人格绑定：personaId=0（或负数）视为"清除绑定，回到全局默认"
        if (patch.getPersonaId() != null) {
            c.setPersonaId(patch.getPersonaId() <= 0 ? null : patch.getPersonaId());
        }
        // 技能包绑定：skillPackId=0（或负数）视为"清除绑定，不套用技能"
        if (patch.getSkillPackId() != null) {
            c.setSkillPackId(patch.getSkillPackId() <= 0 ? null : patch.getSkillPackId());
        }
        // 教练出题规则：空串视为清除
        // 教练规则绑定：<=0 视为清除
        if (patch.getCoachRuleId() != null) {
            c.setCoachRuleId(patch.getCoachRuleId() <= 0 ? null : patch.getCoachRuleId());
        }
        if (patch.getCoachRule() != null) {
            c.setCoachRule(patch.getCoachRule().isBlank() ? null : patch.getCoachRule());
        }
        // 教练出题题型配比：空串视为清除（回到默认均衡）
        if (patch.getQuizConfig() != null) {
            c.setQuizConfig(patch.getQuizConfig().isBlank() ? null : patch.getQuizConfig());
        }
        // 音色配置：空串/空白视为清除（回到不限制，全部音色可选）
        if (patch.getVoiceIds() != null) {
            c.setVoiceIds(patch.getVoiceIds().isBlank() ? null : patch.getVoiceIds().trim());
        }
        collectionMapper.updateById(c);
        return c;
    }

    /**
     * 解析对话应使用的人格 id。
     * 规则：仅当<b>恰好选中 1 个</b>知识库且其绑定了人格时，返回该人格 id；
     * 多选 / 未选 / 未绑定 → 返回 null（由上层回退到全局默认人格）。
     */
    public Long resolvePersonaId(List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.size() != 1) return null;
        KnowledgeCollection c = collectionMapper.selectById(collectionIds.get(0));
        return c == null ? null : c.getPersonaId();
    }

    /**
     * 解析对话应使用的技能包 id。
     * 规则：仅当<b>恰好选中 1 个</b>知识库且其绑定了技能时，返回该技能包 id；
     * 多选 / 未选 / 未绑定 → 返回 null（不套用技能）。
     */
    public Long resolveSkillPackId(List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.size() != 1) return null;
        KnowledgeCollection c = collectionMapper.selectById(collectionIds.get(0));
        return c == null ? null : c.getSkillPackId();
    }

    /**
     * 解析教练模式应使用的出题规则。
     * 规则：仅当<b>恰好选中 1 个</b>知识库且其配置了出题规则时返回；多选 / 未选 / 未配置 → 返回 null。
     */
    public String resolveCoachRule(List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.size() != 1) return null;
        KnowledgeCollection c = collectionMapper.selectById(collectionIds.get(0));
        if (c == null) return null;
        // 优先用绑定的教练规则；未绑定则回退到旧版内联 coach_rule（向后兼容）
        if (c.getCoachRuleId() != null) {
            var rule = coachRuleService.getById(c.getCoachRuleId());
            if (rule != null) return rule.getCoachRule();
        }
        return c.getCoachRule();
    }

    /**
     * 解析教练模式应使用的出题题型配比配置（JSON）。
     * 规则：仅当<b>恰好选中 1 个</b>知识库且其配置了配比时返回；多选 / 未选 / 未配置 → 返回 null（用默认均衡配比）。
     */
    public String resolveQuizConfig(List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.size() != 1) return null;
        KnowledgeCollection c = collectionMapper.selectById(collectionIds.get(0));
        if (c == null) return null;
        if (c.getCoachRuleId() != null) {
            var rule = coachRuleService.getById(c.getCoachRuleId());
            if (rule != null) return rule.getQuizConfig();
        }
        return c.getQuizConfig();
    }

    /**
     * 解析本知识库配置的可用音色 id 列表（voice_persona.id）。
     * 规则：仅当<b>恰好选中 1 个</b>知识库且其配置了音色时返回；多选 / 未选 / 未配置 → 返回 null（不限制，全部音色可选）。
     */
    public java.util.List<Long> resolveVoiceIds(List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.size() != 1) return null;
        KnowledgeCollection c = collectionMapper.selectById(collectionIds.get(0));
        if (c == null || c.getVoiceIds() == null || c.getVoiceIds().isBlank()) return null;
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (String s : c.getVoiceIds().split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try { ids.add(Long.parseLong(t)); } catch (NumberFormatException ignored) {}
        }
        return ids.isEmpty() ? null : ids;
    }

    @Transactional
    public void delete(Long id, Long currentUserId, boolean isAdmin) {
        KnowledgeCollection c = mustGet(id);
        if (c.getIsSystem() != null && c.getIsSystem() == 1) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "系统内置知识库不可删除");
        }
        if (!isAdmin && (c.getOwnerUserId() == null || !c.getOwnerUserId().equals(currentUserId))) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权删除他人的知识库");
        }
        // 软删 · 不级联文档（文档变散文档）
        collectionMapper.deleteById(id);
        // 把库内文档 collection_id 置 NULL
        kbMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                .eq(MedKnowledgeBase::getCollectionId, id)
                .set(MedKnowledgeBase::getCollectionId, null)
                .set(MedKnowledgeBase::getVisibility, "private"));
        log.info("[Collection] 删除 · id={} 库内文档已变为散文档", id);
    }

    public KnowledgeCollection mustGet(Long id) {
        KnowledgeCollection c = collectionMapper.selectById(id);
        if (c == null) throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "知识库不存在");
        return c;
    }

    // ─── 查询 ─────────────────────────────────────────────

    /**
     * 当前用户可访问的全部知识库列表
     *   - admin/auditor → 全部
     *   - 其他 → 由 KbAclService 统一计算公开、本人创建和部门/职位/用户授权
     */
    public List<KnowledgeCollection> listAccessible(Long userId, boolean isAdmin) {
        if (isAdmin) {
            return collectionMapper.selectList(new LambdaQueryWrapper<KnowledgeCollection>()
                    .orderByDesc(KnowledgeCollection::getUpdateTime));
        }

        List<Long> accessibleIds = kbAclService.listAccessibleCollectionIds(userId);
        if (accessibleIds.isEmpty()) {
            return List.of();
        }
        return collectionMapper.selectList(new LambdaQueryWrapper<KnowledgeCollection>()
                .in(KnowledgeCollection::getId, accessibleIds)
                .orderByDesc(KnowledgeCollection::getUpdateTime));
    }

    /** 单个知识库详情 */
    public KnowledgeCollection getOne(Long id) {
        return collectionMapper.selectById(id);
    }

    // ─── 文档归属操作 ──────────────────────────────────────

    /**
     * 把文档移入知识库 · 同时刷新缓存
     */
    @Transactional
    public void assignDocsToCollection(Long collectionId, List<Long> docIds, Long currentUserId, boolean isAdmin) {
        if (docIds == null || docIds.isEmpty()) return;
        KnowledgeCollection c = mustGet(collectionId);
        if (!isAdmin && (c.getOwnerUserId() == null || !c.getOwnerUserId().equals(currentUserId))) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权操作他人的知识库");
        }
        if (!isAdmin) {
            for (Long docId : docIds) {
                if (!kbAclService.canAccess(currentUserId, docId, KbAclService.PERM_ADMIN)) {
                    throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权移动文档: " + docId);
                }
            }
        }
        kbMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                .in(MedKnowledgeBase::getId, docIds)
                .set(MedKnowledgeBase::getCollectionId, collectionId));
        refreshCounters(collectionId);
        log.info("[Collection] 文档归集 · collection={} count={}", collectionId, docIds.size());
    }

    /**
     * 从知识库移出文档（变散文档，不删数据）
     */
    @Transactional
    public void removeDocsFromCollection(Long collectionId, List<Long> docIds, Long currentUserId, boolean isAdmin) {
        if (docIds == null || docIds.isEmpty()) return;
        KnowledgeCollection c = mustGet(collectionId);
        if (!isAdmin && (c.getOwnerUserId() == null || !c.getOwnerUserId().equals(currentUserId))) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权操作他人的知识库");
        }
        kbMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<MedKnowledgeBase>()
                .in(MedKnowledgeBase::getId, docIds)
                .eq(MedKnowledgeBase::getCollectionId, collectionId)
                .set(MedKnowledgeBase::getCollectionId, null)
                .set(MedKnowledgeBase::getVisibility, "private"));
        refreshCounters(collectionId);
    }

    /**
     * 把 collectionIds → 展开成它们包含的文档 id 列表（chat 检索用）
     */
    public List<Long> expandCollectionsToDocIds(List<Long> collectionIds) {
        if (collectionIds == null || collectionIds.isEmpty()) return List.of();
        List<MedKnowledgeBase> docs = kbMapper.selectList(new LambdaQueryWrapper<MedKnowledgeBase>()
                .in(MedKnowledgeBase::getCollectionId, collectionIds)
                .select(MedKnowledgeBase::getId));
        List<Long> ids = new ArrayList<>(docs.size());
        for (MedKnowledgeBase d : docs) ids.add(d.getId());
        return ids;
    }

    /**
     * 刷新缓存：doc_count + total_chunks
     */
    public void refreshCounters(Long collectionId) {
        if (collectionId == null) return;
        try {
            List<MedKnowledgeBase> docs = kbMapper.selectList(new LambdaQueryWrapper<MedKnowledgeBase>()
                    .eq(MedKnowledgeBase::getCollectionId, collectionId));
            int docCount = docs.size();
            int totalChunks = docs.stream()
                    .mapToInt(d -> d.getChunkCount() == null ? 0 : d.getChunkCount())
                    .sum();
            KnowledgeCollection c = collectionMapper.selectById(collectionId);
            if (c != null) {
                c.setDocCount(docCount);
                c.setTotalChunks(totalChunks);
                collectionMapper.updateById(c);
            }
        } catch (Exception e) {
            log.warn("[Collection] 刷新缓存失败 id={} err={}", collectionId, e.getMessage());
        }
    }
}
