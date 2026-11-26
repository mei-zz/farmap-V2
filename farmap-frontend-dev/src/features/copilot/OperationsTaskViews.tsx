import { useEffect, useId, useMemo, useState } from "react";
import { OperationTaskFeedback } from "./OperationsChatArtifacts";
import { permanence } from "@/utils/permanence";
import {
  getOperationsTaskView,
  operationsViewErrorMessage,
  scheduleOperationsTasksBatch,
  type OperationsTaskStatus,
  type OperationsTaskViewCounts,
  type OperationsTaskViewItem,
  type OperationsTaskViewMode,
  type OperationsTaskViewResult,
} from "./operationsTaskViewsService";
import "./operations-task-views.css";

type OperationsTaskViewProps = {
  farmId: number;
  fieldId?: string;
  from?: string;
  to?: string;
  filter?: string;
  view: OperationsTaskViewMode;
  onOpenTask?: (taskId: string) => void;
  professional?: boolean;
  embedded?: boolean;
  allowBatch?: boolean;
};

type StatusFilter = "ALL" | OperationsTaskStatus;
type CalendarMode = "week" | "month";
type CalendarEvent = {
  key: string;
  date: string;
  kind: "planned" | "started" | "completed";
  label: string;
  task: OperationsTaskViewItem;
};

const TIME_ZONE = "Asia/Shanghai";
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/u;
const EMPTY_TASKS: OperationsTaskViewItem[] = [];
const EMPTY_COUNTS: OperationsTaskViewCounts = { total: 0, pending: 0, inProgress: 0, completed: 0 };
const STATUS_LABELS: Record<OperationsTaskStatus, string> = {
  OPEN: "待处理",
  ASSIGNED: "已分配",
  IN_PROGRESS: "执行中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  UNKNOWN: "状态未知",
};
const STATUS_FILTERS = [
  { value: "ALL", label: "全部状态" },
  { value: "OPEN", label: "待处理" },
  { value: "ASSIGNED", label: "已分配" },
  { value: "IN_PROGRESS", label: "执行中" },
  { value: "COMPLETED", label: "已完成" },
  { value: "CANCELLED", label: "已取消" },
] as const;
const WEEKDAY_LABELS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"];
const VIEW_TITLES: Record<OperationsTaskViewMode, string> = {
  tasks: "农事任务",
  calendar: "农事日历",
  records: "执行记录",
};
const VIEW_DESCRIPTIONS: Record<OperationsTaskViewMode, string> = {
  tasks: "查看任务总量、状态与计划日期。",
  calendar: "按周或按月查看计划、开始与完成日期。",
  records: "查看已完成任务的执行结果与证据。",
};

const dateKeyFormatter = new Intl.DateTimeFormat("en-CA", {
  timeZone: TIME_ZONE,
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});
const timeFormatter = new Intl.DateTimeFormat("zh-CN", {
  timeZone: TIME_ZONE,
  hour: "2-digit",
  minute: "2-digit",
  hourCycle: "h23",
});

