import { useMemo, type ComponentProps } from "react";
import { Check, LoaderCircle, Wrench } from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { AgentUiBlocks } from "@/features/copilot/AgentUiBlocks";
import { chatToolLabel, type ChatActivity } from "@/features/copilot/chatActivity";
import { isExecutionBlock, type MessagePresentation } from "@/features/copilot/presentation";
import {
  messagePartCitations,
  type CitationRef,
  type MessageFollowUpsPart,
  type MessagePart,
  type MessageTextPart,
  type MessageThinkingPart,
  type MessageToolCallPart,
  type MessageUiBlockPart,
} from "@/features/copilot/messageParts";
import type { AgentUiBlock, DraftTaskPayload } from "@/features/copilot/uiBlocks";
import { CopilotTurnActivity } from "./CopilotTurnActivity";

export type MessageBlockActions = {
  onPrompt: (message: string) => void;
  onSelectField?: (id: string) => void;
  onOpenDetail?: (route: string) => void;
  onOpenAnalysis?: (runId: string, farmId?: number, fieldId?: string) => void;
  onDraftTask?: (payload: DraftTaskPayload) => void | Promise<void>;
  onOpenEvidence?: (evidenceId: string) => void;
  busy?: boolean;
};

type MessagePartRendererProps = MessageBlockActions & {
  parts?: MessagePart[];
  content: string;
  presentation?: MessagePresentation;
  uiBlocks?: AgentUiBlock[];
  activity?: ChatActivity;
  pending: boolean;
  answering: boolean;
  preparing?: string;
  error?: string;
};

type RemarkPluginList = NonNullable<ComponentProps<typeof ReactMarkdown>["remarkPlugins"]>;

type MarkdownAstNode = {
  type: string;
  value?: string;
  url?: string;
  children?: MarkdownAstNode[];
  data?: unknown;
};

type RenderUnit =
  | { key: string; kind: "part"; part: Exclude<MessagePart, MessageUiBlockPart> }
  | { key: string; kind: "blocks"; parts: MessageUiBlockPart[] };

const citationPattern = /\[(E?[1-9][0-9]{0,3})\]/gu;
const citationHref = /^#copilot-evidence-(E[1-9][0-9]{0,3})$/u;

function citationLookup(citations: readonly CitationRef[] | undefined): Map<string, CitationRef> {
  const lookup = new Map<string, CitationRef>();
  for (const citation of citations ?? []) {
    lookup.set(citation.evidenceId, citation);
    lookup.set(String(citation.index), citation);
  }
  return lookup;
}

function citationFor(reference: string, lookup: ReadonlyMap<string, CitationRef>): CitationRef | undefined {
  return lookup.get(reference);
}

function citationNodes(text: string, lookup: ReadonlyMap<string, CitationRef>): MarkdownAstNode[] | undefined {
  if (!text || !lookup.size) return undefined;
  const nodes: MarkdownAstNode[] = [];
  let cursor = 0;
  for (const match of text.matchAll(citationPattern)) {
    const index = match.index;
    const citation = citationFor(match[1], lookup);
    if (index === undefined || !citation) continue;
    if (index > cursor) nodes.push({ type: "text", value: text.slice(cursor, index) });
    nodes.push({
      type: "link",
      url: `#copilot-evidence-${citation.evidenceId}`,
      children: [{ type: "text", value: match[0] }],
      data: { hProperties: { className: "farmap-citation", "data-evidence-id": citation.evidenceId } },
    });
    cursor = index + match[0].length;
  }
  if (!nodes.length) return undefined;
  if (cursor < text.length) nodes.push({ type: "text", value: text.slice(cursor) });
  return nodes;
}

function rewriteCitations(node: MarkdownAstNode, lookup: ReadonlyMap<string, CitationRef>) {
  if (!node.children?.length || ["code", "inlineCode", "html", "link"].includes(node.type)) return;
  const children: MarkdownAstNode[] = [];
  for (const child of node.children) {
    if (child.type === "text" && child.value) {
      const replacement = citationNodes(child.value, lookup);
      if (replacement) {
        children.push(...replacement);
        continue;
      }
    }
    rewriteCitations(child, lookup);
    children.push(child);
  }
  node.children = children;
}

/** Remark plugin: turns public citation markers into accessible evidence buttons. */
function remarkEvidenceCitations(lookup: ReadonlyMap<string, CitationRef>) {
  return () => (tree: MarkdownAstNode) => rewriteCitations(tree, lookup);
}

