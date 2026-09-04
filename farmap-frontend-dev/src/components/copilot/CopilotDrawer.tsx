import { useMemo } from "react";
import { Button, Divider, Drawer, Empty, Flex, Input, Space, Tag, Typography } from "antd";
import { DeleteOutlined, RobotOutlined, SendOutlined } from "@ant-design/icons";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";

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

  const handleSend = () => {
    const content = draft.trim();
    if (!content) return;

    addMessage({ role: "user", content });
    addMessage({
      role: "assistant",
      content: "已记录这个问题。Agent 与检索链路将在下一阶段接入。",
    });
    setDraft("");
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
              handleSend();
            }
          }}
          placeholder="例如：A-12 地块近期需要关注什么？"
          autoSize={{ minRows: 3, maxRows: 5 }}
        />
        <Button
          type="primary"
          icon={<SendOutlined />}
          style={{ marginTop: 8 }}
          onClick={handleSend}
          disabled={!draft.trim()}
        >
          发送（演示）
        </Button>
      </div>
    </Drawer>
  );
}
