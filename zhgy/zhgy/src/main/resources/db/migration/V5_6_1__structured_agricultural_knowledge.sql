-- FarMap Phase 1: structured crop/stage knowledge.
-- The application keeps an idempotent runtime seed for local compatibility;
-- production deployments should run this migration before enabling lookup.
CREATE TABLE IF NOT EXISTS agri_stage_knowledge (
  rule_id VARCHAR(112) NOT NULL,
  crop_code VARCHAR(64) NOT NULL,
  stage_code VARCHAR(64) NULL,
  knowledge_type VARCHAR(32) NOT NULL,
  knowledge_key VARCHAR(128) NOT NULL,
  content TEXT NOT NULL,
  is_deterministic BOOLEAN NOT NULL DEFAULT TRUE,
  source_ref VARCHAR(512) NULL,
  priority INT NOT NULL DEFAULT 50,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (rule_id),
  UNIQUE KEY uk_agri_stage_rule (crop_code, stage_code, knowledge_type, knowledge_key),
  INDEX idx_agri_stage_lookup (crop_code, stage_code, knowledge_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS agri_signal_rule (
  rule_id VARCHAR(112) NOT NULL,
  crop_code VARCHAR(64) NULL,
  stage_code VARCHAR(64) NULL,
  signal_type VARCHAR(32) NOT NULL,
  signal_key VARCHAR(128) NOT NULL,
  operator VARCHAR(8) NULL,
  threshold_value DECIMAL(12,3) NULL,
  unit VARCHAR(32) NULL,
  judgement TEXT NOT NULL,
  recommendation TEXT NULL,
  source_ref VARCHAR(512) NULL,
  priority INT NOT NULL DEFAULT 50,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMP(3) NOT NULL,
  updated_at TIMESTAMP(3) NOT NULL,
  PRIMARY KEY (rule_id),
  UNIQUE KEY uk_agri_signal_rule (crop_code, stage_code, signal_type, signal_key),
  INDEX idx_agri_signal_lookup (crop_code, stage_code, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT IGNORE INTO agri_stage_knowledge
  (rule_id,crop_code,stage_code,knowledge_type,knowledge_key,content,is_deterministic,source_ref,priority,status,created_at,updated_at)
VALUES
  ('agri-plum-post_harvest-pest_disease','plum','post_harvest','PEST_DISEASE','病虫监测','采后恢复期巡查叶片病斑、枝干病虫和落果，发现异常先记录部位与扩展趋势，再按确诊结果处理。',TRUE,'curated://agri-stage-knowledge-v1',95,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('agri-plum-post_harvest-fertilizer_water','plum','post_harvest','FERTILIZER_WATER','树势恢复','采后补水和秋肥应结合土壤墒情、树势与检测结果，保持根区通气，避免长期积水和过量氮肥。',TRUE,'curated://agri-stage-knowledge-v1',90,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('agri-plum-post_harvest-farm_operation','plum','post_harvest','FARM_OPERATION','采后清园','采后及时清理病果、落叶和修剪残体，配合必要的整形修剪，促进秋梢和树势恢复。',TRUE,'curated://agri-stage-knowledge-v1',90,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('agri-plum-post_harvest-taboo','plum','post_harvest','TABOO','恢复期禁忌','采后恢复期不宜大水漫灌、重施速效氮肥或在未确认病因前反复混用药剂。',TRUE,'curated://agri-stage-knowledge-v1',92,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('agri-citrus-fruit_development-pest_disease','citrus','fruit_development','PEST_DISEASE','膨果期病虫','果实膨大期检查果实、叶片和夏秋梢，重点记录病斑、虫口和落果变化后再制定防治方案。',TRUE,'curated://agri-stage-knowledge-v1',92,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('agri-citrus-fruit_development-fertilizer_water','citrus','fruit_development','FERTILIZER_WATER','膨果期肥水','果实膨大期保持根区水分稳定，肥水结合树势、负载和土壤检测，避免忽干忽湿。',TRUE,'curated://agri-stage-knowledge-v1',90,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('agri-rice-filling-fertilizer_water','rice','filling','FERTILIZER_WATER','灌浆水分','灌浆期保持适宜水分，避免长期积水或过早断水造成灌浆受影响。',TRUE,'curated://agri-stage-knowledge-v1',88,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('agri-rice-filling-taboo','rice','filling','TABOO','灌浆期禁忌','灌浆期不宜仅按日历日期提前收割，也不应把水稻收获后写成木本果树采后恢复期。',TRUE,'curated://agri-stage-knowledge-v1',92,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3));

INSERT IGNORE INTO agri_signal_rule
  (rule_id,crop_code,stage_code,signal_type,signal_key,operator,threshold_value,unit,judgement,recommendation,source_ref,priority,status,created_at,updated_at)
VALUES
  ('signal-plum-yellowing','plum','post_harvest','SYMPTOM','叶片黄化',NULL,NULL,NULL,'叶片黄化需要结合叶龄、叶脉颜色、根区水分和病斑位置判断，不能仅凭颜色定病。','先拍照并检查根区积水、施肥记录和病斑扩展，再决定是否处理。','curated://agri-stage-knowledge-v1',94,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('signal-citrus-rain','citrus','fruit_development','THRESHOLD','连续降雨','>',3,'天','连续降雨超过3天时，柑橘根区过湿和病害风险上升。','优先检查排水、根区通气和叶果病斑，再按实际症状处置。','curated://agri-stage-knowledge-v1',96,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('signal-citrus-heat','citrus','fruit_development','THRESHOLD','高温','>=',35,'℃','日最高温度达到35℃及以上时，果实和嫩梢热害风险上升。','加强遮阴、通风和水分监测，避免高温时段进行刺激性操作。','curated://agri-stage-knowledge-v1',96,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3)),
  ('signal-rice-rain','rice','filling','THRESHOLD','连续降雨','>',3,'天','灌浆期连续降雨超过3天时，倒伏、病害和籽粒充实风险上升。','检查田间积水、倒伏和穗部病害，及时记录受灾范围。','curated://agri-stage-knowledge-v1',94,'ACTIVE',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3));
