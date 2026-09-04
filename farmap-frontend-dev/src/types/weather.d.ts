export type Prediction = {
  fxDate: string;
  tempMax: string;
  tempMin: string;
  iconDay: string;
  iconNight: string;
  textDay: string;
  textNight: string;
  windDirDay: string;
  windDirNight: string;
  windScaleDay: string;
  windScaleNight: string;
  humidity: string;
  precip: string;
  vis: string;
  pressure: string;
};

export type PredictionResult = {
  code: string;
  daily: Prediction[];
  updateTime: string;
};

export interface WeatherIconProps {
  code: string;
  className?: string;
}
