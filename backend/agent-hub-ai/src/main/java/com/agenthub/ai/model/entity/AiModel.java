package com.agenthub.ai.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("ai_model")
public class AiModel {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long providerId;
    private String modelCode;
    private String displayName;
    private String capabilityType;
    private Boolean streamSupported;
    private Boolean reasoningSupported;
    private Integer contextWindow;
    private Integer maxOutputTokens;
    private Boolean defaultModel;
    private Boolean enabled;
    private Integer sort;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProviderId() {
        return providerId;
    }

    public void setProviderId(Long providerId) {
        this.providerId = providerId;
    }

    public String getModelCode() {
        return modelCode;
    }

    public void setModelCode(String modelCode) {
        this.modelCode = modelCode;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getCapabilityType() {
        return capabilityType;
    }

    public void setCapabilityType(String capabilityType) {
        this.capabilityType = capabilityType;
    }

    public Boolean getStreamSupported() {
        return streamSupported;
    }

    public void setStreamSupported(Boolean streamSupported) {
        this.streamSupported = streamSupported;
    }

    public Boolean getReasoningSupported() {
        return reasoningSupported;
    }

    public void setReasoningSupported(Boolean reasoningSupported) {
        this.reasoningSupported = reasoningSupported;
    }

    public Integer getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(Integer contextWindow) {
        this.contextWindow = contextWindow;
    }

    public Integer getMaxOutputTokens() {
        return maxOutputTokens;
    }

    public void setMaxOutputTokens(Integer maxOutputTokens) {
        this.maxOutputTokens = maxOutputTokens;
    }

    public Boolean getDefaultModel() {
        return defaultModel;
    }

    public void setDefaultModel(Boolean defaultModel) {
        this.defaultModel = defaultModel;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getSort() {
        return sort;
    }

    public void setSort(Integer sort) {
        this.sort = sort;
    }
}
