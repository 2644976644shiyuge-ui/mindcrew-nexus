package com.simon.MindCrew.digitalemployee.dto;

import lombok.Data;

@Data
public class DigitalEmployeeCardVO {
    private Long id;
    private String name;
    private String avatar;
    private String summary;
    private String status;
    private String primaryScenario;
    private String primaryScenarioLabel;
    /** 运行中展示用 */
    private String runtimeLabel;
    private Long sessionCount;
    private String tokenDisplay;
    private String activeDisplay;
}