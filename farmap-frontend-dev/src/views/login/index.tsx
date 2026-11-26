import { useState } from "react";
import { Alert, Button, Form, Input, Modal } from "antd";
import type { UserStoreState } from "@/store/user";
import { req } from "@/utils/reqeust";

type Credentials = { username: string; password: string };
type AuthResult = { code: number; data?: { token: string; user: { name: string; role: UserStoreState["role"] }; farms?: UserStoreState["farms"] } };

export default function AccountLogin({ onClose, onLogin }: {
  onClose: () => void;
  onLogin: (token: string, user: UserStoreState) => void;
}) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function signIn(credentials: Credentials) {
    if (loading) return;
    setLoading(true);
    setError("");
    try {
      const { code, data } = await req.post<Credentials, AuthResult>("/user/login", credentials);
      if (code !== 1 || !data?.token || !data.user?.role) {
        setError("账号或密码不正确。");
        return;
      }
      onLogin(data.token, { username: data.user.name, role: data.user.role, farms: data.farms ?? [] });
    } catch {
      setError("登录失败。请检查账号、密码或网络后重试。");
    } finally {
      setLoading(false);
    }
  }

  return <Modal open title="登录 FarMap" onCancel={onClose} closable={!loading} maskClosable={!loading}
    keyboard={!loading} footer={null} width={420}>
    <Form<Credentials> layout="vertical" onFinish={(credentials) => void signIn(credentials)}>
      <p className="agent-shell__login-description">登录后可访问农场数据、摄像头和账号对话历史。</p>
      {error && <Alert type="error" showIcon message={error} className="agent-shell__login-error" />}
      <Form.Item name="username" label="账号" rules={[{ required: true, whitespace: true, message: "请输入账号。" }]}>
        <Input autoFocus autoComplete="username" disabled={loading} />
      </Form.Item>
      <Form.Item name="password" label="密码" rules={[{ required: true, message: "请输入密码。" }]}>
        <Input.Password autoComplete="current-password" disabled={loading} />
      </Form.Item>
      <Button type="primary" htmlType="submit" aria-label="登录" loading={loading} block>登录</Button>
    </Form>
  </Modal>;
}
