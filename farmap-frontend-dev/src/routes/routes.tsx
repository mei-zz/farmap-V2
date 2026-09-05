/* eslint-disable react-refresh/only-export-components */
import { lazy, type ElementType, type JSX } from "react";
import { createBrowserRouter, Navigate } from "react-router";
import type { RouteObject } from "react-router";

import Layout from "@/layout";
import { req } from "@/utils/reqeust";
import { useUserStore } from "@/store/user";
import { permanence } from "@/utils/permanence";
import { getDemoSession } from "@/utils/demoSession";
import type { UserRole } from "./navigation";
import Overview from "@/views/overview";
import SectionPlaceholder from "@/views/architecture/SectionPlaceholder";

const Dashboard = lazy(() => import("@/views/dashboard"));
const Weather = lazy(() => import("@/views/weather"));
const Model = lazy(() => import("@/views/model"));
const Operations = lazy(() => import("@/views/operations"));
const Monitor = lazy(() => import("@/views/monitor"));
const Expert = lazy(() => import("@/views/expert"));
const ExpertHistory = lazy(() => import("@/views/expert/children/history"));
const Admin = lazy(() => import("@/views/admin"));
const Landing = lazy(() => import("@/views/landing"));
const DemoEntry = lazy(() => import("@/views/demo/Entry"));
const DemoFarmMap = lazy(() => import("@/views/demo/FarmMap"));
const DemoFields = lazy(() => import("@/views/demo/Fields"));
const DemoDevices = lazy(() => import("@/views/demo/Devices"));
const DemoDiagnosis = lazy(() => import("@/views/demo/Diagnosis"));
const DiagnosisDetail = lazy(() => import("@/views/diagnosis/Detail"));
const AgentWorkspace = lazy(() => import("@/views/analysis"));

const allRoles: UserRole[] = ["guest", "user", "admin", "expert", "viewer"];
const formalRoles: UserRole[] = ["guest", "user", "admin", "expert"];

function DemoUnavailable() {
  return (
    <SectionPlaceholder
      title="演示模式不可用"
      description="Demo Session 只允许查看 FarMap 的产品与业务演示，管理、专家复核和写入操作需要正式登录。"
      nextPath="/overview"
      nextLabel="返回工作台"
    />
  );
}

function ProtectedRoute({
  Component,
  roles,
}: {
  Component: ElementType;
  roles: UserRole[];
}): JSX.Element {
  const localUserStore = permanence.user.useUserStore();
  const storeRole = useUserStore((state) => state.role);
  const demoSession = getDemoSession();
  if (demoSession) {
    if (roles.includes("viewer")) return <Component />;
    return <DemoUnavailable />;
  }

  const role = localUserStore?.role ?? storeRole;

  if (!roles.includes(role)) {
    if (role === "expert") return <Navigate to="/knowledge/expert-review" replace />;
    return <Navigate to="/403" replace />;
  }
  return <Component />;
}

const protect = (Component: ElementType, roles: UserRole[] = allRoles): JSX.Element => (
  <ProtectedRoute Component={Component} roles={roles} />
);

function DemoOrFormalRoute({
  DemoComponent,
  FormalComponent,
  roles,
}: {
  DemoComponent: ElementType;
  FormalComponent: ElementType;
  roles: UserRole[];
}): JSX.Element {
  return getDemoSession() ? (
    <DemoComponent />
  ) : (
    protect(FormalComponent, roles)
  );
}

function ModelsSettingsPage() {
  return (
    <SectionPlaceholder
      title="模型配置"
      description="集中管理 AI 模型、版本与调用策略，现有模型接口保持不变。"
    />
  );
}

function SystemSettingsPage() {
  return (
    <SectionPlaceholder
      title="系统配置"
      description="系统参数、日志和平台配置统一收纳在 Settings。"
    />
  );
}

function FarmFieldsPage() {
  return (
    <SectionPlaceholder
      title="地块管理"
      description="围绕农场、地块与作物建立可供 AI 理解的空间上下文。"
      nextPath="/farm/map"
      nextLabel="查看数字地图"
    />
  );
}

function DemoOperationsPage() {
  return (
    <SectionPlaceholder
      title="智能农事"
      description="演示模式只展示产品入口和上下文，不会请求或写入真实农事任务。"
      nextPath="/overview"
      nextLabel="返回工作台"
    />
  );
}

