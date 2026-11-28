import { CalendarOutlined, MessageOutlined, ReadOutlined, RobotOutlined } from "@ant-design/icons";
import { Button, Card, Empty, Flex, Image, Tag, Typography } from "antd";
import type { CopilotResponseMetadata, CopilotResponseMode } from "@/features/copilot/contract";
import type { DraftTaskPayload } from "@/features/copilot/uiBlocks";
import type { CopilotMessage } from "@/store/copilot";
import { CopilotTurnActivity } from "./CopilotTurnActivity";
import { MessagePartRenderer } from "./MessagePartRenderer";

const { Text } = Typography;

export type CopilotMessageStreamActions = {
  onPrompt: (prompt: string) => void;
  onDraftTask: (payload: DraftTaskPayload) => void | Promise<void>;
  onOpenEvidence: (messageId: string, evidenceId: string) => void;
  onOpenAnalysis: (runId: string, farmId?: number, fieldId?: string) => void;
  onSelectField: (message: CopilotMessage, fieldId: string) => void;
  onOpenDetail: (route: string) => void;
  onSetDraft: (prompt: string) => void;
};

export type CopilotMessageStreamProps = CopilotMessageStreamActions & {
  messages: CopilotMessage[];
  variant: "drawer" | "workspace";
  sending: boolean;
  activeAssistantId?: string;
  preparationLabel?: string;
  busy?: boolean;
};

function responseClassName(message: CopilotMessage): string {
  return message.role === "assistant" && message.responseMode
    ? `farmap-copilot__message--${message.responseMode.toLowerCase()}`
    : "";
}

function responseModeIcon(mode?: CopilotResponseMode) {
  if (mode === "AGENT") return <RobotOutlined />;
  if (mode === "GUIDED") return <CalendarOutlined />;
  if (mode === "KNOWLEDGE") return <ReadOutlined />;
  return <MessageOutlined />;
}

function ResponseMetadata({ metadata, onSetDraft }: { metadata?: CopilotResponseMetadata; onSetDraft: (prompt: string) => void }) {
  if (!metadata) return null;
  const cards = metadata.cards ?? [];
  const suggestions = (metadata.suggestions ?? []).filter((suggestion) =>
    suggestion.text !== "查看知识依据"
    && (metadata.responseType !== "KNOWLEDGE" || suggestion.text !== "结合当前地块解释"));
  const actions = (metadata.actions ?? []).filter((action) => action.enabled);
  if (!cards.length && !suggestions.length && !actions.length) return null;
  return (
    <div className="farmap-copilot__response-blocks">
      {cards.length > 0 && (
        <Flex vertical gap={6} className="farmap-copilot__response-cards">
          {cards.map((card) => (
            <Card key={card.id} size="small" className="farmap-copilot__response-card" title={card.title}>
              <Text>{card.content}</Text>
            </Card>
          ))}
        </Flex>
      )}
      {suggestions.length > 0 && (
        <Flex wrap gap={6} className="farmap-copilot__response-suggestions">
          {suggestions.map((suggestion) => (
            <Button key={suggestion.id} size="small" onClick={() => onSetDraft(suggestion.text)}>{suggestion.text}</Button>
          ))}
        </Flex>
      )}
      {actions.length > 0 && (
        <Flex wrap gap={6} className="farmap-copilot__response-actions">
          {actions.map((action) => (
            <Tag key={action.id} color="green">{action.label}</Tag>
          ))}
        </Flex>
      )}
    </div>
  );
}

