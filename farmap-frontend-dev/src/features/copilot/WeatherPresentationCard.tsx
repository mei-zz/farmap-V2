import {
  Clock3,
  Cloud,
  CloudFog,
  CloudLightning,
  CloudMoon,
  CloudOff,
  CloudRain,
  CloudSun,
  Database,
  Droplets,
  MapPin,
  Moon,
  Snowflake,
  Sun,
  Wind,
} from "lucide-react";
import type { AgentUiBlock, AgentWeather, BlockItem } from "./uiBlocks";
import "./weather-presentation-card.css";

type GlyphTone = "sun" | "cloud" | "rain" | "storm" | "snow" | "fog" | "wind" | "night";
type WeatherGlyph = { Icon: typeof Sun; tone: GlyphTone };

const missingValues = new Set(["—", "–", "-", "--", "n/a", "na", "nan", "null", "undefined", "暂无", "未知", "不可用", "暂无数据", "无"]);
const numberFormatter = new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 1 });
const observedDateFormatter = new Intl.DateTimeFormat("zh-CN", { month: "long", day: "numeric", timeZone: "Asia/Shanghai" });
const observedDateTimeFormatter = new Intl.DateTimeFormat("zh-CN", {
  month: "long",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: "Asia/Shanghai",
});
const weekdayFormatter = new Intl.DateTimeFormat("zh-CN", { weekday: "short", timeZone: "Asia/Shanghai" });

function readText(value: unknown): string | undefined {
  const text = typeof value === "number" && Number.isFinite(value)
    ? String(value)
    : typeof value === "string" ? value.trim() : "";
  return text && !missingValues.has(text.toLowerCase()) ? text : undefined;
}

function parseCalendarDate(value: string): Date | undefined {
  let input = value.trim().replace(" ", "T");
  if (/^\d{4}-\d{2}-\d{2}$/u.test(input)) input += "T12:00:00+08:00";
  else if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/u.test(input)) input += "+08:00";
  const date = new Date(input);
  return Number.isFinite(date.getTime()) ? date : undefined;
}

function hasWeatherContent(weather: AgentWeather): boolean {
  return Boolean(weather.condition ?? weather.temperature ?? weather.humidity
    ?? weather.windDirection ?? weather.windPower ?? weather.forecast?.length);
}

function getWeatherGlyph(condition?: string): WeatherGlyph | undefined {
  if (!condition) return undefined;
  const numericCode = /^\d{3}$/u.test(condition) ? Number(condition) : undefined;
  if (numericCode !== undefined) {
    if (numericCode === 100 || numericCode === 101 || numericCode === 102 || numericCode === 103) return { Icon: CloudSun, tone: "sun" };
    if (numericCode === 104) return { Icon: Cloud, tone: "cloud" };
    if (numericCode === 150) return { Icon: Moon, tone: "night" };
    if (numericCode >= 151 && numericCode <= 153) return { Icon: CloudMoon, tone: "night" };
    if (numericCode >= 200 && numericCode <= 299) return { Icon: Wind, tone: "wind" };
    if (numericCode >= 300 && numericCode <= 399) {
      return numericCode >= 302 && numericCode <= 304
        ? { Icon: CloudLightning, tone: "storm" }
        : { Icon: CloudRain, tone: "rain" };
    }
    if (numericCode >= 400 && numericCode <= 499) return { Icon: Snowflake, tone: "snow" };
    if (numericCode >= 500 && numericCode <= 599) return { Icon: CloudFog, tone: "fog" };
    return undefined;
  }

  const text = condition.toLowerCase();
  if (/(雷|thunder|storm|lightning)/u.test(text)) return { Icon: CloudLightning, tone: "storm" };
  if (/(雪|snow|sleet|冰雹|hail)/u.test(text)) return { Icon: Snowflake, tone: "snow" };
  if (/(雨|rain|shower|drizzle)/u.test(text)) return { Icon: CloudRain, tone: "rain" };
  if (/(雾|霾|沙|尘|fog|haze|mist|smoke|dust)/u.test(text)) return { Icon: CloudFog, tone: "fog" };
  if (/(阴|overcast)/u.test(text)) return { Icon: Cloud, tone: "cloud" };
  if (/(云|cloud)/u.test(text)) return { Icon: CloudSun, tone: "sun" };
  if (/(晴|clear|sunny|sun)/u.test(text)) return { Icon: Sun, tone: "sun" };
  if (/(风|wind|gust)/u.test(text)) return { Icon: Wind, tone: "wind" };
  return undefined;
}

