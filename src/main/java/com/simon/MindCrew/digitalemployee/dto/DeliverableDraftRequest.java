package com.simon.MindCrew.digitalemployee.dto;

import lombok.Data;

@Data
public class DeliverableDraftRequest {
    private Long conversationId;
    private Long messageId;
    private String format;
    private DeliverableDraftDTO draft;
}
