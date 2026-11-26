import { useEffect, useState } from "react";
import { Alert, Button, Card, Empty, Popconfirm, Spin, Tabs, Tag } from "antd";
import { useSearchParams } from "react-router";
import AdminKnowledgeBrowser from "./AdminKnowledgeBrowser";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import "./planning-knowledge.css";

type User = { id: number; name: string; role: string };
type Resource<T> = { loading: boolean; data?: T; error?: string };
function Details({ value }: { value: unknown }) {
  if (value === null || value === undefined) return <span>未返回</span>;
  if (typeof value !== "object") return <span>{String(value)}</span>;
  if (Array.isArray(value))
    return value.length ? (
      <div>
        {value.map((entry, i) => (
          <div className="fm-pro-source" key={i}>
            <Details value={entry} />
          </div>
        ))}
      </div>
    ) : (
      <span>暂无数据</span>
    );
  return (
    <div>
      {Object.entries(value as Record<string, unknown>).map(([key, entry]) => (
        <details key={key} className="fm-pro-admin-detail">
          <summary>
            {key}
            {typeof entry !== "object" && `：${String(entry ?? "未返回")}`}
          </summary>
          {typeof entry === "object" && <Details value={entry} />}
        </details>
      ))}
    </div>
  );
}
export default function AdminManagementWorkspace() {
  const [params, setParams] = useSearchParams();
  const [users, setUsers] = useState<Resource<User[]>>({ loading: true });
  const [models, setModels] = useState<Resource<unknown>>({ loading: true });
  const [system, setSystem] = useState<Resource<unknown>>({ loading: true });
  const [userNotice, setUserNotice] = useState<string>();
  const [deleting, setDeleting] = useState<string>();
  useEffect(() => {
    let active = true;
    const headers = { Authorization: `Bearer ${permanence.token.useToken()}` };
    const failure = (e: unknown) =>
      e instanceof Error ? e.message : "服务暂不可用";
    req
      .get<{ users?: User[]; message?: string }>("/user/all", headers)
      .then((r) => {
        if (active)
          setUsers(
            Array.isArray(r.users)
              ? { loading: false, data: r.users }
              : { loading: false, error: r.message || "用户列表不可用" },
          );
      })
      .catch((e: unknown) => {
        if (active) setUsers({ loading: false, error: failure(e) });
      });
    req
      .get<{ data?: unknown; msg?: string }>("/ai-model/availability", headers)
      .then((r) => {
        if (active)
          setModels(
            r.data
              ? { loading: false, data: r.data }
              : { loading: false, error: r.msg || "模型状态不可用" },
          );
      })
      .catch((e: unknown) => {
        if (active) setModels({ loading: false, error: failure(e) });
      });
    req
      .get<{ data?: unknown; msg?: string }>(
        "/api/runtime/admin/health",
        headers,
      )
      .then((r) => {
        if (active)
          setSystem(
            r.data
              ? { loading: false, data: r.data }
              : { loading: false, error: r.msg || "系统状态不可用" },
          );
      })
      .catch((e: unknown) => {
        if (active) setSystem({ loading: false, error: failure(e) });
      });
    return () => {
      active = false;
    };
  }, []);
  const deleteUser = async (user: User) => {
    setDeleting(user.name);
    setUserNotice(undefined);
    try {
      const response = await fetch(new URL("/user/delete", req.host), {
        method: "DELETE",
        headers: {
          Authorization: `Bearer ${permanence.token.useToken()}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({ targetUser: user.name }),
      });
      const result = (await response.json()) as {
        status?: number;
        message?: string;
      };
      if (!response.ok || result.status !== 0)
        throw new Error(result.message || "删除失败");
      setUsers((current) => ({
        ...current,
        data: current.data?.filter((item) => item.id !== user.id),
      }));
      setUserNotice(result.message || "用户已删除");
    } catch (e) {
      setUserNotice(e instanceof Error ? e.message : "删除失败");
    } finally {
      setDeleting(undefined);
    }
  };
  const render = <T,>(
    resource: Resource<T>,
    content: (data: T) => React.ReactNode,
  ) =>
    resource.loading ? (
      <Spin />
    ) : resource.error ? (
      <Alert type="error" showIcon title={resource.error} />
    ) : resource.data !== undefined ? (
      content(resource.data)
    ) : (
      <Empty />
    );
  const requested = params.get("view") || "users";
  const view = ["users", "models", "system", "sources"].includes(requested)
    ? requested
    : "users";
  return (
    <div className="fm-pro-workspace">
      <header className="fm-pro-heading">
        <div>
          <span>ADMINISTRATION</span>
          <h1>系统管理</h1>
          <p>查看用户、模型、系统和知识来源状态。</p>
        </div>
      </header>
      <Tabs
        activeKey={view}
        onChange={(key) => {
          const next = new URLSearchParams(params);
          next.set("view", key);
          setParams(next);
        }}
        items={[
          {
            key: "users",
            label: "用户管理",
            children: (
              <Card className="fm-pro-card" title="非管理员用户">
                {userNotice && <Alert showIcon title={userNotice} />}{" "}
                {render(users, (data) =>
                  data.length ? (
                    data.map((user) => (
                      <div className="fm-pro-source" key={user.id}>
                        <b>{user.name}</b>
                        <span>
                          <Tag>{user.role}</Tag>
                          <Popconfirm
                            title={`确认删除用户 ${user.name}？`}
                            onConfirm={() => void deleteUser(user)}
                            okText="删除"
                            cancelText="取消"
                          >
                            <Button
                              danger
                              size="small"
                              loading={deleting === user.name}
                            >
                              删除
                            </Button>
                          </Popconfirm>
                        </span>
                      </div>
                    ))
                  ) : (
                    <Empty description="暂无非管理员用户" />
                  ),
                )}
              </Card>
            ),
          },
          {
            key: "models",
            label: "模型状态",
            children: (
              <Card className="fm-pro-card">
                {render(models, (data) => (
                  <Details value={data} />
                ))}
              </Card>
            ),
          },
          {
            key: "system",
            label: "系统与检索诊断",
            children: (
              <Card className="fm-pro-card">
                {render(system, (data) => (
                  <Details value={data} />
                ))}
              </Card>
            ),
          },
          {
            key: "sources",
            label: "知识来源与版本",
            children: <AdminKnowledgeBrowser />,
          },
        ]}
      />
    </div>
  );
}
