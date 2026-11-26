import { useEffect, useState } from "react";
import { Avatar, Button, Dropdown, Flex, Layout as AntLayout, Select, Tooltip } from "antd";
import { CalendarOutlined, CloudOutlined, LogoutOutlined, MessageOutlined, UserOutlined } from "@ant-design/icons";
import { NavLink, Outlet, useLoaderData, useLocation, useNavigate, useRevalidator } from "react-router";
import { loadCurrentWeather } from "@/features/agricultural/context";
import { clearCopilotConversation } from "@/features/copilot/service";
import { primaryNavigation, settingsNavigation, type UserRole } from "@/routes/navigation";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import { useUserStore, type UserStoreState } from "@/store/user";
import { formatCurrentLocalDate } from "@/utils/currentDate";
import { clearDemoSession } from "@/utils/demoSession";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import AccountLogin from "@/views/login";
import type { WorkspaceSession } from "./session";
import "./shell.css";
import "./design-system.css";

const { Header, Content } = AntLayout;
const farmStorageKey = "farmap.selected-farm-id";
const localOnlyKeys = ["user", "token", "accessToken", "farm", farmStorageKey];
type FarmField = { fieldId: string; name: string; crop?: string; area?: number; growthStage?: string };
export type SessionLoaderResult = { checkedToken: string; isExpired: boolean };

export async function loadWorkspaceSession(): Promise<SessionLoaderResult> {
  const checkedToken = permanence.token.useToken();
  if (checkedToken === "NO_TOKEN_STORED" || !permanence.user.useUserStore()) return { checkedToken, isExpired: true };
  try {
    const response = await req.get<{ data?: { isExpired?: boolean; isValid?: boolean } }>("/user/validate-token", { Authorization: `Bearer ${checkedToken}` });
    return { checkedToken, isExpired: response.data?.isExpired !== false || response.data?.isValid === false };
  } catch { return { checkedToken, isExpired: true }; }
}

