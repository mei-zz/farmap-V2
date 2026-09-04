import type { FarmStoreState } from "@/store/farm";
import type { UserStoreState } from "@/store/user";

// Token operations
function useToken(): string {
  let token = localStorage.getItem("token");
  if (!token) token = "NO_TOKEN_STORED";
  return token;
}
function setToken(token: string): void {
  localStorage.setItem("token", token);
}

// User permanence operations
function useUserStore(): UserStoreState | void {
  const user = localStorage.getItem("user");
  if (!user) return;
  return JSON.parse(user);
}
function setUserStore(user: UserStoreState): void {
  const userStr = JSON.stringify(user);
  localStorage.setItem("user", userStr);
}

// Access token for monitors
interface AccessTokenType {
  accessToken: string;
  expiresAt: number;
}
function useAccessToken(): AccessTokenType | undefined {
  const accessToken = localStorage.getItem("accessToken");
  if (!accessToken) return;
  return JSON.parse(accessToken);
}
function setAccessToken(accessToken: AccessTokenType): void {
  const token = JSON.stringify(accessToken);
  localStorage.setItem("accessToken", token);
}

// Farm
function useFarmStore(): FarmStoreState | undefined {
  const farm = localStorage.getItem("farm");
  if (!farm) return;
  return JSON.parse(farm);
}
function setFarmStore(farm: FarmStoreState): void {
  const farmStr = JSON.stringify(farm);
  localStorage.setItem("farm", farmStr);
}

// Export all performance data
export const permanence = {
  token: {
    useToken,
    setToken,
  },
  user: {
    useUserStore,
    setUserStore,
  },
  farm: {
    useFarmStore,
    setFarmStore,
  },
  accessToken: {
    useAccessToken,
    setAccessToken,
  },
};
