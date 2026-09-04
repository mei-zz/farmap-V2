import { Divider } from "antd";
import { useEffect, useRef, useState, type JSX } from "react";

interface Props {
  title: string | JSX.Element;
  children: JSX.Element;
  style: React.CSSProperties;
}
export default function Window(props: Props) {
  // Layout properties
  const [top, setTop] = useState(200);
  const [left, setLeft] = useState(10);
  const [width, setWidth] = useState(400);
  const [height, setHeight] = useState(300);

  // Resize events
  const isDraggingRef = useRef(false);
  // Width
  const draggingBorder = useRef<"left" | "right" | "top" | "bottom" | null>(null);
  const startX = useRef(0);
  const startY = useRef(0);

  // PERF: resize with each corner
  const onMouseUp = () => {
    document.body.classList.remove("dragging");
    isDraggingRef.current = false;
  };
  const onMouseMove = (e: MouseEvent) => {
    if (!isDraggingRef.current) return;

    if (draggingBorder.current === "left" || draggingBorder.current === "right") {
      if (draggingBorder.current === "right" && e.clientX > window.innerWidth - 10) return;
      if (draggingBorder.current === "left" && e.clientX < 10) return;
      const dx = e.clientX - startX.current;
      startX.current = e.clientX;
      if (draggingBorder.current === "left") {
        setLeft((prev) => prev + dx);
        setWidth((prev) => {
          const newWidth = prev - dx;
          widthRef.current = newWidth;
          return newWidth;
        });
      } else {
        setWidth((prev) => {
          const newWidth = prev + dx;
          widthRef.current = newWidth;
          return newWidth;
        });
      }
    } else {
      if (draggingBorder.current === "bottom" && e.clientY > window.innerHeight - 10) return;
      if (draggingBorder.current === "top" && e.clientY < 10) return;
      const dy = e.clientY - startY.current;
      startY.current = e.clientY;
      if (draggingBorder.current === "top") {
        setTop((prev) => prev + dy);
        setHeight((prev) => {
          const newHeight = prev - dy;
          heightRef.current = newHeight;
          return newHeight;
        });
      } else {
        setHeight((prev) => {
          const newHeight = prev + dy;
          heightRef.current = newHeight;
          return newHeight;
        });
      }
    }
  };

  // Drag header
  const isHeaderDrag = useRef(false);
  const widthRef = useRef(width);
  const heightRef = useRef(height);

  const onHeaderDrag = (e: MouseEvent) => {
    if (!isHeaderDrag.current) return;
    const dx = e.clientX - startX.current;
    startX.current = e.clientX;
    setLeft((prev) => {
      const newLeft = prev + dx;
      return newLeft < 0 || newLeft + widthRef.current > window.innerWidth ? prev : newLeft;
    });

    const dy = e.clientY - startY.current;
    startY.current = e.clientY;
    setTop((prev) => {
      const newTop = prev + dy;
      return newTop < 0 || newTop + heightRef.current > window.innerHeight ? prev : newTop;
    });
  };
  const onHeaderUp = () => {
    document.body.classList.remove("dragging");
    isHeaderDrag.current = false;
  };

  // Bind events
  useEffect(() => {
    window.addEventListener("mousemove", onMouseMove);
    window.addEventListener("mouseup", onMouseUp);
    window.addEventListener("mousemove", onHeaderDrag);
    window.addEventListener("mouseup", onHeaderUp);

    return () => {
      window.removeEventListener("mousemove", onMouseMove);
      window.removeEventListener("mouseup", onMouseUp);
      window.removeEventListener("mousemove", onHeaderDrag);
      window.removeEventListener("mouseup", onHeaderUp);
    };
  }, []);
  return (
    <div
      style={{
        ...props.style,
        backgroundColor: "white",
        border: "1px solid #aaa",
        borderRadius: 8,
        padding: 12,
        position: "fixed",
        top,
        left,
        width,
        height,
      }}>
      <header
        style={{ color: "#2e66e1", fontWeight: "bold", cursor: "grabbing" }}
        onMouseDown={(e) => {
          document.body.classList.add("dragging");
          isHeaderDrag.current = true;
          startX.current = e.clientX;
          startY.current = e.clientY;
        }}>
        {props.title}
      </header>
      <Divider style={{ margin: 6 }} />
      {(isHeaderDrag.current || isDraggingRef.current) && (
        <section
          style={{
            position: "absolute",
            height: "calc(100% - 64px)",
            width: "calc(100% - 24px)",
            zIndex: 10,
          }}></section>
      )}
      <section style={{ height: "calc(100% - 38px)", position: "relative", zIndex: 0 }}>
        {props.children}
      </section>

      {/* Resize border */}
      <div
        style={{
          position: "absolute",
          top: 0,
          // TODO: minus own width
          left: -5,
          height: "100%",
          width: 5,
          // backgroundColor: "black",
          cursor: "w-resize",
        }}
        onMouseDown={(e) => {
          document.body.classList.add("dragging");
          startX.current = e.clientX;
          isDraggingRef.current = true;
          draggingBorder.current = "left";
        }}></div>
      <div
        style={{
          position: "absolute",
          top: 0,
          left: "100%",
          height: "100%",
          width: 5,
          // backgroundColor: "black",
          cursor: "w-resize",
        }}
        onMouseDown={(e) => {
          document.body.classList.add("dragging");
          startX.current = e.clientX;
          isDraggingRef.current = true;
          draggingBorder.current = "right";
        }}></div>
      <div
        style={{
          position: "absolute",
          top: -5,
          left: 0,
          height: 5,
          width: "100%",
          // backgroundColor: "black",
          cursor: "n-resize",
        }}
        onMouseDown={(e) => {
          document.body.classList.add("dragging");
          startY.current = e.clientY;
          isDraggingRef.current = true;
          draggingBorder.current = "top";
        }}></div>
      <div
        style={{
          position: "absolute",
          top: "100%",
          left: 0,
          height: 5,
          width: "100%",
          // backgroundColor: "black",
          cursor: "n-resize",
        }}
        onMouseDown={(e) => {
          document.body.classList.add("dragging");
          startY.current = e.clientY;
          isDraggingRef.current = true;
          draggingBorder.current = "bottom";
        }}></div>
    </div>
  );
}