function dateKeyInShanghai(date: Date): string {
  const parts: Record<string, string> = {};
  for (const part of dateKeyFormatter.formatToParts(date)) {
    if (part.type === "year" || part.type === "month" || part.type === "day") parts[part.type] = part.value;
  }
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function todayDateKey(): string {
  return dateKeyInShanghai(new Date());
}

function hasExplicitOffset(value: string): boolean {
  return /(?:Z|[+-]\d{2}:?\d{2})$/u.test(value.trim());
}

function dateKeyFromValue(value?: string): string | undefined {
  if (!value) return undefined;
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  const simple = trimmed.match(/^(\d{4}-\d{2}-\d{2})(?:$|[T ])/u);
  if (simple && !hasExplicitOffset(trimmed)) return simple[1];
  const timestamp = Date.parse(trimmed);
  if (Number.isFinite(timestamp)) return dateKeyInShanghai(new Date(timestamp));
  return trimmed.match(/^(\d{4}-\d{2}-\d{2})/u)?.[1];
}

function dateInputValue(value?: string): string {
  return dateKeyFromValue(value)?.slice(0, 10) ?? "";
}

function formatDateLabel(value: string): string {
  const [year, month, day] = value.split("-").map(Number);
  if (!year || !month || !day) return value;
  return `${year}年${month}月${day}日`;
}

function shortDateLabel(value: string): string {
  const [, month, day] = value.split("-").map(Number);
  return `${month}月${day}日`;
}

function formatDateTime(value?: string): string {
  if (!value) return "未设置";
  const trimmed = value.trim();
  if (DATE_ONLY.test(trimmed)) return formatDateLabel(trimmed);
  const dateKey = dateKeyFromValue(trimmed);
  const naiveTime = trimmed.match(/[T ](\d{2}:\d{2})(?::\d{2})?/u)?.[1];
  if (dateKey && naiveTime && !hasExplicitOffset(trimmed)) {
    return `${formatDateLabel(dateKey)} ${naiveTime}`;
  }
  const timestamp = Date.parse(trimmed);
  if (Number.isFinite(timestamp)) {
    const instant = new Date(timestamp);
    return `${formatDateLabel(dateKeyInShanghai(instant))} ${timeFormatter.format(instant)}`;
  }
  return dateKey ? formatDateLabel(dateKey) : trimmed;
}

function utcDateFromKey(value: string): Date {
  const [year, month, day] = value.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, day));
}

