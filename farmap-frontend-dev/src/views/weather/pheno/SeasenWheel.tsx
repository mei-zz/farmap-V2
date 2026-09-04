import { useState, useEffect, useRef, useCallback, useMemo } from "react";
import { Flex } from "antd";

interface Props {
  idleTimeoutSeconds?: number;
  onMonthChange?: (monthIndex: number) => void;
  size?: number;
}

interface MonthData {
  index: number;
  label: string;
  seasonColor: string;
  seasonName: string;
}

// Helper to convert polar coordinates to cartesian
const polarToCartesian = (
  centerX: number,
  centerY: number,
  radius: number,
  angleInDegrees: number,
) => {
  const angleInRadians = ((angleInDegrees - 90) * Math.PI) / 180.0;
  return {
    x: centerX + radius * Math.cos(angleInRadians),
    y: centerY + radius * Math.sin(angleInRadians),
  };
};

// Helper to create an SVG arc path
const describeArc = (
  x: number,
  y: number,
  innerRadius: number,
  outerRadius: number,
  startAngle: number,
  endAngle: number,
) => {
  const start = polarToCartesian(x, y, outerRadius, endAngle);
  const end = polarToCartesian(x, y, outerRadius, startAngle);
  const startInner = polarToCartesian(x, y, innerRadius, endAngle);
  const endInner = polarToCartesian(x, y, innerRadius, startAngle);

  const largeArcFlag = endAngle - startAngle <= 180 ? "0" : "1";

  const d = [
    "M",
    start.x,
    start.y,
    "A",
    outerRadius,
    outerRadius,
    0,
    largeArcFlag,
    0,
    end.x,
    end.y,
    "L",
    endInner.x,
    endInner.y,
    "A",
    innerRadius,
    innerRadius,
    0,
    largeArcFlag,
    1,
    startInner.x,
    startInner.y,
    "Z",
  ].join(" ");

  return d;
};

// Constants for seasons
// Winter: Dec (11), Jan (0), Feb (1) - Blue
// Spring: Mar (2), Apr (3), May (4) - Green
// Summer: Jun (5), Jul (6), Aug (7) - Red/Orange
// Autumn: Sep (8), Oct (9), Nov (10) - Gold
const getSeasonColor = (monthIndex: number): string => {
  if (monthIndex === 11 || monthIndex === 0 || monthIndex === 1) return "#3b82f6"; // Winter (Blue-500)
  if (monthIndex >= 2 && monthIndex <= 4) return "#10b981"; // Spring (Emerald-500)
  if (monthIndex >= 5 && monthIndex <= 7) return "#f43f5e"; // Summer (Rose-500)
  return "#f59e0b"; // Autumn (Amber-500)
};

const getSeasonName = (monthIndex: number): string => {
  if (monthIndex === 11 || monthIndex === 0 || monthIndex === 1) return "冬";
  if (monthIndex >= 2 && monthIndex <= 4) return "春";
  if (monthIndex >= 5 && monthIndex <= 7) return "夏";
  return "秋";
};

export default function SeasonWheel({ idleTimeoutSeconds = 10, onMonthChange, size = 300 }: Props) {
  const [selectedMonth, setSelectedMonth] = useState<number>(new Date().getMonth());
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cx = size / 2;
  const cy = size / 2;
  const innerRadius = size * 0.15; // Small hole in middle
  const baseOuterRadius = size * 0.38; // Normal size
  const selectedOuterRadius = size * 0.46; // Pop out size (~1.2x)

  // Generate month data
  const months: MonthData[] = useMemo(
    () =>
      Array.from({ length: 12 }, (_, i) => ({
        index: i,
        label: `${i + 1}月`,
        seasonColor: getSeasonColor(i),
        seasonName: getSeasonName(i),
      })),
    [],
  );

  const triggerChange = useCallback(
    (index: number) => {
      setSelectedMonth(index);
      if (onMonthChange) {
        onMonthChange(index);
      }
    },
    [onMonthChange],
  );

  const resetToRealCurrent = useCallback(() => {
    const realCurrentMonth = new Date().getMonth();
    triggerChange(realCurrentMonth);
  }, [triggerChange]);

  const handleSliceClick = (index: number) => {
    // 1. Clear existing timer
    if (timerRef.current) {
      clearTimeout(timerRef.current);
    }

    // 2. Trigger event and visual update
    triggerChange(index);

    // 3. Start new timer to reset
    timerRef.current = setTimeout(() => {
      resetToRealCurrent();
    }, idleTimeoutSeconds * 1000);
  };

  // Cleanup on unmount
  useEffect(() => {
    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, []);

  return (
    <Flex
      align="center"
      justify="center"
      style={{ position: "relative", width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        {/* Glow Filter */}
        <defs>
          <filter id="glow" x="-20%" y="-20%" width="140%" height="140%">
            <feGaussianBlur stdDeviation="4" result="blur" />
            <feComposite in="SourceGraphic" in2="blur" operator="over" />
          </filter>
        </defs>

        {/* Center Decoration */}
        <circle cx={cx} cy={cy} r={innerRadius - 5} fill="#ddd" stroke="#333" strokeWidth="2" />

        {months.map((month) => {
          const isSelected = selectedMonth === month.index;
          const startAngle = month.index * 30;
          const endAngle = (month.index + 1) * 30;

          // Determine radius based on state
          const currentOuterRadius = isSelected ? selectedOuterRadius : baseOuterRadius;

          // Arc Path
          const pathD = describeArc(cx, cy, innerRadius, currentOuterRadius, startAngle, endAngle);

          // Text Position (Calculate center of the arc wedge)
          const midAngle = startAngle + 15;
          const textRadius = innerRadius + (currentOuterRadius - innerRadius) * 0.6;
          const textPos = polarToCartesian(cx, cy, textRadius, midAngle);

          return (
            <g
              key={month.index}
              onClick={() => handleSliceClick(month.index)}
              style={{
                transformOrigin: `${cx}px ${cy}px`,
                transition: "all 0.3s ease-out",
                cursor: "pointer",
              }}>
              <path
                d={pathD}
                fill={month.seasonColor}
                fillOpacity={isSelected ? 1 : 0.2}
                stroke="#141821"
                strokeWidth="2"
                filter={isSelected ? "url(#glow)" : undefined}
                style={{ transition: "all 0.3s ease-out" }}
              />
              <text
                x={textPos.x}
                y={textPos.y}
                dy="0.35em"
                textAnchor="middle"
                style={{
                  pointerEvents: "none",
                  transition: "all 0.3s ease-out",
                  fill: isSelected ? "#eee" : "black",
                }}>
                {month.label}
              </text>
            </g>
          );
        })}

        {/* Center Label showing Season or Year */}
        <text x={cx} y={cy} dy="0.35em" textAnchor="middle">
          {getSeasonName(selectedMonth)}
        </text>
      </svg>
    </Flex>
  );
}
