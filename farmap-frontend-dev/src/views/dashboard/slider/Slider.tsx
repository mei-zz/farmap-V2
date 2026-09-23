import { useCallback, useEffect, useRef, useState } from "react";
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
  const minimum = Number.isFinite(props.scale.min) ? props.scale.min : 0;
  const maximum = Number.isFinite(props.scale.max) && props.scale.max > minimum
    ? props.scale.max
    : minimum + 1;
  const scale = maximum - minimum;
  const toPosition = useCallback((value: number) => {
    const safeValue = Number.isFinite(value) ? value : minimum;
    return Math.max(0, Math.min(100, ((safeValue - minimum) / scale) * 100));
  }, [minimum, scale]);
  const toValue = (position: number) => {
    const safePosition = Number.isFinite(position) ? Math.max(0, Math.min(100, position)) : 0;
    return (safePosition / 100) * scale + minimum;
  };
  const displayValue = (position: number) => {
    const value = toValue(position);
    return props.decimal ? value.toFixed(2) : Math.floor(value);
  };

  // set initial position and scale through props
  const [position, setPosition] = useState<SliderPrivateProps["position"]>({
    left: toPosition(props.value.left),
    right: toPosition(props.value.right),
  });

  // move dots for initiation
  const latestPosition = useRef<SliderPrivateProps["position"]>({
    left: 0,
    right: 0,
  });
  useEffect(() => {
    const newPosition = {
      left: toPosition(props.value.left),
      right: toPosition(props.value.right),
    };
    // set position for locating
    setPosition(newPosition);
    // latest position for displaying on dots
    latestPosition.current = newPosition;
  }, [props.value, toPosition]);

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
    if (!Number.isFinite(containerWidth) || containerWidth <= 0) return;
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
    const leftValue = toValue(latestPosition.current.left);
    const rightValue = toValue(latestPosition.current.right);
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
          data-value={displayValue(latestPosition.current.left)}
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
          data-value={displayValue(latestPosition.current.right)}
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
