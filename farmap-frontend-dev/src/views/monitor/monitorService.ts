import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";

export interface MonitorAccessToken {
  accessToken: string;
  expiresAt: number;
}

interface AccessTokenResponse {
  code?: number;
  msg?: string;
  data?: MonitorAccessToken;
}

interface PreviewEnvelope {
  code?: number;
  msg?: string;
  data?: {
    previewUrl?: string;
    playbackUrl?: string;
  };
}

interface PreviewResponse {
  code?: number;
  msg?: string;
  data?: PreviewEnvelope;
}

export interface MonitorPreview {
  previewUrl: string;
  playbackUrl?: string;
}

export async function getMonitorAccessToken(): Promise<MonitorAccessToken> {
  const cached = permanence.accessToken.useAccessToken();
  if (cached && cached.accessToken && Date.now() < cached.expiresAt - 30_000) {
    return cached;
  }

  const authToken = permanence.token.useToken();
  const response = await req.get<AccessTokenResponse>(
    "/monitor/get-accesstoken",
    { Authorization: `Bearer ${authToken}` },
  );
  const accessToken = response.data;
  if (!accessToken?.accessToken) {
    throw new Error(response.msg || "摄像头服务尚未配置海康云凭据");
  }

  permanence.accessToken.setAccessToken(accessToken);
  return accessToken;
}

export async function getMonitorPreview(
  accessToken: string,
  deviceSerial: string,
): Promise<MonitorPreview> {
  if (!accessToken) throw new Error("摄像头服务令牌尚未就绪");

  const params = new URLSearchParams({ accessToken, deviceSerial });
  const authToken = permanence.token.useToken();
  const response = await req.get<PreviewResponse>(
    `/monitor/preview?${params.toString()}`,
    { Authorization: `Bearer ${authToken}` },
  );
  const preview = response.data?.data;
  if (!preview?.previewUrl) {
    throw new Error(response.data?.msg || response.msg || "该摄像头暂时无法获取预览地址");
  }

  return {
    previewUrl: preview.previewUrl,
    playbackUrl: preview.playbackUrl,
  };
}
