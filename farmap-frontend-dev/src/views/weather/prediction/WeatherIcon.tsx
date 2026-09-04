import React from "react";
import {
  Sun,
  CloudSun,
  Cloud,
  CloudRain,
  CloudLightning,
  Snowflake,
  Wind,
  CloudFog,
  Moon,
  CloudMoon,
} from "lucide-react";
import type { WeatherIconProps } from "@/types/weather";

export const WeatherIcon: React.FC<WeatherIconProps> = ({ code, className = "w-6 h-6" }) => {
  const iconCode = parseInt(code, 10);

  if (iconCode === 100) return <Sun className={`${className} text-amber-500`} />; // Sunny
  if (iconCode === 150) return <Moon className={`${className} text-slate-400`} />; // Clear Night
  if (iconCode >= 101 && iconCode <= 103)
    return <CloudSun className={`${className} text-amber-400`} />; // Cloudy/Partly Cloudy
  if (iconCode === 104 || iconCode === 154)
    return <Cloud className={`${className} text-gray-500`} />; // Overcast
  if (iconCode >= 151 && iconCode <= 153)
    return <CloudMoon className={`${className} text-slate-500`} />; // Cloudy Night

  // Rain
  if (iconCode >= 300 && iconCode <= 399)
    return <CloudRain className={`${className} text-blue-500`} />;

  // Snow
  if (iconCode >= 400 && iconCode <= 499)
    return <Snowflake className={`${className} text-cyan-400`} />;

  // Fog/Haze
  if (iconCode >= 500 && iconCode <= 599)
    return <CloudFog className={`${className} text-slate-400`} />;

  // Storm
  if (iconCode === 302 || iconCode === 303 || iconCode === 304)
    return <CloudLightning className={`${className} text-purple-500`} />;

  // Default
  return <Wind className={`${className} text-gray-400`} />;
};