function ConditionGlyph({ condition, variant }: { condition?: string; variant: "current" | "forecast" }) {
  const glyph = getWeatherGlyph(condition);
  if (!glyph) return null;
  const Icon = glyph.Icon;
  return (
    <span className={`farmap-weather__glyph farmap-weather__glyph--${variant} farmap-weather__glyph--${glyph.tone}`} aria-hidden="true">
      <Icon size={variant === "current" ? 42 : 22} strokeWidth={1.75} />
    </span>
  );
}

function formatObservedAt(value: string): string {
  const date = parseCalendarDate(value);
  if (!date) return value;
  const hasClock = /(?:T|\s)\d{1,2}:\d{2}/u.test(value);
  return (hasClock ? observedDateTimeFormatter : observedDateFormatter).format(date);
}

function formatForecastDate(value: string): { date: string; weekday: string } {
  const date = parseCalendarDate(value);
  if (!date) return { date: value, weekday: "" };
  return {
    date: value.slice(5).replace(/^0/, "").replace("-0", "-").replace("-", "/"),
    weekday: weekdayFormatter.format(date),
  };
}

function formatMetricValue(item: BlockItem): string {
  const unit = readText(item.unit);
  const value = typeof item.value === "number"
    ? (Number.isFinite(item.value) ? numberFormatter.format(item.value) : undefined)
    : readText(item.value);
  if (value === undefined) return unit ?? "—";
  return unit ? `${value} ${unit}` : value;
}

function LegacyMetrics({ metrics }: { metrics: BlockItem[] }) {
  return (
    <section className="farmap-weather farmap-weather--legacy" data-weather-state="legacy" aria-label="气象指标">
      <dl className="farmap-weather__legacy">
        {metrics.map((item, index) => {
          const status = readText(item.status);
          return (
            <div key={`${item.label}-${index}`}>
              <dt>{item.label}</dt>
              <dd>
                {formatMetricValue(item)}
                {status && <span className="farmap-weather__metric-status">{status}</span>}
              </dd>
            </div>
          );
        })}
      </dl>
    </section>
  );
}

function WeatherEmpty({ state }: { state: "loading" | "unavailable" | "empty" }) {
  const message = state === "unavailable"
    ? "天气数据暂不可用。"
    : state === "loading" ? "正在读取天气数据…" : "暂无可展示的天气数据。";
  return (
    <div className="farmap-weather farmap-weather--empty" data-weather-state={state} role="status">
      <span className="farmap-weather__empty-icon" aria-hidden="true"><CloudOff size={18} /></span>
      <p className="farmap-weather__empty-text">{message}</p>
    </div>
  );
}

