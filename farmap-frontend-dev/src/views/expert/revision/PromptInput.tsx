import { AudioOutlined, StopOutlined } from "@ant-design/icons";
import { Divider, Flex, Input } from "antd";
import { Loader2 } from "lucide-react";
import { useRef } from "react";
const { Search } = Input;

interface SpeechRecognitionAlternativeLike {
  transcript: string;
}

interface SpeechRecognitionResultLike {
  [index: number]: SpeechRecognitionAlternativeLike;
}

interface SpeechRecognitionEventLike {
  results: {
    length: number;
    [index: number]: SpeechRecognitionResultLike;
  };
}

interface SpeechRecognitionLike {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  start: () => void;
  stop: () => void;
}

interface SpeechRecognitionConstructor {
  new (): SpeechRecognitionLike;
}

declare global {
  interface Window {
    SpeechRecognition?: SpeechRecognitionConstructor;
    webkitSpeechRecognition?: SpeechRecognitionConstructor;
  }
}

interface Props {
  prompt: string;
  onChange: (newPrompt: string) => void;
  onSubmit: () => void;
  loading: boolean;
}

const hasSR = typeof window !== "undefined" && Boolean(window.SpeechRecognition || window.webkitSpeechRecognition);
export default function PromptInput(props: Props) {
  // Create speech recognition ref
  const recognitionRef = useRef<SpeechRecognitionLike | null>(null);
  const isRecordingRef = useRef(false);
  const SpeechRecognition = typeof window === "undefined"
    ? undefined
    : window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!recognitionRef.current && SpeechRecognition) {
    const r = new SpeechRecognition();
    r.lang = "zh-CN";
    r.continuous = true;
    r.interimResults = false;

    r.onresult = (e) => {
      let full = "";
      for (let i = 0; i < e.results.length; i++) {
        full += e.results[i][0].transcript;
      }
      props.onChange(full);
    };

    recognitionRef.current = r;
  }

  // Audio input
  const suffix = !hasSR ? (
    <StopOutlined style={{ color: "#dd0000" }} />
  ) : (
    <AudioOutlined
      style={{ fontSize: 24, color: "#1677ff" }}
      onClick={() => {
        const r = recognitionRef.current;
        if (!r) return;

        if (!isRecordingRef.current) {
          // start
          r.start();
          isRecordingRef.current = true;
        } else {
          // stop
          r.stop();
          isRecordingRef.current = false;
        }
      }}
    />
  );

  // Trigger json update
  const audioRevise = () => {
    props.onSubmit();
  };

  return (
    <div style={{ marginTop: 10 }}>
      <Divider style={{ margin: 10 }} />
      <Flex gap="0.5rem">
        {props.loading && (
          <Loader2 className="w-6 h-6 text-blue-500 animate-spin" />
        )}
        <Search
          placeholder="请输入整体修改建议，由 AI 进行具体修改"
          value={props.prompt}
          enterButton="提交修改提示词"
          size="middle"
          suffix={suffix}
          onSearch={audioRevise}
          onChange={(e) => {
            props.onChange(e.target.value);
          }}
          disabled={props.loading}
        />
      </Flex>
    </div>
  );
}
