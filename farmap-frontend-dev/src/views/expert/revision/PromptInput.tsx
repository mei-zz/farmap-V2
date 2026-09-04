import { AudioOutlined, StopOutlined } from "@ant-design/icons";
import { Divider, Flex, Input } from "antd";
import { Loader2 } from "lucide-react";
import { useRef } from "react";
const { Search } = Input;

declare global {
  interface Window {
    SpeechRecognition: any;
    webkitSpeechRecognition: any;
  }

  var SpeechRecognition: any;
  var webkitSpeechRecognition: any;
}

interface Props {
  prompt: string;
  onChange: (newPrompt: string) => void;
  onSubmit: () => void;
  loading: boolean;
}

const hasSR = window.SpeechRecognition || window.webkitSpeechRecognition;
export default function PromptInput(props: Props) {
  // Create speech recognition ref
  const recognitionRef = useRef<any>(null);
  const isRecordingRef = useRef(false);
  if (!recognitionRef.current) {
    const SpeechRecognition =
      window.SpeechRecognition || window.webkitSpeechRecognition;
    const r = new SpeechRecognition();
    r.lang = "zh-CN";
    r.continuous = true;
    r.interimResult = false;

    r.onresult = (e: any) => {
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
