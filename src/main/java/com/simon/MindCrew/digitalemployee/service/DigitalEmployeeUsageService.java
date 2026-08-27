package com.simon.MindCrew.digitalemployee.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.digitalemployee.entity.DigitalEmployeeUsageDaily;
import com.simon.MindCrew.digitalemployee.mapper.DigitalEmployeeUsageDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 数字员工用量日聚合（幂等 upsert，按员工+日期）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DigitalEmployeeUsageService {

    private final DigitalEmployeeUsageDailyMapper usageMapper;

    @Transactional
    public void recordChatCompletion(Long employeeId, Long userId, int tokenEstimate) {
        if (employeeId == null) {
            return;
        }
        LocalDate today = LocalDate.now();
        DigitalEmployeeUsageDaily row = usageMapper.selectOne(new LambdaQueryWrapper<DigitalEmployeeUsageDaily>()
                .eq(DigitalEmployeeUsageDaily::getEmployeeId, employeeId)
                .eq(DigitalEmployeeUsageDaily::getStatDate, today)
                .last("LIMIT 1"));

        if (row == null) {
            row = new DigitalEmployeeUsageDaily();
            row.setEmployeeId(employeeId);
            row.setStatDate(today);
            row.setSessionCount(0);
            row.setMessageCount(0);
            row.setTokenEstimate(0L);
            row.setActiveUserCount(0);
            usageMapper.insert(row);
        }

        row.setMessageCount((row.getMessageCount() == null ? 0 : row.getMessageCount()) + 1);
        row.setTokenEstimate((row.getTokenEstimate() == null ? 0L : row.getTokenEstimate())
                + Math.max(0, tokenEstimate));
        usageMapper.updateById(row);
        // activeUserCount 需跨用户去重，首版用 message 近似；日批任务可修正
    }

    public long sumTokensLast30Days(Long employeeId) {
        if (employeeId == null) {
            return 0L;
        }
        LocalDate from = LocalDate.now().minusDays(30);
        List<DigitalEmployeeUsageDaily> rows = usageMapper.selectList(new LambdaQueryWrapper<DigitalEmployeeUsageDaily>()
                .eq(DigitalEmployeeUsageDaily::getEmployeeId, employeeId)
                .ge(DigitalEmployeeUsageDaily::getStatDate, from));
        return rows.stream()
                .mapToLong(r -> r.getTokenEstimate() == null ? 0L : r.getTokenEstimate())
                .sum();
    }

    public static String formatTokenDisplay(long tokens) {
        if (tokens < 1_000) {
            return String.valueOf(tokens);
        }
        if (tokens < 1_000_000) {
            return String.format("%.1fk", tokens / 1000.0);
        }
        return String.format("%.1fM", tokens / 1_000_000.0);
    }
}