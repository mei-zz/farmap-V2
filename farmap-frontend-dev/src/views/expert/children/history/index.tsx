import type { CaseContent } from "@/types/expert";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import { Card, Flex, Select } from "antd";
import { useEffect, useState } from "react";
import Content from "../../preview/Content";
import { Loader2 } from "lucide-react";
import Image from "../../preview/Image";

const token = permanence.token.useToken();

export default function index() {
  // --- States ---
  // Expert pending cases
  const [pendingCases, setPendingCases] = useState<string[]>([]);
  const [listLoading, setListLoading] = useState(false);

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
    fecthList(() => {
      setListLoading(false);
    });
  }, []);

  useEffect(() => {
    if (selectedRequestId) {
      fetchCaseDetail(selectedRequestId);
    }
  }, [selectedRequestId]);

  // --- Handlers ---
  async function fecthList(cb: () => void) {
    try {
      const resp = await req.get<{ data: string[] }>("/expert/modified-request-ids", {
        Authorization: `Bearer ${token}`,
      });
      setPendingCases(resp.data);
    } catch {
      console.log("[FARMAP]: failed to fetch expert pending cases.");
    } finally {
      cb();
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

  interface RevisedHistory {
    revisionId: string;
    expertId: number;
    revisionTime: string;
    isAgree: string;
    revisionNotes: string;
  }
  interface RevisedHistoryResponse {
    code: number;
    msg: null;
    data: {
      requestId: string;
      initialJson: string;
      revisionDataList: RevisedHistory[];
    };
  }

  // Revised history
  const [revisedHistory, setRevisedHistory] = useState<RevisedHistory[]>([]);
  async function fetchRevisedHistory(id: string) {
    try {
      const resp = await req.get<RevisedHistoryResponse>(`/expert/request-data-comparison/${id}`, {
        Authorization: token,
      });
      console.log("request-data-comparison", resp);
      setRevisedHistory(resp.data.revisionDataList);
    } catch {
      // PERF: failed to fetch
    }
  }

  return (
    <Card title="修改历史" variant="borderless" className="">
      <Flex gap="1rem" vertical>
        <Select
          style={{ width: "100%" }}
          loading={listLoading}
          disabled={listLoading}
          defaultValue="请选择 request id"
          options={pendingCases.map((c) => ({
            value: c,
            label: c,
          }))}
          onChange={(rid) => {
            setSelectedRequestId(rid);
            fetchRevisedHistory(rid);
          }}></Select>
        {detail.initialResultInfo.jsonData && (
          <div className="grow h-full">
            {detailLoading ? (
              <Loader2 className="w-6 h-6 text-blue-500 animate-spin" />
            ) : (
              <>
                <Select
                  style={{ width: "100%" }}
                  defaultValue="请选择修改记录"
                  options={revisedHistory.map((h) => ({
                    value: h.revisionId,
                    label: `[专家id：${h.expertId}]-${h.revisionId}`,
                  }))}></Select>
                <Flex
                  style={{ marginTop: "1rem", width: "100%" }}
                  gap="1rem"
                  className="resp-expert__history-content">
                  <Image urls={detail.userRequestInfo.imageUrls} />
                  <Content
                    jsonData={detail.initialResultInfo.jsonData}
                    element="view"
                    unSubmittable={true}
                    submitLoading={false}
                    submitResult=""
                    onSubmit={() => {}}
                    onParseEnd={() => {}}
                  />
                </Flex>
              </>
            )}
          </div>
        )}
      </Flex>
    </Card>
  );
}
