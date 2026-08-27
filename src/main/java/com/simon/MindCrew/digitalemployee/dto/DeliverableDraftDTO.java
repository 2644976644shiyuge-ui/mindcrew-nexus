package com.simon.MindCrew.digitalemployee.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DeliverableDraftDTO {
    private String draftType;
    private String title;
    private String scenario;
    private Integer qualityScore = 0;
    private String readiness = "NEEDS_REVIEW";
    private PresentationProfile presentation = new PresentationProfile();
    private List<String> warnings = new ArrayList<>();
    private List<QualityCheck> qualityChecks = new ArrayList<>();
    private List<Slide> slides = new ArrayList<>();
    private List<ContractSection> sections = new ArrayList<>();
    private List<RiskItem> risks = new ArrayList<>();

    @Data
    public static class Slide {
        private String title;
        private List<String> bullets = new ArrayList<>();
        private String speakerNotes;
        private String layout = "content";
    }

    @Data
    public static class PresentationProfile {
        private String generationMode = "auto";
        private String visualStyle = "business";
        private String audience;
        private String purpose;
        private Boolean editable = true;
        private Boolean includeSpeakerNotes = true;
        private Boolean preferVisuals = true;
    }

    @Data
    public static class ContractSection {
        private String title;
        private List<String> clauses = new ArrayList<>();
    }

    @Data
    public static class RiskItem {
        private String level;
        private String position;
        private String description;
        private String suggestion;
    }

    @Data
    public static class QualityCheck {
        private String label;
        private String status;
        private String message;
    }
}
