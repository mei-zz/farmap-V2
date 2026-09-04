import { create } from "zustand";

type MonitorOwnerStoreState = {
  accessToken: string;
};
type MonitorOwnerStoreActions = {
  setToken: (token: string) => void;
};
type MonitorOwnerStore = MonitorOwnerStoreState & MonitorOwnerStoreActions;

export const useMonitorOwnerStore = create<MonitorOwnerStore>((set) => ({
  accessToken: "",
  setToken: (token) => set(() => ({ accessToken: token })),
}));
