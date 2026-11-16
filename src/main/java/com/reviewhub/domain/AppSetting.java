package com.reviewhub.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/** 键值配置（LLM 连接、评审参数等，可在 Web 设置页修改） */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    @Id
    @Column(name = "skey", length = 100)
    private String key;

    @Lob
    @Column(name = "svalue", columnDefinition = "CLOB")
    private String value;

    public AppSetting() {
    }

    public AppSetting(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
