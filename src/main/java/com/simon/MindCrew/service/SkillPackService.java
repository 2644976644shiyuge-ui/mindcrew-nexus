package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.SkillPack;
import com.simon.MindCrew.mapper.SkillPackMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 技能包服务 · 管理员 CRUD + 销售端可用列表
 */
@Service
@RequiredArgsConstructor
public class SkillPackService {

    private final SkillPackMapper mapper;

    /** 管理员：全部（含停用） */
    public List<SkillPack> listAll() {
        return mapper.selectList(new LambdaQueryWrapper<SkillPack>()
                .orderByAsc(SkillPack::getSortOrder).orderByDesc(SkillPack::getId));
    }

    /** 销售端：启用中的 */
    public List<SkillPack> listEnabled() {
        return mapper.selectList(new LambdaQueryWrapper<SkillPack>()
                .eq(SkillPack::getEnabled, 1)
                .orderByAsc(SkillPack::getSortOrder).orderByDesc(SkillPack::getId));
    }

    public SkillPack getById(Long id) {
        return id == null ? null : mapper.selectById(id);
    }

    public SkillPack create(SkillPack p) {
        if (p.getEnabled() == null) p.setEnabled(1);
        if (p.getSortOrder() == null) p.setSortOrder(0);
        mapper.insert(p);
        return p;
    }

    public void update(Long id, SkillPack p) {
        p.setId(id);
        mapper.updateById(p);
    }

    public void setEnabled(Long id, boolean enabled) {
        SkillPack p = new SkillPack();
        p.setId(id);
        p.setEnabled(enabled ? 1 : 0);
        mapper.updateById(p);
    }

    public void delete(Long id) {
        mapper.deleteById(id);
    }
}
