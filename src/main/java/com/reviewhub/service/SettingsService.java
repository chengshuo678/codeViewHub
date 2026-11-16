package com.reviewhub.service;

import com.reviewhub.domain.AppSetting;
import com.reviewhub.repo.AppSettingRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/** 运行时配置（存于数据库，可在 Web 设置页修改，即时生效） */
@Service
public class SettingsService {

    private final AppSettingRepository repo;

    public SettingsService(AppSettingRepository repo) {
        this.repo = repo;
    }

    public String get(String key) {
        return repo.findById(key).map(AppSetting::getValue).orElse("");
    }

    public String get(String key, String dft) {
        String v = get(key);
        return v == null || v.isBlank() ? dft : v;
    }

    public int getInt(String key, int dft) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (Exception e) {
            return dft;
        }
    }

    public boolean getBool(String key, boolean dft) {
        String v = get(key).trim().toLowerCase();
        if (v.isEmpty()) return dft;
        return v.equals("true") || v.equals("1") || v.equals("yes");
    }

    public Map<String, String> all() {
        Map<String, String> m = new HashMap<>();
        repo.findAll().forEach(s -> m.put(s.getKey(), s.getValue() == null ? "" : s.getValue()));
        return m;
    }

    public void put(String key, String value) {
        repo.save(new AppSetting(key, value == null ? "" : value));
    }
}
