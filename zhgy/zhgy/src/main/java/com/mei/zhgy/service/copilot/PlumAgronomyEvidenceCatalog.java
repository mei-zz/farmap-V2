package com.mei.zhgy.service.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mei.zhgy.service.copilot.PlumAgronomyRuleEngine.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Operator-curated records. User disclosures and model output cannot populate this source. */
@Service
public class PlumAgronomyEvidenceCatalog {
    private final Document document;

    @org.springframework.beans.factory.annotation.Autowired
    public PlumAgronomyEvidenceCatalog(ObjectMapper json, @Value("${farmap.agronomy.evidence-path:}") String evidencePath) {
        this(load(json, evidencePath));
    }

    PlumAgronomyEvidenceCatalog(Document document) {
        this.document = document == null ? new Document(List.of(), List.of()) : document;
    }

    record Document(List<Label> labels, List<Orchard> orchards) {
        Document {
            labels = labels == null ? List.of() : List.copyOf(labels);
            orchards = orchards == null ? List.of() : List.copyOf(orchards);
        }
    }

    record Label(String cropCode, String productId, String productName, String activeIngredient, Integer phiDays,
                 Boolean syntheticChemical, boolean prohibited, String registrationSource, String version,
                 LocalDate validFrom, LocalDate validThrough) {}

    record Orchard(Integer farmId, String fieldId, String cropCode, String cultivar, Double elevationM,
                   LocalDate expectedHarvestDate, CertificationStatus certification, String sourceReference, String version,
                   LocalDate validThrough, Set<String> allowedProducts, Set<String> allowedIngredients,
                   Set<String> prohibitedProducts, Set<String> prohibitedIngredients,
                   List<Observation> observations, List<Calibration> calibrations) {}

    record Observation(String stageCode, LocalDate observedOn, String sourceReference, String version) {}

    record Calibration(String cultivar, Double minElevationM, Double maxElevationM, Double minThermalSum,
                       Double maxThermalSum, String thermalMetric, String stageCode, LocalDate validFrom, LocalDate validThrough,
                       String sourceReference, String version) {}

    public record CorrectionInputs(List<PhenologyObservation> observations, List<PhenologyCalibrationWindow> windows) {}
    public record SprayInputs(LabelInput label, OrchardCertification certification, LocalDate expectedHarvestDate) {}

    public SprayInputs spray(Integer farmId, String fieldId, String cropCode, String productId, LocalDate date) {
        Orchard orchard = orchard(farmId, fieldId, cropCode, date);
        LabelInput label = null;
        if (productId != null && cropCode != null) {
            var matches = document.labels().stream().filter(row -> productId.equals(row.productId()) && cropCode.equals(row.cropCode())
                    && valid(date, row.validFrom(), row.validThrough()) && row.phiDays() != null && row.phiDays() >= 0
                    && publicReference(row.registrationSource()) && present(row.version())).limit(2).toList();
            if (matches.size() == 1) {
                var row = matches.get(0);
                label = new LabelInput(row.productId(), row.productName(), row.activeIngredient(), row.phiDays(), row.syntheticChemical(),
                        row.prohibited(), provenance(SourceKind.PUBLIC_LABEL, row.registrationSource(), row.version(), true));
            }
        }
        OrchardCertification certification = orchard == null || orchard.certification() == null ? null
                : new OrchardCertification(orchard.certification(),
                        provenance(SourceKind.ORCHARD_RECORD, orchard.sourceReference(), orchard.version(), false),
                        orchard.allowedProducts(), orchard.allowedIngredients(), orchard.prohibitedProducts(), orchard.prohibitedIngredients());
        return new SprayInputs(label, certification, orchard == null ? null : orchard.expectedHarvestDate());
    }

    public boolean hasField(Integer farmId, String fieldId, String cropCode, LocalDate date) {
        return orchard(farmId, fieldId, cropCode, date) != null;
    }

