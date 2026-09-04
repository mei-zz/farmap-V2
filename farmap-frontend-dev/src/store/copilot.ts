import { create } from "zustand";

export type CopilotMessage = {
  id: string;
  role: "user" | "assistant";
  content: string;
};

type CopilotStore = {
  isOpen: boolean;
  draft: string;
  messages: CopilotMessage[];
  open: () => void;
  close: () => void;
  toggle: () => void;
  setDraft: (draft: string) => void;
  addMessage: (message: Omit<CopilotMessage, "id">) => void;
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
  messages: initialMessages,
  open: () => set({ isOpen: true }),
  close: () => set({ isOpen: false }),
  toggle: () => set((state) => ({ isOpen: !state.isOpen })),
  setDraft: (draft) => set({ draft }),
  addMessage: (message) =>
    set((state) => ({
      messages: [...state.messages, { ...message, id: `${message.role}-${Date.now()}` }],
    })),
  clearMessages: () => set({ messages: initialMessages }),
}));
