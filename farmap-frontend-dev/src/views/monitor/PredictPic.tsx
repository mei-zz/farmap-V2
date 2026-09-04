import { Divider } from "antd";
import { useState } from "react";
import Upload from "../model/analyze/Upload";
import ModelResult from "../model/ModelResult";
import { Loader2 } from "lucide-react";
import type { ResultType } from "../model";

export default function PredictPic() {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState("请输入图片进行模型推理。");
  const [type, setType] = useState<ResultType>("default");

  return (
    <>
      <Upload
        onProgress={(curr, status) => {
          if (status === "error") {
            setLoading(false);
            return;
          }

          if (curr < 3) setLoading(true);
          else setLoading(false);
        }}
        onFinish={(res, type) => {
          setResult(res);
          setType(type);
        }}
      />

      <Divider />

      <div style={{ height: "calc(100% - 205px)" }}>
        <h4 style={{ padding: 0, margin: 0, marginBottom: "1rem" }}>推理结果</h4>
        <div style={{ height: "100%", overflowY: "scroll" }}>
          {loading ? (
            <Loader2 className="w-6 h-6 text-blue-500 animate-spin" />
          ) : (
            <ModelResult text={result} type={type} />
          )}
        </div>
      </div>
    </>
  );
}
