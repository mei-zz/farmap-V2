-- Canonical real camera inventory and explicit spatial bindings.
-- A point is considered bound only when the nearest canonical map point is
-- within 5 km; the source camera coordinate remains authoritative otherwise.
-- Runtime service also creates/seed-checks this table for the existing local
-- MySQL workflow, because this project does not execute Flyway migrations.
CREATE TABLE IF NOT EXISTS farm_cameras (
    camera_id VARCHAR(96) NOT NULL,
    serial VARCHAR(96) NOT NULL,
    name VARCHAR(128) NOT NULL,
    farm_id INT NOT NULL,
    field_id VARCHAR(96) NULL,
    point_id VARCHAR(160) NULL,
    longitude DECIMAL(11,8) NOT NULL,
    latitude DECIMAL(10,8) NOT NULL,
    source_longitude DECIMAL(11,8) NOT NULL,
    source_latitude DECIMAL(10,8) NOT NULL,
    binding_distance_meters DECIMAL(12,2) NULL,
    binding_status VARCHAR(32) NOT NULL DEFAULT 'BOUND',
    binding_method VARCHAR(64) NOT NULL DEFAULT 'CURATED_REAL_COORDINATE',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (camera_id),
    UNIQUE KEY uk_farm_camera_serial (serial),
    KEY idx_farm_camera_farm (farm_id, status),
    KEY idx_farm_camera_point (point_id),
    CONSTRAINT fk_farm_camera_farm FOREIGN KEY (farm_id) REFERENCES farm(id)
);
