interface Props {
  requestId: string;
  modelVersion: string;
}
export default function Header(props: Props) {
  return (
    <>
      <div
        style={{
          fontSize: "0.6rem",
          color: "#555",
          backgroundColor: "#eee",
          width: "max-content",
          padding: "2px 5px",
          borderRadius: 4,
          marginBottom: 5,
        }}>
        {props.requestId}
      </div>
      {/*
      <div
        style={{
          fontSize: "0.6rem",
          color: "#555",
          backgroundColor: "#eee",
          width: "max-content",
          padding: "2px 5px",
          borderRadius: 4,
          marginBottom: 5,
        }}>
        推理模型：{props.modelVersion}
      </div>
        */}
    </>
  );
}
