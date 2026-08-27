package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.CoachRule;
import com.simon.MindCrew.mapper.CoachRuleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 教练规则服务 · 管理员 CRUD + 可用列表（知识库下拉选用）
 */
@Service
@RequiredArgsConstructor
public class CoachRuleService {

    private final CoachRuleMapper mapper;

    /** 管理员：全部（含停用） */
    public List<CoachRule> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<CoachRule>()
                .orderByAsc(CoachRule::getSortOrder).orderByDesc(CoachRule::getId));
    }

    /** 可用：启用中的（知识库下拉） */
    public List<CoachRule> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<CoachRule>()
                .eq(CoachRule::getEnabled, 1)
                .orderByAsc(CoachRule::getSortOrder).orderByDesc(CoachRule::getId));
    }

    public CoachRule getById(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    public CoachRule create(CoachRule p) {
        if (p.getEnabled() == null) p.setEnabled(1);
        if (p.getSortOrder() == null) p.setSortOrder(0);
        mapper.insert(p);
        return p;
    }

    public void update(Long id, CoachRule p) {
        p.setId(id);
        mapper.updateById(p);
    }

    public void setEnabled(Long id, boolean enabled) {
        CoachRule p = new CoachRule();
        p.setId(id);
        p.setEnabled(enabled ? 1 : 0);
        mapper.updateById(p);
    }

    public void delete(Long id) {
        mapper.deleteById(id);
    }
}