export default function Layout() {
  const loader = useLoaderData<SessionLoaderResult>();
  const [signedInToken, setSignedInToken] = useState("");
  const [loginOpen, setLoginOpen] = useState(false);
  const [date, setDate] = useState(() => new Date());
  const [loadedFields, setLoadedFields] = useState<{ farmId: number; items: FarmField[] }>();
  const revalidator = useRevalidator();
  const navigate = useNavigate();
  const location = useLocation();
  const token = permanence.token.useToken();
  const storedUser = permanence.user.useUserStore();
  const signedIn = Boolean(storedUser && token !== "NO_TOKEN_STORED" &&
    (loader.checkedToken === token ? !loader.isExpired : signedInToken === token));
  const role: UserRole = signedIn && storedUser ? storedUser.role : "guest";
  const farms = signedIn ? storedUser?.farms ?? [] : [];
  const username = signedIn ? storedUser?.username : undefined;
  const context = useAIContextStore((state) => state.context);
  const setContext = useAIContextStore((state) => state.setContext);
  const isCopilotOpen = useCopilotStore((state) => state.isOpen);
  const farmId = farms.find((farm) => farm.id === context.currentFarm.id)?.id
    ?? farms.find((farm) => farm.id === Number(localStorage.getItem(farmStorageKey)))?.id
    ?? farms[0]?.id;
  const fields = loadedFields?.farmId === farmId ? loadedFields?.items ?? [] : [];

  useEffect(() => {
    clearDemoSession();
    const timer = window.setInterval(() => setDate(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (location.pathname !== "/workspace") setContext({ currentPage: location.pathname, pageKey: location.pathname });
  }, [location.pathname, setContext]);

  const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Shanghai", year: "numeric", month: "2-digit", day: "2-digit" }).format(date);
  const referenceDate = context.referenceDate?.slice(0, 10) || today;
  useEffect(() => {
    if (signedIn && !useAIContextStore.getState().context.referenceDate) setContext({ referenceDate: today });
  }, [today, setContext, signedIn]);

  useEffect(() => {
    if (!isCopilotOpen || !signedIn) return;
    if (location.pathname !== "/workspace") navigate("/workspace");
    useCopilotStore.getState().close();
  }, [isCopilotOpen, location.pathname, navigate, signedIn]);

  useEffect(() => {
    if (!signedIn || farmId === undefined) return;
    const farm = farms.find((item) => item.id === farmId);
    if (!farm || farm.id === useAIContextStore.getState().context.currentFarm.id) return;
    setContext({ ...clearFieldContext, currentFarm: { id: farm.id, name: farm.name, address: "" }, currentWeather: undefined });
  }, [farmId, farms, setContext, signedIn]);

  useEffect(() => {
    if (!signedIn || farmId === undefined) return;
    let live = true;
    setContext({ currentWeather: undefined });
    void loadCurrentWeather(farmId, token).then((weather) => {
      if (!live) return;
      setContext({ currentWeather: {
        summary: weather.condition ?? "暂无气象观测",
        temperature: weather.status === "READY" && weather.temperature ? `${weather.temperature}°C` : "—",
        precipitation: weather.rainfallForecast24h !== undefined ? `未来24小时 ${weather.rainfallForecast24h.toFixed(1)} mm` : "暂无降雨信息",
        updatedAt: weather.observedAt ?? weather.retrievedAt ?? new Date().toISOString(),
        rainfall14d: weather.rainfall14d,
        rainfallForecast24h: weather.rainfallForecast24h,
        rainfallProbability24h: weather.rainfallProbability24h,
        rainfallForecast: weather.rainfallForecast,
        rainfallForecastDays: weather.rainfallForecastDays,
        forecast: weather.forecast,
      } });
    }).catch(() => undefined);
    void req.get<{ data?: FarmField[] }>(`/api/farms/${farmId}/fields`, { Authorization: `Bearer ${token}` }).then((response) => {
      if (!live) return;
      setLoadedFields({ farmId, items: (response.data ?? []).filter((field) => field.fieldId && field.name) });
    }).catch(() => { if (live) setLoadedFields({ farmId, items: [] }); });
    return () => { live = false; };
  }, [farmId, setContext, signedIn, token]);

  function chooseFarm(id: number) {
    const farm = farms.find((item) => item.id === id);
    if (!farm) return;
    localStorage.setItem(farmStorageKey, String(id));
    setContext({ ...clearFieldContext, currentFarm: { id, name: farm.name, address: "" }, currentWeather: undefined });
    useCopilotStore.getState().setContext(undefined);
  }

  function chooseField(id: string | undefined) {
    const field = fields.find((item) => item.fieldId === id);
    setContext({ ...clearFieldContext,
      currentField: field ? { id: field.fieldId, name: field.name, area: field.area ?? 0, crop: field.crop ?? "" } : undefined,
      currentCrop: field?.crop ? { name: field.crop, growthStage: field.growthStage } : undefined,
      currentPhenology: field?.growthStage ? { stage: field.growthStage, progress: 0 } : undefined,
    });
    useCopilotStore.getState().setContext(undefined);
  }

  function acceptAccount(nextToken: string, user: UserStoreState) {
    resetWorkspaceIdentity();
    permanence.token.setToken(nextToken);
    permanence.user.setUserStore(user);
    useUserStore.getState().login(user);
    setLoadedFields(undefined);
    setSignedInToken(nextToken);
    setLoginOpen(false);
    void revalidator.revalidate();
  }

  function signOut() {
    resetWorkspaceIdentity();
    localOnlyKeys.forEach((key) => localStorage.removeItem(key));
    useUserStore.getState().logout();
    setLoadedFields(undefined);
    setSignedInToken("");
    navigate("/workspace", { replace: true });
  }

  const session: WorkspaceSession = { signedIn, role, openLogin: () => setLoginOpen(true) };
  const nav = [...primaryNavigation, settingsNavigation].filter((item) => item.roles.includes(role) && (signedIn || item.key === "agent"));
  const inWorkspace = location.pathname === "/workspace";
  const routeRoot = location.pathname.split("/")[1];
  const activeKey = ["workspace", "planning", "diagnosis", "analysis"].includes(routeRoot) ? "agent" : routeRoot === "admin" ? "settings" : routeRoot;

  return <AntLayout className={`farmap-shell agent-shell${signedIn ? " has-account" : ""}`}>
    {signedIn && <aside className="agent-shell__rail" aria-label="主导航">
      <NavLink to="/workspace" className="agent-shell__rail-mark" aria-label="FarMap 工作台">F</NavLink>
      <nav className="agent-shell__nav">{nav.map((item) => {
        const Icon = item.icon;
        return <Tooltip key={item.key} title={item.label} placement="right"><NavLink to={item.path} aria-label={item.label}
          className={`agent-shell__nav-link ${activeKey === item.key || (item.key === "agent" && inWorkspace) ? "is-active" : ""}`}><Icon /></NavLink></Tooltip>;
      })}</nav>
    </aside>}
    <AntLayout className="farmap-main agent-shell__main">
      <Header className="farmap-header agent-shell__header">
        <div className="agent-shell__header-left">
          <NavLink to="/workspace" className="agent-shell__brand">FarMap <span>农事智能工作台</span></NavLink>
          {signedIn && <div className="agent-shell__scope" aria-label="共享农场上下文">
            <Select aria-label="当前农场" className="agent-shell__farm-select" value={farmId} placeholder="暂无农场"
              options={farms.map(({ id, name }) => ({ value: id, label: name }))} onChange={chooseFarm} />
            <Select aria-label="当前地块" className="agent-shell__field-select" placeholder="全部地块" allowClear
              value={context.currentField?.id} options={fields.map(({ fieldId, name }) => ({ value: fieldId, label: name }))} onChange={chooseField} />
            {context.currentCrop?.name && <span className="agent-shell__scope-label">{context.currentCrop.name}</span>}
          </div>}
        </div>
        <Flex align="center" gap={12} className="agent-shell__header-right">
          {signedIn && context.currentWeather && <span className="agent-shell__weather"><CloudOutlined /> {context.currentWeather.temperature}</span>}
          {signedIn ? <label className="agent-shell__date agent-shell__date-picker"><CalendarOutlined />
            <input type="date" aria-label="参考日期" value={referenceDate}
              onChange={(event) => setContext({ referenceDate: event.target.value || today })} />
          </label> : <span className="agent-shell__date"><CalendarOutlined /> {formatCurrentLocalDate(date)}</span>}
          {signedIn && !inWorkspace && <Button className="agent-shell__takeover" aria-label="让 Agent 接管当前上下文" icon={<MessageOutlined />}
            onClick={() => useCopilotStore.getState().open()}><span className="agent-shell__takeover-text">询问 Agent</span></Button>}
          {signedIn ? <Dropdown trigger={["click"]} menu={{ items: [
            { key: "user", label: username, disabled: true },
            { key: "logout", icon: <LogoutOutlined />, label: "退出登录", onClick: signOut },
          ] }}><Button type="text" aria-label="账号菜单" className="agent-shell__user"><Avatar size={30} icon={<UserOutlined />} /><span>{username}</span></Button></Dropdown>
            : <Button type="text" className="agent-shell__login-entry" aria-label="登录" onClick={session.openLogin}><Avatar size={30} icon={<UserOutlined />} />登录</Button>}
        </Flex>
      </Header>
      <Content className={`farmap-content agent-shell__content ${inWorkspace ? "is-workspace" : ""}`}>
        <Outlet key={signedIn ? `account-${username}` : "guest"} context={session} />
      </Content>
    </AntLayout>
    {loginOpen && !signedIn && <AccountLogin onClose={() => setLoginOpen(false)} onLogin={acceptAccount} />}
  </AntLayout>;
}

const clearFieldContext = {
  currentField: undefined, currentCrop: undefined, currentLocation: undefined, currentPhenology: undefined,
  selectedImages: [], selectedDevice: undefined, selectedDiagnosis: undefined, agentRunId: undefined, taskId: undefined,
};

function resetWorkspaceIdentity() {
  clearDemoSession();
  clearCopilotConversation();
  useAIContextStore.getState().resetContext();
  const copilot = useCopilotStore.getState();
  copilot.clearMessages();
  copilot.setDraft("");
  copilot.setContext(undefined);
  copilot.close();
}
