import { useEffect, useRef, useState } from "react";
import style from "./index.module.css";

interface SliderCssVariables extends React.CSSProperties {
  "--l-back-pos"?: string;
  "--r-back-pos"?: string;
}

type SliderPrivateProps = {
  position: {
    left: number;
    right: number;
  };
};
export type SliderProps = {
  disabled: boolean;
  value: {
    left: number;
    right: number;
  };
  scale: {
    min: number;
    max: number;
  };
  decimal: boolean;
  onChangeEnd: (leftValue: number, rightValue: number) => void;
};
function Slider(props: SliderProps) {
  // set initial position and scale through props
  const scale = props.scale.max - props.scale.min;
  const [position, setPosition] = useState<SliderPrivateProps["position"]>({
    left: ((props.value.left - props.scale.min) / (props.scale.max - props.scale.min)) * 100,
    right: ((props.value.right - props.scale.min) / (props.scale.max - props.scale.min)) * 100,
  });

  // move dots for initiation
  const latestPosition = useRef<SliderPrivateProps["position"]>({
    left: 0,
    right: 0,
  });
  useEffect(() => {
    const newPosition = {
      left: ((props.value.left - props.scale.min) / (props.scale.max - props.scale.min)) * 100,
      right: ((props.value.right - props.scale.min) / (props.scale.max - props.scale.min)) * 100,
    };
    // set position for locating
    setPosition(newPosition);
    // latest position for displaying on dots
    latestPosition.current = newPosition;
  }, [props.value, props.scale]);

  // get references of container and target
  const sliderContainer = useRef<HTMLDivElement | null>(null);
  const leftTarget = useRef<HTMLDivElement | null>(null);
  const rightTarget = useRef<HTMLDivElement | null>(null);
  const currentTarget = useRef<HTMLDivElement | null>(null);

  // events
  const isDragging = useRef(false);
  const handlePointerMove = (e: PointerEvent) => {
    if (!isDragging.current || !sliderContainer.current) return;
    // get the denominator of calculated left
    const containerRect = sliderContainer.current.getBoundingClientRect();
    const containerLeft = containerRect.left;
    const containerWidth = containerRect.width;
    // get the numerator of calculcated left
    const pointLeft = e.clientX;
    // calculate left
    const type = currentTarget.current!.getAttribute("data-type") as "left" | "right";
    const calculatedLeft =
      type === "left"
        ? Math.max(
            0,
            Math.min(((pointLeft - containerLeft) / containerWidth) * 100, position.right),
          )
        : Math.max(
            position.left,
            Math.min(((pointLeft - containerLeft) / containerWidth) * 100, 100),
          );
    // set new position
    const newPosition = {
      left: type === "left" ? calculatedLeft : position.left,
      right: type === "right" ? calculatedLeft : position.right,
    };
    setPosition(newPosition);
    // set latest position for using
    latestPosition.current = newPosition;
  };
  const handlePointerUp = () => {
    currentTarget.current = null;
    isDragging.current = false;
    // trigger change end event to modify outer states
    const leftValue = (latestPosition.current.left / 100) * scale + props.scale.min;
    const rightValue = (latestPosition.current.right / 100) * scale + props.scale.min;
    props.onChangeEnd(leftValue, rightValue);
    // remove events from window object
    window.removeEventListener("pointermove", handlePointerMove);
    window.removeEventListener("pointerup", handlePointerUp);
  };
  // switch upper dot when click
  const upperDot = useRef<"left" | "right">("left");
  const handlePointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (props.disabled) return;
    // find current dot target
    const target = e.target as HTMLDivElement;
    currentTarget.current = target;
    // adapt z-index
    const type = target.getAttribute("data-type") as "left" | "right";
    upperDot.current = type;
    isDragging.current = true;
    // add events to window object
    window.addEventListener("pointermove", handlePointerMove);
    window.addEventListener("pointerup", handlePointerUp);
  };

  return (
    <div>
      <div className={style.slider__container} ref={sliderContainer}>
        <div
          className={style.slider__background}
          style={
            {
              "--l-back-pos": `${position.left}%`,
              "--r-back-pos": `${position.right}%`,
            } as SliderCssVariables
          }></div>

        <div
          className={style.slider__dot}
          data-disabled={props.disabled}
          data-type="left"
          data-value={((v) => (Number.isNaN(v) ? 0 : v))(
            !props.decimal
              ? Math.floor((latestPosition.current.left / 100) * scale + props.scale.min)
              : ((latestPosition.current.left / 100) * scale + props.scale.min).toFixed(2),
          )}
          ref={leftTarget}
          style={{
            left: `${position.left}%`,
            zIndex: `${upperDot.current === "left" ? 1 : 0}`,
            cursor: props.disabled ? "not-allowed" : "pointer",
          }}
          onPointerDown={handlePointerDown}></div>
        <div
          className={style.slider__dot}
          data-disabled={props.disabled}
          data-type="right"
          data-value={((v) => (Number.isNaN(v) ? 0 : v))(
            !props.decimal
              ? Math.floor((latestPosition.current.right / 100) * scale + props.scale.min)
              : ((latestPosition.current.right / 100) * scale + props.scale.min).toFixed(2),
          )}
          ref={rightTarget}
          style={{
            left: `${position.right}%`,
            zIndex: `${upperDot.current === "right" ? 1 : 0}`,
            cursor: props.disabled ? "not-allowed" : "pointer",
          }}
          onPointerDown={handlePointerDown}></div>
      </div>
    </div>
  );
}

export default Slider;
