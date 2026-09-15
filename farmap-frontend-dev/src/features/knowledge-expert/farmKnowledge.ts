import type { KnowledgeSource } from "@/features/knowledge-expert/service";

export type FarmKnowledgeProfile = {
  farmId: number;
  farmName: string;
  corpusFolder: string;
};

/**
 * The corpus folder is the audited admin-curated boundary. We resolve its
 * source ID from the server instead of hardcoding generated UUIDs here.
 */
export const farmKnowledgeProfiles: Record<number, FarmKnowledgeProfile> = {
  1: { farmId: 1, farmName: "云阳", corpusFolder: "脆李时期" },
  8: { farmId: 8, farmName: "大足", corpusFolder: "柑橘数据" },
  // The existing 脆李时期 corpus is authoritative for 巫山. Keep the
  // existing 云阳 mapping for compatibility and expose the same ingested
  // source under 巫山 without duplicating documents or vectors.
  7: { farmId: 7, farmName: "巫山", corpusFolder: "脆李时期" },
};

export function knowledgeProfileForFarm(farmId: number): FarmKnowledgeProfile | undefined {
  return farmKnowledgeProfiles[farmId];
}

export function resolveFarmKnowledgeSource(sources: KnowledgeSource[], farmId: number): KnowledgeSource | undefined {
  const profile = knowledgeProfileForFarm(farmId);
  if (!profile) return undefined;
  const folder = profile.corpusFolder.toLowerCase();
  const matchesFolder = (source: KnowledgeSource) => {
    const name = source.name?.toLowerCase() ?? "";
    const origin = source.originUri?.toLowerCase() ?? "";
    return name.includes(folder) || origin.includes(folder);
  };
  return sources.find((source) => matchesFolder(source) && source.status !== "DISABLED")
    ?? sources.find(matchesFolder);
}
