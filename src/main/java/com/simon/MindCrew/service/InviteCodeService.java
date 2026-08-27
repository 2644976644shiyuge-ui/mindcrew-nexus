package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.entity.InviteCode;
import com.simon.MindCrew.mapper.InviteCodeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 邀请码：管理员生成 / 列表 / 启停 / 删除，注册时校验并消费。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InviteCodeService {

    private final InviteCodeMapper inviteCodeMapper;

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // 去掉易混字符

    /** 批量生成邀请码 */
    @Transactional
    public List<InviteCode> generate(int count, Integer maxUses, LocalDateTime expireTime, String remark, Long createBy) {
        if (count <= 0) count = 1;
        if (count > 100) count = 100;
        List<InviteCode> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            InviteCode ic = new InviteCode();
            ic.setCode(randomCode());
            ic.setMaxUses(maxUses);
            ic.setUsedCount(0);
            ic.setEnabled(1);
            ic.setExpireTime(expireTime);
            ic.setRemark(remark);
            ic.setCreateBy(createBy);
            inviteCodeMapper.insert(ic);
            result.add(ic);
        }
        return result;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder("INV-");
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public List<InviteCode> list() {
        return inviteCodeMapper.selectList(
                new LambdaQueryWrapper<InviteCode>()
                        .eq(InviteCode::getDeleted, 0)
                        .orderByDesc(InviteCode::getCreateTime));
    }

    public void setEnabled(Long id, boolean enabled) {
        InviteCode ic = inviteCodeMapper.selectById(id);
        if (ic == null) throw new BusinessException("邀请码不存在");
        ic.setEnabled(enabled ? 1 : 0);
        inviteCodeMapper.updateById(ic);
    }

    public void delete(Long id) {
        inviteCodeMapper.deleteById(id);
    }

    /**
     * 校验邀请码并消费一次（注册时调用）。无效则抛 BusinessException。
     */
    @Transactional
    public void validateAndConsume(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException("请填写邀请码");
        }
        InviteCode ic = inviteCodeMapper.selectOne(
                new LambdaQueryWrapper<InviteCode>()
                        .eq(InviteCode::getCode, code.trim())
                        .eq(InviteCode::getDeleted, 0));
        if (ic == null) {
            throw new BusinessException("邀请码无效");
        }
        if (ic.getEnabled() == null || ic.getEnabled() == 0) {
            throw new BusinessException("邀请码已停用");
        }
        if (ic.getExpireTime() != null && ic.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new BusinessException("邀请码已过期");
        }
        if (ic.getMaxUses() != null && ic.getUsedCount() != null && ic.getUsedCount() >= ic.getMaxUses()) {
            throw new BusinessException("邀请码使用次数已用尽");
        }
        ic.setUsedCount((ic.getUsedCount() == null ? 0 : ic.getUsedCount()) + 1);
        inviteCodeMapper.updateById(ic);
        log.info("邀请码消费: code={} usedCount={}", ic.getCode(), ic.getUsedCount());
    }
}
