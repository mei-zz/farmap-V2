import React from "react";
import type { PlantData, AnalysisResult, PlantValidation, ValidationSummary } from "@/types/expert";
import { Activity, Search } from "lucide-react";
import { Divider, Flex } from "antd";

// --- Shared Helper: Editable Field ---
// "Short use input, long use textarea"

interface EditableFieldProps {
  value: any;
  onChange: (val: any) => void;
  label?: string;
  element: "input" | "view";
}

const EditableField: React.FC<EditableFieldProps> = ({ value, onChange, label, element }) => {
  const isString = typeof value === "string";
  const isNumber = typeof value === "number";
  const isBoolean = typeof value === "boolean";

  // Boolean Toggle
  if (isBoolean) {
    return (
      <div className="flex items-center gap-3 py-1">
        <label className="relative inline-flex items-center cursor-pointer group">
          <input
            type="checkbox"
            checked={value}
            onChange={(e) => onChange(e.target.checked)}
            className="sr-only peer"
          />
          <div className="w-10 h-5 bg-gray-200 peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-emerald-300 rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-emerald-600 transition-colors"></div>
          {label && (
            <span className="ml-3 text-sm text-gray-600 group-hover:text-gray-800 transition-colors select-none">
              {label}
            </span>
          )}
        </label>
      </div>
    );
  }

  // Text Logic
  const strVal = String(value);
  const useTextarea = isString && (strVal.length > 25 || strVal.includes("\n"));

  return (
    <div className="w-full">
      {label && (
        <label className="block text-sm font-medium text-gray-500 mb-1.5 uppercase tracking-wide">
          {label}
        </label>
      )}
      {element === "input" ? (
        <>
          {useTextarea ? (
            <textarea
              value={value}
              onChange={(e) => onChange(e.target.value)}
              rows={Math.max(2, Math.ceil(strVal.length / 35))}
              className="w-full px-2 text-sm text-gray-800 border border-gray-200 rounded-xs focus:outline-none focus:ring-2 focus:ring-emerald-500/20 focus:border-emerald-500 transition-all bg-white hover:border-gray-300 "
            />
          ) : (
            <input
              type={isNumber ? "number" : "text"}
              value={value}
              onChange={(e) => onChange(isNumber ? parseFloat(e.target.value) : e.target.value)}
              className="w-full px-2 text-sm text-gray-800 border border-gray-200 rounded-xs focus:outline-none focus:ring-2 focus:ring-emerald-500/20 focus:border-emerald-500 transition-all bg-white hover:border-gray-300 "
            />
          )}
        </>
      ) : (
        <p className="w-full px-2 text-sm text-gray-800 border border-gray-200 rounded-xs bg-white">
          {value}
        </p>
      )}
    </div>
  );
};

// --- Sub-Component: Plant Validation Section ---

interface SectionProps<T> {
  data: T;
  path: (string | number)[];
  element: "input" | "view";
  onUpdate: (path: (string | number)[], value: any) => void;
}

const PlantValidationSection: React.FC<{ data: PlantValidation }> = ({ data }) => {
  return (
    <div>
      <label className="text-sm font-bold uppercase tracking-wide">
        <div className="flex items-center gap-3">
          <Search className="w-4 h-4 text-blue-500" />
          <h3>识别置信度</h3>
        </div>
      </label>
      <p className="text-gray-800 mt-1 font-mono text-sm">置信度：{data.confidence}</p>
      <p className="text-gray-800 mt-1 font-mono text-sm">图片情况：{data.message}</p>
      {/*
      <p className="text-gray-800 mt-1 font-mono text-sm">详情：{data.details}</p>
        */}
    </div>
  );
};

// --- Sub-Component: Analysis Result Section ---

