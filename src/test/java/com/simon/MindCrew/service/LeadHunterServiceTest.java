package com.simon.MindCrew.service;

import com.simon.MindCrew.entity.LeadHuntCompany;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeadHunterServiceTest {

    @Test
    void normalizesUnsupportedSerperFreeAccountQueryPatterns() {
        String raw = "\"End User\" commercial audio (California OR Washington) "
                + "site:linkedin.com United States Western region";

        String normalized = LeadHunterService.normalizeSearchQuery(raw);

        assertFalse(normalized.contains("\""));
        assertFalse(normalized.contains("("));
        assertFalse(normalized.contains(")"));
        assertFalse(normalized.toLowerCase().contains("site:"));
        assertFalse(normalized.matches(".*\\bOR\\b.*"));
        assertTrue(normalized.contains("End User commercial audio"));
    }

    @Test
    void capsNormalizedQueryAtSerperSafeLength() {
        String normalized = LeadHunterService.normalizeSearchQuery("commercial audio ".repeat(30));

        assertTrue(normalized.length() <= 180);
        assertFalse(normalized.endsWith(" "));
    }

    @Test
    void enforcesSelectedUnitedStatesRegionWhenStateIsKnown() {
        assertTrue(LeadHunterService.addressesMatchCountry(
                "300 Market Street", "San Francisco", "CA", "94105", "United States - West", "example.com"));
        assertFalse(LeadHunterService.addressesMatchCountry(
                "300 East Godfrey Ave", "Philadelphia", "PA", "19120", "United States - West", "mmsproav.com"));
        assertTrue(LeadHunterService.addressesMatchCountry(
                null, null, null, null, "United States - West", "unknown.com"));
    }

    @Test
    void derivesStructuredFieldsOnlyFromExplicitCompanyEvidence() {
        String evidence = "Mondo Media Solutions is a leading AV integration company offering custom audio, video and security integration. "
                + "Company size: 11-50 employees.";

        assertEquals("Professional Audio Visual", LeadHunterService.classifyIndustry(evidence));
        assertEquals("System Integrator", LeadHunterService.classifyCustomerType(evidence));
        assertEquals("11-50", LeadHunterService.extractCompanySize(evidence));
        assertEquals("443", LeadHunterService.extractCompanySize("Teachers 304.69 Staff 443.72 www.ovsd.org"));
        assertEquals("System Integrator", LeadHunterService.classifyCustomerType(
                "A life safety systems market leader providing the systems we install"));
        assertNull(LeadHunterService.extractCompanySize("A growing global company with a large team"));
    }

    @Test
    void keepsUnverifiedOrOutOfRegionProspectsAndRejectsOnlyClearlyIrrelevantPages() {
        LeadHuntCompany nationalIntegrator = new LeadHuntCompany();
        nationalIntegrator.setCountry("United States - West");
        nationalIntegrator.setState("PA");
        nationalIntegrator.setCustomerType("System Integrator");
        nationalIntegrator.setIndustry("Professional Audio Visual");
        assertNull(LeadHunterService.companyRejectReason(nationalIntegrator, List.of("System Integrator")));

        LeadHuntCompany unknown = new LeadHuntCompany();
        unknown.setCountry("United States - West");
        unknown.setCustomerType("Unknown");
        assertNull(LeadHunterService.companyRejectReason(unknown, List.of("System Integrator")));

        LeadHuntCompany media = new LeadHuntCompany();
        media.setCustomerType("Other");
        media.setIndustry("Media & Publishing");
        assertEquals("clearly-irrelevant-media",
                LeadHunterService.companyRejectReason(media, List.of("System Integrator")));
    }

    @Test
    void ranksExactTerritoryAndCustomerTypeEvidenceAboveMismatches() {
        LeadHunterService.StartRequest request = new LeadHunterService.StartRequest();
        request.setCountries(List.of("United States - West"));
        request.setCustomerTypes(List.of("System Integrator"));

        LeadHuntCompany westernIntegrator = new LeadHuntCompany();
        westernIntegrator.setCountry("United States - West");
        westernIntegrator.setState("CA");
        westernIntegrator.setCustomerType("System Integrator");
        LeadHuntCompany easternManufacturer = new LeadHuntCompany();
        easternManufacturer.setCountry("United States - West");
        easternManufacturer.setState("PA");
        easternManufacturer.setCustomerType("Other");

        assertTrue(LeadHunterService.targetFitAdjustment(westernIntegrator, request)
                > LeadHunterService.targetFitAdjustment(easternManufacturer, request));
    }
}
