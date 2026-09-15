package com.mei.zhgy.service.camera;

import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import com.mei.zhgy.vo.UserLoginResponseVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Owner-scoped camera catalogue and explicit spatial binding layer.
 *
 * The legacy inventory is real Hikvision inventory, but it was previously
 * kept only in a frontend array.  The seed coordinates resolve to farm 7
 * (巫山) in the restored source data.  We retain those coordinates and bind
 * each camera to the nearest canonical farm map point for a reversible,
 * auditable map relationship.  We intentionally do not invent field IDs.
 */
@Service
public class FarmCameraService {
    private final JdbcTemplate jdbc;
    private final FarmFieldService farmAccess;
    private volatile boolean schemaChecked;
    private volatile boolean registrySeeded;

    @Value("${farmap.camera.source-farm-id:7}")
    private Integer sourceFarmId;

    /**
     * A canonical map point must be spatially close enough to be useful for
     * AI context.  Cameras farther away keep their real coordinates on the
     * map but remain explicitly unbound until an administrator confirms the
     * relationship.
     */
    @Value("${farmap.camera.max-binding-distance-meters:5000}")
    private double maxBindingDistanceMeters;

    @Value("${farmap.presentation-data.enabled:false}")
    private boolean presentationDataEnabled;

    private static final List<FieldSeed> WUSHAN_PRESENTATION_FIELDS = List.of(
            new FieldSeed("field-wushan-plum-core-east", "L-01 巫山脆李核心监测区", 18.6, "采后恢复期", 109.92235, 31.09639,
                    "GC9054260", "GA1738420", "GA1738425", "GA1738438", "GA1738401", "GA3302963"),
            new FieldSeed("field-wushan-plum-west", "L-02 巫山脆李西部监测区", 21.4, "采后恢复期", 109.72612, 31.24607,
                    "GA1738435", "GA1738422", "GA1738434", "GA3302944", "GA1738431", "GA3302956", "GA1738402"),
            new FieldSeed("field-wushan-plum-central", "L-03 巫山脆李中部监测区", 16.8, "采后恢复期", 109.74600, 31.05445,
                    "GA1738418", "GD1221224", "GC5448241", "GD1221262", "GA1738411", "GA1738437", "GA1738393"),
            new FieldSeed("field-wushan-plum-east", "L-04 巫山脆李东部监测区", 12.2, "采后恢复期", 110.06531, 31.20212,
                    "GA1738409", "GA1738433"),
            new FieldSeed("field-wushan-plum-south", "L-05 巫山脆李南部监测区", 8.5, "采后恢复期", 109.61916, 30.87152,
                    "GA3302969"),
            new FieldSeed("field-wushan-plum-north", "L-06 巫山脆李北部监测区", 9.7, "采后恢复期", 109.82110, 31.96363,
                    "GA1738413")
    );

    private static final List<CameraSeed> INVENTORY = List.of(
            new CameraSeed("GC9054260", "廖荣华-球机", 109.914693, 31.082733),
            new CameraSeed("GA1738420", "陈仁俊-球机", 109.932434, 31.084613),
            new CameraSeed("GA1738425", "刘庆贵-球机", 109.932259, 31.103548),
            new CameraSeed("GA1738438", "王祖保-球机", 109.920324, 31.143435),
            new CameraSeed("GA1738413", "贺维清-球机", 109.821102, 31.963625),
            new CameraSeed("GA1738435", "聂宗寿-球机", 109.671962, 31.293361),
            new CameraSeed("GA1738401", "刘书建-球机", 109.914693, 31.082733),
            new CameraSeed("GA3302963", "彭美春-球机", 109.914693, 31.082733),
            new CameraSeed("GA1738422", "邹贤亮-球机", 109.650080, 31.270829),
            new CameraSeed("GA1738434", "罗伟-球机", 109.780183, 31.220559),
            new CameraSeed("GA3302944", "刘毅-球机", 109.782013, 31.201212),
            new CameraSeed("GA1738431", "彭斌-球机", 109.774911, 31.232603),
            new CameraSeed("GA1738418", "李军-球机", 109.767093, 31.137653),
            new CameraSeed("GD1221224", "龚正第-球机", 109.718101, 31.023009),
            new CameraSeed("GC5448241", "陈嗣春-球机", 109.725240, 31.017196),
            new CameraSeed("GD1221262", "李洪-球机", 109.777335, 31.070149),
            new CameraSeed("GA1738411", "李淑桂-球机", 109.791725, 31.071729),
            new CameraSeed("GA1738437", "张怀-球机", 109.713775, 31.029861),
            new CameraSeed("GA1738393", "向耀松-球机", 109.726345, 31.031300),
            new CameraSeed("GA3302969", "翁发福-球机", 109.619158, 30.871521),
            new CameraSeed("GA3302956", "陈勇-球机", 109.694230, 31.234700),
            new CameraSeed("GA1738402", "覃传德-球机", 109.707898, 31.275263),
            new CameraSeed("GA1738409", "刘绪海-球机", 110.128162, 31.172659),
            new CameraSeed("GA1738433", "赖明星-球机", 110.002466, 31.231587)
    );

