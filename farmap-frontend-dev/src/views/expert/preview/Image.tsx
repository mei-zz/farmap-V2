import { Carousel, Divider, Flex, Image as ImageAntd } from "antd";
import { useEffect, useRef, useState } from "react";

interface Props {
  urls: string;
}
export default function Image(props: Props) {
  const urls = props.urls.split(",");
  const images = urls.length === 0 ? [] : urls;

  const containerRef = useRef<HTMLDivElement>(null);
  const [imageWidth, setImageWidth] = useState(0);
  useEffect(() => {
    if (!containerRef.current) return;
    const width = window.innerWidth < 1000 ? window.innerWidth - 64 : 200;
    // : containerRef.current.getBoundingClientRect().width * 0.5;

    setImageWidth(width);
  }, []);

  return (
    <Flex vertical style={{ width: imageWidth }} ref={containerRef}>
      <Carousel arrows infinite={false} style={{ width: "100%" }}>
        {images.map(
          (img) => img !== "" && <ImageAntd alt="作物图像" width={imageWidth} src={img} />,
        )}
      </Carousel>
      <Divider style={{ width: imageWidth }} />
      <div style={{ width: "100%", flexGrow: 1 }}>
        <span style={{ color: "#dd0000" }}>*&nbsp;&nbsp;</span>作物记录图像
        <Divider orientation="vertical" />
        <span>点击可预览大图</span>
        <Divider orientation="vertical" />
        <span
          style={{
            fontSize: "0.6rem",
            color: "#555",
            backgroundColor: "#eee",
            width: "max-content",
            padding: "2px 5px",
            borderRadius: 4,
            marginBottom: 5,
          }}>
          共 {urls.length} 张
        </span>
      </div>
    </Flex>
  );
}
