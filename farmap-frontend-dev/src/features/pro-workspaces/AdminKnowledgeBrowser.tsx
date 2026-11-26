import { useEffect, useState } from "react";
import { Alert, Button, Card, Empty, Popconfirm, Space, Spin, Tag } from "antd";
import {
  listKnowledgeSources,
  type KnowledgeSource,
} from "@/features/knowledge-expert/service";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
type Document = {
  documentId: string;
  title: string;
  status?: string;
  currentVersionId?: string;
};
type Version = {
  versionId: string;
  versionNumber?: number;
  content?: string;
  createdAt?: string;
};
type Envelope<T> = { data?: T; msg?: string; code?: number };
const headers = () => ({
  Authorization: `Bearer ${permanence.token.useToken()}`,
});
async function readList<T>(path: string): Promise<T[]> {
  const res = await req.get<Envelope<T[]>>(path, headers());
  if (!Array.isArray(res.data)) throw new Error(res.msg || "数据不可用");
  return res.data;
}
export default function AdminKnowledgeBrowser() {
  const [sources, setSources] = useState<KnowledgeSource[]>([]);
  const [source, setSource] = useState<KnowledgeSource>();
  const [docs, setDocs] = useState<Document[]>([]);
  const [doc, setDoc] = useState<Document>();
  const [versions, setVersions] = useState<Version[]>([]);
  const [loading, setLoading] = useState("");
  const [error, setError] = useState<string>();
  const loadSources = async () => {
    setSources(await listKnowledgeSources());
  };
  useEffect(() => {
    let live = true;
    setLoading("sources");
    listKnowledgeSources()
      .then((data) => {
        if (live) setSources(data);
      })
      .catch((e: unknown) => {
        if (live) setError(e instanceof Error ? e.message : "来源不可用");
      })
      .finally(() => {
        if (live) setLoading("");
      });
    return () => {
      live = false;
    };
  }, []);
  const openSource = async (item: KnowledgeSource) => {
    setSource(item);
    setDoc(undefined);
    setDocs([]);
    setVersions([]);
    setError(undefined);
    setLoading("docs");
    try {
      setDocs(
        await readList<Document>(
          `/api/knowledge/sources/${encodeURIComponent(item.sourceId)}/documents`,
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "文档不可用");
    } finally {
      setLoading("");
    }
  };
  const openDoc = async (item: Document) => {
    setDoc(item);
    setVersions([]);
    setError(undefined);
    setLoading("versions");
    try {
      setVersions(
        await readList<Version>(
          `/api/knowledge/documents/${encodeURIComponent(item.documentId)}/versions`,
        ),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : "版本不可用");
    } finally {
      setLoading("");
    }
  };
  const action = async (path: string, after: () => Promise<void>) => {
    setError(undefined);
    setLoading("action");
    try {
      const res = await req.post<Record<string, never>, Envelope<unknown>>(
        path,
        {},
        headers(),
      );
      if (!res.data) throw new Error(res.msg || "操作失败");
      await after();
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败");
    } finally {
      setLoading("");
    }
  };
  const protectedSource = source?.sourceType === "PACKAGED";
  return (
    <div className="fm-pro-admin-knowledge">
      {error && <Alert showIcon type="error" title={error} />}
      <Card title="知识来源">
        {loading === "sources" ? (
          <Spin />
        ) : sources.length ? (
          sources.map((item) => (
            <div className="fm-pro-source" key={item.sourceId}>
              <Button
                type={source?.sourceId === item.sourceId ? "primary" : "link"}
                onClick={() => void openSource(item)}
              >
                {item.name}
              </Button>
              <Space wrap>
                <Tag>{item.status || "未知"}</Tag>
                {item.sourceType !== "PACKAGED" && (
                  <Popconfirm
                    title={`确认${item.status === "DISABLED" ? "启用" : "停用"}该来源？`}
                    onConfirm={() =>
                      void action(
                        `/api/knowledge/sources/${encodeURIComponent(item.sourceId)}/${item.status === "DISABLED" ? "enable" : "disable"}`,
                        async () => {
                          await loadSources();
                          if (source?.sourceId === item.sourceId)
                            setSource({
                              ...item,
                              status:
                                item.status === "DISABLED"
                                  ? "ACTIVE"
                                  : "DISABLED",
                            });
                        },
                      )
                    }
                  >
                    <Button size="small" loading={loading === "action"}>
                      {item.status === "DISABLED" ? "启用" : "停用"}
                    </Button>
                  </Popconfirm>
                )}
              </Space>
            </div>
          ))
        ) : (
          <Empty description="暂无知识来源" />
        )}
      </Card>
      <Card title={source ? `${source.name} · 文档` : "文档"}>
        {!source ? (
          <Empty description="请选择知识来源" />
        ) : loading === "docs" ? (
          <Spin />
        ) : docs.length ? (
          docs.map((item) => (
            <div className="fm-pro-source" key={item.documentId}>
              <Button
                type={doc?.documentId === item.documentId ? "primary" : "link"}
                onClick={() => void openDoc(item)}
              >
                {item.title}
              </Button>
              <Space wrap>
                <Tag>{item.status || "未知"}</Tag>
                {!protectedSource && (
                  <Popconfirm
                    title={`确认${item.status === "DISABLED" ? "启用" : "停用"}该文档？`}
                    onConfirm={() =>
                      void action(
                        `/api/knowledge/documents/${encodeURIComponent(item.documentId)}/${item.status === "DISABLED" ? "enable" : "disable"}`,
                        () => openSource(source),
                      )
                    }
                  >
                    <Button size="small">
                      {item.status === "DISABLED" ? "启用" : "停用"}
                    </Button>
                  </Popconfirm>
                )}
              </Space>
            </div>
          ))
        ) : (
          <Empty description="暂无文档" />
        )}
      </Card>
      <Card title={doc ? `${doc.title} · 版本` : "版本"}>
        {!doc ? (
          <Empty description="请选择文档" />
        ) : loading === "versions" ? (
          <Spin />
        ) : versions.length ? (
          versions.map((item) => (
            <div className="fm-pro-version" key={item.versionId}>
              <Space wrap>
                <b>版本 {item.versionNumber ?? "—"}</b>
                <span>{item.createdAt}</span>
                {doc.currentVersionId === item.versionId ? (
                  <Tag color="green">当前版本</Tag>
                ) : (
                  !protectedSource && (
                    <Popconfirm
                      title="确认启用此版本？"
                      onConfirm={() =>
                        void action(
                          `/api/knowledge/documents/${encodeURIComponent(doc.documentId)}/versions/${encodeURIComponent(item.versionId)}/activate`,
                          async () => {
                            const next = {
                              ...doc,
                              currentVersionId: item.versionId,
                            };
                            setDoc(next);
                            setDocs((current) =>
                              current.map((d) =>
                                d.documentId === next.documentId ? next : d,
                              ),
                            );
                          },
                        )
                      }
                    >
                      <Button size="small">设为当前版本</Button>
                    </Popconfirm>
                  )
                )}
              </Space>
              {item.content && (
                <details>
                  <summary>查看内容</summary>
                  <p>{item.content}</p>
                </details>
              )}
            </div>
          ))
        ) : (
          <Empty description="暂无版本" />
        )}
      </Card>
    </div>
  );
}
