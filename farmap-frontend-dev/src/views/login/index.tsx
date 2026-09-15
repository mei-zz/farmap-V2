import { useUserStore, type FarmPreviewType, type UserStoreState } from "@/store/user";
import { permanence } from "@/utils/permanence";
import { req } from "@/utils/reqeust";
import { Button, Card, Flex, Form, Input, Space, Tag, Typography } from "antd";
import type { FormProps } from "antd";
import { ArrowLeftOutlined, RobotOutlined } from "@ant-design/icons";
import { Loader2 } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router";
import { isDemoModeEnabled } from "@/utils/demoSession";

type UserLoginRequest = {
  username: string;
  password: string;
};
type UserLoginResult = {
  data: {
    user: {
      name: string;
      role: UserStoreState["role"];
    };
    farms: FarmPreviewType[];
    token: string;
  };
};

type LoginType = {
  username?: string;
  password?: string;
};

function Login() {
  // Enter the home page when token is valid
  const login = useUserStore((s) => s.login);
  const navigate = useNavigate();

  // Permanence
  const setToken = permanence.token.setToken;
  const setUserStore = permanence.user.setUserStore;

  // Loading
  const [loading, setLoading] = useState(false);

  // Error message for user
  const [msg, setMsg] = useState<string>("");

  const onFinish: FormProps<LoginType>["onFinish"] = async (values) => {
    try {
      setLoading(true);
      const resp = await req.post<UserLoginRequest, UserLoginResult>("/user/login", {
        username: values.username!,
        password: values.password!,
      });
      // Store this response to user store
      const userStore: UserStoreState = {
        username: resp.data.user.name,
        role: resp.data.user.role,
        farms: resp.data.farms,
      };
      login(userStore);
      // Store in local storage
      setToken(resp.data.token);
      setUserStore(userStore);

      navigate("/overview");

      // Reset error message
      setMsg("");
    } catch {
      // const msg = Request.getErrorMsg(e);
      setMsg("用户或密码错误");
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="farmap-auth-page">
      <section className="farmap-auth__brand">
        <Tag color="green">Agriculture × GIS × AI</Tag>
        <Typography.Title>FarMap</Typography.Title>
        <Typography.Title level={2}>多模态农业智能决策平台</Typography.Title>
        <Typography.Text className="farmap-landing__english">
          Multimodal Agricultural Intelligence Platform
        </Typography.Text>
        <Typography.Paragraph>
          面向真实农业任务，连接农场空间、农情数据、AI 诊断与专家知识。
        </Typography.Paragraph>
        <div className="farmap-auth__flow">感知 → 检索 → 推理 → 决策 → 执行</div>
      </section>

      <section className="farmap-auth__panel">
        <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate("/")}>
          返回首页
        </Button>
        <Card bordered={false} className="farmap-auth__card">
          <Typography.Text type="secondary">FarMap Console</Typography.Text>
          <Typography.Title level={2}>欢迎回来</Typography.Title>
          <Form name="login" layout="vertical" onFinish={onFinish} autoComplete="off">
            <Form.Item<LoginType>
              label="账号"
              name="username"
              rules={[{ required: true, message: "请输入账号" }]}
            >
              <Input placeholder="请输入账号" />
            </Form.Item>

            <Form.Item<LoginType>
              label="密码"
              name="password"
              rules={[{ required: true, message: "请输入密码" }]}
            >
              <Input.Password placeholder="请输入密码" />
            </Form.Item>

            <Form.Item>
              <Button type="primary" htmlType="submit" block disabled={loading}>
                登录控制台
              </Button>
              {loading && (
                <Flex justify="center" style={{ marginTop: 12 }}>
                  <Loader2 className="w-6 h-6 text-blue-500 animate-spin" />
                </Flex>
              )}
            </Form.Item>
          </Form>
          {msg.length !== 0 && <div className="farmap-auth__error">{msg}</div>}
          {isDemoModeEnabled && (
            <>
              <div className="farmap-auth__separator">或</div>
              <Button block icon={<RobotOutlined />} onClick={() => navigate("/demo")}>
                进入演示
              </Button>
            </>
          )}
        </Card>
        <Space className="farmap-auth__security-note">
          <Typography.Text type="secondary">正式登录使用现有认证服务</Typography.Text>
        </Space>
      </section>
    </main>
  );
}

export default Login;
