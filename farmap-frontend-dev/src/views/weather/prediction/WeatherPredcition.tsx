import { useEffect, useState } from "react";
import type { Prediction, PredictionResult } from "@/types/weather";
import { WeatherCard } from "./WeatherCard";
import { AlertCircle, Loader2 } from "lucide-react";
import { permanence } from "@/utils/permanence";
import { useFarmStore } from "@/store/farm";

const API_KEY = "31b6db36c66f461e89c408c864b51da9";

export default function WeatherPrediction() {
  const localFarmStore = permanence.farm.useFarmStore();
  const location = localFarmStore ? localFarmStore.center : useFarmStore((s) => s.center);
  const farmAddr = localFarmStore ? localFarmStore.address : useFarmStore((s) => s.address);

  const [predictions, setPredictions] = useState<Prediction[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [isError, setIsError] = useState<boolean>(false);

  useEffect(() => {
    if (!location) {
      setIsError(true);
      setLoading(false);
      return;
    }

    setLoading(true);

    fetch(`https://m25eghjq33.re.qweatherapi.com/v7/weather/3d?location=${location}`, {
      headers: {
        "X-QW-Api-Key": API_KEY,
      },
    })
      .then((res) => {
        if (!res.ok) throw new Error("Fetch failed");
        return res.json();
      })
      .then((data: PredictionResult) => {
        // Check API response code "200" means success
        if (data.code !== "200") {
          // If not 200, something went wrong (quota, key, etc)
          throw new Error(`API Error: ${data.code}`);
        }

        const pres = data.daily;
        if (!pres) throw new Error("No data found");

        setPredictions(pres);
        setIsError(false);
      })
      .catch((err) => {
        console.error(err);
        setIsError(true);

        setPredictions([]);
        setIsError(false);
      })
      .finally(() => {
        setLoading(false);
      });
  }, [location]);

  return (
    <div className="w-full">
      <h2 className="mb-2">
        当前地区：
        <span className="px-2 rounded-xl  bg-gray-100 border border-gray-200 cursor-pointer">
          {farmAddr}
        </span>
      </h2>
      {loading ? (
        <div className="w-full h-40 flex items-center justify-center bg-white rounded-xl border border-slate-100">
          <Loader2 className="w-6 h-6 text-blue-500 animate-spin" />
        </div>
      ) : isError ? (
        <div className="w-full h-40 flex flex-col items-center justify-center bg-red-50 rounded-xl border border-red-100">
          <AlertCircle className="w-6 h-6 text-red-400 mb-2" />
          <span className="text-xs text-red-500">无法加载气象数据</span>
        </div>
      ) : (
        <div className="grid grid-cols-3 gap-3 w-full resp-weather__prediction">
          {predictions.map((p) => (
            <WeatherCard key={p.fxDate} data={p} />
          ))}
        </div>
      )}
    </div>
  );
}
