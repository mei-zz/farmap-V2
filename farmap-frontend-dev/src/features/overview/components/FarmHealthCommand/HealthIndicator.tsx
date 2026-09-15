import type { ReactNode } from "react";

type HealthIndicatorProps = {
  label: string;
  value: string;
  detail: string;
  icon: ReactNode;
  tone?: "healthy" | "attention" | "risk" | "neutral";
};

export default function HealthIndicator({ label, value, detail, icon, tone = "neutral" }: HealthIndicatorProps) {
  return (
    <div className={`fm-health-indicator fm-health-indicator--${tone}`}>
      <div className="fm-health-indicator__icon">{icon}</div>
      <div className="fm-health-indicator__body">
        <span>{label}</span>
        <strong>{value}</strong>
        <small>{detail}</small>
      </div>
    </div>
  );
}
