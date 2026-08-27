package com.simon.MindCrew.digitalemployee.export.provider;

import com.simon.MindCrew.digitalemployee.export.ExportBranding;
import com.simon.MindCrew.digitalemployee.export.PptGenerationService;

public record PptProviderRequest(
        String title,
        String markdown,
        String userInstruction,
        ExportBranding branding,
        PptGenerationService.PptGenerationOptions options
) {
}
