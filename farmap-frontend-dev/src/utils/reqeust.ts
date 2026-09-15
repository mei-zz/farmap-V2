interface RequestHeader {
  Authorization?: string;
}

export class HttpRequestError extends Error {
  readonly status: number;
  readonly payload?: unknown;

  constructor(
    status: number,
    message: string,
    payload?: unknown,
  ) {
    super(message);
    this.name = "HttpRequestError";
    this.status = status;
    this.payload = payload;
  }
}

export class Request {
  host: URL;
  constructor(url: string) {
    this.host = new URL(url, window.location.origin);
  }

  static getErrorMsg(e: Error | unknown): string {
    return e instanceof Error ? e.message : "请求失败，请稍后重试。";
  }

  private getUrl(path: string): URL {
    return new URL(path, this.host);
  }
  private fallbackStatusMessage(status: number): string {
    if (status === 400) return "请求参数不完整，请检查后重试。";
    if (status === 401) return "登录已失效，请重新登录。";
    if (status === 403) return "当前账号无权访问这项数据。";
    if (status === 404) return "请求的数据已失效或不存在。";
    if (status === 409) return "页面上下文已更新，请重试。";
    if (status === 422) return "当前数据不满足执行条件。";
    if ([502, 503, 504].includes(status)) return "后端服务暂时繁忙，请稍后重试。";
    if (status === 500) return "服务内部错误，请稍后重试。";
    return `请求失败（${status}），请稍后重试。`;
  }

  private async parseError(resp: Response): Promise<HttpRequestError> {
    let payload: unknown;
    let message = "";
    try {
      const text = await resp.text();
      if (text) {
        try { payload = JSON.parse(text); }
        catch { payload = text; }
      }
      if (payload && typeof payload === "object") {
        const body = payload as Record<string, unknown>;
        message = [body.message, body.msg, body.error, body.reason]
          .find((value) => typeof value === "string" && value.trim()) as string || "";
      } else if (typeof payload === "string") {
        message = payload.trim();
      }
    } catch {
      // Keep the status-based fallback when an upstream has no readable body.
    }
    return new HttpRequestError(resp.status, message || this.fallbackStatusMessage(resp.status), payload);
  }

  private async execute<T>(url: URL, init: RequestInit): Promise<T> {
    try {
      const resp = await fetch(url, init);
      if (!resp.ok) throw await this.parseError(resp);
      if (resp.status === 204) return undefined as T;
      return await resp.json() as T;
    } catch (error: unknown) {
      if (error instanceof HttpRequestError) throw error;
      const message = error instanceof Error && error.message ? error.message : "网络连接失败，请检查本地服务后重试。";
      throw new HttpRequestError(0, message, error);
    }
  }

  async get<T extends object>(path: string, headers: RequestHeader = {}): Promise<T> {
    let url = this.getUrl(path);
    if (path.startsWith("http")) url = new URL(path);
    return this.execute<T>(url, { method: "GET", headers: { ...headers } });
  }
  async post<T extends object, U>(path: string, body: T, headers: RequestHeader = {}): Promise<U> {
    let url = this.getUrl(path);
    if (path.startsWith("http")) url = new URL(path);
    return this.execute<U>(url, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...headers,
        },
        body: JSON.stringify(body),
      });
  }

  async put<T extends object, U>(path: string, body: T, headers: RequestHeader = {}): Promise<U> {
    let url = this.getUrl(path);
    if (path.startsWith("http")) url = new URL(path);
    return this.execute<U>(url, {
        method: "PUT",
        headers: { "Content-Type": "application/json", ...headers },
        body: JSON.stringify(body),
      });
  }

  async postForm<U>(path: string, body: FormData, headers: RequestHeader = {}): Promise<U> {
    let url = this.getUrl(path);
    if (path.startsWith("http")) url = new URL(path);
    return this.execute<U>(url, {
        method: "POST",
        headers: { ...headers },
        body,
      });
  }
}

// 本地默认走 8080；生产构建使用同源 /api，避免绑定服务器 IP 或域名。
const SERVICE_URL =
  import.meta.env.VITE_API_BASE_URL ||
  import.meta.env.VITE_API_URL ||
  (import.meta.env.PROD ? "/api" : "http://localhost:8080");
export const req = new Request(SERVICE_URL);
