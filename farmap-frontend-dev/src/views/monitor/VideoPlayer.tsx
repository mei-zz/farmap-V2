interface Props {
  videoUrl: string;
  style?: React.CSSProperties;
}
export default function VideoPlayer(props: Props) {
  return (
    <>
      {props.videoUrl.length === 0 ? (
        <div>请从监控列表选择视频以预览。</div>
      ) : (
        <iframe
          src={props.videoUrl}
          style={{
            border: "none",
            width: "100%",
            height: "100%",
            ...props.style,
          }}></iframe>
      )}
    </>
  );
}
