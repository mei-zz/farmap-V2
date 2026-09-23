type Callable = (...args: never[]) => void;
type NestedRecord = Record<string | number | symbol, unknown>;

function debounce<T extends Callable>(
  fn: T,
  delay: number,
): (...args: Parameters<T>) => void {
  let timer: number | undefined;

  return function (...args: Parameters<T>): void {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      fn(...args);
    }, delay);
  };
}

function deepCopy<T>(obj: T, wm = new WeakMap<object, unknown>()): T {
  if (typeof obj !== "object" || obj === null) return obj;
  if (wm.has(obj)) return wm.get(obj) as T;

  const newObj = (Array.isArray(obj) ? [] : {}) as NestedRecord;
  wm.set(obj, newObj);

  for (const key of Reflect.ownKeys(obj)) {
    newObj[key] = deepCopy((obj as NestedRecord)[key], wm);
  }

  return newObj as T;
}

const setNestedValue = <T>(obj: T, path: (string | number)[], value: unknown): T => {
  if (path.length === 0) {
    return value as T;
  }

  const [head, ...tail] = path;
  const source = typeof obj === "object" && obj !== null ? (obj as NestedRecord) : {};
  const nextObj = source[head] ?? {};

  if (Array.isArray(obj)) {
    const newArr = [...obj];
    newArr[Number(head)] = setNestedValue(nextObj, tail, value);
    return newArr as T;
  }

  return {
    ...source,
    [head]: setNestedValue(nextObj, tail, value),
  } as T;
};

export { debounce, deepCopy, setNestedValue };
