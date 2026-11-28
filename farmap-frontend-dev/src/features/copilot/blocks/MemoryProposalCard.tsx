import { useEffect, useState } from "react";
import { confirmCopilotMemoryProposal, ignoreCopilotMemoryProposal, readCopilotMemoryProposalStatus } from "../service";
import type { AgentUiBlock, MemoryActionDecision } from "../uiBlocks";

export type MemoryProposalData = {
  proposalId: string;
  displayText: string;
  scope: "FARM" | "FIELD";
  confirmed: boolean;
};

export function MemoryProposalCard({ block }: { block: AgentUiBlock }) {
  const proposal = block.data?.memoryProposal;
  const [state, setState] = useState<"idle" | "saving" | "saved" | "ignored" | "failed">("idle");
  const proposalId = proposal?.proposalId;
  const confirmed = proposal?.confirmed;
  useEffect(() => {
    if (!proposalId || confirmed) return;
    let visible = true;
    void readCopilotMemoryProposalStatus(proposalId).then((status) => {
      if (!visible) return;
      if (status === "CONFIRMED") setState("saved");
      if (status === "IGNORED") setState("ignored");
    }).catch(() => undefined);
    return () => { visible = false; };
  }, [proposalId, confirmed]);
  if (!proposal) return null;
  const saved = proposal.confirmed || state === "saved";
  const actions = block.actions.filter((action) => action.type === "confirm-memory" && action.proposalId === proposal.proposalId);
  async function decide(id: string, decision: MemoryActionDecision) {
    setState("saving");
    try {
      if (decision === "confirm") await confirmCopilotMemoryProposal(id);
      else await ignoreCopilotMemoryProposal(id);
      setState(decision === "confirm" ? "saved" : "ignored");
    } catch {
      setState("failed");
    }
  }
  return <section aria-label="确认长期记忆">
    <p>{proposal.displayText}</p>
    <p>{proposal.scope === "FIELD" ? "保存到该地块" : "保存到该农场"} · 用户自报资料</p>
    {saved ? <p role="status">已保存到长期记忆。</p>
      : state === "ignored" ? <p role="status">已忽略这条资料提案。</p>
      : <div className="farmap-block__actions">
        {(["confirm", "ignore"] as const).map((decision) => <button key={decision} type="button"
          className={decision === "confirm" ? "farmap-block__primary" : undefined}
          disabled={state === "saving"} onClick={() => void decide(proposal.proposalId, decision)}>
          {actions.find((action) => action.decision === decision)?.label ?? (decision === "confirm" ? "确认保存" : "忽略")}
        </button>)}
      </div>}
    {state === "failed" && <p role="alert">暂时无法处理资料提案，请重试。</p>}
  </section>;
}
