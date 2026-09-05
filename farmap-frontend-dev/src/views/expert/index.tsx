import { Card, Divider, Flex, List, Tag, Typography } from "antd";

import { useEffect, useState } from "react";
import { req } from "@/utils/reqeust";
import { CoffeeOutlined, OrderedListOutlined } from "@ant-design/icons";

import { permanence } from "@/utils/permanence";
import DetailPrevew from "./preview//DetailPrevew";
import type { CaseContent, CasesStoreResult, PendingCase } from "@/types/expert";
import { Loader2 } from "lucide-react";
const token = permanence.token.useToken();

export default function Expert() {
  // --- States ---

  // Expert pending cases
  const [pendingCases, setPendingCases] = useState<PendingCase[]>([]);
  const [listLoading, setListLoading] = useState(false);
  const [reviewStates, setReviewStates] = useState<Array<{ diagnosisId: string; status: string; reviewer?: string; reviewedAt?: string }>>([]);

  // Selection & details
  const [selectedRequestId, setSelectedRequestId] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const initialCaseContent: Omit<CaseContent, "onClear"> = {
    userRequestInfo: {
      requestId: "",
      imageUrls: "",
      uploadTime: "",
      status: 1,
    },
    initialResultInfo: {
      jsonData: "",
      modelVersion: "",
    },
    revisionRecords: [],
  };
  const [detail, setDetail] = useState<Omit<CaseContent, "onClear">>(initialCaseContent);

  // --- Effects ---

  // Fetch pending cases list
  useEffect(() => {
    setListLoading(true);
    fecthList().finally(() => {
      setListLoading(false);
    });
  }, []);

  useEffect(() => {
    req.get<{ data: Array<{ diagnosisId: string; status: string; reviewer?: string; reviewedAt?: string }> }>("/api/expert-review", { Authorization: `Bearer ${token}` })
      .then((response) => setReviewStates(response.data || []))
      .catch(() => setReviewStates([]));
  }, []);

  useEffect(() => {
    if (selectedRequestId) {
      fetchCaseDetail(selectedRequestId);
    }
  }, [selectedRequestId]);

  // --- Handlers ---
  async function fecthList() {
    try {
      const resp = await req.get<CasesStoreResult>("/expert/pending-cases", {
        Authorization: `Bearer ${token}`,
      });
      setPendingCases(resp.data.list);
    } catch {
      console.log("[FARMAP]: failed to fetch expert pending cases.");
    }
  }

  async function fetchCaseDetail(id: string) {
    setDetailLoading(true);
    try {
      const resp = await req.get<{ data: CaseContent }>(`/expert/cases/${id}`, {
        Authorization: token,
      });
      setDetail(resp.data);
    } catch {
    } finally {
      setDetailLoading(false);
    }
  }

  return (
    <Flex gap="0.5rem" style={{ height: "100%" }} className="resp-expert__container">
      <Card
        title={
          <>
            <OrderedListOutlined />
            &nbsp;&nbsp;待修改列表
          </>
        }
        style={{ flex: 0 }}
        className="resp-expert__container-list"
        classNames={{ body: "resp-expert__container-list-cardbody" }}>
        <div style={{ marginBottom: "0.5rem" }}>请点击列表内容以开始校正。</div>
        <div
          style={{
            fontSize: "0.8rem",
            border: "1px solid #eee",
            borderRadius: 8,
            height: "calc(100% - 2rem)",
            padding: 2,
            overflowX: "hidden",
            overflowY: "scroll",
          }}>
          {listLoading ? (
            <Loader2 className="w-6 h-6 text-blue-500 animate-spin" />
          ) : (
            pendingCases.map((cs) => (
              <div key={cs.requestId}>
                <div
                  style={{ cursor: "pointer", display: "flex", padding: "12px 5px" }}
                  onClick={() => {
                    setSelectedRequestId(cs.requestId);
                  }}
                  className="resp-expert__container-list-item">
                  <p>
                    <span style={{ backgroundColor: "#ffdd00" }}>
                      #{cs.requestId.substring(0, 8)}
                    </span>
                  </p>
                  <div>&nbsp;&nbsp;{cs.uploadTime.substring(0, 10)}</div>
                  <div>&nbsp;&nbsp;{cs.imageCount} 张图片</div>
                  <div>&nbsp;&nbsp;已修改 {cs.revisionCount} 次 </div>
                </div>
                <Divider style={{ margin: 0 }} />
              </div>
            ))
          )}
          {reviewStates.length > 0 && <div style={{ padding: "12px 8px" }}><Typography.Text strong>Agent 诊断复核 / 历史候选</Typography.Text><List size="small" dataSource={reviewStates} renderItem={(review) => <List.Item><span>{review.diagnosisId}</span><Flex gap={4}><Tag color={review.status === "CORRECTED" ? "orange" : review.status === "CONFIRMED" ? "green" : "blue"}>{review.status}</Tag>{review.status === "CONFIRMED" && <Tag color="cyan">历史候选</Tag>}</Flex></List.Item>} /></div>}
        </div>
      </Card>
      <Flex style={{ flexGrow: 1 }}>
        <Card
          title={
            <>
              <CoffeeOutlined />
              &nbsp;&nbsp;当前修改作物
            </>
          }
          style={{ width: "100%" }}
          styles={{ body: { height: "calc(100% - 60px)" } }}>
          <DetailPrevew
            requestId={selectedRequestId}
            loading={detailLoading}
            header={detail.userRequestInfo}
            content={detail.initialResultInfo}
            onSubmitSuccess={() => {
              try {
                setListLoading(true);
                fecthList();
                setSelectedRequestId(null);
              } catch {
              } finally {
                setListLoading(false);
              }
            }}
          />
        </Card>
      </Flex>
    </Flex>
  );
}