export function WeatherPresentationCard({ block }: { block: AgentUiBlock }) {
  const weather = block.data?.weather;
  const metrics = block.data?.metrics ?? [];
  const farmName = weather?.farmName ?? readText(block.data?.farmName);
  const observedAt = weather?.observedAt ?? readText(block.data?.observedAt);
  const unavailable = block.status === "unavailable" || block.status === "failed";

  if (unavailable) return <WeatherEmpty state="unavailable" />;
  if (weather && hasWeatherContent(weather)) {
    const forecast = weather.forecast ?? [];
    const wind = [weather.windDirection, weather.windPower]
      .filter((part): part is string => part !== undefined)
      .join(" · ");
    const observedLabel = observedAt ? formatObservedAt(observedAt) : undefined;
    return (
      <section className="farmap-weather" data-weather-state="ready" aria-label={farmName ? `${farmName}天气信息` : "天气信息"}>
        {(farmName || observedLabel) && (
          <div className="farmap-weather__meta">
            {farmName && (
              <span className="farmap-weather__meta-item farmap-weather__farm">
                <MapPin size={13} aria-hidden="true" />
                <span>{farmName}</span>
              </span>
            )}
            {observedLabel && (
              <span className="farmap-weather__meta-item farmap-weather__observed">
                <Clock3 size={13} aria-hidden="true" />
                <span>观测于 {observedLabel}</span>
              </span>
            )}
          </div>
        )}

        {(weather.temperature || weather.condition) && (
          <div className="farmap-weather__current">
            <div className="farmap-weather__reading">
              {weather.temperature && (
                <p className="farmap-weather__temperature">
                  <span className="farmap-weather__sr-only">当前温度 </span>
                  {weather.temperature}<span className="farmap-weather__temperature-unit">°C</span>
                </p>
              )}
              {weather.condition && (
                <p className={`farmap-weather__condition${weather.temperature ? "" : " farmap-weather__condition--lead"}`}>
                  {weather.condition}
                </p>
              )}
            </div>
            <ConditionGlyph condition={weather.condition} variant="current" />
          </div>
        )}

        {(weather.humidity || wind) && (
          <dl className="farmap-weather__details">
            {weather.humidity && (
              <div className="farmap-weather__detail">
                <dt><Droplets size={13} aria-hidden="true" />湿度</dt>
                <dd>{weather.humidity}%</dd>
              </div>
            )}
            {wind && (
              <div className="farmap-weather__detail">
                <dt><Wind size={13} aria-hidden="true" />风向与风力</dt>
                <dd>{wind}</dd>
              </div>
            )}
          </dl>
        )}

        {forecast.length > 0 && (
          <div className="farmap-weather__forecast">
            <h4>未来天气</h4>
            <ul className="farmap-weather__forecast-list">
              {forecast.map((day, index) => {
                const label = formatForecastDate(day.date);
                const high = day.temperatureMax !== undefined ? `${numberFormatter.format(day.temperatureMax)}°` : undefined;
                const low = day.temperatureMin !== undefined ? `${numberFormatter.format(day.temperatureMin)}°` : undefined;
                const rain = [
                  day.precipitationProbability !== undefined ? `${numberFormatter.format(day.precipitationProbability)}%` : undefined,
                  day.precipitationMm !== undefined ? `${numberFormatter.format(day.precipitationMm)} mm` : undefined,
                ].filter((part): part is string => part !== undefined).join(" · ");
                return (
                  <li className="farmap-weather__forecast-day" key={`${day.date}-${index}`}>
                    <div className="farmap-weather__forecast-date">
                      <time dateTime={day.date}>{label.date}</time>
                      {label.weekday && <span>{label.weekday}</span>}
                    </div>
                    <ConditionGlyph condition={day.condition} variant="forecast" />
                    {day.condition && <span className="farmap-weather__forecast-condition" title={day.condition}>{day.condition}</span>}
                    {(high || low) && (
                      <span className="farmap-weather__forecast-temperature">
                        {high && <><span className="farmap-weather__sr-only">最高 </span><strong>{high}</strong></>}
                        {high && low && <span aria-hidden="true"> / </span>}
                        {low && <><span className="farmap-weather__sr-only">最低 </span><span>{low}</span></>}
                      </span>
                    )}
                    {rain && (
                      <span className="farmap-weather__forecast-rain">
                        <Droplets size={11} aria-hidden="true" />
                        <span className="farmap-weather__sr-only">降水 </span>
                        {rain}
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
          </div>
        )}

        {weather.source && (
          <p className="farmap-weather__source">
            <Database size={12} aria-hidden="true" />
            <span>数据来源：{weather.source}</span>
            {forecast.length > 0 && forecast.every((day) => day.precipitationMm === undefined) && <span>预报暂无降水量</span>}
          </p>
        )}
      </section>
    );
  }

  if (metrics.length > 0) return <LegacyMetrics metrics={metrics} />;
  return <WeatherEmpty state={block.status === "loading" ? "loading" : "empty"} />;
}
