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
};
