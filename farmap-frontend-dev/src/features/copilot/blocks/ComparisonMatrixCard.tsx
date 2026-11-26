import type { AgentUiBlock, DraftTaskPayload } from "../uiBlocks";

export type ComparisonOption = {
  id: string;
  label: string;
  summary?: string;
  recommended?: boolean;
  values: Record<string, string>;
  draftTask?: DraftTaskPayload;
};

export type ComparisonRow = {
  id: string;
  label: string;
  values: Record<string, string>;
};

export type ComparisonMatrixData = {
  options: ComparisonOption[];
  rows: ComparisonRow[];
  note?: string;
};

type Props = {
  block: AgentUiBlock;
  onDraftTask?: (payload: DraftTaskPayload) => void;
};

export function ComparisonMatrixCard({ block, onDraftTask }: Props) {
  const matrix = block.data?.comparisonMatrix;
  if (!matrix?.options.length || !matrix.rows.length) return <p>暂无可比较的结构化方案。</p>;

  const taskFor = (option: ComparisonOption) => {
    if (option.draftTask) return option.draftTask;
    const fieldId = block.data?.fieldId;
    if (!fieldId) return undefined;
    const detail = matrix.rows
      .map((row) => `${row.label}：${option.values[row.id] ?? row.values[row.label] ?? "未提供"}`)
      .join("\n")
      .slice(0, 880);
    return {
      fieldId,
      title: `执行方案：${option.label}`.slice(0, 120),
      detail: detail || `选用方案：${option.label}`,
    } satisfies DraftTaskPayload;
  };

  return <section className="farmap-comparison" aria-label="农事方案对比矩阵">
    <div className="farmap-comparison__scroller">
      <table>
        <caption>{matrix.note ?? "按当前地块条件比较可选方案；成本和见效速度以业务资料为准。"}</caption>
        <thead><tr><th scope="col">对比项</th>{matrix.options.map((option) => <th key={option.id} scope="col">
          <span>{option.label}</span>
          {option.recommended && <small className="farmap-comparison__recommended">推荐</small>}
          {option.summary && <small>{option.summary}</small>}
        </th>)}</tr></thead>
        <tbody>{matrix.rows.map((row) => <tr key={row.id}>
          <th scope="row">{row.label}</th>
          {matrix.options.map((option) => <td key={option.id}>{option.values[row.id] ?? row.values[option.id] ?? row.values[option.label] ?? "—"}</td>)}
        </tr>)}</tbody>
        <tfoot><tr><th scope="row">下一步</th>{matrix.options.map((option) => {
          const task = taskFor(option);
          return <td key={option.id}>{task ? <button type="button" className="farmap-block__primary"
            disabled={!onDraftTask} onClick={() => onDraftTask?.(task)}>选用此方案生成任务草稿</button> : <span>缺少地块，无法生成草稿</span>}</td>;
        })}</tr></tfoot>
      </table>
    </div>
  </section>;
}
