package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.SysSetting;
import com.simon.MindCrew.mapper.SysSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 通用系统配置 KV 读写（注册二维码 URL、默认有效期天数等）。
 */
@Service
@RequiredArgsConstructor
public class SettingService {

    private final SysSettingMapper settingMapper;

    public String getString(String key, String defaultValue) {
        SysSetting s = settingMapper.selectOne(
                new LambdaQueryWrapper<SysSetting>().eq(SysSetting::getSettingKey, key));
        return (s == null || s.getSettingValue() == null) ? defaultValue : s.getSettingValue();
    }

    public int getInt(String key, int defaultValue) {
        String v = getString(key, null);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    public void set(String key, String value) {
        SysSetting existing = settingMapper.selectOne(
                new LambdaQueryWrapper<SysSetting>().eq(SysSetting::getSettingKey, key));
        if (existing == null) {
            SysSetting s = new SysSetting();
            s.setSettingKey(key);
            s.setSettingValue(value);
            settingMapper.insert(s);
        } else {
            existing.setSettingValue(value);
            settingMapper.updateById(existing);
        }
    }
}
