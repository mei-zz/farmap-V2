export function getPercentageColor(percentage: number): string {
  if (percentage <= 0) return "rgb(255, 255, 91)";
  if (percentage < 33) return "rgb(145, 244, 128)";
  if (percentage < 67) return "rgb(66, 89, 166)";
  return "rgb(64, 7, 88)";
}
