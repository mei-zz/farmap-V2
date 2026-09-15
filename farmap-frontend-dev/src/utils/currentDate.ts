const dateFormatter = new Intl.DateTimeFormat("zh-CN", {
  year: "numeric",
  month: "numeric",
  day: "numeric",
  weekday: "long",
});

export function formatCurrentLocalDate(value: Date = new Date()): string {
  const parts = dateFormatter.formatToParts(value);
  const year = parts.find((part) => part.type === "year")?.value ?? "";
  const month = parts.find((part) => part.type === "month")?.value ?? "";
  const day = parts.find((part) => part.type === "day")?.value ?? "";
  const weekday = parts.find((part) => part.type === "weekday")?.value ?? "";
  return `${year}年${month}月${day}日 ${weekday}`;
}
