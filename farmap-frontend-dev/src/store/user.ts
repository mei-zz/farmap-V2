import { create } from "zustand";

export type FarmPreviewType = {
  id: number;
  name: string;
};

export type UserStoreState = {
  username: string;
  role: "guest" | "user" | "admin" | "expert";
  farms: FarmPreviewType[];
};
type UserStoreActions = {
  login: (user: UserStoreState) => void;
  logout: () => void;
};
type UserStore = UserStoreState & UserStoreActions;

const initUserStore: UserStoreState = {
  username: "",
  role: "guest",
  farms: [],
};
export const useUserStore = create<UserStore>((set) => ({
  ...initUserStore,

  login: (user) =>
    set(() => ({
      ...user,
    })),
  logout: () => set(() => initUserStore),
}));