    public FarmCameraService(JdbcTemplate jdbc, FarmFieldService farmAccess) {
        this.jdbc = jdbc;
        this.farmAccess = farmAccess;
    }

    public List<FarmCamera> list(Integer farmId, Long actorId) {
        UserLoginResponseVO.FarmVO farm = farmAccess.requireFarm(farmId, actorId);
        // Ensure the optional field master exists before the left join below.
        farmAccess.list(farmId, actorId);
        ensureSchema();
        seedRegistry();
        return jdbc.query(
                "SELECT c.camera_id,c.serial,c.name,c.farm_id,c.field_id,ff.name AS field_name,ff.crop,ff.growth_stage,ff.area AS field_area,ff.source_mode AS field_source_mode,c.point_id,c.longitude,c.latitude,c.source_longitude,c.source_latitude,c.binding_distance_meters,c.binding_status,c.binding_method,c.status " +
                        "FROM farm_cameras c LEFT JOIN farm_fields ff ON ff.field_id=c.field_id WHERE c.farm_id=? AND c.status='ACTIVE' ORDER BY c.name,c.serial",
                (rs, rowNum) -> map(rs, farm.getName()), farmId);
    }

    public FarmCamera get(String cameraId, Long actorId) {
        ensureSchema();
        seedRegistry();
        List<FarmCamera> cameras = jdbc.query(
                "SELECT c.camera_id,c.serial,c.name,c.farm_id,c.field_id,ff.name AS field_name,ff.crop,ff.growth_stage,ff.area AS field_area,ff.source_mode AS field_source_mode,c.point_id,c.longitude,c.latitude,c.source_longitude,c.source_latitude,c.binding_distance_meters,c.binding_status,c.binding_method,c.status,f.name AS farm_name " +
                        "FROM farm_cameras c JOIN farm f ON f.id=c.farm_id LEFT JOIN farm_fields ff ON ff.field_id=c.field_id WHERE c.camera_id=? AND c.status='ACTIVE'",
                (rs, rowNum) -> map(rs, rs.getString("farm_name")), cameraId);
        if (cameras.isEmpty()) throw new FieldAccessException(404, "CAMERA_NOT_FOUND", "camera not found");
        FarmCamera camera = cameras.get(0);
        farmAccess.requireFarm(camera.farmId(), actorId);
        return camera;
    }

    private FarmCamera map(ResultSet rs, String farmName) throws java.sql.SQLException {
        return new FarmCamera(
                rs.getString("camera_id"), rs.getString("serial"), rs.getString("name"),
                rs.getInt("farm_id"), farmName, rs.getString("field_id"), rs.getString("field_name"),
                rs.getString("crop"), rs.getString("growth_stage"), number(rs, "field_area"), rs.getString("field_source_mode"),
                rs.getString("point_id"), number(rs, "longitude"), number(rs, "latitude"),
                number(rs, "source_longitude"), number(rs, "source_latitude"),
                number(rs, "binding_distance_meters"), rs.getString("binding_status"),
                rs.getString("binding_method"), rs.getString("status"));
    }

