import { useMemo, useState } from "react";
import { Button, Divider, Drawer, Empty, Flex, Input, Space, Tag, Typography } from "antd";
import { DeleteOutlined, RobotOutlined, SendOutlined } from "@ant-design/icons";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";
import { useNavigate } from "react-router";
import { agentMode, createAgentRun } from "@/features/agent/service";
import { toAgentContext } from "@/features/agent/adapter";

const { Text, Title } = Typography;

export default function CopilotDrawer() {
  const context = useAIContextStore((state) => state.context);
  const isOpen = useCopilotStore((state) => state.isOpen);
  const close = useCopilotStore((state) => state.close);
  const draft = useCopilotStore((state) => state.draft);
  const setDraft = useCopilotStore((state) => state.setDraft);
  const messages = useCopilotStore((state) => state.messages);
  const addMessage = useCopilotStore((state) => state.addMessage);
  const clearMessages = useCopilotStore((state) => state.clearMessages);
  const navigate = useNavigate();
  const [sending, setSending] = useState(false);

  const contextTags = useMemo(
    () =>
      [
        context.currentFarm.name,
        context.currentField?.name,
        context.currentCrop?.name,
        context.currentPhenology?.stage,
      ].filter(Boolean),
    [context],
  );

  const handleSend = async () => {
    const content = draft.trim();
    if (!content) return;

    addMessage({ role: "user", content });
    setDraft("");
    const complex = /(综合分析|深度分析|生成方案|比较多源数据|查找原因|制定计划|处理方案|为什么|原因)/.test(content) || content.length >= 48;
    if (!complex) {
      addMessage({ role: "assistant", content: `已结合${context.currentField?.name || "当前地块"}上下文记录：${context.currentWeather?.summary || "天气数据已接入"}。如需跨数据源分析，请让我启动 Agent。` });
      return;
    }
    addMessage({ role: "assistant", content: "这个问题需要综合地块、天气、图像和知识证据，正在准备 Agent 运行…" });
    setSending(true);
    try {
      if (agentMode === "real") {
        const run = await createAgentRun(content, toAgentContext(context), "real");
        navigate(`/analysis/${run.runId}`);
      } else {
        navigate("/analysis/a12-demo");
      }
    } catch {
      addMessage({ role: "assistant", content: "Agent Runtime 暂不可用，已保留问题；可以稍后重试。" });
    } finally { setSending(false); }
  };

  return (
    <Drawer
      title={
        <Flex align="center" justify="space-between">
          <Space>
            <RobotOutlined />
            <span>FarMap Copilot</span>
          </Space>
          <Button
            type="text"
            size="small"
            icon={<DeleteOutlined />}
            onClick={clearMessages}
            aria-label="清空 Copilot 对话"
          />
        </Flex>
      }
      placement="right"
      width={380}
      open={isOpen}
      onClose={close}
      destroyOnClose={false}
    >
      <div className="farmap-copilot__context">
        <Flex align="center" justify="space-between">
          <Text strong>当前上下文</Text>
          <Tag color="purple">{context.currentPage}</Tag>
        </Flex>
        <Flex wrap gap={6} style={{ marginTop: 10 }}>
          {contextTags.map((tag) => (
            <Tag key={tag}>{tag}</Tag>
          ))}
        </Flex>
        <Text type="secondary">
          {context.currentWeather?.summary} · {context.currentWeather?.temperature} ·
          {context.currentPhenology?.progress}% 物候进度
        </Text>
      </div>

      <Divider />

      <Flex vertical gap={12} className="farmap-copilot__messages">
        {messages.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有对话" />
        ) : (
          messages.map((message) => (
            <div
              key={message.id}
              className={`farmap-copilot__message farmap-copilot__message--${message.role}`}
            >
              <Text>{message.content}</Text>
            </div>
          ))
        )}
      </Flex>

      <div className="farmap-copilot__composer">
        <Title level={5} style={{ marginTop: 0 }}>
          询问当前农情
        </Title>
        <Input.TextArea
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onPressEnter={(event) => {
            if (!event.shiftKey) {
              event.preventDefault();
              void handleSend();
            }
          }}
          placeholder="例如：A-12 地块近期需要关注什么？"
          autoSize={{ minRows: 3, maxRows: 5 }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          style={{ marginTop: 8 }}
          onClick={() => void handleSend()}
          disabled={!draft.trim() || sending}
        >
          {sending ? "准备 Agent…" : "发送"}
        </Button>
      </div>
    </Drawer>
  );
}
