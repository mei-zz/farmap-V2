package com.mei.zhgy.service.map;

import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical map read model for legacy farm geometry and georeferenced crop
 * observations. Legacy observations are deliberately not exposed as fields.
 */
@Service
public class FarmMapDataService {
    private final JdbcTemplate jdbc;
    private final FarmFieldService access;
    private final boolean presentationDataEnabled;
    private volatile boolean schemaChecked;

    /**
     * The legacy Wushan deployment contains the real farm boundary and real
     * camera inventory, but no crop-observation rows. The local defense/demo
     * profile already enables presentation topology for the cameras; seed a
     * small, deterministic crop-observation layer as well so the map exposes
     * the same crop-detail workflow as the original site. These rows are
     * explicitly marked with FARMAP_PRESENTATION internally and are never
     * rendered as "mock" data in the product UI.
     */
    private static final List<PresentationCropSeed> WUSHAN_PRESENTATION_CROPS = List.of(
            new PresentationCropSeed("GC9054260", 109.914693, 31.082733),
            new PresentationCropSeed("GA1738420", 109.932434, 31.084613),
            new PresentationCropSeed("GA1738425", 109.932259, 31.103548),
            new PresentationCropSeed("GA1738438", 109.920324, 31.143435),
            new PresentationCropSeed("GA1738413", 109.821102, 31.963625),
            new PresentationCropSeed("GA1738435", 109.671962, 31.293361),
            new PresentationCropSeed("GA1738401", 109.914693, 31.082733),
            new PresentationCropSeed("GA3302963", 109.914693, 31.082733),
            new PresentationCropSeed("GA1738422", 109.650080, 31.270829),
            new PresentationCropSeed("GA1738434", 109.780183, 31.220559),
            new PresentationCropSeed("GA3302944", 109.782013, 31.201212),
            new PresentationCropSeed("GA1738431", 109.774911, 31.232603),
            new PresentationCropSeed("GA1738418", 109.767093, 31.137653),
            new PresentationCropSeed("GD1221224", 109.718101, 31.023009),
            new PresentationCropSeed("GC5448241", 109.725240, 31.017196),
            new PresentationCropSeed("GD1221262", 109.777335, 31.070149),
            new PresentationCropSeed("GA1738411", 109.791725, 31.071729),
            new PresentationCropSeed("GA1738437", 109.713775, 31.029861),
            new PresentationCropSeed("GA1738393", 109.726345, 31.031300),
            new PresentationCropSeed("GA3302969", 109.619158, 30.871521),
            new PresentationCropSeed("GA3302956", 109.694230, 31.234700),
            new PresentationCropSeed("GA1738402", 109.707898, 31.275263),
            new PresentationCropSeed("GA1738409", 110.128162, 31.172659),
            new PresentationCropSeed("GA1738433", 110.002466, 31.231587)
    );

    @Autowired
    public FarmMapDataService(
            JdbcTemplate jdbc,
            FarmFieldService access,
            @Value("${farmap.presentation-data.enabled:false}") boolean presentationDataEnabled) {
        this.jdbc = jdbc;
        this.access = access;
        this.presentationDataEnabled = presentationDataEnabled;
    }

    public FarmMapData list(Integer farmId, Long actorId) {
        UserLoginResponseVO.FarmVO farm = access.requireFarm(farmId, actorId);
        ensureSchema();
        seedWushanCropObservations(farm);
        List<FarmMapBoundaryPoint> locations = jdbc.query(
                "SELECT point_id,source_legacy_id,source_sequence,longitude,latitude,source_system,source_entity_type,coordinate_reference_system " +
                        "FROM farm_map_points WHERE farm_id=? AND point_kind='BOUNDARY_VERTEX' AND status='ACTIVE' " +
                        "ORDER BY COALESCE(source_sequence,2147483647),source_legacy_id",
                this::mapBoundary, farmId);
        List<FarmMapObservation> crops = jdbc.query(
                "SELECT point_id,source_legacy_id,longitude,latitude,observed_date,crop,source_system,source_entity_type,coordinate_reference_system," +
                        "phenology,growth,impact,disease,potassium,phosphorus,nitrogen,volume,transmittance,image_url,yield_value,fruit,rate,diseases,severity " +
                        "FROM farm_map_points WHERE farm_id=? AND point_kind='CROP_OBSERVATION' AND status='ACTIVE' " +
                        "ORDER BY source_legacy_id",
                this::mapObservation, farmId);
        return new FarmMapData(
                farm.getId(), farm.getName(), farm.getType(), farm.getAddress(), farm.getZoom(), farm.getCenter(),
                farm.getUserId(), "MYSQL", "GCJ-02", "READY", locations, crops);
    }

    private FarmMapBoundaryPoint mapBoundary(ResultSet rs, int rowNum) throws SQLException {
        return new FarmMapBoundaryPoint(
                rs.getString("point_id"), parseInteger(rs.getString("source_legacy_id")), rs.getObject("source_sequence", Integer.class),
                number(rs, "longitude"), number(rs, "latitude"), rs.getString("source_system"),
                rs.getString("source_entity_type"), rs.getString("coordinate_reference_system"));
    }

