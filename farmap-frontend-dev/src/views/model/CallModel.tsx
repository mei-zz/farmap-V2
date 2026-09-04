import { useState } from "react";

import { Card, Flex, type StepsProps } from "antd";
import { BarsOutlined, UploadOutlined } from "@ant-design/icons";
import Upload from "./analyze/Upload";
import Progress from "./analyze/Progress";

interface Props {
  onSubmit: () => void;
  onSubmitEnd: () => void;
  onFinish: (res: string, type: "normal" | "error") => void;
}
export default function CallModel(props: Props) {
  // Steps
  const [currentStep, setCurrentStep] = useState<number>(0);
  const [stepStatus, setStepStatus] = useState<StepsProps["status"]>("process");

  return (
    <Flex gap="0.5rem" style={{ flexGrow: 0 }} className="resp-model__upload">
      <Card
        title={
          <>
            <UploadOutlined />
            &nbsp;&nbsp;上传作物
          </>
        }
        styles={{ body: { height: "calc(100% - 60px)" } }}>
        <Upload
          onProgress={(curr, status) => {
            setCurrentStep(curr);
            setStepStatus(status);

            // Loading
            props.onSubmit();
            if (status === "error") {
              props.onSubmitEnd();
              return;
            }

            if (curr < 3) props.onSubmit();
            else props.onSubmitEnd();
          }}
          onFinish={props.onFinish}
        />
      </Card>
      <Card
        title={
          <>
            <BarsOutlined />
            &nbsp;&nbsp;推理进度
          </>
        }
        style={{ flexGrow: 1 }}>
        <Progress currentStep={currentStep} stepStatus={stepStatus} />
      </Card>
    </Flex>
  );
}
