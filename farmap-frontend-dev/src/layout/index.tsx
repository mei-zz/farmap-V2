import { useEffect, useMemo, useState } from "react";
import { useLoaderData } from "react-router";
import {
  Avatar,
  Badge,
  Button,
  Divider,
  Dropdown,
  Flex,
  Input,
  Layout as LayoutAntd,
  Menu,
  Select,
  Tag,
  Tooltip,
} from "antd";
import { BellOutlined, CalendarOutlined, CloudOutlined, LogoutOutlined, RobotOutlined, SearchOutlined, UserOutlined } from "@ant-design/icons";
import type { MenuProps } from "antd";
import { NavLink, Outlet, useLocation, useNavigate } from "react-router";
import { useUserStore } from "@/store/user";
import { permanence } from "@/utils/permanence";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import CopilotDrawer from "@/components/copilot/CopilotDrawer";
import { clearDemoSession, getDemoSession } from "@/utils/demoSession";
import { formatCurrentLocalDate } from "@/utils/currentDate";
import { loadCurrentWeather } from "@/features/agricultural/context";
import { req } from "@/utils/reqeust";
import {
  primaryNavigation,
  settingsNavigation,
  type NavigationItem,
  type UserRole,
} from "@/routes/navigation";

const { Header, Sider, Content } = LayoutAntd;
const SELECTED_FARM_STORAGE_KEY = "farmap.selected-farm-id";

function readSelectedFarmId(): number | undefined {
  if (typeof window === "undefined") return undefined;
  const value = Number(window.localStorage.getItem(SELECTED_FARM_STORAGE_KEY));
  return Number.isFinite(value) && value > 0 ? value : undefined;
}

function getNavigationKey(pathname: string): string {
  if (pathname.startsWith("/diagnosis") || pathname.startsWith("/analysis")) return "agent";
  const match = primaryNavigation.find((item) => {
    const rootPath = `/${item.path.split("/")[1]}`;
    return pathname.startsWith(rootPath);
  });
  return match?.key ?? (pathname.startsWith("/settings") ? settingsNavigation.key : "overview");
}

function getNavigationItems(items: NavigationItem[], role: UserRole): MenuProps["items"] {
  return items
    .filter((item) => item.roles.includes(role))
    .map((item) => {
      const Icon = item.icon;
      return {
        key: item.key,
        icon: <Icon />,
        label: (
          <NavLink to={item.path} className="farmap-nav-item">
            <span>{item.label}</span>
            <small>{item.english}</small>
          </NavLink>
        ),
      };
    });
}

