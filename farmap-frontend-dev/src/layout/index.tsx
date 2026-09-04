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
import {
  primaryNavigation,
  settingsNavigation,
  type NavigationItem,
  type UserRole,
} from "@/routes/navigation";

const { Header, Sider, Content } = LayoutAntd;

function getNavigationKey(pathname: string): string {
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
  const [searchValue, setSearchValue] = useState("");
  const demoFarmId = demoSession?.farm.id;
  const demoFarmName = demoSession?.farm.name;
  const demoFarmAddress = demoSession?.farm.address;

  useEffect(() => {
    if (!isDemoMode && (isExpired || !localUserStore)) navigate("/login");
  }, [isDemoMode, isExpired, localUserStore, navigate]);

  useEffect(() => {
    setAIContext({ currentPage: location.pathname });
  }, [location.pathname, setAIContext]);

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
    : farmOptions[0].value;

  useEffect(() => {
    if (selectedFarmId === context.currentFarm.id) return;
    const selectedFarm = farmOptions.find((farm) => farm.value === selectedFarmId);
    if (!selectedFarm) return;

    setAIContext({
      currentFarm: {
        ...context.currentFarm,
        id: selectedFarm.value,
        name: selectedFarm.label,
      },
    });
  }, [context.currentFarm, context.currentFarm.id, farmOptions, selectedFarmId, setAIContext]);

  const handleFarmChange = (farmId: number) => {
    const selectedFarm = farmOptions.find((farm) => farm.value === farmId);
    if (!selectedFarm) return;

    setAIContext({
      currentFarm: {
        ...context.currentFarm,
        id: selectedFarm.value,
        name: selectedFarm.label,
      },
    });
  };

  const handleSearch = (value: string) => {
    const normalized = value.trim().toLowerCase();
    if (!normalized) return;
    if (normalized.includes("地图") || normalized.includes("农场") || normalized.includes("farm")) {
      navigate("/farm/map");
    } else if (normalized.includes("诊断") || normalized.includes("ai")) {
      navigate("/diagnosis/a12-demo");
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
              <div className="farmap-header__weather"><CloudOutlined /><strong>24°C</strong><span>多云</span></div>
              <div className="farmap-header__date"><CalendarOutlined /><span>2026年9月4日&nbsp; 星期四</span></div>
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
              <Button type="primary" ghost icon={<RobotOutlined />} onClick={openCopilot}>
                FarMap Copilot
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
      <CopilotDrawer />
    </LayoutAntd>
  );
}
