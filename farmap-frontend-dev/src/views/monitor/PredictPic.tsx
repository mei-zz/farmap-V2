import { Alert, Button, Flex, Tag, Typography } from "antd";
import { CameraOutlined, RobotOutlined, RightOutlined } from "@ant-design/icons";
import { useAIContextStore } from "@/store/aiContext";
import { useCopilotStore } from "@/store/copilot";

const { Paragraph, Text } = Typography;

export default function PredictPic() {
  const context = useAIContextStore((state) => state.context);
  const openCopilot = useCopilotStore((state) => state.open);
  const setDraft = useCopilotStore((state) => state.setDraft);
  const selectedDevice = context.selectedDevice;
  const fieldLabel = selectedDevice?.fieldName || context.currentField?.name;

  const openAgentForCamera = () => {
    const cameraLabel = selectedDevice?.name || "当前摄像头";
    const target = fieldLabel ? `当前地块“${fieldLabel}”` : "当前选中的摄像头现场";
    setDraft(`请结合${target}的现场图片、作物物候、天气和知识库，分析${cameraLabel}拍到的可见异常，给出可能原因、证据、不确定性和下一步建议。暂不执行农事操作。`);
    openCopilot();
  };

  return (
    <Flex vertical gap={16} style={{ height: "100%" }}>
      <Alert
        type="info"
        showIcon
        icon={<RobotOutlined />}
        message="图像分析统一由 FarMap Agent 完成"
        description="Agent 会把现场图片与当前农场、地块、摄像头、物候、天气和知识库一起分析，并保留证据与不确定性。"
      />

      <div style={{ border: "1px solid #e6f4ea", borderRadius: 10, padding: 14, background: "#fbfffc" }}>
        <Flex align="center" gap={8} style={{ marginBottom: 8 }}>
          <CameraOutlined style={{ color: "#237b57" }} />
          <Text strong>当前分析对象</Text>
          <Tag color={selectedDevice ? "green" : "default"}>
            {selectedDevice ? "已选择" : "未选择"}
          </Tag>
        </Flex>
        <Paragraph style={{ margin: 0 }}>
          {selectedDevice ? (
            <>
              <Text strong>{selectedDevice.name}</Text>
              <br />
              <Text type="secondary">
                {fieldLabel || "尚未绑定地块"}
                {selectedDevice.pointId ? ` · ${selectedDevice.pointId}` : ""}
              </Text>
            </>
          ) : (
            <Text type="secondary">请先从左侧监控列表选择摄像头，Agent 才能准确绑定现场上下文。</Text>
          )}
        </Paragraph>
      </div>

      <Button
        type="primary"
        size="large"
        icon={<RobotOutlined />}
        onClick={openAgentForCamera}
        block
      >
        {selectedDevice ? "打开 Agent 添加现场图片" : "打开 Agent"}
        <RightOutlined />
      </Button>

      <div style={{ color: "#667085", fontSize: 12, lineHeight: 1.7 }}>
        <div>推荐上传：整株/枝条、叶片正反面、果实或疑似病斑近照。</div>
        <div>Agent 只会先给出有证据的判断；打药、施肥等写操作仍需人工确认。</div>
      </div>
    </Flex>
  );
}
