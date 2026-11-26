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
};

export function ActionChecklistCard({ block, onDraftTask }: Props) {
  const checklist = block.data?.actionChecklist;
  const items = checklist?.items ?? emptyItems;
  const [selected, setSelected] = useState<Set<string>>(new Set());

  useEffect(() => {
    setSelected((current) => new Set([...current].filter((id) => items.some((item) => item.id === id))));
  }, [items]);

  if (!items.length) return <p>当前没有可执行的结构化农事步骤。</p>;

  const selectedItems = items.filter((item) => selected.has(item.id));
  const fieldId = selectedItems.flatMap((item) => item.draftTask?.fieldId ? [item.draftTask.fieldId] : []).at(0) ?? block.data?.fieldId;
  const scheduledFor = selectedItems.flatMap((item) => item.scheduledFor ?? item.draftTask?.scheduledFor ?? []).sort()[0];
  const task: DraftTaskPayload | undefined = fieldId && selectedItems.length ? {
    fieldId,
    title: selectedItems.length === 1
      ? selectedItems[0].draftTask?.title ?? selectedItems[0].title
      : `${block.title}（${selectedItems.length}项）`.slice(0, 120),
    detail: selectedItems.map((item) => item.draftTask?.detail ?? [item.title, item.detail].filter(Boolean).join("："))
      .join("\n").slice(0, 880),
    scheduledFor,
  } : undefined;

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
      <button type="button" className="farmap-block__primary" disabled={!task || !onDraftTask} onClick={() => task && onDraftTask?.(task)}>
        转为待审批农事任务
      </button>
    </div>
    {!fieldId && selectedItems.length > 0 && <p role="status">当前清单缺少地块编号，暂不能生成结构化任务草稿。</p>}
  </section>;
}