const productRoutes: RouteObject[] = [
  {
    path: "overview",
    element: protect(Overview),
  },
  {
    path: "farm",
    children: [
      { index: true, element: <Navigate to="map" replace /> },
      {
        path: "map",
        element: (
          <DemoOrFormalRoute
            DemoComponent={DemoFarmMap}
            FormalComponent={Dashboard}
            roles={allRoles}
          />
        ),
      },
      {
        path: "fields",
        element: (
          <DemoOrFormalRoute
            DemoComponent={DemoFields}
            FormalComponent={FarmFieldsPage}
            roles={allRoles}
          />
        ),
      },
      {
        path: "devices",
        element: (
          <DemoOrFormalRoute
            DemoComponent={DemoDevices}
            FormalComponent={Monitor}
            roles={["admin"]}
          />
        ),
      },
    ],
  },
  {
    path: "diagnosis",
    children: [
      { index: true, element: <Navigate to="new" replace /> },
      {
        path: "a12-demo",
        element: protect(DiagnosisDetail, allRoles),
      },
      {
        path: "new",
        element: (
          <DemoOrFormalRoute
            DemoComponent={DemoDiagnosis}
            FormalComponent={Model}
            roles={["user", "admin", "expert"]}
          />
        ),
      },
      {
        path: "history",
        element: (
          <DemoOrFormalRoute
            DemoComponent={DemoUnavailable}
            FormalComponent={ExpertHistory}
            roles={["expert", "admin"]}
          />
        ),
      },
      {
        path: ":id",
        element: (
          <SectionPlaceholder
            title="诊断详情"
            description="诊断详情将汇总图像证据、检索内容、模型推理、置信度与可执行建议。"
            nextPath="/diagnosis/new"
            nextLabel="发起新诊断"
          />
        ),
      },
    ],
  },
  {
    path: "analysis/a12-demo",
    element: protect(AgentWorkspace, allRoles),
  },
  {
    path: "analysis/:runId",
    element: protect(AgentWorkspace, allRoles),
  },
  {
    path: "operations",
    children: [
      {
        index: true,
        element: (
          <DemoOrFormalRoute
            DemoComponent={DemoOperationsPage}
            FormalComponent={Operations}
            roles={allRoles}
          />
        ),
      },
      {
        path: "tasks",
        element: (
          <DemoOrFormalRoute
            DemoComponent={DemoOperationsPage}
            FormalComponent={Operations}
            roles={allRoles}
          />
        ),
      },
      {
        path: "recommendations",
        element: (
          <SectionPlaceholder
            title="农事推荐"
            description="将天气、物候、诊断和知识检索结果汇总为可执行的农事建议。"
            nextPath="/operations/tasks"
            nextLabel="查看现有操作指导"
          />
        ),
      },
    ],
  },
  {
    path: "knowledge",
    children: [
      { index: true, element: <Navigate to="library" replace /> },
      {
        path: "library",
        element: (
          <SectionPlaceholder
            title="知识库"
            description="为多模态 RAG 预留知识检索入口，后续接入现有农业知识与案例数据。"
          />
        ),
      },
      {
        path: "expert-review",
        element: (
          <DemoOrFormalRoute
            DemoComponent={DemoUnavailable}
            FormalComponent={Expert}
            roles={["expert", "admin"]}
          />
        ),
      },
      {
        path: "cases",
        element: (
          <SectionPlaceholder
            title="案例库"
            description="沉淀经专家复核的农情案例，为后续检索增强与决策推理提供可追溯素材。"
          />
        ),
      },
    ],
  },
  {
    path: "settings",
    children: [
      { index: true, element: <Navigate to="users" replace /> },
      { path: "users", element: protect(Admin, ["admin"]) },
      {
        path: "models",
        element: protect(ModelsSettingsPage, ["admin"]),
      },
      {
        path: "system",
        element: protect(SystemSettingsPage, ["admin"]),
      },
    ],
  },

  // Legacy entry points. Keep these available while users transition to the new IA.
  { path: "weather", element: protect(Weather, formalRoles) },
  { path: "call-model", element: protect(Model, ["user", "admin"]) },
  { path: "monitor", element: protect(Monitor, ["admin"]) },
  { path: "expert", element: protect(Expert, ["expert", "admin"]) },
  { path: "expert-history", element: protect(ExpertHistory, ["expert", "admin"]) },
  { path: "admin", element: protect(Admin, ["admin"]) },
];

// Kept as a named export for any existing consumers that used the old route module.
export const ALL_ROUTES = productRoutes;

const routes: RouteObject[] = [
  {
    path: "/",
    Component: Landing,
  },
  {
    path: "/demo",
    Component: DemoEntry,
  },
  {
    path: "/login",
    Component: lazy(() => import("@/views/login")),
  },
  {
    path: "/",
    Component: Layout,
    children: ALL_ROUTES,
    loader: async () => {
      if (getDemoSession()) {
        return { isExpired: false, isDemo: true };
      }

      const token = permanence.token.useToken();
      try {
        const resp = await req.get<{ data: { isExpired: boolean } }>(
          "/user/validate-token",
          { Authorization: `Bearer ${token}` },
        );

        return resp.data;
      } catch {
        return {
          isExpired: true,
        };
      }
    },
  },
  {
    path: "/403",
    Component: lazy(() => import("@/layout/Forbidden")),
  },
  {
    path: "/*",
    Component: lazy(() => import("@/layout/NotFound")),
  },
];

const router = createBrowserRouter(routes, { basename: "/farmap" });

export default router;