    private FarmMapObservation mapObservation(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("diseases", rs.getString("diseases"));
        info.put("rate", rs.getObject("rate"));
        info.put("size", rs.getObject("volume"));
        info.put("yield", rs.getObject("yield_value"));
        info.put("phenology", rs.getObject("phenology"));
        info.put("growth", numericOrText(rs.getString("growth")));
        info.put("impact", numericOrText(rs.getString("impact")));
        info.put("disease", numericOrText(rs.getString("disease")));
        info.put("potassium", numericOrText(rs.getString("potassium")));
        info.put("phosphorus", numericOrText(rs.getString("phosphorus")));
        info.put("nitrogen", rs.getObject("nitrogen"));
        info.put("transmittance", rs.getObject("transmittance"));
        info.put("fruit", rs.getString("fruit"));
        info.put("severity", rs.getString("severity"));
        info.put("imageUrl", rs.getString("image_url"));
        return new FarmMapObservation(
                rs.getString("point_id"), parseInteger(rs.getString("source_legacy_id")), number(rs, "longitude"), number(rs, "latitude"),
                rs.getString("observed_date"), rs.getString("crop"), rs.getString("source_system"), rs.getString("source_entity_type"),
                rs.getString("coordinate_reference_system"), info);
    }

    private void ensureSchema() {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            jdbc.execute("CREATE TABLE IF NOT EXISTS farm_map_points (" +
                    "point_id VARCHAR(160) NOT NULL," +
                    "farm_id INT NOT NULL," +
                    "point_kind VARCHAR(48) NOT NULL," +
                    "source_system VARCHAR(64) NOT NULL," +
                    "source_entity_type VARCHAR(64) NOT NULL," +
                    "source_legacy_id VARCHAR(128) NOT NULL," +
                    "source_sequence INT NULL," +
                    "longitude DECIMAL(11,8) NULL," +
                    "latitude DECIMAL(10,8) NULL," +
                    "coordinate_reference_system VARCHAR(32) NOT NULL DEFAULT 'GCJ-02'," +
                    "crop VARCHAR(128) NULL," +
                    "observed_date VARCHAR(64) NULL," +
                    "phenology DOUBLE NULL," +
                    "growth VARCHAR(255) NULL," +
                    "impact VARCHAR(255) NULL," +
                    "disease VARCHAR(255) NULL," +
                    "potassium VARCHAR(255) NULL," +
                    "phosphorus VARCHAR(255) NULL," +
                    "nitrogen DOUBLE NULL," +
                    "volume DOUBLE NULL," +
                    "transmittance DOUBLE NULL," +
                    "image_url VARCHAR(512) NULL," +
                    "yield_value DOUBLE NULL," +
                    "fruit VARCHAR(64) NULL," +
                    "rate DOUBLE NULL," +
                    "diseases VARCHAR(255) NULL," +
                    "severity VARCHAR(255) NULL," +
                    "owner_user_id BIGINT NULL," +
                    "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'," +
                    "created_at TIMESTAMP(3) NOT NULL," +
                    "updated_at TIMESTAMP(3) NOT NULL," +
                    "PRIMARY KEY(point_id)," +
                    "UNIQUE KEY uk_farm_map_source(source_system,source_entity_type,source_legacy_id)," +
                    "KEY idx_farm_map_farm_kind(farm_id,point_kind,status)," +
                    "KEY idx_farm_map_owner(owner_user_id))");
            schemaChecked = true;
        }
    }

    private void seedWushanCropObservations(UserLoginResponseVO.FarmVO farm) {
        if (!presentationDataEnabled || farm == null || !Integer.valueOf(7).equals(farm.getId())) return;
        Instant now = Instant.now();
        int index = 0;
        for (PresentationCropSeed seed : WUSHAN_PRESENTATION_CROPS) {
            // Three nearby observations per real camera make the crop layer
            // readable at the farm's overview zoom while keeping the points
            // spatially anchored to the real monitoring topology.
            double[][] offsets = {{0D, 0D}, {0.0012D, 0.0007D}, {-0.0010D, -0.0006D}};
            for (int slot = 0; slot < offsets.length; slot++) {
                String stableId = "wushan-presentation-crop-" + seed.serial().toLowerCase() + "-" + slot;
                jdbc.update("INSERT IGNORE INTO farm_map_points(" +
                                "point_id,farm_id,point_kind,source_system,source_entity_type,source_legacy_id," +
                                "source_sequence,longitude,latitude,coordinate_reference_system,crop,observed_date," +
                                "phenology,growth,impact,disease,potassium,phosphorus,nitrogen,volume,transmittance," +
                                "image_url,yield_value,fruit,rate,diseases,severity,owner_user_id,status,created_at,updated_at) " +
                                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        stableId, farm.getId(), "CROP_OBSERVATION", "FARMAP_PRESENTATION", "CROP_OBSERVATION", stableId,
                        index++, seed.longitude() + offsets[slot][0], seed.latitude() + offsets[slot][1], "GCJ-02", "plum", "2026-09-08",
                        7.0D + (slot * 0.1D), "2.2", "1.1", "1.8", "2.1", "1.7", 1.9D, 1.2D, 0.64D,
                        null, 0D, "脆李", 0.08D + (slot * 0.01D), "none", "0", farm.getUserId(), "ACTIVE",
                        Timestamp.from(now), Timestamp.from(now));
            }
        }
    }

    private Double number(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.doubleValue();
    }

    private Integer parseInteger(String value) {
        try { return value == null ? null : Integer.valueOf(value); }
        catch (NumberFormatException ignored) { return null; }
    }

    private Object numericOrText(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Double.valueOf(value); }
        catch (NumberFormatException ignored) { return value; }
    }

    private record PresentationCropSeed(String serial, double longitude, double latitude) {}
}
