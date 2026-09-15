function debounce<T extends (...args: any[]) => void>(
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

function deepCopy<T>(obj: T, wm = new WeakMap()): T {
  if (typeof obj !== "object" || obj === null) return obj;
  if (wm.has(obj)) return wm.get(obj);

  const newObj: any = Array.isArray(obj) ? [] : {};
  wm.set(obj, newObj);

  for (const key in obj) {
    newObj[key] = deepCopy((obj as any)[key], wm);
  }

  return newObj;
}

const setNestedValue = (obj: any, path: (string | number)[], value: any): any => {
  if (path.length === 0) {
    return value;
  }

  const [head, ...tail] = path;
  const nextObj = obj && obj[head] ? obj[head] : {};

  if (Array.isArray(obj)) {
    const newArr = [...obj];
    newArr[Number(head)] = setNestedValue(nextObj, tail, value);
    return newArr;
  }

  return {
    ...obj,
    [head]: setNestedValue(nextObj, tail, value),
  };
};

export { debounce, deepCopy, setNestedValue };