export default function Layout() {
  const { isExpired } = useLoaderData<{ isExpired: boolean }>();
  const localUserStore = permanence.user.useUserStore();
  const demoSession = getDemoSession();
  const isDemoMode = Boolean(demoSession);
  const storeFarms = useUserStore((state) => state.farms);
  const logout = useUserStore((state) => state.logout);
  const role: UserRole = isDemoMode ? "viewer" : localUserStore?.role ?? "guest";
  const username = demoSession?.user.name ?? localUserStore?.username ?? "FarMap 用户";
  const navigate = useNavigate();
  const location = useLocation();
  const setAIContext = useAIContextStore((state) => state.setContext);
  const context = useAIContextStore((state) => state.context);
  const openCopilot = useCopilotStore((state) => state.open);
  const isCopilotOpen = useCopilotStore((state) => state.isOpen);
  const setCopilotContext = useCopilotStore((state) => state.setContext);
  const [searchValue, setSearchValue] = useState("");
  const [currentDate, setCurrentDate] = useState(() => new Date());
  const [preferredFarmId, setPreferredFarmId] = useState<number | undefined>(readSelectedFarmId);
  const demoFarmId = demoSession?.farm.id;
  const demoFarmName = demoSession?.farm.name;
  const demoFarmAddress = demoSession?.farm.address;
  const token = permanence.token.useToken();

  useEffect(() => {
    const timer = window.setInterval(() => setCurrentDate(new Date()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!isDemoMode && (isExpired || !localUserStore)) navigate("/login");
  }, [isDemoMode, isExpired, localUserStore, navigate]);

  useEffect(() => {
    setAIContext({ currentPage: location.pathname, pageKey: location.pathname });
    setCopilotContext(undefined);
  }, [location.pathname, setAIContext, setCopilotContext]);

  useEffect(() => {
    if (demoFarmId === undefined || !demoFarmName || !demoFarmAddress) return;
    setAIContext({
      currentFarm: {
        id: demoFarmId,
        name: demoFarmName,
        address: demoFarmAddress,
      },
    });
  }, [demoFarmAddress, demoFarmId, demoFarmName, setAIContext]);

  const farmOptions = useMemo(() => {
    if (demoFarmId !== undefined && demoFarmName) {
      return [{ value: demoFarmId, label: demoFarmName }];
    }
    const farms = localUserStore?.farms?.length ? localUserStore.farms : storeFarms;
    return farms.length
      ? farms.map((farm) => ({ value: farm.id, label: farm.name }))
      : [{ value: context.currentFarm.id, label: context.currentFarm.name }];
  }, [context.currentFarm.id, context.currentFarm.name, demoFarmId, demoFarmName, localUserStore?.farms, storeFarms]);

  const selectedFarmId = farmOptions.some((farm) => farm.value === context.currentFarm.id)
    ? context.currentFarm.id
    : farmOptions.find((farm) => farm.value === preferredFarmId)?.value ?? farmOptions[0].value;

  useEffect(() => {
    if (selectedFarmId === context.currentFarm.id) return;
    const selectedFarm = farmOptions.find((farm) => farm.value === selectedFarmId);
    if (!selectedFarm) return;

    setPreferredFarmId(selectedFarmId);
    window.localStorage.setItem(SELECTED_FARM_STORAGE_KEY, String(selectedFarmId));

    setAIContext({
      currentFarm: {
        ...context.currentFarm,
        id: selectedFarm.value,
        name: selectedFarm.label,
      },
      currentField: undefined,
      currentCrop: undefined,
      currentLocation: undefined,
      currentPhenology: undefined,
      selectedDiagnosis: undefined,
      selectedImages: [],
      selectedDevice: undefined,
      currentWeather: undefined,
      agentRunId: undefined,
      taskId: undefined,
    });
  }, [context.currentFarm, context.currentFarm.id, farmOptions, selectedFarmId, setAIContext]);

  useEffect(() => {
    if (isDemoMode || selectedFarmId === undefined || selectedFarmId <= 0) return;
    let active = true;
    setAIContext({ currentWeather: undefined });
    loadCurrentWeather(selectedFarmId, token)
      .then((weather) => {
        if (!active) return;
        const precipitation = weather.rainfallForecast24h !== undefined
          ? `未来24小时 ${weather.rainfallForecast24h.toFixed(1)} mm${weather.rainfallProbability24h !== undefined ? ` · ${weather.rainfallProbability24h}%概率` : ""}`
          : "暂无降雨信息";
        setAIContext({
          currentWeather: {
            summary: weather.condition ?? (weather.status === "NOT_CONFIGURED" ? "天气服务尚未配置" : "天气服务已配置但暂不可用"),
            temperature: weather.status === "READY" && weather.temperature ? `${weather.temperature}°C` : "—",
            precipitation,
            updatedAt: weather.observedAt ?? weather.retrievedAt ?? new Date().toISOString(),
            rainfall14d: weather.rainfall14d,
            rainfallForecast24h: weather.rainfallForecast24h,
            rainfallProbability24h: weather.rainfallProbability24h,
            rainfallForecast: weather.rainfallForecast,
            rainfallForecastDays: weather.rainfallForecastDays,
            forecast: weather.forecast,
          },
        });
      })
      .catch(() => {
        // Keep the compact header fallback; detailed pages show the request error.
      });
    return () => { active = false; };
  }, [isDemoMode, selectedFarmId, setAIContext, token]);

  useEffect(() => {
    if (isDemoMode || selectedFarmId === undefined || selectedFarmId <= 0 || context.currentField?.id) return;
    let active = true;
    req.get<{ data?: Array<{ fieldId?: string; name?: string; crop?: string; area?: number; growthStage?: string }> }>(
      `/api/farms/${selectedFarmId}/fields`,
      { Authorization: `Bearer ${token}` },
    ).then((response) => {
      const field = response.data?.find((item) => item.fieldId && item.name);
      if (!active || !field?.fieldId) return;
      setAIContext({
        currentField: { id: field.fieldId, name: field.name ?? field.fieldId, area: field.area ?? 0, crop: field.crop ?? "" },
        currentCrop: field.crop ? { name: field.crop, growthStage: field.growthStage } : undefined,
        currentPhenology: field.growthStage ? { stage: field.growthStage, progress: 0 } : undefined,
      });
    }).catch(() => {
      // Detail pages remain authoritative; this only repairs the global Agent context.
    });
    return () => { active = false; };
  }, [context.currentField?.id, isDemoMode, selectedFarmId, setAIContext, token]);

  const handleFarmChange = (farmId: number) => {
    const selectedFarm = farmOptions.find((farm) => farm.value === farmId);
    if (!selectedFarm) return;

    setPreferredFarmId(farmId);
    window.localStorage.setItem(SELECTED_FARM_STORAGE_KEY, String(farmId));

    setAIContext({
      currentFarm: {
        ...context.currentFarm,
        id: selectedFarm.value,
        name: selectedFarm.label,
      },
      currentField: undefined,
      currentCrop: undefined,
      currentLocation: undefined,
      currentPhenology: undefined,
      selectedDiagnosis: undefined,
      selectedImages: [],
      selectedDevice: undefined,
      currentWeather: undefined,
      agentRunId: undefined,
      taskId: undefined,
    });
  };

  const handleHeaderCopilotOpen = () => {
    openCopilot();
  };

  const handleSearch = (value: string) => {
    const normalized = value.trim().toLowerCase();
    if (!normalized) return;
    if (normalized.includes("地图") || normalized.includes("农场") || normalized.includes("farm")) {
      navigate("/farm/map");
    } else if (normalized.includes("诊断") || normalized.includes("ai")) {
      navigate("/diagnosis/new");
    } else if (normalized.includes("农事") || normalized.includes("操作")) {
      navigate("/operations/tasks");
    } else if (normalized.includes("专家") || normalized.includes("知识")) {
      navigate("/knowledge/library");
    }
    setSearchValue("");
  };

  const handleLogout = () => {
    if (isDemoMode) {
      clearDemoSession();
      navigate("/");
      return;
    }
    logout();
    permanence.token.setToken("NO_TOKEN_STORED");
    navigate("/login");
  };

  const userMenuItems: MenuProps["items"] = [
    { key: "user", label: `${username} · ${role}`, disabled: true },
    { type: "divider" },
    {
      key: "logout",
      icon: <LogoutOutlined />,
      label: isDemoMode ? "退出演示" : "退出登录",
      onClick: handleLogout,
    },
  ];

  return (
    <LayoutAntd className="farmap-shell">
      <Sider breakpoint="lg" collapsedWidth="0" className="farmap-sidebar">
        <div className="farmap-brand">
          <div className="farmap-brand__mark">F</div>
          <div>
            <strong>FarMap</strong>
            <small>农业智能决策平台</small>
          </div>
        </div>

        <div className="farmap-sidebar__label">工作空间</div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[getNavigationKey(location.pathname)]}
          items={getNavigationItems(primaryNavigation, role)}
          onClick={({ key }) => {
            const item = primaryNavigation.find((entry) => entry.key === key);
            if (item) navigate(item.path);
          }}
        />

        {settingsNavigation.roles.includes(role) && (
          <>
            <Divider className="farmap-sidebar__divider" />
            <div className="farmap-sidebar__label">系统</div>
            <Menu
              theme="dark"
              mode="inline"
              selectedKeys={location.pathname.startsWith("/settings") ? [settingsNavigation.key] : []}
              items={getNavigationItems([settingsNavigation], role)}
              onClick={() => navigate(settingsNavigation.path)}
            />
          </>
        )}
      </Sider>

      <LayoutAntd className="farmap-main">
        <Header className="farmap-header">
          <Flex align="center" justify="space-between" gap={16} style={{ width: "100%" }}>
            <div className="farmap-header__context">
              <Select
                aria-label="当前农场"
                value={selectedFarmId}
                options={farmOptions}
                onChange={handleFarmChange}
                className="farmap-header__farm-select"
                suffixIcon={<span>⌄</span>}
              />
              <div className="farmap-header__weather"><CloudOutlined /><strong>{context.currentWeather?.temperature ?? "—"}</strong><span>{context.currentWeather?.summary ?? "天气数据不可用"}</span></div>
              <div className="farmap-header__date"><CalendarOutlined /><span>{formatCurrentLocalDate(currentDate)}</span></div>
            </div>

            <Flex align="center" gap={10} className="farmap-header__tools">
              <Input.Search
                allowClear
                value={searchValue}
                onChange={(event) => setSearchValue(event.target.value)}
                onSearch={handleSearch}
                placeholder="搜索农场、地块或诊断"
                enterButton={<SearchOutlined />}
                style={{ width: 300 }}
              />
              <Tooltip title="通知">
                <Badge dot>
                  <Button type="text" icon={<BellOutlined />} aria-label="通知" />
                </Badge>
              </Tooltip>
              <Button type="primary" ghost icon={<RobotOutlined />} onClick={handleHeaderCopilotOpen}>
                FarMap Agent
              </Button>
              {isDemoMode && <Tag color="gold">DEMO MODE</Tag>}
              <Dropdown menu={{ items: userMenuItems }} trigger={["click"]}>
                <Button type="text" className="farmap-user-button">
                  <Avatar size={30} icon={<UserOutlined />} />
                  <span>{username}</span>
                </Button>
              </Dropdown>
            </Flex>
          </Flex>
        </Header>

        <Content className="farmap-content">
          <Outlet />
        </Content>
      </LayoutAntd>
      {!isCopilotOpen && (
        <Button
          type="primary"
          size="large"
          icon={<RobotOutlined />}
          className="farmap-agent-launcher"
          onClick={handleHeaderCopilotOpen}
          aria-label="打开 FarMap 多模态农业 Agent"
        >
          <span><b>问 Agent</b><small>{context.currentField?.name ? `已连接 ${context.currentField.name}` : `已连接 ${context.currentFarm.name}`}</small></span>
        </Button>
      )}
      <CopilotDrawer />
    </LayoutAntd>
  );
}
