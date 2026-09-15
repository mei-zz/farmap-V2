export function cropDisplayName(crop?: string): string {
  if (!crop) return "";
  const labels: Record<string, string> = {
    plum: "脆李",
    citrus: "柑橘",
    orange: "柑橘",
    rice: "水稻",
  };
  return labels[crop.trim().toLowerCase()] || crop;
}

export function growthStageDisplayName(stage?: string): string {
  if (!stage) return "";
  const labels: Record<string, string> = {
    dormancy: "休眠期",
    germination: "萌芽期",
    leafing: "展叶期",
    budding: "花蕾期",
    flowering: "花期",
    fruiting: "坐果期",
    fruit_development: "果实发育期",
    fruiting_development: "果实发育期",
    ripening: "成熟期",
    harvest: "采收期",
    post_harvest: "采后恢复期",
    "post-harvest": "采后恢复期",
  };
  const normalized = stage.trim().toLowerCase().replace(/\s+/g, "_");
  return labels[normalized] || stage;
}
