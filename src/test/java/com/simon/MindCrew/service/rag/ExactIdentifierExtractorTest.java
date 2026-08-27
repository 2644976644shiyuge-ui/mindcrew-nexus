package com.simon.MindCrew.service.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExactIdentifierExtractorTest {

    @Test
    void extractsModelWhenChineseTouchesBothSides() {
        assertEquals(List.of("SC15"),
                ExactIdentifierExtractor.extract("分析sc15目前在美国市场的竞品"));
    }

    @Test
    void supportsShortAndHyphenatedModelFamilies() {
        assertEquals(List.of("IPS-M1-D2W", "IPG-X2", "X10", "M100"),
                ExactIdentifierExtractor.extract("比较IPS-M1-D2W、IPG-X2、X10和M100"));
    }

    @Test
    void modelBoundaryDoesNotConfuseLongerSku() {
        assertFalse(ExactIdentifierExtractor.containsReference("SC150 datasheet", "SC15"));
        assertFalse(ExactIdentifierExtractor.containsReference("SC15-DANTE datasheet", "SC15"));
        assertTrue(ExactIdentifierExtractor.containsReference("SC15-DANTE datasheet", "SC15-DANTE"));
    }

    @Test
    void treatsOmittedHyphenAsTheSameCompleteModelOnly() {
        assertTrue(ExactIdentifierExtractor.containsEquivalentReference(
                "IASL100 V1 DS_EN.pdf", "IAS-L100"));
        assertTrue(ExactIdentifierExtractor.containsEquivalentReference(
                "IAS-L100 User Guide", "IASL100"));
        assertFalse(ExactIdentifierExtractor.containsEquivalentReference(
                "IAS-L100-PRO User Guide", "IAS-L100"));
        assertFalse(ExactIdentifierExtractor.containsEquivalentReference(
                "SC150 datasheet", "SC15"));
    }
}
