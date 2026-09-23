import { resolveServiceUrl } from "./reqeust";

export type ServerSentEvent = {
  event: string;
  id?: string;
  data: unknown;
};

/** Reads an authenticated text/event-stream response without EventSource. */
export async function readServerSentEvents(
  response: Response,
  onEvent: (event: ServerSentEvent) => void,
): Promise<void> {
  if (!response.body) throw new Error("SSE 响应没有可读取的数据流。");
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let eventName = "message";
  let eventId: string | undefined;
  let dataLines: string[] = [];

  const dispatch = () => {
    if (!dataLines.length) {
      eventName = "message";
      eventId = undefined;
      return;
    }
    const raw = dataLines.join("\n");
    let data: unknown = raw;
    try { data = JSON.parse(raw); } catch { /* text payloads are valid SSE data */ }
    onEvent({ event: eventName, id: eventId, data });
    eventName = "message";
    eventId = undefined;
    dataLines = [];
  };

  const consume = (chunk: string) => {
    buffer += chunk;
    const lines = buffer.split(/\r?\n/);
    buffer = lines.pop() ?? "";
    for (const line of lines) {
      if (!line) { dispatch(); continue; }
      if (line.startsWith(":")) continue;
      const separator = line.indexOf(":");
      const field = separator < 0 ? line : line.slice(0, separator);
      const value = separator < 0 ? "" : line.slice(separator + 1).replace(/^ /, "");
      if (field === "event") eventName = value || "message";
      else if (field === "id") eventId = value;
      else if (field === "data") dataLines.push(value);
    }
  };

  while (true) {
    const next = await reader.read();
    if (next.done) break;
    consume(decoder.decode(next.value, { stream: true }));
  }
  consume(decoder.decode());
  if (buffer.trim()) consume("\n");
  dispatch();
}

export function requestUrl(path: string): URL {
  // VITE_API_URL is normally a same-origin relative base such as "/api".
  // URL(path, "/api") throws in the browser, which used to make every real
  // streaming Copilot request fail before fetch() could reach Spring Boot.
  const serviceBase = new URL(resolveServiceUrl(), window.location.origin);
  return new URL(path, serviceBase);
}
