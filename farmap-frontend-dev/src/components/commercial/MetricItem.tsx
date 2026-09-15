import type { ReactNode } from "react";

export default function MetricItem({ label, value, unit, trend, icon }: {
  label: string;
  value: string | number;
  unit?: string;
  trend?: string;
  icon?: ReactNode;
}) {
  return (
    <div className="fm-metric">
      <div className="fm-metric__label">{icon}<span>{label}</span></div>
      <div className="fm-metric__value">{value}<small>{unit}</small></div>
      {trend && <div className="fm-metric__trend">{trend}</div>}
    </div>
  );
}
