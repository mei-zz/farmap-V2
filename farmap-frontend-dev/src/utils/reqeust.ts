interface RequestHeader {
  Authorization?: string;
}

export class Request {
  host: URL;
  constructor(url: string) {
    this.host = new URL(url, window.location.origin);
  }

  static getErrorMsg(e: Error | unknown): string {
    return (e as Error).message;
  }

  private getUrl(path: string): URL {
    return new URL(this.host + path);
  }
  private parseStatusCode(e: Error): string {
    const code = e.message;
    if (code === "401") return "请求无权限，检查 token。";
    if (code === "500") return "服务内部错误，请联系管理员。";
    if (code === "502") return "后端部署缺失，请联系管理员。";
    return "未知错误，请检查网络重试或联系管理员。";
  }

  async get<T extends object>(path: string, headers: RequestHeader = {}): Promise<T> {
    let url = this.getUrl(path);
    if (path.startsWith("http")) url = new URL(path);
    try {
      const resp = await fetch(url, {
        method: "GET",
        headers: { ...headers },
      });
      if (!resp.ok) throw new Error(resp.status.toString());
      return await resp.json();
    } catch (e) {
      const msg = this.parseStatusCode(e as Error);
      throw new Error(msg);
    }
  }
  async post<T extends object, U>(path: string, body: T, headers: RequestHeader = {}): Promise<U> {
    let url = this.getUrl(path);
    if (path.startsWith("http")) url = new URL(path);
    try {
      const resp = await fetch(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...headers,
        },
        body: JSON.stringify(body),
      });

      if (!resp.ok) throw new Error(resp.status.toString());
      return await resp.json();
    } catch (e) {
      const msg = this.parseStatusCode(e as Error);
      throw new Error(msg);
    }
  }

  async postForm<U>(path: string, body: FormData, headers: RequestHeader = {}): Promise<U> {
    let url = this.getUrl(path);
    if (path.startsWith("http")) url = new URL(path);
    try {
      const resp = await fetch(url, {
        method: "POST",
        headers: { ...headers },
        body,
      });
      if (!resp.ok) throw new Error(resp.status.toString());
      return await resp.json();
    } catch (e) {
      const msg = this.parseStatusCode(e as Error);
      throw new Error(msg);
    }
  }
}

// 本地默认走 8080；生产构建使用同源 /api，避免绑定服务器 IP 或域名。
const SERVICE_URL =
  import.meta.env.VITE_API_URL || (import.meta.env.PROD ? "/api" : "http://localhost:8080");
export const req = new Request(SERVICE_URL);
