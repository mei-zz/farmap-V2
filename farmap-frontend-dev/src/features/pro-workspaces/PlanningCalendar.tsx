import { useEffect, useMemo, useState } from "react";
import { Button, Empty } from "antd";
import { useAIContextStore } from "@/store/aiContext";
import type { OperationsTaskViewModel } from "@/types/operations";

type Event = { task: OperationsTaskViewModel; label: string; date: string };
function dateKey(value?: string) {
  return value?.match(/^\d{4}-\d{2}-\d{2}/)?.[0];
}
function monthOf(value?: string) {
  return (
    dateKey(value)?.slice(0, 7) ??
    new Date()
      .toLocaleDateString("en-CA", { timeZone: "Asia/Shanghai" })
      .slice(0, 7)
  );
}
function shiftMonth(month: string, delta: number) {
  const [year, index] = month.split("-").map(Number);
  const d = new Date(year, index - 1 + delta, 1);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}`;
}
export default function PlanningCalendar({
  tasks,
  onSelect,
}: {
  tasks: OperationsTaskViewModel[];
  onSelect: (task: OperationsTaskViewModel) => void;
}) {
  const referenceDate = useAIContextStore(
    (state) => state.context.referenceDate,
  );
  const [month, setMonth] = useState(() => monthOf(referenceDate));
  useEffect(() => setMonth(monthOf(referenceDate)), [referenceDate]);
  const events = useMemo(
    () =>
      tasks.flatMap((task) =>
        [
          { task, label: "计划", date: dateKey(task.scheduledFor) },
          { task, label: "开始", date: dateKey(task.startedAt) },
          { task, label: "完成", date: dateKey(task.completedAt) },
        ].filter((event): event is Event => Boolean(event.date)),
      ),
    [tasks],
  );
  const [year, number] = month.split("-").map(Number);
  const offset = (new Date(year, number - 1, 1).getDay() + 6) % 7;
  const count = new Date(year, number, 0).getDate();
  const unscheduled = tasks.filter(
    (task) => !dateKey(task.scheduledFor) && !["COMPLETED", "CANCELLED"].includes(task.status),
  );
  return (
    <section className="fm-pro-calendar">
      <div className="fm-pro-calendar__heading">
        <div>
          <h3>
            {year} 年 {number} 月
          </h3>
          <p>查看计划日期和实际开始、完成记录。</p>
        </div>
        <div>
          <Button
            onClick={() => setMonth(shiftMonth(month, -1))}
            aria-label="上个月"
          >
            ‹
          </Button>{" "}
          <Button
            onClick={() => setMonth(shiftMonth(month, 1))}
            aria-label="下个月"
          >
            ›
          </Button>
        </div>
      </div>
      <div className="fm-pro-calendar__grid">
        {["一", "二", "三", "四", "五", "六", "日"].map((day) => (
          <div className="fm-pro-calendar__weekday" key={day}>
            周{day}
          </div>
        ))}
        {Array.from(
          { length: Math.ceil((offset + count) / 7) * 7 },
          (_, index) => {
            const day = index - offset + 1;
            if (day < 1 || day > count)
              return (
                <div className="fm-pro-calendar__day is-outside" key={index} />
              );
            const date = `${month}-${String(day).padStart(2, "0")}`;
            const dayEvents = events.filter((event) => event.date === date);
            return (
              <div className="fm-pro-calendar__day" key={date}>
                <b>{day}</b>
                {dayEvents.map((event) => (
                  <button
                    type="button"
                    key={`${event.task.key}-${event.label}`}
                    onClick={() => onSelect(event.task)}
                  >
                    <span>{event.label}</span> {event.task.title}
                  </button>
                ))}
              </div>
            );
          },
        )}
      </div>
      <div className="fm-pro-calendar__unscheduled">
        <h4>
          未排期任务 <small>{unscheduled.length}</small>
        </h4>
        {unscheduled.length ? (
          unscheduled.map((task) => (
            <Button key={task.key} type="link" onClick={() => onSelect(task)}>
              {task.title}
            </Button>
          ))
        ) : (
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description="暂无待处理任务"
          />
        )}
      </div>
    </section>
  );
}