export function MarkdownMessage({
  text,
  citations,
  onEvidenceClick,
}: {
  text: string;
  citations?: readonly CitationRef[];
  onEvidenceClick?: (evidenceId: string) => void;
}) {
  const lookup = useMemo(() => citationLookup(citations), [citations]);
  const plugins = useMemo<RemarkPluginList>(() => {
    const list: RemarkPluginList = [remarkGfm];
    if (lookup.size) list.push(remarkEvidenceCitations(lookup) as unknown as RemarkPluginList[number]);
    return list;
  }, [lookup]);

  return (
    <div className="farmap-copilot__markdown-message">
      <ReactMarkdown
        remarkPlugins={plugins}
        components={{
          p: ({ children }) => <p style={{ margin: "0 0 8px" }}>{children}</p>,
          ul: ({ children }) => <ul style={{ margin: "0 0 8px", paddingLeft: 22 }}>{children}</ul>,
          ol: ({ children }) => <ol style={{ margin: "0 0 8px", paddingLeft: 22 }}>{children}</ol>,
          strong: ({ children }) => <strong>{children}</strong>,
          code: ({ children, ...props }) => (
            <code {...props} style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}>{children}</code>
          ),
          a: ({ children, href, title }) => {
            const match = href?.match(citationHref);
            if (match && lookup.has(match[1])) {
              return (
                <button
                  type="button"
                  className="farmap-citation"
                  data-evidence-id={match[1]}
                  title={title}
                  aria-label={`查看证据 ${match[1]}`}
                  onClick={() => onEvidenceClick?.(match[1])}
                >
                  {children}
                </button>
              );
            }
            return <a href={href} title={title} target="_blank" rel="noreferrer noopener">{children}</a>;
          },
          table: ({ children }) => (
            <div style={{ maxWidth: "100%", overflowX: "auto", marginBottom: 8 }}>
              <table>{children}</table>
            </div>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}

function groupParts(parts: MessagePart[]): RenderUnit[] {
  const units: RenderUnit[] = [];
  let blocks: MessageUiBlockPart[] = [];
  const flushBlocks = () => {
    if (!blocks.length) return;
    units.push({ key: `blocks-${blocks[0].blockKey}`, kind: "blocks", parts: blocks });
    blocks = [];
  };
  for (const part of parts) {
    if (part.type === "ui_block") {
      blocks.push(part);
      continue;
    }
    flushBlocks();
    units.push({ key: part.type === "text" || part.type === "thinking" ? part.id : part.type === "tool_call" ? part.callId : "follow-ups", kind: "part", part });
  }
  flushBlocks();
  return units;
}

function ThinkingPart({ part, pending }: { part: MessageThinkingPart; pending: boolean }) {
  const running = part.status === "running" && pending;
  return (
    <div className="farmap-message-part-thinking" data-part-kind="thinking" data-status={part.status} role="status">
      {running ? <LoaderCircle size={13} aria-hidden="true" /> : <Check size={13} aria-hidden="true" />}
      <span>{part.label ?? (running ? "正在处理" : "处理完成")}</span>
    </div>
  );
}

function ToolPart({ part }: { part: MessageToolCallPart }) {
  const label = part.status === "running" ? "执行中" : part.status === "done" ? "已完成" : "失败";
  return (
    <span className="farmap-message-part-tool" data-part-kind="tool_call" data-status={part.status} title={part.summary}>
      <Wrench size={13} aria-hidden="true" />
      <span>{part.label || chatToolLabel(part.toolName)}</span>
      <small>{label}</small>
    </span>
  );
}

function FollowUpsPart({ part, onPrompt }: { part: MessageFollowUpsPart; onPrompt: (prompt: string) => void }) {
  return (
    <div className="farmap-response__followups" data-part-kind="follow_ups" aria-label="建议追问">
      {part.items.map((item) => (
        <button type="button" key={`${item.label}:${item.prompt}`} onClick={() => onPrompt(item.prompt)}>{item.label}</button>
      ))}
    </div>
  );
}

function StreamError({ message }: { message: string }) {
  return <div className="farmap-message-parts__error" role="alert">{message}</div>;
}

function blockActionProps(props: MessageBlockActions) {
  return {
    onPrompt: props.onPrompt,
    onSelectField: props.onSelectField,
    onOpenDetail: props.onOpenDetail,
    onOpenAnalysis: props.onOpenAnalysis,
    ...(props.onDraftTask ? { onDraftTask: props.onDraftTask } : {}),
    ...(props.onOpenEvidence ? { onOpenEvidence: props.onOpenEvidence } : {}),
  };
}

function uniqueBlocks(blocks: readonly AgentUiBlock[] | undefined): AgentUiBlock[] {
  const seen = new Set<string>();
  return (blocks ?? []).filter((block) => {
    if (seen.has(block.blockId)) return false;
    seen.add(block.blockId);
    return true;
  });
}

function LegacyMessage({
  content,
  presentation,
  uiBlocks,
  activity,
  pending,
  answering,
  preparing,
  error,
  ...actions
}: Omit<MessagePartRendererProps, "parts">) {
  const visibleBlocks = uniqueBlocks(uiBlocks);
  const executionBlocks = visibleBlocks.filter(isExecutionBlock);
  const contentBlocks = visibleBlocks.filter((block) => !isExecutionBlock(block));
  return (
    <>
      <CopilotTurnActivity activity={activity} pending={pending} answering={answering} preparing={preparing} />
      {executionBlocks.length > 0 && <AgentUiBlocks blocks={executionBlocks} {...blockActionProps(actions)} busy={actions.busy} />}
      {content && (
        <div className={presentation?.summary ? "farmap-response__summary" : undefined}>
          <MarkdownMessage text={content} />
        </div>
      )}
      {contentBlocks.length > 0 && <AgentUiBlocks blocks={contentBlocks} {...blockActionProps(actions)} busy={actions.busy} />}
      {presentation?.analysis && <section className="farmap-response__analysis" aria-label="补充分析">
        <MarkdownMessage text={presentation.analysis} />
      </section>}
      {error && <StreamError message={error} />}
    </>
  );
}

export function MessagePartRenderer({ parts, ...props }: MessagePartRendererProps) {
  const citations = useMemo(() => [...messagePartCitations(parts ?? []).values()], [parts]);
  const units = useMemo(() => groupParts(parts ?? []), [parts]);

  if (!parts?.length) return <LegacyMessage {...props} />;

  const summary = props.presentation?.summary;
  const analysis = props.presentation?.analysis;
  const summaryPart = summary ? parts.find((part): part is MessageTextPart => part.type === "text" && part.markdown === summary) : undefined;
  const analysisPart = analysis ? parts.find((part): part is MessageTextPart => part.type === "text" && part.markdown === analysis) : undefined;
  const protocolHasText = parts.some((part) => part.type === "text" && Boolean(part.markdown.trim()));
  const blockIds = new Set(parts.flatMap((part) => part.type === "ui_block" ? [part.block.blockId] : []));
  const missingLegacyBlocks = uniqueBlocks(props.uiBlocks).filter((block) => !blockIds.has(block.blockId));
  const hasTraceParts = parts.some((part) => part.type === "tool_call" || part.type === "thinking");
  const hasLegacyActivity = Boolean(props.activity?.knowledge || props.activity?.thinking || props.activity?.modelStarted);

  return (
    <div className="farmap-message-parts" data-presentation="ordered">
      {(!hasTraceParts || hasLegacyActivity) && <CopilotTurnActivity activity={props.activity} pending={props.pending} answering={props.answering} preparing={props.preparing} showTools={!hasTraceParts} />}
      {summary && !summaryPart && (
        <div className="farmap-response__summary" data-part-kind="summary">
          <MarkdownMessage text={summary} citations={citations} onEvidenceClick={props.onOpenEvidence} />
        </div>
      )}
      {!summary && !protocolHasText && props.content && (
        <div className="farmap-message-parts__text" data-part-kind="legacy_text">
          <MarkdownMessage text={props.content} citations={citations} onEvidenceClick={props.onOpenEvidence} />
        </div>
      )}
      {units.map((unit) => {
        if (unit.kind === "blocks") {
          return (
            <AgentUiBlocks
              key={unit.key}
              blocks={unit.parts.map((part) => part.block)}
              {...blockActionProps(props)}
              busy={props.busy}
            />
          );
        }
        const part = unit.part;
        if (part.type === "text") {
          if (!part.markdown.trim()) return null;
          if (analysisPart?.id === part.id) return (
            <section key={unit.key} className="farmap-response__analysis" aria-label="补充分析" data-part-kind="text" data-part-id={part.id}>
              <MarkdownMessage text={part.markdown} citations={part.citations?.length ? part.citations : citations} onEvidenceClick={props.onOpenEvidence} />
            </section>
          );
          const isSummaryPart = summaryPart?.id === part.id;
          return (
            <div
              key={unit.key}
              className={isSummaryPart ? "farmap-response__summary" : "farmap-message-parts__text"}
              data-part-kind="text"
              data-part-id={part.id}
            >
              <MarkdownMessage
                text={isSummaryPart ? summary ?? part.markdown : part.markdown}
                citations={part.citations?.length ? part.citations : citations}
                onEvidenceClick={props.onOpenEvidence}
              />
            </div>
          );
        }
        if (part.type === "thinking") return <ThinkingPart key={unit.key} part={part} pending={props.pending} />;
        if (part.type === "tool_call") return <ToolPart key={unit.key} part={part} />;
        return <FollowUpsPart key={unit.key} part={part} onPrompt={props.onPrompt} />;
      })}
      {props.error && <StreamError message={props.error} />}
      {missingLegacyBlocks.length > 0 && (
        <AgentUiBlocks blocks={missingLegacyBlocks} {...blockActionProps(props)} busy={props.busy} />
      )}
      {analysis && !analysisPart && <section className="farmap-response__analysis" aria-label="补充分析">
        <MarkdownMessage text={analysis} citations={citations} onEvidenceClick={props.onOpenEvidence} />
      </section>}
    </div>
  );
}