    private void ensureSchema() {
        if (schemaChecked) return;
        synchronized (this) {
            if (schemaChecked) return;
            jdbc.execute("CREATE TABLE IF NOT EXISTS farm_cameras (" +
                    "camera_id VARCHAR(96) NOT NULL," +
                    "serial VARCHAR(96) NOT NULL," +
                    "name VARCHAR(128) NOT NULL," +
                    "farm_id INT NOT NULL," +
                    "field_id VARCHAR(96) NULL," +
                    "point_id VARCHAR(160) NULL," +
                    "longitude DECIMAL(11,8) NOT NULL," +
                    "latitude DECIMAL(10,8) NOT NULL," +
                    "source_longitude DECIMAL(11,8) NOT NULL," +
                    "source_latitude DECIMAL(10,8) NOT NULL," +
                    "binding_distance_meters DECIMAL(12,2) NULL," +
                    "binding_status VARCHAR(32) NOT NULL DEFAULT 'BOUND'," +
                    "binding_method VARCHAR(64) NOT NULL DEFAULT 'CURATED_REAL_COORDINATE'," +
                    "status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE'," +
                    "created_at TIMESTAMP(3) NOT NULL," +
                    "updated_at TIMESTAMP(3) NOT NULL," +
                    "PRIMARY KEY(camera_id)," +
                    "UNIQUE KEY uk_farm_camera_serial(serial)," +
                    "KEY idx_farm_camera_farm(farm_id,status)," +
                    "KEY idx_farm_camera_point(point_id)," +
                    "CONSTRAINT fk_farm_camera_farm FOREIGN KEY(farm_id) REFERENCES farm(id))");
            schemaChecked = true;
        }
    }

    private void seedRegistry() {
        if (registrySeeded) return;
        synchronized (this) {
            if (registrySeeded) return;
            Integer farmId = sourceFarmId == null ? 7 : sourceFarmId;
            if (presentationDataEnabled) seedWushanPresentationTopology(farmId);
            List<MapPoint> points = jdbc.query(
                    "SELECT point_id,longitude,latitude FROM farm_map_points WHERE farm_id=? AND point_kind='BOUNDARY_VERTEX' AND status='ACTIVE' AND longitude IS NOT NULL AND latitude IS NOT NULL",
                    (rs, rowNum) -> new MapPoint(rs.getString("point_id"), rs.getDouble("longitude"), rs.getDouble("latitude")), farmId);
            List<String> sourceFarms = jdbc.query("SELECT name FROM farm WHERE id=?", (rs, rowNum) -> rs.getString("name"), farmId);
            if (sourceFarms.isEmpty()) {
                registrySeeded = true;
                return;
            }
            Instant now = Instant.now();
            for (CameraSeed camera : INVENTORY) {
                FieldSeed presentationField = presentationDataEnabled ? presentationField(camera.serial()) : null;
                MapPoint nearest = presentationField == null ? nearest(camera, points) : new MapPoint(cameraPointId(camera.serial()), camera.longitude(), camera.latitude());
                double distance = nearest == null ? 0D : distanceMeters(camera.longitude(), camera.latitude(), nearest.longitude(), nearest.latitude());
                boolean bound = nearest != null && (presentationField != null || distance <= maxBindingDistanceMeters);
                String fieldId = presentationField == null ? null : presentationField.fieldId();
                String method = presentationField != null ? "CURATED_PRESENTATION_TOPOLOGY" : bound ? "CURATED_REAL_COORDINATE" : "SOURCE_COORDINATE_ONLY";
                jdbc.update("INSERT IGNORE INTO farm_cameras(camera_id,serial,name,farm_id,field_id,point_id,longitude,latitude,source_longitude,source_latitude,binding_distance_meters,binding_status,binding_method,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        camera.serial(), camera.serial(), camera.name(), farmId, fieldId, bound ? nearest.pointId() : null,
                        camera.longitude(), camera.latitude(), camera.longitude(), camera.latitude(), nearest == null ? null : distance,
                        bound ? "BOUND" : "UNBOUND", method,
                        "ACTIVE", Timestamp.from(now), Timestamp.from(now));
                if (presentationField != null) {
                    jdbc.update("UPDATE farm_cameras SET farm_id=?,field_id=?,point_id=?,longitude=?,latitude=?,source_longitude=?,source_latitude=?,binding_distance_meters=0,binding_status='BOUND',binding_method='CURATED_PRESENTATION_TOPOLOGY',status='ACTIVE',updated_at=? WHERE camera_id=?",
                            farmId, presentationField.fieldId(), cameraPointId(camera.serial()), camera.longitude(), camera.latitude(), camera.longitude(), camera.latitude(), Timestamp.from(now), camera.serial());
                }
            }
            // Reconcile rows created by an earlier runtime so a stale nearest
            // point can never remain an implicit AI relationship.
            if (!presentationDataEnabled) {
                jdbc.update("UPDATE farm_cameras SET point_id=NULL,binding_status='UNBOUND',binding_method='SOURCE_COORDINATE_ONLY' WHERE farm_id=? AND (binding_distance_meters IS NULL OR binding_distance_meters>?)", farmId, maxBindingDistanceMeters);
            }
            registrySeeded = true;
        }
    }

