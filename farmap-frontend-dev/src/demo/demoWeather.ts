import type { DemoWeatherDay } from "./types";

export const demoWeather = {
  summary: "多云",
  temperature: "24°C",
  location: "重庆 · 云阳县",
  rainfallForecast: "58–76 mm",
  temperatureRange: "18° / 27°",
  updatedAt: "2026-09-04 08:00",
  days: [
    { day: "今天", icon: "rain", high: 24, low: 18 },
    { day: "明天", icon: "rain", high: 22, low: 17 },
    { day: "周六", icon: "cloud", high: 21, low: 16 },
    { day: "周日", icon: "sun", high: 25, low: 18 },
    { day: "周一", icon: "sun", high: 26, low: 19 },
  ] satisfies DemoWeatherDay[],
};
