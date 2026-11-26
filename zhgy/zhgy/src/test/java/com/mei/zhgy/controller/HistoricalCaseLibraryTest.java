package com.mei.zhgy.controller;

import com.mei.zhgy.context.BaseContext;
import com.mei.zhgy.service.WorkspaceAdminAccess;
import com.mei.zhgy.service.diagnosis.*;
import com.mei.zhgy.service.field.FarmFieldService;
import com.mei.zhgy.service.historical.*;
import com.mei.zhgy.vo.DiagnosisAnalysisResponse;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HistoricalCaseLibraryTest {
 @Test void returnsOnlyOwnedCompletedProvenanceInRequestedFarm() {
  var candidates=mock(HistoricalCaseCandidateService.class); var diagnoses=mock(DiagnosisPersistenceService.class);
  var fields=mock(FarmFieldService.class); var admin=mock(WorkspaceAdminAccess.class);
  var controller=new HistoricalCaseController(mock(HistoricalCaseIngestionService.class),mock(HistoricalCaseSearchService.class),candidates,diagnoses,fields,admin);
  when(candidates.list(7L,"ELIGIBLE")).thenReturn(List.of(
   Map.of("candidateId","ok","reviewId","r","diagnosisId","d","ownerUserId",7L,"status","ELIGIBLE"),
   Map.of("candidateId","ownerless","reviewId","r","diagnosisId","d"),
   Map.of("candidateId","other","reviewId","r","diagnosisId","d","ownerUserId",8L),
   Map.of("candidateId","wrongfarm","reviewId","r","diagnosisId","wrong","ownerUserId",7L)));
  var record=DiagnosisRecord.builder().diagnosisId("d").sourceRunId("run").userId(7L).farmId(1).fieldId("f").status("COMPLETED").payload(new DiagnosisAnalysisResponse()).build();
  var wrong=DiagnosisRecord.builder().diagnosisId("wrong").sourceRunId("run").userId(7L).farmId(2).status("COMPLETED").payload(new DiagnosisAnalysisResponse()).build();
  when(diagnoses.findRecord("d",7L)).thenReturn(Optional.of(record)); when(diagnoses.findRecord("wrong",7L)).thenReturn(Optional.of(wrong));
  BaseContext.setCurrentId(7L); try {
   var items=(List<?>)controller.library(1,null).getData().get("items"); assertEquals(1,items.size()); assertEquals("ok",((Map<?,?>)items.get(0)).get("id"));
   verify(fields).requireFarm(1,7L); verify(diagnoses,never()).findRecord("d",8L);
  } finally {BaseContext.removeCurrentId();}
 }
 @Test void ingestChecksAdminBeforeCallingService() {
  var ingestion=mock(HistoricalCaseIngestionService.class);var admin=mock(WorkspaceAdminAccess.class);
  doThrow(new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN)).when(admin).requireAdmin();
  var c=new HistoricalCaseController(ingestion,mock(HistoricalCaseSearchService.class),mock(HistoricalCaseCandidateService.class),mock(DiagnosisPersistenceService.class),mock(FarmFieldService.class),admin);
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->c.ingest(List.of(),true)); verifyNoInteractions(ingestion);
 }
}