    /**
     * Creates an idempotent presentation topology around the real Hikvision
     * coordinates. The cameras and source coordinates remain real; field
     * boundaries/areas are presentation fixtures and retain explicit source
     * provenance in farm_fields.source_mode and binding_method.
     */
    private void seedWushanPresentationTopology(Integer farmId) {
        Instant now = Instant.now();
        for (FieldSeed field : WUSHAN_PRESENTATION_FIELDS) {
            jdbc.update("INSERT IGNORE INTO farm_fields(field_id,farm_id,name,crop,area,growth_stage,latitude,longitude,status,source_mode,owner_user_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    field.fieldId(), farmId, field.name(), "plum", field.area(), field.growthStage(), field.latitude(), field.longitude(),
                    "ACTIVE", "SYNTHETIC_PRESENTATION", null, Timestamp.from(now), Timestamp.from(now));
        }
        for (CameraSeed camera : INVENTORY) {
            jdbc.update("INSERT IGNORE INTO farm_map_points(point_id,farm_id,point_kind,source_system,source_entity_type,source_legacy_id,source_sequence,longitude,latitude,coordinate_reference_system,crop,owner_user_id,status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    cameraPointId(camera.serial()), farmId, "CAMERA_ANCHOR", "HIKVISION", "CAMERA", camera.serial(), null,
                    camera.longitude(), camera.latitude(), "GCJ-02", "plum", null, "ACTIVE", Timestamp.from(now), Timestamp.from(now));
        }
    }

    private FieldSeed presentationField(String serial) {
        for (FieldSeed field : WUSHAN_PRESENTATION_FIELDS) {
            if (Arrays.asList(field.serials()).contains(serial)) return field;
        }
        return null;
    }

    private String cameraPointId(String serial) { return "camera-anchor-" + serial.toLowerCase(); }

    private MapPoint nearest(CameraSeed camera, List<MapPoint> points) {
        MapPoint best = null;
        double bestDistance = Double.MAX_VALUE;
        for (MapPoint point : points) {
            double distance = distanceMeters(camera.longitude(), camera.latitude(), point.longitude(), point.latitude());
            if (distance < bestDistance) { bestDistance = distance; best = point; }
        }
        return best;
    }

    private double distanceMeters(double lon1, double lat1, double lon2, double lat2) {
        double lat = Math.toRadians((lat1 + lat2) / 2D);
        double dx = (lon1 - lon2) * 111320D * Math.cos(lat);
        double dy = (lat1 - lat2) * 110540D;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private Double number(ResultSet rs, String column) throws java.sql.SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.doubleValue();
    }

    private record CameraSeed(String serial, String name, double longitude, double latitude) {}
    private record MapPoint(String pointId, double longitude, double latitude) {}
    private record FieldSeed(String fieldId, String name, double area, String growthStage, double longitude, double latitude, String... serials) {}
}
