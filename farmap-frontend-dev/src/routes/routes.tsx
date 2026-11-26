import { lazy, type ElementType } from "react";
import { createBrowserRouter, Navigate } from "react-router";
import type { RouteObject } from "react-router";
import Layout, { loadWorkspaceSession } from "@/layout";
import { useWorkspaceSession } from "@/layout/session";

const Copilot = lazy(() => import("@/components/copilot/CopilotDrawer"));
const GuestCopilot = lazy(() => import("@/components/copilot/GuestCopilotWorkspace"));
const Fields = lazy(() => import("@/features/pro-workspaces/SpatialWorkspace"));
const Monitoring = lazy(() => import("@/features/pro-workspaces/MonitoringWorkspace"));
const Planning = lazy(() => import("@/features/pro-workspaces/PlanningWorkspace"));
const Knowledge = lazy(() => import("@/features/pro-workspaces/KnowledgeWorkspace"));
const Administration = lazy(() => import("@/features/pro-workspaces/AdminManagementWorkspace"));
const DiagnosisStart = lazy(() => import("@/views/diagnosis/Start"));
const DiagnosisDetail = lazy(() => import("@/views/diagnosis/Detail"));
const TaskRun = lazy(() => import("@/views/analysis"));
const NotFound = lazy(() => import("@/layout/NotFound"));

function CopilotWorkspace() {
  const { signedIn } = useWorkspaceSession();
  return signedIn ? <Copilot variant="workspace" /> : <GuestCopilot />;
}

function AccountRoute({ page: Page, admin }: { page: ElementType; admin?: boolean }) {
  const { signedIn, role } = useWorkspaceSession();
  return signedIn && (!admin || role === "admin") ? <Page /> : <Navigate to="/workspace" replace />;
}

const to = (path: string) => <Navigate to={path} replace />;

// Existing operation links remain usable as secondary data views.
const aliases: Record<string, string> = {
  login: "/workspace", demo: "/workspace", overview: "/workspace", dashboard: "/workspace",
  farm: "/fields", "farm/map": "/fields", "farm/fields": "/fields", "farm/devices": "/monitoring?view=devices",
  monitor: "/monitoring?view=images", weather: "/monitoring",
  operations: "/planning?view=ledger", "operations/tasks": "/planning?view=ledger", "operations/recommendations": "/planning?view=recommendations",
  diagnosis: "/planning?view=records", "diagnosis/history": "/planning?view=records", "diagnosis/a12-demo": "/planning",
  analysis: "/planning", "analysis/a12-demo": "/planning", "call-model": "/monitoring?view=images",
  "knowledge/library": "/knowledge", "knowledge/cases": "/knowledge?view=cases", "knowledge/expert-review": "/knowledge?view=review",
  expert: "/knowledge?view=review", "expert-history": "/knowledge?view=review",
  settings: "/admin", "settings/users": "/admin?view=users", "settings/models": "/admin?view=models", "settings/system": "/admin?view=system",
};

export const ALL_ROUTES: RouteObject[] = [
  { index: true, element: to("/workspace") },
  { path: "workspace", element: <CopilotWorkspace /> },
  { path: "fields", element: <AccountRoute page={Fields} /> },
  { path: "monitoring", element: <AccountRoute page={Monitoring} /> },
  { path: "planning", element: <AccountRoute page={Planning} /> },
  { path: "knowledge", element: <AccountRoute page={Knowledge} /> },
  { path: "admin", element: <AccountRoute page={Administration} admin /> },
  { path: "diagnosis/new", element: <AccountRoute page={DiagnosisStart} /> },
  { path: "diagnosis/:id", element: <AccountRoute page={DiagnosisDetail} /> },
  { path: "analysis/:runId", element: <AccountRoute page={TaskRun} /> },
  ...Object.entries(aliases).map(([path, target]) => ({ path, element: to(target) })),
  { path: "*", Component: NotFound },
];

export default createBrowserRouter([{ path: "/", Component: Layout, loader: loadWorkspaceSession, children: ALL_ROUTES }], { basename: "/farmap" });
