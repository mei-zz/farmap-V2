import { Flex, message } from "antd";
import Header from "./Header";
import Image from "./Image";
import PromptInput from "../revision/PromptInput";
import type { CaseContent } from "@/types/expert";
import { Loader2 } from "lucide-react";
import { req } from "@/utils/reqeust";
import { permanence } from "@/utils/permanence";
import { useEffect, useState } from "react";
import Content from "./Content";

const token = permanence.token.useToken();

interface Props {
  requestId: string | null;
  header: CaseContent["userRequestInfo"];
  content: CaseContent["initialResultInfo"];
  loading: boolean;
  onSubmitSuccess: () => void;
}
export default function DetailPrevew(props: Props) {
  const [showPromptInput, setShowPromptInput] = useState(false);

  const [submistLoading, setSubmitLoading] = useState(false);
  const [submitResult, setSubmitResult] = useState("");

  // Revise json data
  const [jsonData, setJsonData] = useState(props.content.jsonData);
  useEffect(() => {
    setJsonData(props.content.jsonData);
  }, [props.content.jsonData]);

  // Show message
  const [messageApi, contextHolder] = message.useMessage();
  const showMessage = (msg: string) => {
    messageApi.error(msg);
  };

  const [revisionPrompt, setRevisionPrompt] = useState("");
  const [jsonDataModifyLoading, setJsonDataModifyLoading] = useState(false);
  const onAudioRevise = async () => {
    try {
      setJsonDataModifyLoading(true);
      const resp = await req.post<
        { jsonData: string; modificationText: string },
        { data: CaseContent }
      >(
        "/ai-json/modify",
        {
          jsonData: jsonData,
          modificationText: revisionPrompt,
        },
        {
          Authorization: `Bearer ${token}`,
        },
      );
      setJsonData(JSON.stringify(resp.data));
      setRevisionPrompt("");
    } catch {
      showMessage("修改内容失败，请重试。");
    } finally {
      setJsonDataModifyLoading(false);
    }
  };

  return (
    <>
      {contextHolder}
      {!props.requestId || props.loading ? (
        <>
          {props.loading ? (
            <Flex gap="0.5rem">
              <Loader2 className="w-6 h-6 text-blue-500 animate-spin" />
              <div>记录加载中...</div>
            </Flex>
          ) : (
            <div>请选择记录。</div>
          )}
        </>
      ) : (
        <Flex gap="0.5rem" style={{ height: "100%" }} className="resp-expert__detail">
          <Image urls={props.header.imageUrls} />
          <Flex vertical style={{ flexGrow: 1, height: "100%" }}>
            <Header requestId={props.header.requestId} modelVersion={props.content.modelVersion} />
            <Flex vertical justify="space-between" style={{ height: "calc(100% - 50px)" }}>
              <Content
                jsonData={jsonData}
                element="input"
                unSubmittable={false}
                submitLoading={submistLoading}
                submitResult={submitResult}
                onSubmit={async (j) => {
                  try {
                    setSubmitLoading(true);

                    const resp = await req.post<
                      {
                        revisedJson: string;
                        isAgree: 1;
                        revisionNotes: string;
                      },
                      { data: string }
                    >(
                      `/expert/cases/${props.requestId}/revise`,
                      {
                        revisedJson: j,
                        isAgree: 1,
                        revisionNotes: "提交修改111",
                      },
                      { Authorization: `Bearer ${token}` },
                    );
                    setSubmitResult(resp.data);
                    // Fetch pending cases again
                    props.onSubmitSuccess();
                  } catch {
                    setSubmitResult("提交修改出错");
                  } finally {
                    setSubmitLoading(false);
                  }
                }}
                onParseEnd={(status) => {
                  setShowPromptInput(status);
                }}
              />
              {showPromptInput && (
                <PromptInput
                  prompt={revisionPrompt}
                  onSubmit={onAudioRevise}
                  onChange={(newPrompt) => {
                    setRevisionPrompt(newPrompt);
                  }}
                  loading={jsonDataModifyLoading}
                />
              )}
            </Flex>
          </Flex>
        </Flex>
      )}
    </>
  );
}