// Helper to render a group of properties (either nested object or single value)
const PropertyGroup: React.FC<{
  label: string;
  data: any;
  path: (string | number)[];
  element: "input" | "view";
  onUpdate: (path: (string | number)[], value: any) => void;
}> = ({ label, data, path, onUpdate, element }) => {
  if (label === "回答置信度") return null;

  // If the data is just a string (e.g., "当前生长阶段")
  if (typeof data !== "object" || data === null) {
    return (
      <div>
        <h4 className="text-sm font-bold text-gray-700 mb-3 border-t border-gray-200">{label}</h4>
        <EditableField value={data} element={element} onChange={(val) => onUpdate(path, val)} />
      </div>
    );
  }

  // If data is an object, render its keys
  return (
    <div className="pt-2 text-sm">
      <h4 className="font-bold text-gray-700 mb-3 border-t border-gray-200">{label}</h4>
      <div className="grid grid-cols-1 gap-4">
        {Object.entries(data).map(([key, val]) => (
          <div key={key}>
            <label className="block font-medium text-gray-500 mb-1.5">{key}</label>
            <EditableField
              value={val}
              element={element}
              onChange={(newVal) => onUpdate([...path, key], newVal)}
            />
          </div>
        ))}
      </div>
    </div>
  );
};

const AnalysisResultSection = ({ data, path, onUpdate, element }: SectionProps<AnalysisResult>) => {
  // We can iterate over keys because we know the structure, but we want to present them nicely.
  // We'll use a grid layout for the cards.

  return (
    <div className="h-full">
      <label className="text-sm font-bold uppercase tracking-wide">
        <div className="flex items-center gap-3">
          <Activity className="w-4 h-4 text-emerald-600" />
          <h3 className="text-sm font-bold text-gray-800">分析结果</h3>
        </div>
      </label>

      <div className="flex gap-2 flex-col h-full">
        {/* Iterate through fixed keys to ensure we render everything in the object */}
        {Object.entries(data).map(([key, value]) => (
          <PropertyGroup
            key={key}
            label={key}
            data={value}
            path={[...path, key]}
            element={element}
            onUpdate={onUpdate}
          />
        ))}
      </div>
    </div>
  );
};

// --- Sub-Component: Validation Summary Section ---

const ValidationSummarySection: React.FC<{
  data: ValidationSummary | undefined;
}> = ({ data }) => {
  if (!data) return null;
  return (
    <div>
      <label className="text-sm font-bold uppercase tracking-wide">
        <div className="flex items-center gap-3">
          <CheckCircleIcon className="w-4 h-4 text-purple-500" />
          <h3>图像验证</h3>
        </div>
      </label>
      <p className="text-gray-800 mt-1 font-mono text-sm">置信度：{data.plant_type}</p>
      <p className="text-gray-800 mt-1 font-mono text-sm">图片情况：{data.count}</p>
      <p className="text-gray-800 mt-1 font-mono text-sm">图片情况：{data.quality}</p>
      {/*
      <p className="text-gray-800 mt-1 font-mono text-sm">详情：{data.details}</p>
        */}
    </div>
  );
};

// Internal icon for this file
const CheckCircleIcon = (props: any) => (
  <svg
    xmlns="http://www.w3.org/2000/svg"
    width="24"
    height="24"
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    {...props}>
    <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
    <polyline points="22 4 12 14.01 9 11.01" />
  </svg>
);

// --- Main Form Component ---

export const RevisionForm: React.FC<SectionProps<PlantData>> = ({ data, onUpdate, element }) => {
  return (
    <Flex vertical style={{ height: "100%" }}>
      <div
        className="space-y-2 animate-in fade-in duration-500"
        style={{ flexGrow: 1, height: "100%" }}>
        {/* 1. Validation Metadata (Always present) */}
        <PlantValidationSection data={data.plant_validation} />
        <ValidationSummarySection data={data.validation} />
        <Divider style={{ margin: 5 }} />

        {/* 2. Analysis Result (Only if present) */}
        {data.analysis_result && (
          <AnalysisResultSection
            data={data.analysis_result}
            path={["analysis_result"]}
            element={element}
            onUpdate={onUpdate}
          />
        )}
      </div>
    </Flex>
  );
};