    public Double thermalTarget(Integer farmId, String fieldId, String cropCode, LocalDate date, String nextStage) {
        Orchard orchard = orchard(farmId, fieldId, cropCode, date);
        if (orchard == null || !present(nextStage) || !present(orchard.cultivar())) return null;
        var candidates = list(orchard.calibrations()).stream().filter(row -> nextStage.equals(row.stageCode())
                && orchard.cultivar().equalsIgnoreCase(row.cultivar()) && "FARM_DAILY_TEMP_SUM".equals(row.thermalMetric())
                && range(orchard.elevationM(), row.minElevationM(), row.maxElevationM())
                && valid(date, row.validFrom(), row.validThrough()) && present(row.sourceReference()) && present(row.version())
                && row.minThermalSum() != null && Double.isFinite(row.minThermalSum()) && row.minThermalSum() > 0).limit(2).toList();
        return candidates.size() == 1 ? candidates.get(0).minThermalSum() : null;
    }

    public CorrectionInputs correction(Integer farmId, String fieldId, String cropCode, LocalDate date,
                                       Double thermalSum, LocalDate thermalDate) {
        Orchard orchard = orchard(farmId, fieldId, cropCode, date);
        if (orchard == null) return new CorrectionInputs(List.of(), List.of());
        List<PhenologyObservation> observations = new ArrayList<>();
        for (var row : list(orchard.observations())) {
            if (row.observedOn() != null && !row.observedOn().isAfter(date) && present(row.sourceReference()) && present(row.version())) {
                observations.add(new PhenologyObservation(row.stageCode(), row.observedOn(),
                        provenance(SourceKind.EXPERT_OBSERVATION, row.sourceReference(), row.version(), false)));
            }
        }
        List<PhenologyCalibrationWindow> windows = new ArrayList<>();
        for (var row : list(orchard.calibrations())) {
            if (!present(orchard.cultivar()) || !orchard.cultivar().equalsIgnoreCase(row.cultivar())
                    || !"FARM_DAILY_TEMP_SUM".equals(row.thermalMetric()) || thermalDate == null
                    || thermalDate.isAfter(date) || thermalDate.isBefore(date.minusDays(2))
                    || !range(orchard.elevationM(), row.minElevationM(), row.maxElevationM())
                    || !range(thermalSum, row.minThermalSum(), row.maxThermalSum())
                    || !valid(date, row.validFrom(), row.validThrough()) || !present(row.sourceReference()) || !present(row.version())) continue;
            windows.add(new PhenologyCalibrationWindow(row.stageCode(), row.validFrom(), row.validThrough(),
                    provenance(SourceKind.LOCAL_CALIBRATION, row.sourceReference(), row.version(), false)));
        }
        return new CorrectionInputs(List.copyOf(observations), List.copyOf(windows));
    }

    private Orchard orchard(Integer farmId, String fieldId, String cropCode, LocalDate date) {
        if (farmId == null || fieldId == null || cropCode == null) return null;
        var rows = document.orchards().stream().filter(row -> Objects.equals(farmId, row.farmId())
                && fieldId.equals(row.fieldId()) && cropCode.equals(row.cropCode())
                && row.validThrough() != null && !date.isAfter(row.validThrough())
                && present(row.sourceReference()) && present(row.version())).limit(2).toList();
        return rows.size() == 1 ? rows.get(0) : null;
    }

    private static boolean valid(LocalDate date, LocalDate from, LocalDate through) {
        return date != null && from != null && through != null && !date.isBefore(from) && !date.isAfter(through);
    }

    private static boolean range(Double value, Double from, Double through) {
        return value != null && from != null && through != null && Double.isFinite(value)
                && Double.isFinite(from) && Double.isFinite(through) && value >= from && value <= through;
    }

    private static boolean publicReference(String value) {
        if (!present(value)) return false;
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }

    private static Provenance provenance(SourceKind kind, String reference, String version, boolean publicSource) {
        return new Provenance(kind, reference, version, Instant.now(), publicSource, true);
    }

    private static Document load(ObjectMapper json, String configured) {
        if (!present(configured)) return null;
        try {
            Path path = Path.of(configured);
            if (Files.isRegularFile(path) && Files.size(path) <= 1_048_576) return json.readValue(Files.readAllBytes(path), Document.class);
        } catch (java.io.IOException | RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(PlumAgronomyEvidenceCatalog.class)
                    .warn("Curated agronomy evidence unavailable: {}", failure.getClass().getSimpleName());
        }
        return null;
    }
}
