package com.simon.MindCrew.digitalemployee.export.provider;

public record PptProviderConfig(
        String apiUrl,
        String apiKey,
        int timeoutSeconds,
        int pollIntervalMillis,
        String themeId,
        String qwenMode,
        String qwenTemplateId,
        String plannerProvider,
        String plannerModel,
        String plannerBaseUrl,
        String plannerApiKey
) {
}
