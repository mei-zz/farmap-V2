import { useOutletContext } from "react-router";
import type { UserRole } from "@/routes/navigation";

export type WorkspaceSession = {
  signedIn: boolean;
  role: UserRole;
  openLogin: () => void;
};

export function useWorkspaceSession() {
  return useOutletContext<WorkspaceSession>();
}