function keyFromUtcDate(date: Date): string {
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}-${String(date.getUTCDate()).padStart(2, "0")}`;
}

function addDaysToKey(value: string, days: number): string {
  const date = utcDateFromKey(value);
  date.setUTCDate(date.getUTCDate() + days);
  return keyFromUtcDate(date);
}

function startOfWeekKey(value: string): string {
  const date = utcDateFromKey(value);
  return addDaysToKey(value, -((date.getUTCDay() + 6) % 7));
}

function shiftMonthKey(value: string, delta: number): string {
  const [year, month] = value.split("-").map(Number);
  return keyFromUtcDate(new Date(Date.UTC(year, month - 1 + delta, 1)));
}

function monthLabel(value: string): string {
  const [year, month] = value.split("-").map(Number);
  return `${year}年${month}月`;
}

function weekRangeLabel(value: string): string {
  const start = startOfWeekKey(value);
  const end = addDaysToKey(start, 6);
  return `${shortDateLabel(start)} – ${shortDateLabel(end)}`;
}

function monthCellKeys(value: string): string[] {
  const start = startOfWeekKey(`${value.slice(0, 7)}-01`);
  return Array.from({ length: 42 }, (_, index) => addDaysToKey(start, index));
}

function canSchedule(task: OperationsTaskViewItem): boolean {
  return (task.status === "OPEN" || task.status === "ASSIGNED")
    && Number.isSafeInteger(task.version)
    && task.version >= 0;
}


function searchableText(task: OperationsTaskViewItem): string {
  return [
    task.taskId,
    task.title,
    task.fieldId,
    task.fieldName,
    STATUS_LABELS[task.status],
    task.outcome?.summary,
    task.outcome?.notes,
    task.outcome?.executionEvidence,
  ].filter(Boolean).join(" ").toLocaleLowerCase("zh-CN");
}


function sortKey(task: OperationsTaskViewItem, view: OperationsTaskViewMode): string {
  const candidate = view === "records"
    ? task.completedAt || task.startedAt || task.createdAt || task.scheduledFor
    : task.scheduledFor || task.startedAt || task.completedAt || task.createdAt;
  return dateKeyFromValue(candidate) ?? "";
}

function sortTasks(tasks: OperationsTaskViewItem[], view: OperationsTaskViewMode): OperationsTaskViewItem[] {
  return [...tasks].sort((left, right) => {
    const leftKey = sortKey(left, view);
    const rightKey = sortKey(right, view);
    if (leftKey && !rightKey) return -1;
    if (!leftKey && rightKey) return 1;
    const dateOrder = leftKey.localeCompare(rightKey);
    if (dateOrder) return view === "records" ? -dateOrder : dateOrder;
    return left.title.localeCompare(right.title, "zh-CN");
  });
}

function calendarEvents(tasks: OperationsTaskViewItem[]): CalendarEvent[] {
  return tasks.flatMap((task) => {
    const entries: Array<[string | undefined, CalendarEvent["kind"], string]> = [
      [task.scheduledFor, "planned", "计划"],
      [task.startedAt, "started", "开始"],
      [task.completedAt, "completed", "完成"],
    ];
    return entries.flatMap(([value, kind, label]) => {
      const date = dateKeyFromValue(value);
      return date ? [{ key: `${task.taskId}:${kind}:${date}`, date, kind, label, task }] : [];
    });
  }).sort((left, right) => left.date.localeCompare(right.date) || left.label.localeCompare(right.label, "zh-CN"));
}

function StatusBadge({ status }: { status: OperationsTaskStatus }) {
  return <span className="farmap-ops-view__status" data-status={status}>{STATUS_LABELS[status]}</span>;
}

function EmptyState({ message }: { message: string }) {
  return <div className="farmap-ops-view__empty" role="status">
    <strong>暂无数据</strong>
    <p>{message}</p>
  </div>;
}
type TaskCardProps = {
  task: OperationsTaskViewItem;
  view: OperationsTaskViewMode;
  detailOpen: boolean;
  detailId: string;
  externalOpen: boolean;
  batchEnabled: boolean;
  batchSelected: boolean;
  batchBusy: boolean;
  onOpen: (taskId: string) => void;
  onBatchChange: (taskId: string) => void;
};

function TaskCard({
  task,
  view,
  detailOpen,
  detailId,
  externalOpen,
  batchEnabled,
  batchSelected,
  batchBusy,
  onOpen,
  onBatchChange,
}: TaskCardProps) {
  const titleId = useId();
  const fieldLabel = task.fieldName;
  const primaryDate = view === "records"
    ? { label: "完成", value: task.completedAt, empty: "无完成时间" }
    : { label: "计划", value: task.scheduledFor, empty: "未排期" };
  const primaryDateKey = dateKeyFromValue(primaryDate.value);
  const openLabel = externalOpen
    ? "打开任务详情"
    : detailOpen ? "收起任务详情" : "查看任务详情";

  return <article
    className={`farmap-ops-view__task${detailOpen ? " is-selected" : ""}${batchSelected ? " is-batch-selected" : ""}`}
    data-status={task.status}
    aria-labelledby={titleId}
  >
    <div className="farmap-ops-view__task-top">
      {batchEnabled && <label className="farmap-ops-view__check" title="选择此任务">
        <input
          type="checkbox"
          checked={batchSelected}
          disabled={batchBusy}
          aria-label={`选择任务：${task.title}`}
          onChange={() => onBatchChange(task.taskId)}
        />
      </label>}
      <div className="farmap-ops-view__task-heading">
        <div className="farmap-ops-view__task-title-row">
          <h4 id={titleId}>{task.title}</h4>
          <StatusBadge status={task.status} />
        </div>
        <div className="farmap-ops-view__task-meta">
          <span className={`farmap-ops-view__task-date${primaryDate.value ? "" : " is-empty"}`}>
            <span className="farmap-ops-view__task-date-label">{primaryDate.label}</span>
            {primaryDate.value
              ? <time dateTime={primaryDateKey || undefined}>{formatDateTime(primaryDate.value)}</time>
              : primaryDate.empty}
          </span>
          {fieldLabel && <span className="farmap-ops-view__task-field">地块：{fieldLabel}</span>}
        </div>
      </div>
      <button
        type="button"
        className="farmap-ops-view__open"
        aria-expanded={externalOpen ? undefined : detailOpen}
        aria-controls={externalOpen || !detailOpen ? undefined : detailId}
        aria-label={externalOpen ? `打开任务详情：${task.title}` : undefined}
        onClick={() => onOpen(task.taskId)}
      >
        {openLabel}
      </button>
    </div>
  </article>;
}

type CalendarDayProps = {
  dateKey: string;
  outside?: boolean;
  today?: boolean;
  compact?: boolean;
  events: CalendarEvent[];
  onOpen: (taskId: string) => void;
};

function CalendarDay({ dateKey, outside = false, today = false, compact = false, events, onOpen }: CalendarDayProps) {
  return <div
    className={`farmap-ops-view__day${outside ? " is-outside" : ""}${today ? " is-today" : ""}${compact ? " is-week" : ""}`}
    role="group"
    aria-label={`${formatDateLabel(dateKey)}的任务`}
  >
    <div className="farmap-ops-view__day-head">
      <span>{compact ? shortDateLabel(dateKey) : Number(dateKey.slice(8, 10))}</span>
      {today && <small>今天</small>}
    </div>
    <div className="farmap-ops-view__day-events">
      {events.map((event) => <button
        type="button"
        key={event.key}
        data-kind={event.kind}
        aria-label={`${event.label}：${event.task.title}，${formatDateLabel(event.date)}`}
        onClick={() => onOpen(event.task.taskId)}
      >
        <span>{event.label}</span>
        {event.task.title}
      </button>)}
    </div>
  </div>;
}

type TaskCalendarProps = {
  tasks: OperationsTaskViewItem[];
  anchor: string;
  mode: CalendarMode;
  onModeChange: (mode: CalendarMode) => void;
  onNavigate: (anchor: string) => void;
  onToday: () => void;
  onOpen: (taskId: string) => void;
};

function TaskCalendar({ tasks, anchor, mode, onModeChange, onNavigate, onToday, onOpen }: TaskCalendarProps) {
  const today = todayDateKey();
  const events = useMemo(() => calendarEvents(tasks), [tasks]);
  const eventsByDate = useMemo(() => {
    const grouped = new Map<string, CalendarEvent[]>();
    for (const event of events) {
      const current = grouped.get(event.date);
      if (current) current.push(event);
      else grouped.set(event.date, [event]);
    }
    return grouped;
  }, [events]);
  const month = anchor.slice(0, 7);
  const days = mode === "month"
    ? monthCellKeys(anchor)
    : Array.from({ length: 7 }, (_, index) => addDaysToKey(startOfWeekKey(anchor), index));
  const unscheduled = tasks.filter((task) => {
    if (dateKeyFromValue(task.scheduledFor)) return false;
    return task.status !== "COMPLETED" && task.status !== "CANCELLED";
  });
  const title = mode === "month" ? monthLabel(anchor) : weekRangeLabel(anchor);

  function move(delta: number) {
    onNavigate(mode === "month" ? shiftMonthKey(anchor, delta) : addDaysToKey(startOfWeekKey(anchor), delta * 7));
  }

  return <section className="farmap-ops-view__calendar" aria-label="任务日历">
    <header className="farmap-ops-view__calendar-head">
      <div>
        <h4>{title}</h4>
        <p>计划、开始和完成日期均按 Asia/Shanghai 时间显示。</p>
      </div>
      <div className="farmap-ops-view__calendar-controls">
        <div className="farmap-ops-view__segmented" role="group" aria-label="日历显示方式">
          <button type="button" aria-pressed={mode === "week"} onClick={() => onModeChange("week")}>周视图</button>
          <button type="button" aria-pressed={mode === "month"} onClick={() => onModeChange("month")}>月视图</button>
        </div>
        <div className="farmap-ops-view__calendar-nav" role="group" aria-label="切换日期范围">
          <button type="button" aria-label={mode === "month" ? "上个月" : "上一周"} onClick={() => move(-1)}>‹</button>
          <button type="button" onClick={onToday}>今天</button>
          <button type="button" aria-label={mode === "month" ? "下个月" : "下一周"} onClick={() => move(1)}>›</button>
        </div>
      </div>
    </header>

    <div className="farmap-ops-view__calendar-scroll">
      <div className={`farmap-ops-view__calendar-grid is-${mode}`} aria-label={mode === "month" ? "月任务日历" : "周任务日历"}>
        {WEEKDAY_LABELS.map((label) => <div className="farmap-ops-view__weekday" key={label}>{label}</div>)}
        {days.map((day) => {
          const outside = mode === "month" && day.slice(0, 7) !== month;
          const dayEvents = outside ? [] : eventsByDate.get(day) ?? [];
          return <CalendarDay
            key={day}
            dateKey={day}
            outside={outside}
            today={day === today}
            compact={mode === "week"}
            events={dayEvents}
            onOpen={onOpen}
          />;
        })}
      </div>
    </div>

    {events.length === 0 && <p className="farmap-ops-view__calendar-empty" role="status">
      当前筛选范围内暂无排期或执行日期。
    </p>}

    <section className="farmap-ops-view__unscheduled" aria-label="未排期任务">
      <h4>未排期任务 <small>{unscheduled.length}</small></h4>
      {unscheduled.length ? <div>
        {unscheduled.map((task) => <button type="button" key={task.taskId} onClick={() => onOpen(task.taskId)}>
          {task.title}
        </button>)}
      </div> : <p>没有未排期任务。</p>}
    </section>
  </section>;
}
export function OperationsTaskView({
  farmId,
  fieldId,
  from,
  to,
  filter,
  view,
  onOpenTask,
  professional = false,
  embedded = false,
  allowBatch = false,
}: OperationsTaskViewProps) {
  const token = permanence.token.useToken();
  const headingId = useId();
  const detailId = useId();
  const currentMonth = `${todayDateKey().slice(0, 7)}-01`;
  const defaultMonth = view === "calendar" && !from && !to;
  const scopeFrom = defaultMonth ? currentMonth : dateInputValue(from);
  const scopeTo = defaultMonth ? addDaysToKey(shiftMonthKey(currentMonth, 1), -1) : dateInputValue(to);
  const [dateFrom, setDateFrom] = useState(scopeFrom);
  const [dateTo, setDateTo] = useState(scopeTo);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>("ALL");
  const [search, setSearch] = useState("");
  const [data, setData] = useState<OperationsTaskViewResult>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const [selectedTaskId, setSelectedTaskId] = useState<string>();
  const [selectedTaskIds, setSelectedTaskIds] = useState<string[]>([]);
  const [batchDate, setBatchDate] = useState("");
  const [batchPending, setBatchPending] = useState(false);
  const [batchError, setBatchError] = useState("");
  const [calendarMode, setCalendarMode] = useState<CalendarMode>(() => scopeFrom && scopeTo && scopeTo <= addDaysToKey(scopeFrom, 6) ? "week" : "month");

  const dateRangeInvalid = Boolean(dateFrom && dateTo && dateFrom > dateTo);

  useEffect(() => {
    setDateFrom(scopeFrom);
    setDateTo(scopeTo);
  }, [scopeFrom, scopeTo]);

  useEffect(() => {
    setSelectedTaskId(undefined);
    setSelectedTaskIds([]);
    setBatchDate("");
    setBatchPending(false);
    setBatchError("");
  }, [farmId, fieldId, view]);

  useEffect(() => {
    let active = true;
    if (!Number.isSafeInteger(farmId) || farmId <= 0) {
      setData(undefined);
      setError("缺少有效的农场编号，无法读取农事任务。");
      setLoading(false);
      return () => { active = false; };
    }
    if (dateRangeInvalid) {
      setData(undefined);
      setError("开始日期不能晚于结束日期。");
      setLoading(false);
      return () => { active = false; };
    }

    setLoading(true);
    setError("");
    setData(undefined);
    void getOperationsTaskView({
      farmId,
      fieldId,
      from: dateFrom,
      to: dateTo,
      view,
      filter,
    }, token).then((result) => {
      if (active) setData(result);
    }).catch((reason: unknown) => {
      if (active) setError(operationsViewErrorMessage(reason, "农事任务读取失败"));
    }).finally(() => {
      if (active) setLoading(false);
    });

    return () => { active = false; };
  }, [farmId, fieldId, dateFrom, dateTo, view, filter, token, reloadKey, dateRangeInvalid]);

  const counts = data?.counts ?? EMPTY_COUNTS;
  const allTasks = data?.items ?? EMPTY_TASKS;
  const normalizedSearch = search.trim().toLocaleLowerCase("zh-CN");
  const baseVisibleTasks = useMemo(() => allTasks.filter((task) => {
    if (statusFilter !== "ALL" && task.status !== statusFilter) return false;
    if (normalizedSearch && !searchableText(task).includes(normalizedSearch)) return false;
    return true;
  }), [allTasks, statusFilter, normalizedSearch]);
  const listTasks = useMemo(() => sortTasks(baseVisibleTasks, view), [baseVisibleTasks, view]);
  const selectedSet = useMemo(() => new Set(selectedTaskIds), [selectedTaskIds]);
  const batchCandidates = useMemo(() => baseVisibleTasks.filter(canSchedule), [baseVisibleTasks]);
  const selectedBatchTasks = useMemo(
    () => batchCandidates.filter((task) => selectedSet.has(task.taskId)),
    [batchCandidates, selectedSet],
  );
  const selectedTask = selectedTaskId ? allTasks.find((task) => task.taskId === selectedTaskId) : undefined;
  const showBatch = professional && allowBatch;
  const filtersActive = Boolean(dateFrom || dateTo || statusFilter !== "ALL" || normalizedSearch);
  const calendarAnchor = dateFrom || todayDateKey();

  function navigateCalendar(anchor: string, mode: CalendarMode) {
    const start = mode === "week" ? startOfWeekKey(anchor) : `${anchor.slice(0, 7)}-01`;
    setCalendarMode(mode);
    setDateFrom(start);
    setDateTo(mode === "week" ? addDaysToKey(start, 6) : addDaysToKey(shiftMonthKey(start, 1), -1));
  }

  function refresh() {
    setReloadKey((value) => value + 1);
  }

  function clearBatchState() {
    setSelectedTaskIds([]);
    setBatchError("");
  }

  function changeDateFrom(value: string) {
    setDateFrom(value);
    setNotice("");
    clearBatchState();
  }

  function changeDateTo(value: string) {
    setDateTo(value);
    setNotice("");
    clearBatchState();
  }

  function changeStatus(value: string) {
    setStatusFilter(value as StatusFilter);
    setNotice("");
    clearBatchState();
  }

  function changeSearch(value: string) {
    setSearch(value);
    setNotice("");
    clearBatchState();
  }

  function resetFilters() {
    setDateFrom(scopeFrom);
    setDateTo(scopeTo);
    setStatusFilter("ALL");
    setSearch("");
    setNotice("");
    clearBatchState();
  }

  function openTask(taskId: string) {
    if (onOpenTask) {
      onOpenTask(taskId);
      return;
    }
    setSelectedTaskId((current) => current === taskId ? undefined : taskId);
  }

  function toggleBatchTask(taskId: string) {
    setSelectedTaskIds((current) => current.includes(taskId)
      ? current.filter((item) => item !== taskId)
      : [...current, taskId]);
    setBatchError("");
  }

  function selectAllBatchTasks() {
    setSelectedTaskIds((current) => [
      ...new Set([...current, ...batchCandidates.map((task) => task.taskId)]),
    ]);
    setBatchError("");
  }

  function handleTaskScheduled(task: OperationsTaskViewItem, scheduledFor: string) {
    setNotice(`已更新“${task.title}”的计划日期为 ${formatDateLabel(scheduledFor)}。`);
    clearBatchState();
    refresh();
  }


  async function submitBatch() {
    if (batchPending) return;
    if (!selectedBatchTasks.length) {
      setBatchError("请先勾选需要调整的待执行任务。");
      return;
    }
    if (!DATE_ONLY.test(batchDate)) {
      setBatchError("请选择批量计划日期。");
      return;
    }
    const items = selectedBatchTasks.map((task) => ({
      taskId: task.taskId,
      scheduledFor: batchDate,
      version: task.version,
    }));
    setBatchPending(true);
    setBatchError("");
    try {
      await scheduleOperationsTasksBatch(farmId, items, token);
      setNotice(`已将 ${items.length} 项任务的计划日期调整为 ${formatDateLabel(batchDate)}。`);
      setSelectedTaskIds([]);
      setBatchDate("");
      refresh();
    } catch (reason: unknown) {
      setBatchError(operationsViewErrorMessage(reason, "批量调整计划日期失败"));
    } finally {
      setBatchPending(false);
    }
  }

  return <section
    className={`farmap-ops-view${professional ? " is-professional" : ""}`}
    aria-labelledby={embedded ? undefined : headingId}
    aria-label={embedded ? VIEW_TITLES[view] : undefined}
    aria-busy={loading || batchPending}
  >
    <header className={`farmap-ops-view__header${embedded ? " is-embedded" : ""}`}>
      {!embedded && <div>
        <h3 id={headingId}>{VIEW_TITLES[view]}</h3>
        <p>{VIEW_DESCRIPTIONS[view]}{professional ? " 可按日期、状态和关键词筛选。" : ""}</p>
      </div>}
      <button type="button" onClick={refresh} disabled={loading}>
        {loading ? "正在读取…" : "刷新"}
      </button>
    </header>

    {professional && <div className="farmap-ops-view__filters" role="search" aria-label="任务筛选">
      <label>
        开始日期
        <input type="date" value={dateFrom} onChange={(event) => changeDateFrom(event.target.value)} />
      </label>
      <label>
        结束日期
        <input type="date" value={dateTo} onChange={(event) => changeDateTo(event.target.value)} />
      </label>
      <label>
        任务状态
        <select value={statusFilter} onChange={(event) => changeStatus(event.target.value)}>
          {STATUS_FILTERS.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}
        </select>
      </label>
      <label className="farmap-ops-view__search">
        搜索
        <input
          type="search"
          value={search}
          placeholder="任务、地块或执行结果"
          onChange={(event) => changeSearch(event.target.value)}
        />
      </label>
      <button type="button" className="farmap-ops-view__reset" onClick={resetFilters}>重置筛选</button>
    </div>}

    <dl className="farmap-ops-view__totals" aria-label="任务数量汇总">
      <div><dt>任务总数</dt><dd>{data ? counts.total : "—"}</dd></div>
      <div><dt>待执行</dt><dd>{data ? counts.pending : "—"}</dd></div>
      <div><dt>执行中</dt><dd>{data ? counts.inProgress : "—"}</dd></div>
      <div><dt>已完成</dt><dd>{data ? counts.completed : "—"}</dd></div>
    </dl>

    {notice && <p className="farmap-ops-view__notice" role="status">{notice}</p>}
    {data?.truncated && <p className="farmap-ops-view__warning" role="status">
      数据较多，服务端只返回了部分任务。请缩小日期范围后查看。
    </p>}

    {error && <div className="farmap-ops-view__alert" role="alert">
      <p>{error}</p>
      <button type="button" onClick={refresh} disabled={loading}>重新读取</button>
    </div>}

    {showBatch && <section className="farmap-ops-view__batch" aria-label="批量调整计划日期">
      <div className="farmap-ops-view__batch-head">
        <div>
          <h4>批量调整计划日期</h4>
          <p>只处理待处理或已分配任务，当前已选 {selectedBatchTasks.length} 项。</p>
        </div>
        <div className="farmap-ops-view__batch-select">
          <button type="button" disabled={!batchCandidates.length || batchPending} onClick={selectAllBatchTasks}>
            全选当前待执行任务
          </button>
          <button type="button" disabled={!selectedBatchTasks.length || batchPending} onClick={clearBatchState}>
            清除选择
          </button>
        </div>
      </div>
      {batchCandidates.length ? <>
        <label className="farmap-ops-view__batch-date">
          批量计划日期
          <input
            type="date"
            value={batchDate}
            disabled={batchPending}
            onChange={(event) => {
              setBatchDate(event.target.value);
              setBatchError("");
            }}
          />
        </label>
        <div className="farmap-ops-view__batch-confirm" role="alert">
          <p>{selectedBatchTasks.length && DATE_ONLY.test(batchDate) ? <>
            确认将 <strong>{selectedBatchTasks.length}</strong> 项任务的计划日期统一调整为
            <strong> {formatDateLabel(batchDate)}</strong>？
          </> : "勾选待执行任务并选择日期后，点击“确认批量调整”。"}</p>
          <div>
            <button
              type="button"
              className="farmap-ops-view__primary"
              disabled={!selectedBatchTasks.length || !DATE_ONLY.test(batchDate) || batchPending}
              onClick={() => void submitBatch()}
            >
              {batchPending ? "正在提交…" : "确认批量调整"}
            </button>
          </div>
        </div>
      </> : <p className="farmap-ops-view__batch-empty">当前筛选范围内没有可批量调整的待执行任务。</p>}
      {batchError && <p className="farmap-ops-view__inline-error" role="alert">{batchError}</p>}
    </section>}

    {loading && !data ? <div className="farmap-ops-view__loading" role="status">
      <span aria-hidden="true" />
      <p>正在读取农事任务…</p>
    </div> : data ? (view === "calendar" ? <TaskCalendar
      tasks={baseVisibleTasks}
      anchor={calendarAnchor}
      mode={calendarMode}
      onModeChange={(mode) => navigateCalendar(calendarAnchor, mode)}
      onNavigate={(anchor) => navigateCalendar(anchor, calendarMode)}
      onToday={() => navigateCalendar(todayDateKey(), calendarMode)}
      onOpen={openTask}
    /> : <section className="farmap-ops-view__list-section" aria-label={view === "records" ? "执行记录列表" : "任务列表"}>
      <div className="farmap-ops-view__list-head">
        <h4>{view === "records" ? "执行记录" : "任务列表"} <small>{listTasks.length}</small></h4>
        {professional && <p>共 {counts.total} 项任务{filtersActive ? "，已应用筛选" : ""}。</p>}
      </div>
      {listTasks.length ? <div className="farmap-ops-view__list">
        {listTasks.map((task) => <TaskCard
          key={task.taskId}
          task={task}
          view={view}
          detailOpen={!onOpenTask && selectedTaskId === task.taskId}
          detailId={detailId}
          externalOpen={Boolean(onOpenTask)}
          batchEnabled={showBatch && canSchedule(task)}
          batchSelected={selectedSet.has(task.taskId)}
          batchBusy={batchPending}
          onOpen={openTask}
          onBatchChange={toggleBatchTask}
        />)}
      </div> : <EmptyState message={filtersActive
        ? "没有符合当前日期、状态或关键词筛选的任务。"
        : view === "records" ? "当前农场暂无执行记录。" : "当前农场暂无农事任务。"} />}
    </section>) : null}

    {!onOpenTask && selectedTask && <section
      id={detailId}
      className="farmap-ops-view__detail"
      aria-label={`任务详情：${selectedTask.title}`}
      tabIndex={-1}
    >
      <div className="farmap-ops-view__detail-head">
        <h4>任务详情</h4>
        <div className="farmap-ops-view__detail-actions">
          <button type="button" onClick={() => setSelectedTaskId(undefined)}>关闭详情</button>
        </div>
      </div>
      <OperationTaskFeedback
        key={selectedTask.taskId}
        taskId={selectedTask.taskId}
        expandedByDefault
        onScheduled={(scheduledFor) => handleTaskScheduled(selectedTask, scheduledFor)}
      />
    </section>}
  </section>;
}
