package com.simon.MindCrew.digitalemployee.dto;

import lombok.Data;

import java.util.List;

@Data
public class DigitalEmployeeSaveRequest {
    private String name;
    private String avatar;
    private String summary;
    private String systemPrompt;
    private String modelProvider;
    private String modelName;
    private Boolean webSearch;
    private Boolean memoryEnabled;
    private String scenarioConfig;
    private String primaryScenario;
    private String visibility;
    private Boolean kbOnlyReply;
    private Integer sortOrder;
    private List<Long> collectionIds;
    private List<AclEntry> aclEntries;

    @Data
    public static class AclEntry {
        private String principalType;
        private Long principalId;
        private String permission;
    }
}