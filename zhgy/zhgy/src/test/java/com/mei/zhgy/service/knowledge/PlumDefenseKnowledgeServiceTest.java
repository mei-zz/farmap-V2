package com.mei.zhgy.service.knowledge;

import com.mei.zhgy.service.agriculture.CropResolver;
import com.mei.zhgy.service.copilot.PlumAgronomyEvidenceCatalog;
import com.mei.zhgy.service.copilot.PlumAgronomyEvidenceCatalog.SprayInputs;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.CertificationStatus;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.LabelInput;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.OrchardCertification;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.Provenance;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.SourceKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlumDefenseKnowledgeServiceTest {
    private static final LocalDate DAY = LocalDate.of(2026, 11, 27);
    private static final Instant RETRIEVED_AT = Instant.parse("2026-11-27T00:00:00Z");

    private PlumAgronomyEvidenceCatalog catalog;
    private PlumDefenseKnowledgeService service;

    @BeforeEach
    void setUp() {
        catalog = mock(PlumAgronomyEvidenceCatalog.class);
        service = new PlumDefenseKnowledgeService(catalog);
    }

    @Test
    void curatedRowsCoverEveryNamedPlumTargetWithoutInventingProductFacts() {
        List<PlumDefenseKnowledgeService.TargetSummary> targets = service.targets();
        assertEquals(Set.of("plum_brown_rot", "plum_bacterial_shot_hole", "plum_gummosis",
                        "plum_bag_fruit", "plum_fruit_moth", "plum_aphid", "plum_spider_mite"),
                targets.stream().map(PlumDefenseKnowledgeService.TargetSummary::targetCode)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(targets.stream().allMatch(target ->
                target.chemicalForbiddenStages().containsAll(Set.of("flowering", "harvest"))));

        var assessment = service.assess(new PlumDefenseKnowledgeService.AssessmentRequest(
                "褐腐病", "果实发育期", DAY, 7, "east", null));
        assertEquals(PlumDefenseKnowledgeService.AssessmentStatus.READY, assessment.status());
        assertEquals("fruit_development", assessment.stageCode());
        assertEquals(2, assessment.rows().size());
        assertTrue(assessment.rows().stream().allMatch(row -> row.kind() != PlumDefenseKnowledgeService.MeasureKind.CHEMICAL_LABEL));
        assertTrue(assessment.rows().stream().allMatch(row -> row.phiDays() == null));
        assertTrue(assessment.rows().stream().allMatch(row -> row.organicAllowed() == null));
        assertTrue(assessment.rows().stream().allMatch(row ->
                row.organicPermission() == PlumDefenseKnowledgeService.OrganicPermission.GENERAL_NONCHEMICAL_PRACTICE));
    }

    @Test
    void agriculturalStructuredKnowledgeFacadeDelegatesTheTypedAssessment() {
        var facade = new AgriculturalStructuredKnowledgeService(null, new CropResolver(), service);
        assertEquals(7, facade.plumDefenseTargets().size());

        var assessment = facade.assessPlumDefense(new PlumDefenseKnowledgeService.AssessmentRequest(
                "红蜘蛛", "leafing", DAY, 7, "east", null));
        assertEquals(PlumDefenseKnowledgeService.AssessmentStatus.READY, assessment.status());
        assertEquals("plum_spider_mite", assessment.targetCode());
        assertEquals(PlumDefenseKnowledgeService.GateStatus.ELIGIBLE, assessment.rows().get(0).gateStatus());
    }

    @Test
    void springWiringKeepsTheCatalogAndGuardrailIndependent() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(PlumAgronomyEvidenceCatalog.class, () -> catalog);
            context.register(PlumDefenseKnowledgeService.class, AgriculturalStructuredKnowledgeService.class,
                    CropResolver.class);
            context.refresh();

            assertNotNull(context.getBean(PlumDefenseKnowledgeService.class));
            assertNotNull(context.getBean(AgriculturalStructuredKnowledgeService.class));
        }
    }

    @Test
    void verifiedLabelInsidePhiIsBlockedDeterministically() {
        when(catalog.spray(7, "east", "plum", "fixture-product", DAY))
                .thenReturn(new SprayInputs(label(14, false), certification(CertificationStatus.NONE, Set.of()), DAY.plusDays(10)));

        var assessment = service.assess(new PlumDefenseKnowledgeService.AssessmentRequest(
                "褐腐病", "fruit_development", DAY, 7, "east", "fixture-product"));
        var chemical = chemicalRow(assessment);

        assertTrue(assessment.productVerified());
        assertEquals(10, assessment.daysUntilHarvest());
        assertEquals(PlumDefenseKnowledgeService.GateStatus.BLOCKED_BY_PHI, chemical.gateStatus());
        assertEquals(14, chemical.phiDays());
        assertEquals("fixture-product", chemical.productId());
        assertEquals(PlumDefenseKnowledgeService.EvidenceScope.VERIFIED_PRODUCT_LABEL, chemical.evidenceScope());
        assertFalse(chemical.labelTargetUseVerified());
    }

    @Test
    void unregisteredProductDoesNotCreateAChemicalRow() {
        when(catalog.spray(7, "east", "plum", "unregistered-product", DAY))
                .thenReturn(new SprayInputs(null, null, null));

        var assessment = service.assess(new PlumDefenseKnowledgeService.AssessmentRequest(
                "褐腐病", "fruit_development", DAY, 7, "east", "unregistered-product"));

        assertFalse(assessment.productVerified());
        assertTrue(assessment.rows().stream().noneMatch(row ->
                row.kind() == PlumDefenseKnowledgeService.MeasureKind.CHEMICAL_LABEL));
        assertTrue(assessment.unavailableData().contains("verifiedLabel"));
    }

    @Test
    void floweringChemicalWindowIsBlockedBeforePhiConfirmation() {
        when(catalog.spray(7, "east", "plum", "fixture-product", DAY))
                .thenReturn(new SprayInputs(label(14, false), certification(CertificationStatus.NONE, Set.of()), DAY.plusDays(40)));

        var assessment = service.assess(new PlumDefenseKnowledgeService.AssessmentRequest(
                "褐腐病", "flowering", DAY, 7, "east", "fixture-product"));

        assertEquals(PlumDefenseKnowledgeService.GateStatus.BLOCKED_BY_STAGE, chemicalRow(assessment).gateStatus());
    }

    @Test
    void organicAllowListMustExplicitlyContainTheVerifiedProduct() {
        when(catalog.spray(7, "east", "plum", "fixture-product", DAY))
                .thenReturn(new SprayInputs(label(14, false),
                        certification(CertificationStatus.ORGANIC, Set.of()), DAY.plusDays(40)));

        var blocked = chemicalRow(service.assess(new PlumDefenseKnowledgeService.AssessmentRequest(
                "褐腐病", "fruit_development", DAY, 7, "east", "fixture-product")));
        assertEquals(PlumDefenseKnowledgeService.GateStatus.BLOCKED_BY_ORGANIC, blocked.gateStatus());
        assertEquals(Boolean.FALSE, blocked.organicAllowed());
        assertEquals(PlumDefenseKnowledgeService.OrganicPermission.NOT_APPROVED_BY_VERIFIED_CERTIFICATION,
                blocked.organicPermission());

        when(catalog.spray(7, "east", "plum", "fixture-product", DAY))
                .thenReturn(new SprayInputs(label(14, false),
                        certification(CertificationStatus.ORGANIC, Set.of("fixture-product")), DAY.plusDays(40)));

        var passed = chemicalRow(service.assess(new PlumDefenseKnowledgeService.AssessmentRequest(
                "褐腐病", "fruit_development", DAY, 7, "east", "fixture-product")));
        assertEquals(PlumDefenseKnowledgeService.GateStatus.LABEL_TARGET_CONFIRMATION_REQUIRED, passed.gateStatus());
        assertEquals(Boolean.TRUE, passed.organicAllowed());
        assertEquals(PlumDefenseKnowledgeService.OrganicPermission.APPROVED_BY_VERIFIED_CERTIFICATION,
                passed.organicPermission());
    }

    private PlumDefenseKnowledgeService.DefenseRow chemicalRow(PlumDefenseKnowledgeService.Assessment assessment) {
        return assessment.rows().stream().filter(row ->
                        row.kind() == PlumDefenseKnowledgeService.MeasureKind.CHEMICAL_LABEL)
                .findFirst().orElseThrow();
    }

    private static LabelInput label(int phiDays, boolean prohibited) {
        return new LabelInput("fixture-product", "测试药剂", "测试成分", phiDays, true, prohibited,
                new Provenance(SourceKind.PUBLIC_LABEL,
                        "https://example.test/registration/fixture", "v1", RETRIEVED_AT, true, true));
    }

    private static OrchardCertification certification(CertificationStatus status, Set<String> allowedProducts) {
        return new OrchardCertification(status,
                new Provenance(SourceKind.ORCHARD_RECORD, "certificate-fixture", "v1", RETRIEVED_AT, false, true),
                allowedProducts, Set.of(), Set.of(), Set.of());
    }
}