function MessageItem({
  message,
  pending,
  preparationLabel,
  busy,
  actions,
}: {
  message: CopilotMessage;
  pending: boolean;
  preparationLabel?: string;
  busy?: boolean;
  actions: CopilotMessageStreamActions;
}) {
  const uiBlocks = message.uiBlocks?.map((block) => block.summary === message.content ? { ...block, summary: undefined } : block);
  return (
    <div className={`farmap-copilot__message farmap-copilot__message--${message.role} ${responseClassName(message)}`}>
      {message.role === "assistant" && message.responseLabel && (
        <div className="farmap-copilot__message-mode">
          {responseModeIcon(message.responseMode)}
          <span>{message.responseLabel}</span>
        </div>
      )}
      {message.role === "assistant" ? (
        <MessagePartRenderer
          parts={message.parts}
          content={message.content}
          presentation={message.presentation}
          uiBlocks={uiBlocks}
          activity={message.activity}
          pending={pending}
          answering={Boolean(message.content || message.parts?.length || message.streamError)}
          preparing={pending ? preparationLabel : undefined}
          error={message.streamError}
          busy={busy}
          onPrompt={actions.onPrompt}
          onDraftTask={actions.onDraftTask}
          onOpenEvidence={(evidenceId) => actions.onOpenEvidence(message.id, evidenceId)}
          onOpenAnalysis={actions.onOpenAnalysis}
          onSelectField={(fieldId) => actions.onSelectField(message, fieldId)}
          onOpenDetail={actions.onOpenDetail}
        />
      ) : message.content && <p>{message.content}</p>}
      {message.imageAttachments && message.imageAttachments.length > 0 && (
        <div className="farmap-copilot__message-images">
          <Image.PreviewGroup>
            {message.imageAttachments.map((image) => (
              <Image
                key={image.id}
                src={image.url}
                alt={image.name}
                width={148}
                height={104}
                preview={{ mask: "查看图片" }}
                fallback="data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs="
                style={{ objectFit: "cover", borderRadius: 8, border: "1px solid #d9e8df" }}
              />
            ))}
          </Image.PreviewGroup>
        </div>
      )}
      {message.role === "assistant" && message.referenceImages && message.referenceImages.length > 0 && (
        <div
          className="farmap-copilot__reference-images"
          style={{ display: "flex", gap: 8, overflowX: "auto", maxWidth: "100%", paddingTop: 8, paddingBottom: 2 }}
        >
          <Image.PreviewGroup>
            {message.referenceImages.map((image) => (
              <div key={image.refId} data-reference-image="true" style={{ flex: "0 0 92px", minWidth: 0 }}>
                <Image
                  src={image.imageUrl}
                  alt={image.label}
                  width={92}
                  height={68}
                  fallback="data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs="
                  onError={(event) => {
                    event.currentTarget.closest("[data-reference-image='true']")?.setAttribute("hidden", "true");
                  }}
                  style={{ objectFit: "cover", borderRadius: 6, border: "1px solid #d9e8df" }}
                />
                <small style={{ display: "block", marginTop: 3, color: "#6b7280", lineHeight: 1.35, wordBreak: "break-all" }}>{image.label}</small>
              </div>
            ))}
          </Image.PreviewGroup>
        </div>
      )}
      {message.role === "assistant" && message.referenceImageNotice && (
        <small className="farmap-copilot__reference-image-note">{message.referenceImageNotice}</small>
      )}
      {message.role === "assistant" && !message.presentation?.followUps?.length && (
        <ResponseMetadata metadata={message.responseMeta} onSetDraft={actions.onSetDraft} />
      )}
      {message.role === "assistant" && !!message.presentation?.followUps?.length
        && !message.parts?.some((part) => part.type === "follow_ups") && (
          <div className="farmap-response__followups" aria-label="下一步">
            {message.presentation.followUps.map(({ label, prompt }) => (
              <button type="button" key={prompt} onClick={() => actions.onSetDraft(prompt)}>{label}</button>
            ))}
          </div>
        )}
    </div>
  );
}

export function CopilotMessageStream({
  messages,
  variant,
  sending,
  activeAssistantId,
  preparationLabel,
  busy,
  ...actions
}: CopilotMessageStreamProps) {
  const visibleMessages = messages.filter((message) => (variant !== "workspace" || message.id !== "welcome")
    && (message.content || message.parts?.length || message.streamError || message.uiBlocks?.length
      || message.activity?.tools.length || message.activity?.thinking || message.activity?.knowledge
      || (sending && message.id === activeAssistantId)));

  return (
    <Flex vertical gap={12} className="farmap-copilot__messages">
      {messages.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有对话" />
      ) : (
        visibleMessages.map((message) => (
          <MessageItem
            key={message.id}
            message={message}
            pending={sending && message.id === activeAssistantId}
            preparationLabel={preparationLabel}
            busy={busy}
            actions={actions}
          />
        ))
      )}
      {sending && !messages.some((message) => message.id === activeAssistantId) && (
        <div className="farmap-copilot__message farmap-copilot__message--assistant">
          <CopilotTurnActivity pending answering={false} preparing={preparationLabel} />
        </div>
      )}
    </Flex>
  );
}
