package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.result.Result;
import com.mei.zhgy.service.knowledge.KnowledgeBackendException;
import com.mei.zhgy.service.knowledge.KnowledgeBackendService;
import com.mei.zhgy.service.knowledge.KnowledgeGraphService;
import com.mei.zhgy.mapper.UserMapper;
import com.mei.zhgy.entity.User;
import com.mei.zhgy.vo.KnowledgeGraphResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal Knowledge Backend V1 command/read boundary. */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private final KnowledgeBackendService knowledge;
    private final KnowledgeGraphService graph;
    private final UserMapper users;
    public KnowledgeController(KnowledgeBackendService knowledge, KnowledgeGraphService graph, @org.springframework.beans.factory.annotation.Autowired(required = false) UserMapper users) { this.knowledge = knowledge; this.graph = graph; this.users = users; }

    @PostMapping("/sources") public Result<Map<String,Object>> createSource(@RequestBody(required=false) Map<String,Object> input){return Result.success(knowledge.createSource(input,adminActor()));}
    @GetMapping("/sources") public Result<List<Map<String,Object>>> sources(){return Result.success(knowledge.listSources(actor()));}
    @GetMapping("/sources/{sourceId}") public Result<Map<String,Object>> source(@PathVariable String sourceId){return Result.success(knowledge.getSource(sourceId,actor()));}
    @PostMapping("/sources/{sourceId}/documents") public Result<Map<String,Object>> createDocument(@PathVariable String sourceId,@RequestBody(required=false) Map<String,Object> input){return Result.success(knowledge.createDocument(sourceId,input,adminActor()));}
    @GetMapping("/sources/{sourceId}/documents") public Result<List<Map<String,Object>>> documents(@PathVariable String sourceId){return Result.success(knowledge.listDocuments(sourceId,actor()));}
    @GetMapping("/documents/{documentId}") public Result<Map<String,Object>> document(@PathVariable String documentId){return Result.success(knowledge.getDocument(documentId,actor()));}
    @PostMapping("/documents/{documentId}/versions") public Result<Map<String,Object>> createVersion(@PathVariable String documentId,@RequestBody(required=false) Map<String,Object> input){return Result.success(knowledge.createVersion(documentId,input,adminActor()));}
    @GetMapping("/documents/{documentId}/versions") public Result<List<Map<String,Object>>> versions(@PathVariable String documentId){return Result.success(knowledge.listVersions(documentId,actor()));}
    @GetMapping("/versions/{versionId}") public Result<Map<String,Object>> version(@PathVariable String versionId){return Result.success(knowledge.getVersion(versionId,actor()));}
    @PostMapping("/documents/{documentId}/versions/{versionId}/activate") public Result<Map<String,Object>> activate(@PathVariable String documentId,@PathVariable String versionId){return Result.success(knowledge.activateVersion(documentId,versionId,adminActor()));}
    @PostMapping("/sources/{sourceId}/disable") public Result<Map<String,Object>> disableSource(@PathVariable String sourceId){return Result.success(knowledge.setSourceStatus(sourceId,"DISABLED",adminActor()));}
    @PostMapping("/sources/{sourceId}/enable") public Result<Map<String,Object>> enableSource(@PathVariable String sourceId){return Result.success(knowledge.setSourceStatus(sourceId,"ACTIVE",adminActor()));}
    @PostMapping("/documents/{documentId}/disable") public Result<Map<String,Object>> disableDocument(@PathVariable String documentId){return Result.success(knowledge.setDocumentStatus(documentId,"DISABLED",adminActor()));}
    @PostMapping("/documents/{documentId}/enable") public Result<Map<String,Object>> enableDocument(@PathVariable String documentId){return Result.success(knowledge.setDocumentStatus(documentId,"ACTIVE",adminActor()));}
    @GetMapping("/health")
    public ResponseEntity<Result<Map<String,Object>>> health(){
        Map<String,Object> report = knowledge.health();
        HttpStatus status = "UNAVAILABLE".equals(report.get("status")) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(Result.success(report));
    }

    @GetMapping("/graph")
    public Result<KnowledgeGraphResponse> graph(
            @RequestParam(required = false) String nodeTypes,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String documentId,
            @RequestParam(required = false) String diagnosisId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "true") Boolean includeEvidence) {
        return Result.success(graph.read(actor(), nodeTypes, sourceId, documentId, diagnosisId, limit, includeEvidence));
    }

    private Long actor(){Long value=BaseContext.getCurrentId();if(value==null)throw KnowledgeBackendException.forbidden(null);return value;}
    private Long adminActor(){Long value=actor(); if(users==null) throw KnowledgeBackendException.forbidden(String.valueOf(value)); User user=users.getById(value.intValue()); if(user==null||!"admin".equalsIgnoreCase(user.getRole())) throw KnowledgeBackendException.forbidden(String.valueOf(value)); return value;}
    @ExceptionHandler(KnowledgeBackendException.class)
    public ResponseEntity<Map<String,Object>> error(KnowledgeBackendException e){Map<String,Object>b=new LinkedHashMap<>();b.put("code",e.getCode());b.put("message",e.getMessage());if(e.getResourceId()!=null)b.put("resourceId",e.getResourceId());b.put("timestamp", Instant.now().toString());return ResponseEntity.status(status(e.getCode())).body(b);}
    private HttpStatus status(String code){if(code!=null&&code.endsWith("_NOT_FOUND"))return HttpStatus.NOT_FOUND;if("FORBIDDEN".equals(code))return HttpStatus.FORBIDDEN;if("VALIDATION_FAILED".equals(code))return HttpStatus.UNPROCESSABLE_ENTITY;if("KNOWLEDGE_PERSISTENCE_UNAVAILABLE".equals(code))return HttpStatus.SERVICE_UNAVAILABLE;return HttpStatus.CONFLICT;}
}
