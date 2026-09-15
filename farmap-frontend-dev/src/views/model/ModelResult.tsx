import { useEffect, useState, type JSX } from "react";
import PlantReport from "./PlantReport";
import { AlertCircle } from "lucide-react";
import type { ResultType } from ".";

interface Props {
  text: string;
  type: ResultType;
}
export default function ModelResult(props: Props) {
  const [text, setText] = useState<JSX.Element>(<span>{props.text}</span>);

  useEffect(() => {
    if (props.type === "default") return;

    if (props.type === "error") {
      const span = (
        <div className="p-8 text-center text-red-500 bg-red-50 rounded-lg border border-red-200">
          <AlertCircle className="w-10 h-10 mx-auto mb-2" />
          <p>无法解析 json 数据，请检查输入图片是否清晰。</p>
        </div>
      );
      setText(span);
      return;
    }

    // Get the analyzed result
    const report = <PlantReport jsonString={props.text} />;
    setText(report);
  }, [props]);

  return <>{text}</>;
}
