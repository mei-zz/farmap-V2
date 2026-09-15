import { Flex, Spin, Typography } from "antd";
import { useEffect } from "react";
import { useNavigate } from "react-router";
import { createDemoSession, isDemoModeEnabled } from "@/utils/demoSession";

export default function DemoEntry() {
  const navigate = useNavigate();

  useEffect(() => {
    if (!isDemoModeEnabled) {
      navigate("/login", { replace: true });
      return;
    }

    createDemoSession();
    navigate("/overview", { replace: true });
  }, [navigate]);

  return (
    <Flex vertical align="center" justify="center" gap={12} style={{ minHeight: "100vh" }}>
      <Spin />
      <Typography.Text>正在初始化 FarMap 演示环境…</Typography.Text>
    </Flex>
  );
}
