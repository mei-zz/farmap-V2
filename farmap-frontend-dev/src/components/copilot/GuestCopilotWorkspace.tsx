import { useEffect, useRef, useState, type FormEvent } from "react";
import { ArrowDown, ArrowUp, CloudSun, Leaf, Sprout, Square, Wheat } from "lucide-react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { readServerSentEvents, requestUrl } from "@/utils/sse";
import { applyChatActivity, type ChatActivity } from "@/features/copilot/chatActivity";
import { CopilotTurnActivity } from "./CopilotTurnActivity";
import "./CopilotWorkspace.css";
import "./GuestCopilotWorkspace.css";

type Turn = { id: string; role: "user" | "assistant"; text: string; activity?: ChatActivity };

const pinnedThreshold = 40;

const suggestions = [
  { icon: Wheat, label: "作物栽培", prompt: "柑橘萌芽期要注意哪些栽培管理事项？" },
  { icon: Leaf, label: "病虫防治", prompt: "柑橘叶片发黄一般有哪些原因，应该怎么排查？" },
  { icon: Sprout, label: "肥水管理", prompt: "果园如何判断是否需要灌溉？" },
  { icon: CloudSun, label: "气象与农事", prompt: "连续降雨时果园可以做哪些准备？" },
];

export default function GuestCopilotWorkspace() {
  // The transcript belongs to this mounted page. Reloads, tab closure and login discard it.
  const [turns, setTurns] = useState<Turn[]>([]);
  const [draft, setDraft] = useState("");
  const [sending, setSending] = useState(false);
  const [error, setError] = useState("");
  const [showJump, setShowJump] = useState(false);
  const active = useRef<AbortController | null>(null);
  const transcript = useRef<HTMLElement>(null);
  const pinned = useRef(true);

  useEffect(() => () => { active.current?.abort(); active.current = null; }, []);
  useEffect(() => {
    if (!pinned.current) return;
    const node = transcript.current;
    if (!node) return;
    const frame = window.requestAnimationFrame(() => {
      if (pinned.current) node.scrollTop = node.scrollHeight;
    });
    return () => window.cancelAnimationFrame(frame);
  }, [turns]);

  function trackScroll() {
    const node = transcript.current;
    if (!node) return;
    const atLatest = node.scrollHeight - node.scrollTop - node.clientHeight <= pinnedThreshold;
    pinned.current = atLatest;
    setShowJump(!atLatest);
  }

  function jumpToLatest() {
    const node = transcript.current;
    pinned.current = true;
    setShowJump(false);
    if (node) node.scrollTop = node.scrollHeight;
  }

  function resetConversation() {
    pinned.current = true;
    setShowJump(false);
    setTurns([]);
    setError("");
    setDraft("");
  }

  async function ask(prompt: string) {
    const message = prompt.trim();
    if (!message || active.current) return;
    const controller = new AbortController();
    active.current = controller;
    jumpToLatest();
    const answerId = crypto.randomUUID();
    const history = turns.filter((turn) => turn.text.trim()).slice(-8)
      .map(({ role, text }) => ({ role, text: text.slice(0, 1200) }));
    setTurns([...turns, { id: crypto.randomUUID(), role: "user", text: message }, { id: answerId, role: "assistant", text: "" }]);
    setSending(true);
    setDraft("");
    setError("");

    try {
      const response = await fetch(requestUrl("/api/copilot/guest/chat"), {
        method: "POST",
        credentials: "omit",
        headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
        body: JSON.stringify({ message, history }),
        signal: controller.signal,
      });
      if (!response.ok) throw new Error(response.status === 429 ? "请求较多，请稍后再试。" : "当前无法获取农事指导，请稍后重试。");
      let completed = false;
      let failure = "";
      await readServerSentEvents(response, ({ event, data }) => {
        if (active.current !== controller || !data || typeof data !== "object") return;
        const value = data as Record<string, unknown>;
        if (event === "agent.runtime" || event.startsWith("tool.") || event.startsWith("web.search.")) {
          setTurns((before) => {
            let changed = false;
            const next = before.map((turn) => {
              if (turn.id !== answerId) return turn;
              const activity = applyChatActivity(turn.activity, event, data);
              if (activity === turn.activity) return turn;
              changed = true;
              return { ...turn, activity };
            });
            return changed ? next : before;
          });
        }
        if (event === "content.delta" && typeof value.delta === "string") {
          const delta = value.delta;
          setTurns((before) => before.map((turn) => turn.id === answerId ? { ...turn, text: turn.text + delta } : turn));
        }
        if (event === "error" && typeof value.message === "string") failure = value.message;
        if (event === "done") completed = value.status === "COMPLETED";
      });
      if (!completed) throw new Error(failure || "回答未完成，请重试。");
    } catch (problem) {
      if (active.current !== controller || controller.signal.aborted) return;
      setError(problem instanceof Error ? problem.message : "暂时无法生成回答。");
      setTurns((before) => before.filter((turn) => turn.id !== answerId || turn.text.trim()));
    } finally {
      if (active.current === controller) { active.current = null; setSending(false); }
    }
  }

  function stop() {
    active.current?.abort();
    active.current = null;
    setSending(false);
    setTurns((before) => before.filter((turn) => turn.text.trim()));
  }

  function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void ask(draft);
  }

  return <div className={`farmap-copilot-workspace farmap-guest ${turns.length ? "has-conversation" : "is-empty"}`}>
    {!turns.length ? <section className="farmap-copilot-workspace__welcome">
      <div className="farmap-guest__mark"><Sprout size={24} /></div>
      <h1>你的农事智能助手</h1>
      <p>从作物栽培、病虫排查到肥水管理，聊聊你的农事问题。</p>
    </section> : <>
      <div className="farmap-guest__toolbar"><button type="button" disabled={sending} onClick={resetConversation}>新对话</button></div>
      <div className="farmap-guest__transcript">
        <section ref={transcript} className="farmap-copilot__messages farmap-guest__messages" aria-label="临时对话" tabIndex={0} onScroll={trackScroll}>
          {turns.map((turn, index) => <article key={turn.id} className={`farmap-copilot__message farmap-copilot__message--${turn.role}`}>
            {turn.role === "assistant" && <>
              <span className="farmap-guest__role"><Sprout size={14} /> 农事助手</span>
              <CopilotTurnActivity activity={turn.activity} pending={sending && index === turns.length - 1} answering={Boolean(turn.text)} />
              {turn.text && <ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml disallowedElements={["img"]}>
                {turn.text}
              </ReactMarkdown>}
            </>}
            {turn.role === "user" && <p>{turn.text}</p>}
          </article>)}
        </section>
        {showJump && <button type="button" className="farmap-copilot__jump farmap-guest__jump" onClick={jumpToLatest} aria-label="回到最新回答">
          <ArrowDown size={14} />回到最新
        </button>}
      </div>
    </>}
    <form className="farmap-copilot__composer farmap-guest__composer" onSubmit={send}>
      {error && <p role="alert" className="farmap-guest__error">{error}</p>}
      <textarea aria-label="描述你的农事需求" placeholder="描述你的农事需求……" value={draft} maxLength={2000} rows={3}
        onChange={(event) => setDraft(event.target.value)} onKeyDown={(event) => {
          if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
            event.preventDefault();
            if (!sending) void ask(draft);
          }
        }} />
      <div className="farmap-guest__composer-footer">
        <small>访客对话 · 仅在此页面保留</small>
        {sending ? <button type="button" onClick={stop} aria-label="停止生成"><Square size={15} />停止</button>
          : <button type="submit" disabled={!draft.trim()} aria-label="发送消息"><ArrowUp size={18} />发送</button>}
      </div>
    </form>
    {!turns.length && <div className="farmap-copilot-workspace__suggestions">{suggestions.map(({ icon: Icon, label, prompt }) =>
      <button key={label} type="button" onClick={() => void ask(prompt)}><Icon size={17} />{label}</button>)}</div>}
  </div>;
}
