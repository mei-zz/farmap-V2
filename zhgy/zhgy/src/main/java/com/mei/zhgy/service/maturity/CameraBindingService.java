package com.mei.zhgy.service.maturity;

import com.mei.zhgy.service.camera.FarmCameraService;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.field.FieldAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CameraBindingService {
    private final JdbcTemplate jdbc;
    private final FarmFieldService fields;
    private final FarmCameraService cameras;
    private volatile boolean ready;
    public CameraBindingService(JdbcTemplate jdbc, FarmFieldService fields, FarmCameraService cameras) { this.jdbc=jdbc; this.fields=fields; this.cameras=cameras; }
    private synchronized void ensureSchema() {
        if (ready) return;
        jdbc.execute("CREATE TABLE IF NOT EXISTS farm_monitoring_regions (region_id VARCHAR(96) PRIMARY KEY, farm_id INT NOT NULL, field_id VARCHAR(96) NOT NULL, name VARCHAR(128) NOT NULL, KEY idx_region_farm (farm_id,field_id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS farm_camera_region_bindings (camera_id VARCHAR(96) PRIMARY KEY, farm_id INT NOT NULL, field_id VARCHAR(96) NOT NULL, region_id VARCHAR(96) NOT NULL, KEY idx_binding_farm (farm_id,field_id))"); ready=true;
    }
    public List<Map<String,Object>> list(Integer farmId, Long actor) {
        fields.requireFarm(farmId,actor); cameras.list(farmId,actor); ensureSchema();
        return jdbc.queryForList("SELECT b.camera_id AS cameraId,b.field_id AS fieldId,b.region_id AS areaId,r.name AS areaName FROM farm_camera_region_bindings b JOIN farm_monitoring_regions r ON r.region_id=b.region_id WHERE b.farm_id=? ORDER BY b.camera_id",farmId);
    }
    @org.springframework.transaction.annotation.Transactional
    public Map<String,Object> bind(Integer farmId, Long actor, String cameraId, String fieldId, String areaId, String areaName) {
        fields.requireFarm(farmId, actor);
        var camera = cameras.get(cameraId, actor);
        if (!Objects.equals(camera.farmId(),farmId)) throw new FieldAccessException(404,"CAMERA_NOT_FOUND","camera not in farm");
        ensureSchema();
        if (fieldId == null || fieldId.isBlank()) {
            jdbc.update("DELETE FROM farm_camera_region_bindings WHERE camera_id=? AND farm_id=?",cameraId,farmId);
            jdbc.update("UPDATE farm_cameras SET field_id=NULL,binding_status='UNBOUND',binding_method='USER_CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE camera_id=? AND farm_id=?",cameraId,farmId);
            return Map.of("cameraId",cameraId,"status","UNBOUND");
        }
        var field=fields.get(fieldId, actor);
        if (!Objects.equals(field.getFarmId(),farmId) || (field.getSourceMode()!=null && field.getSourceMode().startsWith("SYNTHETIC"))) throw new FieldAccessException(422,"INVALID_FIELD","field must be real and belong to farm");
        String regionId=areaId;
        if (regionId == null || regionId.isBlank()) {
            regionId="region-"+UUID.randomUUID();
            jdbc.update("INSERT INTO farm_monitoring_regions(region_id,farm_id,field_id,name) VALUES (?,?,?,?)",regionId,farmId,fieldId,areaName == null || areaName.isBlank() ? field.getName()+"监测区" : areaName.trim());
        } else {
            Integer matches=jdbc.queryForObject("SELECT COUNT(*) FROM farm_monitoring_regions WHERE region_id=? AND farm_id=? AND field_id=?",Integer.class,regionId,farmId,fieldId);
            if (matches==null || matches==0) throw new FieldAccessException(422,"INVALID_REGION","monitoring region must belong to field");
        }
        jdbc.update("INSERT INTO farm_camera_region_bindings(camera_id,farm_id,field_id,region_id) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE farm_id=VALUES(farm_id),field_id=VALUES(field_id),region_id=VALUES(region_id)",cameraId,farmId,fieldId,regionId);
        jdbc.update("UPDATE farm_cameras SET field_id=?,binding_status='BOUND',binding_method='USER_CONFIRMED',updated_at=CURRENT_TIMESTAMP WHERE camera_id=? AND farm_id=?",fieldId,cameraId,farmId);
        return Map.of("cameraId",cameraId,"fieldId",fieldId,"areaId",regionId,"status","BOUND");
    }
}
