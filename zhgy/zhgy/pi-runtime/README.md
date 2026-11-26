# Pi runtime

Private Node 22.19+ worker. Install with `npm ci --ignore-scripts` and start with `node worker.mjs`. Set `FARMAP_PI_API_KEY`, `FARMAP_PI_BASE_URL` (base URL or full `/chat/completions` URL), and `FARMAP_PI_MODEL`. The default endpoint is Bailian's compatible-mode endpoint and the default model is `qwen-plus`.

The process reads one UTF-8 JSON object per line on stdin and writes one JSON object per line on stdout. `id` identifies a command and `session` is an owner-scoped key. `start` accepts `contextVersion`, `scope`, `message`, `systemPrompt`, optional `environment`, `history` (user and assistant text), and `tools` (name, description, parameters). A start with tools also supplies a `toolsetId`. Matching scope and history let Pi retain its transcript while refreshing the prompt and tool declarations.

For each model tool call, stdout emits `{id,type:"tool_call_request",callId,toolsetId,toolName,args}`. The Spring bridge validates the scope and arguments, runs the query, and replies on stdin with `{op:"tool_call_response",id,callId,text,isError}`. The opaque `callId` and command `id` correlate parallel requests. `text` is bounded JSON delivered to Pi as one tool result. Browser activity receives lifecycle metadata through the event stream. On abort or timeout, the worker releases outstanding tool waits and discards late replies.

`abort`, `continue`, `steer`, and `followUp` use the same session key and execution version. Steering and follow-up can be queued during a run. A control command gets its own `done` or `error`; the start or continue command receives its own events and terminal response. On abort, wait for the active command's terminal response before continuing. A newer version can start fresh. Event payloads contain lifecycle metadata and deltas contain assistant text. A process restart loses live state; the next turn can load durable visible history from FarMap. Provider failures return a safe error code.

Limits: 64 retained sessions, 30-minute idle expiry, 120 seconds and eight model turns per run, 16 tool calls, eight queued messages per run, 40 history entries, and 256 KiB input lines. Context trimming preserves the system message and a recent user-led suffix. Test with `npm test`.

