import { useEffect, useState } from "react";
import { confirmCopilotMemoryProposal, isCopilotMemoryProposalConfirmed } from "../service";
import type { AgentUiBlock } from "../uiBlocks";

export type MemoryProposalData = {
  proposalId: string;
  displayText: string;
  scope: "FARM" | "FIELD";
  confirmed: boolean;
};

export function MemoryProposalCard({ block }: { block: AgentUiBlock }) {
  const proposal = block.data?.memoryProposal;
  const [state, setState] = useState<"idle" | "saving" | "saved" | "failed">("idle");
  const proposalId = proposal?.proposalId;
  const confirmed = proposal?.confirmed;
  useEffect(() => {
    if (!proposalId || confirmed) return;
    let visible = true;
    void isCopilotMemoryProposalConfirmed(proposalId).then((saved) => {
      if (visible && saved) setState("saved");
    }).catch(() => undefined);
    return () => { visible = false; };
  }, [proposalId, confirmed]);
  if (!proposal) return null;
  const saved = proposal.confirmed || state === "saved";
  async function confirm(id: string) {
    setState("saving");
    try {
      await confirmCopilotMemoryProposal(id);
      setState("saved");
    } catch {
      setState("failed");
    }
  }
  return <section aria-label="确认长期记忆">
    <p>{proposal.displayText}</p>
    <p>{proposal.scope === "FIELD" ? "保存到该地块" : "保存到该农场"} · 用户自报资料</p>
    {saved ? <p role="status">已保存到长期记忆。</p> : <button type="button" className="farmap-block__primary"
      disabled={state === "saving"} onClick={() => void confirm(proposal.proposalId)}>
      {state === "saving" ? "正在保存…" : "确认保存"}
    </button>}
    {state === "failed" && <p role="alert">暂时无法保存，请检查权限后重试。</p>}
  </section>;
}
