-- FarMap P0: make the existing curated knowledge corpus safe for crop-aware retrieval.
-- Only unambiguous document titles are backfilled. Generic stage documents stay
-- untagged and are intentionally excluded from a known-crop production query.
UPDATE knowledge_chunks c
JOIN knowledge_documents d ON d.document_id = c.document_id
SET c.classification = 'ADMIN_CURATED:crop:rice_late'
WHERE c.classification = 'ADMIN_CURATED'
  AND d.title REGEXP '晚稻|再生稻';

UPDATE knowledge_chunks c
JOIN knowledge_documents d ON d.document_id = c.document_id
SET c.classification = 'ADMIN_CURATED:crop:plum'
WHERE c.classification = 'ADMIN_CURATED'
  AND d.title REGEXP '脆李|巫山脆李|李子|plum';

UPDATE knowledge_chunks c
JOIN knowledge_documents d ON d.document_id = c.document_id
SET c.classification = 'ADMIN_CURATED:crop:citrus'
WHERE c.classification = 'ADMIN_CURATED'
  AND d.title REGEXP '柑橘|沃柑|脐橙|椪柑|春见|砂糖橘|爱媛|citrus|orange';

UPDATE knowledge_chunks c
JOIN knowledge_documents d ON d.document_id = c.document_id
SET c.classification = 'ADMIN_CURATED:crop:rice'
WHERE c.classification = 'ADMIN_CURATED'
  AND d.title REGEXP '水稻|稻谷|中稻|Rice|rice';
