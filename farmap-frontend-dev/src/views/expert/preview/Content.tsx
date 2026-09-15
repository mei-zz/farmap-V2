import { useState, useEffect, useCallback } from "react";
import type { PlantAnalysisEditorProps, PlantData } from "@/types/expert";
import { setNestedValue } from "@/utils/function";
import { AlertTriangle, CheckCircle2, XCircle } from "lucide-react";
import { Button, Divider } from "antd";
import { RevisionForm } from "../revision/RevisionForm";

export default function Content({
  jsonData,
  unSubmittable,
  submitLoading,
  submitResult,
  onSubmit,
  onParseEnd,
  element,
}: PlantAnalysisEditorProps) {
  const [parsedData, setParsedData] = useState<PlantData | null>(null);
  const [parseError, setParseError] = useState<string | null>(null);
  const [isConsistencyError, setIsConsistencyError] = useState(false);

  useEffect(() => {
    try {
      const parsed: PlantData = JSON.parse(jsonData);
      setParsedData(parsed);
      setParseError(null);

      // Check for consistency/validity
      if (parsed.plant_validation?.consistent === false) {
        setIsConsistencyError(true);
        onParseEnd(false);
      } else {
        setIsConsistencyError(false);
        onParseEnd(true);
      }
    } catch (e) {
      setParseError("Invalid JSON string provided.");
      setParsedData(null);
      onParseEnd(false);
    }
  }, [jsonData]);

  const handleFieldUpdate = useCallback((path: (string | number)[], value: any) => {
    setParsedData((prev) => {
      if (!prev) return prev;
      return setNestedValue(prev, path, value);
    });
  }, []);

  const handleSubmit = () => {
    if (parsedData) {
      onSubmit(JSON.stringify(parsedData, null, 2));
    }
  };

  if (parseError) {
    return (
      <div className="p-6 bg-red-50 border border-red-200 rounded-lg text-red-700 flex items-center gap-3">
        <AlertTriangle className="w-6 h-6" />
        <span>{parseError}</span>
      </div>
    );
  }

  if (!parsedData) {
    return null;
  }

  // --- Scenario: Validation Failure (Read Only) ---
  if (isConsistencyError) {
    const { plant_validation } = parsedData;
    return (
      <div>
        <div className="flex items-center gap-3">
          <XCircle className="w-6 h-6 text-red-500" />
          <h2 className="text-lg font-bold text-red-800">识别失败</h2>
        </div>
        <Divider style={{ margin: 5 }} />
        <div>
          <label className="text-sm font-bold text-red-600 uppercase tracking-wide">错误信息</label>
          <p className="text-gray-800 mt-1 font-medium">{plant_validation.message}</p>
        </div>
        <div>
          <label className="text-sm font-bold text-red-600 uppercase tracking-wide">
            识别置信度
          </label>
          <p className="text-gray-800 mt-1 font-mono">{plant_validation.confidence ?? 0}</p>
        </div>
        <div>
          <label className="text-sm font-bold text-red-600 uppercase tracking-wide">详细内容</label>
          <p className="text-gray-800 mt-1">{plant_validation.details}</p>
        </div>
      </div>
    );
  }

  // --- Scenario: Success (Editable) ---
  return (
    <div className="resp-expert__revision-record" style={{ height: "100%" }}>
      <div className="flex items-center gap-3 text-emerald-600 grow">
        <CheckCircle2 className="w-6 h-6" />
        <h2 className="text-lg font-bold">识别结果</h2>
      </div>
      <Divider style={{ margin: 5 }} />
      <div>
        <RevisionForm data={parsedData} path={[]} onUpdate={handleFieldUpdate} element={element} />
      </div>
      {!unSubmittable && (
        <div>
          <Button
            type="primary"
            disabled={submitLoading}
            style={{ marginTop: 10 }}
            onClick={() => {
              handleSubmit();
            }}>
            提交修改
          </Button>
          {submitResult && submitResult.length > 0 && (
            <span className="text-blue-600" style={{ marginLeft: 10 }}>
              {submitResult}
            </span>
          )}
        </div>
      )}
    </div>
  );
}
