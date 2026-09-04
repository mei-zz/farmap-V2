import type { ComponentType } from "react";
import {
  AppstoreOutlined,
  BookOutlined,
  ExperimentOutlined,
  EnvironmentOutlined,
  RobotOutlined,
  SettingOutlined,
  ToolOutlined,
} from "@ant-design/icons";

export type UserRole = "guest" | "user" | "admin" | "expert" | "viewer";

export type NavigationItem = {
  key: string;
  path: string;
  label: string;
  english: string;
  icon: ComponentType;
  roles: UserRole[];
};

const allRoles: UserRole[] = ["guest", "user", "admin", "expert", "viewer"];

export const primaryNavigation: NavigationItem[] = [
  {
    key: "overview",
    path: "/overview",
    label: "工作台",
    english: "Overview",
    icon: AppstoreOutlined,
    roles: allRoles,
  },
  {
    key: "farm",
    path: "/farm/map",
    label: "数字农场",
    english: "Smart Farm",
    icon: EnvironmentOutlined,
    roles: allRoles,
  },
  {
    key: "diagnosis",
    path: "/diagnosis/a12-demo",
    label: "AI 农情诊断",
    english: "AI Diagnosis",
    icon: ExperimentOutlined,
    roles: ["user", "admin", "expert", "viewer"],
  },
  {
    key: "analysis",
    path: "/analysis/a12-demo",
    label: "智能分析",
    english: "Agent",
    icon: RobotOutlined,
    roles: allRoles,
  },
  {
    key: "operations",
    path: "/operations/tasks",
    label: "智能农事",
    english: "Operations",
    icon: ToolOutlined,
    roles: allRoles,
  },
  {
    key: "knowledge",
    path: "/knowledge/library",
    label: "知识与专家",
    english: "Knowledge & Experts",
    icon: BookOutlined,
    roles: allRoles,
  },
];

export const settingsNavigation: NavigationItem = {
  key: "settings",
  path: "/settings/users",
  label: "管理",
  english: "Settings",
  icon: SettingOutlined,
  roles: ["admin"],
};

export const navigationTitleMap: Record<string, string> = {
  "/overview": "工作台",
  "/farm": "数字农场",
  "/farm/map": "数字农场 / 地图",
  "/farm/fields": "数字农场 / 地块",
  "/farm/devices": "数字农场 / 设备与监控",
  "/diagnosis": "AI 农情诊断",
  "/diagnosis/new": "AI 农情诊断 / 新建诊断",
  "/diagnosis/a12-demo": "AI 农情诊断 / A-12 地块诊断",
  "/diagnosis/history": "AI 农情诊断 / 诊断记录",
  "/operations": "智能农事",
  "/operations/tasks": "智能农事 / 农事任务",
  "/operations/recommendations": "智能农事 / 推荐",
  "/knowledge": "知识与专家",
  "/knowledge/library": "知识与专家 / 知识库",
  "/knowledge/expert-review": "知识与专家 / 专家复核",
  "/knowledge/cases": "知识与专家 / 案例库",
  "/analysis/a12-demo": "智能分析 / Agent 工作空间",
  "/settings": "管理 / Settings",
  "/settings/users": "管理 / 用户管理",
  "/settings/models": "管理 / 模型配置",
  "/settings/system": "管理 / 系统配置",
};
