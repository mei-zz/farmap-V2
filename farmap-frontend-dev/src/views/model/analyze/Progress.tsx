import { Steps } from "antd";
import type { StepsProps } from "antd";

interface Props {
  currentStep: number;
  stepStatus: StepsProps["status"];
}
export default function Progress(props: Props) {
  const stepItems = [
    { title: "接收图片", content: "请手动选择图片并点击上传。" },
    { title: "上传图片", content: "将图片上传到服务器中..." },
    { title: "模型推理", content: "推理中..." },
    { title: "展示结果", content: "模型推理完成，请查看推理结果。" },
  ];
  const verticalStepProps: StepsProps = {
    type: "dot",
    items: stepItems,
    orientation: "vertical",
    style: {
      flex: "auto",
    },
  } as const;

  return (
    <Steps
      {...verticalStepProps}
      variant="outlined"
      current={props.currentStep}
      status={props.stepStatus}
    />
  );
}
