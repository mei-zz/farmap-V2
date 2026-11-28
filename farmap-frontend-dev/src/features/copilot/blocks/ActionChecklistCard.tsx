import { useEffect, useState } from "react";
import { CircleAlert, Clock3, ListChecks } from "lucide-react";
import type { AgentUiBlock, DraftTaskPayload } from "../uiBlocks";

export type ChecklistPriority = "urgent" | "routine" | "preventive";

export type ChecklistItem = {
  id: string;
  title: string;
  detail?: string;
  priority: ChecklistPriority;
  prerequisites?: string[];
  scheduledFor?: string;
  draftTask?: DraftTaskPayload;
};

export type ActionChecklistData = {
  items: ChecklistItem[];
  note?: string;
};

const emptyItems: ChecklistItem[] = [];
const priorityLabels: Record<ChecklistPriority, string> = { urgent: "紧急", routine: "常规", preventive: "预防" };

type Props = {
  block: AgentUiBlock;
  onDraftTask?: (payload: DraftTaskPayload) => void;
  onDraftBatch?: (payload: DraftTaskPayload[]) => void;
};

export function ActionChecklistCard({ block, onDraftTask, onDraftBatch }: Props) {
  const checklist = block.data?.actionChecklist;
  const items = checklist?.items ?? emptyItems;
  const [selected, setSelected] = useState<Set<string>>(new Set());

  useEffect(() => {
    setSelected((current) => new Set([...current].filter((id) => items.some((item) => item.id === id))));
  }, [items]);

  if (!items.length) return <p>当前没有可执行的结构化农事步骤。</p>;

  const selectedItems = items.filter((item) => selected.has(item.id));
  const tasks = selectedItems.flatMap((item) => {
    if (item.draftTask) return [item.draftTask];
    const fieldId = block.data?.fieldId;
    return fieldId && item.detail ? [{ fieldId, title: item.title, detail: item.detail, scheduledFor: item.scheduledFor }] : [];
  });
  const actionable = tasks.length > 0 && tasks.length === selectedItems.length
    && (tasks.length === 1 ? Boolean(onDraftTask) : Boolean(onDraftBatch));
  const submit = () => {
    if (!actionable) return;
    if (tasks.length === 1) onDraftTask?.(tasks[0]);
    else onDraftBatch?.(tasks);
  };

  const toggle = (id: string) => {
    setSelected((current) => {
      const next = new Set(current);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  return <section className="farmap-checklist" aria-label="可执行农事步骤清单">
    {checklist?.note && <p className="farmap-checklist__note">{checklist.note}</p>}
    <ul>{items.map((item) => <li key={item.id} data-selected={selected.has(item.id)}>
      <label className="farmap-checklist__select">
        <input type="checkbox" checked={selected.has(item.id)} onChange={() => toggle(item.id)} aria-label={`选择 ${item.title}`} />
        <span className="farmap-checklist__check" aria-hidden="true"><ListChecks size={14} /></span>
        <span className="farmap-checklist__content">
          <span className="farmap-checklist__heading">
            <strong>{item.title}</strong>
            <span className={`farmap-checklist__priority is-${item.priority}`}>{priorityLabels[item.priority]}</span>
          </span>
          {item.detail && <small>{item.detail}</small>}
          {item.prerequisites?.length ? <span className="farmap-checklist__preconditions">
            <CircleAlert size={12} aria-hidden="true" />前置条件：{item.prerequisites.join("；")}
          </span> : null}
          {item.scheduledFor && <span className="farmap-checklist__schedule"><Clock3 size={12} aria-hidden="true" />计划 {item.scheduledFor}</span>}
        </span>
      </label>
    </li>)}</ul>
    <div className="farmap-checklist__footer">
      <span aria-live="polite">已选 {selectedItems.length} / {items.length} 项</span>
      <button type="button" className="farmap-block__primary" disabled={!actionable} onClick={submit}>
        转为待审批农事任务
      </button>
    </div>
    {tasks.length < selectedItems.length && <p role="status">部分步骤缺少地块或任务说明，请先补充。</p>}
  </section>;
}
