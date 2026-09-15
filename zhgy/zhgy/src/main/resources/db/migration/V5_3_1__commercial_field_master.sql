-- FarMap Commercial MVP Integration Phase C.
-- Stable farm-owned field identity. Legacy location rows are deliberately not
-- migrated because they do not carry a trustworthy field boundary/name.
CREATE TABLE IF NOT EXISTS farm_fields (
    field_id VARCHAR(96) NOT NULL,
    farm_id INT NOT NULL,
    name VARCHAR(128) NOT NULL,
    crop VARCHAR(128) NULL,
    area DECIMAL(12,4) NULL,
    growth_stage VARCHAR(128) NULL,
    latitude DECIMAL(10,8) NULL,
    longitude DECIMAL(11,8) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    source_mode VARCHAR(32) NOT NULL DEFAULT 'MYSQL',
    owner_user_id BIGINT NULL,
    created_at TIMESTAMP(3) NOT NULL,
    updated_at TIMESTAMP(3) NOT NULL,
    PRIMARY KEY (field_id),
    UNIQUE KEY uk_farm_field_name (farm_id, name),
    KEY idx_farm_fields_farm (farm_id),
    KEY idx_farm_fields_owner (owner_user_id),
    CONSTRAINT fk_farm_fields_farm FOREIGN KEY (farm_id) REFERENCES farm(id)
);
