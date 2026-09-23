import { create } from "zustand";
import type { OverviewCopilotContext } from "@/features/overview/copilot/context";
import type { CopilotResponseMetadata, CopilotResponseMode } from "@/features/copilot/contract";

export type CopilotMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
  /**
   * Presentation is intentionally a UI concern. The server stays the source
   * of truth for intent, authorization and tool routing; this only prevents
   * a short answer from being rendered as an oversized decision card.
   */
  responseMode?: CopilotResponseMode;
  responseLabel?: string;
  responseMeta?: CopilotResponseMetadata;
  imageAttachments?: Array<{ id: string; name: string; url: string }>;
  knowledgeCount?: number;
  referenceImages?: Array<{ refId: string; label: string; imageUrl: string; match?: string }>;
  referenceImageNotice?: string;
};

type CopilotStore = {
  isOpen: boolean;
  draft: string;
  context?: OverviewCopilotContext;
  messages: CopilotMessage[];
  open: () => void;
  close: () => void;
  toggle: () => void;
  setDraft: (draft: string) => void;
  setContext: (context: OverviewCopilotContext | undefined) => void;
  addMessage: (message: Omit<CopilotMessage, "id">) => void;
  upsertMessage: (message: CopilotMessage) => void;
  setMessages: (messages: CopilotMessage[]) => void;
  clearMessages: () => void;
};

const initialMessages: CopilotMessage[] = [
  {
    id: "welcome",
    role: "assistant",
    content: "我已准备好。后续会基于当前农场、地块、天气和诊断上下文协助决策。",
  },
];

export const useCopilotStore = create<CopilotStore>((set) => ({
  isOpen: false,
  draft: "",
  context: undefined,
  messages: initialMessages,
  open: () => set({ isOpen: true }),
  close: () => set({ isOpen: false }),
  toggle: () => set((state) => ({ isOpen: !state.isOpen })),
  setDraft: (draft) => set({ draft }),
  setContext: (context) => set({ context }),
  addMessage: (message) =>
    set((state) => ({
      messages: [...state.messages, { ...message, id: `${message.role}-${Date.now()}` }],
    })),
  upsertMessage: (message) => set((state) => ({
    messages: state.messages.some((item) => item.id === message.id)
      ? state.messages.map((item) => item.id === message.id ? message : item)
      : [...state.messages, message],
  })),
  setMessages: (messages) => set({ messages }),
  clearMessages: () => set({ messages: initialMessages }),
}));
