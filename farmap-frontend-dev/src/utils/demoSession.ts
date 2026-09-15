export const isDemoModeEnabled =
  import.meta.env.DEV && import.meta.env.VITE_ENABLE_DEMO_MODE === "true";

export type DemoSession = {
  mode: "demo";
  user: {
    name: "演示用户";
    role: "viewer";
  };
  farm: {
    id: number;
    name: string;
    address: string;
  };
  startedAt: string;
};

const DEMO_SESSION_KEY = "farmap_demo_session";

function isValidDemoSession(value: unknown): value is DemoSession {
  if (!value || typeof value !== "object") return false;
  const session = value as Partial<DemoSession>;
  return (
    session.mode === "demo" &&
    session.user?.name === "演示用户" &&
    session.user?.role === "viewer" &&
    typeof session.farm?.id === "number" &&
    typeof session.farm?.name === "string" &&
    typeof session.farm?.address === "string"
  );
}

export function getDemoSession(): DemoSession | undefined {
  if (!isDemoModeEnabled) return undefined;
  const raw = sessionStorage.getItem(DEMO_SESSION_KEY);
  if (!raw) return undefined;

  try {
    const session: unknown = JSON.parse(raw);
    return isValidDemoSession(session) ? session : undefined;
  } catch {
    return undefined;
  }
}

export function createDemoSession(): DemoSession | undefined {
  if (!isDemoModeEnabled) return undefined;

  const session: DemoSession = {
    mode: "demo",
    user: {
      name: "演示用户",
      role: "viewer",
    },
    farm: {
      id: 9001,
      name: "云阳智慧果园",
      address: "重庆市云阳县",
    },
    startedAt: new Date().toISOString(),
  };
  sessionStorage.setItem(DEMO_SESSION_KEY, JSON.stringify(session));
  return session;
}

export function clearDemoSession(): void {
  sessionStorage.removeItem(DEMO_SESSION_KEY);
}
