import type { ComponentType } from "react";
import { BookOutlined, EnvironmentOutlined, HomeOutlined, SettingOutlined, VideoCameraOutlined } from "@ant-design/icons";

export type UserRole = "guest" | "user" | "admin" | "expert" | "viewer";
export type NavigationItem = { key: string; path: string; label: string; english: string; icon: ComponentType; roles: UserRole[] };

const members: UserRole[] = ["user", "admin", "expert", "viewer"];

export const primaryNavigation: NavigationItem[] = [
  { key: "agent", path: "/workspace", label: "Agent 工作台", english: "Agent Workspace", icon: HomeOutlined, roles: ["guest", ...members] },
  { key: "fields", path: "/fields", label: "地块空间", english: "Spatial Workspace", icon: EnvironmentOutlined, roles: members },
  { key: "monitoring", path: "/monitoring", label: "生产监测", english: "Monitoring", icon: VideoCameraOutlined, roles: members },
  { key: "knowledge", path: "/knowledge", label: "知识中心", english: "Knowledge Center", icon: BookOutlined, roles: members },
];

export const settingsNavigation: NavigationItem = { key: "settings", path: "/admin", label: "系统管理", english: "Administration", icon: SettingOutlined, roles: ["admin"] };

export const operationsViews = {
  tasks: { label: "任务台账", path: "/planning?view=ledger", query: "ledger" },
  calendar: { label: "任务日历", path: "/planning?view=calendar", query: "calendar" },
  records: { label: "执行档案", path: "/planning?view=archive", query: "archive" },
} as const;

export const navigationTitleMap: Record<string, string> = {
  ...Object.fromEntries([...primaryNavigation, settingsNavigation].map((item) => [item.path, item.label])),
  "/planning": "农事数据",
};
