## 6.3 Repeatable golden agricultural Agent evaluation

The repeatable suite lives in `zhgy/zhgy/src/test/java/com/mei/zhgy/evaluation/PlumAgentGoldenEvaluationTest.java`.
Its labels and replay scripts are in `zhgy/zhgy/src/test/resources/evaluation/plum-agent-golden-v1.json`.
Each run writes the machine-readable result to `zhgy/zhgy/target/evaluation-results/plum-agent-golden-eval.json`.

### What is measured

| Dimension | Production seam | Reported denominator/metrics |
| --- | --- | --- |
| Multihop tool use | Real Pi worker + `PiAgentRuntime` + `AgriculturalToolRegistry`; scripted local OpenAI-compatible HTTP provider | Expected/observed tool-call slots, precision, recall, ordered-hop accuracy, context carried between hops |
| Parameter recovery | Real tool errors are returned to the Pi context, then the scripted replay retries with corrected arguments | Field accuracy and the number of rejected calls successfully corrected |
| Retrieval calibration | `EvidenceFusionService.fuse` versus native scores with the same modality and source reliability weights | Recall@1/3/5 and nDCG@5 with graded labels, plus per-case ranked IDs |
| Typed image facts | Actual `VisualObservation.parse` (reflection because it is package-private) | Typed-structure accuracy, symptom precision/recall, image-clear agreement |
| Evidence faithfulness | `EvidenceReferenceValidator.validateOrThrow` and labeled claim-to-source facts | Citation acceptance and rejection, factual-support match rate, and the supported-claim denominator |
| PHI safety | `PlumAgronomyRuleEngine.assessSpray` with verified label provenance | Verified PHI violation block rate, safe-control false-block rate, missing-PHI safe-outcome rate |
| Missing evidence | `EvidenceSufficiencyEvaluator` and `CopilotResponsePolicy.choose` | Refusal accuracy, clarification accuracy, combined safe-outcome rate |
| UI contract | `AgriculturalToolRegistry.tools(...).render_ui_block` + `CopilotUiBlockValidator` | First-pass schema accuracy, component match rate, invalid rejection rate |

Recall@K counts a relevant ID in the top K. nDCG@5 uses the fixture's graded relevance
(0-3), with `(2^grade - 1) / log2(rank + 1)` and the ideal ordering as the denominator.
Every metric reports its case/judgment denominator; zero-denominator live-provider results
are marked `NOT_RUN`, never converted into a score.

### Controlled replay versus a real provider

The tool-call section is a **controlled replay**. A local HTTP fixture returns the scripted
tool calls, while the real Node/Pi worker, Java runtime, schemas and tool handlers execute.
It therefore validates runtime behavior and parameter normalization, not live model
tool-selection quality. The real-provider section has status `NOT_RUN` and denominator `0`;
this suite does not read `.env` or make a provider-quality claim. Run a separately approved
live experiment if a model-quality number is needed.

The remaining sections use deterministic labeled fixtures and production validators. They
are contract/safety evaluations, not field accuracy or semantic-entailment experiments.
Evidence faithfulness checks citation IDs and labeled factual fields such as rainfall and
registered phenology. Attribution of arbitrary prose still requires human annotation.

### Reproduce offline

From `zhgy/zhgy`:

```powershell
& '..\..\.tools\apache-maven-3.9.16\bin\mvn.cmd' -o -Dtest=PlumAgentGoldenEvaluationTest test
```

If the Codex sandbox uses an isolated Maven home, point it at the populated local cache while
keeping offline mode:

```powershell
& '..\..\.tools\apache-maven-3.9.16\bin\mvn.cmd' -o `
  '-Dmaven.repo.local=C:\Users\Administrator\.m2\repository' `
  -Dtest=PlumAgentGoldenEvaluationTest test
```

The test first validates that every metric section has at least two labeled cases, then runs
the evaluators and writes the JSON report. Add a case by extending the corresponding array in
the fixture; keep its labeled expected outcome and denominator inputs explicit.
