export type CopilotIntent =
  | "GENERAL_QA"
  | "FARM_STATUS_QUERY"
  | "FIELD_STATUS_QUERY"
  | "PRIORITIES_QUERY"
  | "HISTORICAL_CASE_QUERY"
  | "DIAGNOSIS_QUERY"
  | "DIAGNOSIS_REQUEST"
  | "RECOMMENDATION_QUERY"
  | "ACTION_REQUEST"
  | "OPERATIONS_STATUS_QUERY"
  | "OUTCOME_QUERY"
  | "KNOWLEDGE_QUERY"
  | "NAVIGATION_REQUEST"
  | "MEMORY_WRITE_REQUEST"
  | "MEMORY_QUERY"
  | "MEMORY_FORGET_REQUEST"
  | "CLARIFICATION_REQUIRED"
  | "UNSUPPORTED";

export type CopilotCapability =
  | "CONTEXTUAL_QA"
  | "FARM_CONTEXT_READ"
  | "FIELD_CONTEXT_READ"
  | "PRIORITIES_READ"
  | "HISTORICAL_CASE_READ"
  | "DIAGNOSIS_CREATE"
  | "DIAGNOSIS_READ"
  | "RECOMMENDATION_READ"
  | "AGENT_START"
  | "AGENT_READ"
  | "OPERATIONS_READ"
  | "OUTCOME_READ"
  | "KNOWLEDGE_SEARCH"
  | "NAVIGATE"
  | "MEMORY_WRITE"
  | "MEMORY_READ"
  | "MEMORY_REVOKE";

export type CopilotResolutionStatus = "RESOLVED" | "CLARIFICATION_REQUIRED" | "NOT_FOUND" | "FORBIDDEN" | "STALE" | "UNSUPPORTED";

export type CopilotResponseMode = "SIMPLE" | "KNOWLEDGE" | "GUIDED" | "AGENT" | "CLARIFICATION";

export type CopilotResponseCard = {
  id: string;
  type: string;
  title: string;
  content: string;
  sourceIds?: string[];
};

export type CopilotSourceReference = {
  id: string;
  title?: string;
  quote?: string;
  sourceId?: string;
  documentId?: string;
  versionId?: string;
  chunkId?: string;
  score?: number;
};

export type CopilotSuggestedQuestion = { id: string; text: string };

export type CopilotAgentAction = { id: string; type: string; label: string; enabled: boolean };

export type CopilotResponseMetadata = {
  schemaVersion: number;
  messageId: string;
  intent?: CopilotIntent;
  resolutionStatus?: CopilotResolutionStatus;
  normalizedQuery?: string;
  mediaNeed?: "NONE" | "BENEFICIAL" | "EXPLICIT";
  responseType: CopilotResponseMode;
  content: string;
  cards?: CopilotResponseCard[];
  sources?: CopilotSourceReference[];
  suggestions?: CopilotSuggestedQuestion[];
  actions?: CopilotAgentAction[];
};

export type CopilotStableContext = {
  farmId?: string;
  fieldId?: string;
  diagnosisId?: string;
  recommendationId?: string;
  runId?: string;
  actionId?: string;
  taskId?: string;
  outcomeId?: string;
};

export type CopilotPageContext = CopilotStableContext & { route?: string };

export type CopilotRouteRequest = {
  message: string;
  sessionId?: string;
  conversationId?: string;
  clientMessageId?: string;
  contextVersion?: number;
  /** Set only after an explicit history restore; server-owned context then wins over the current page. */
  useConversationContext?: boolean;
  pageContext: CopilotPageContext;
};

export type CopilotChatRequest = CopilotRouteRequest & {
  imageUrls?: string[];
};

export type CopilotRouteResult = {
  intent: CopilotIntent;
  resolutionStatus: CopilotResolutionStatus;
  resolvedContext?: CopilotStableContext;
  capability?: CopilotCapability;
  requiresClarification: boolean;
  clarificationQuestion?: string;
  allowedNextAction?: string;
  safeRoute?: string;
  traceId: string;
  confidence: number;
  conversationId?: string;
  turnId?: string;
  memoryStatus?: "PERSISTED" | "DUPLICATE_REPLAY" | "ACTIVE" | "DUPLICATE_ACTIVE" | "REVOKED";
  contextVersion?: number;
  memoryId?: string;
  memoryScope?: "USER" | "FARM" | "FIELD";
  memoryType?: "USER_PREFERENCE" | "FARM_PREFERENCE" | "FIELD_PREFERENCE" | "OPERATING_CONSTRAINT" | "COMMUNICATION_PREFERENCE";
  relevantLongTermMemories?: CopilotMemoryView[];
  originalQuery?: string;
  normalizedQuery?: string;
  mediaNeed?: "NONE" | "BENEFICIAL" | "EXPLICIT";
  requestedAction?: string;
};

export type CopilotMemoryView = {
  memoryId: string;
  scope: "USER" | "FARM" | "FIELD";
  scopeId?: string;
  type: "USER_PREFERENCE" | "FARM_PREFERENCE" | "FIELD_PREFERENCE" | "OPERATING_CONSTRAINT" | "COMMUNICATION_PREFERENCE";
  key: string;
  value: string;
  displayText?: string;
  status: "ACTIVE" | "SUPERSEDED" | "REVOKED";
  sourceConversationId?: string;
  sourceTurnId?: string;
  createdAt?: string;
  updatedAt?: string;
};

export type CopilotConversationTurn = {
  turnId: string;
  role: "USER" | "ASSISTANT" | "SYSTEM_EVENT";
  message: string;
  intent?: CopilotIntent;
  createdAt?: string;
  responseMetadata?: {
    schemaVersion?: number;
    referenceImages?: Array<{ refId: string; label: string; imageUrl: string; match?: string }>;
    referenceImageNotice?: string;
    referenceImageStatus?: string;
    referenceImageMatch?: string;
  };
};

export type CopilotConversationView = {
  conversationId: string;
  status: "ACTIVE" | "CLOSED" | "EXPIRED";
  activeContext?: CopilotStableContext;
  lastIntent?: CopilotIntent;
  summary?: string;
  summaryVersion: number;
  turnCount: number;
  contextVersion: number;
  pendingClarification?: { type: string; question?: string };
  relevantLongTermMemories?: CopilotMemoryView[];
  recentTurns: CopilotConversationTurn[];
  /** Full transcript for the history UI; recentTurns remains the bounded model context. */
  historyTurns?: CopilotConversationTurn[];
};

export type CopilotConversationListItem = {
  conversationId: string;
  title: string;
  status: string;
  lastIntent?: CopilotIntent;
  turnCount: number;
  updatedAt?: string;
  activeContext?: CopilotStableContext;
};
