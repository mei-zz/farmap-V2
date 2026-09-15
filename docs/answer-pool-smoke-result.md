# FarMap answer-pool 全池冒烟结果

- 执行时间：2026-09-14 18:30:42
- 请求方式：通过 `BailianModelClient.probe` 串行调用；固定中文短问；`max_tokens=1`；不记录响应正文。
- 配置池模型数：45
- 结论：已通过 BailianModelClient 串行完成池内模型最小请求冒烟。

## 汇总

- 可用：45/45
- 无权限（permission_denied）：无
- 不存在（model_not_found）：无
- 超时（timeout）：无
- 其他结果：无

## 逐模型结果

| # | 模型 ID | 结果 | errorType | 延迟 ms |
|---:|---|---|---|---:|
|1|glm-5|ok|ok|2046|
|2|glm-5.1|ok|ok|1435|
|3|glm-5.2|ok|ok|1450|
|4|glm-4.7|ok|ok|283|
|5|qwen-max|ok|ok|347|
|6|qwen3-max-preview|ok|ok|322|
|7|qwen3-max-2025-09-23|ok|ok|671|
|8|qwen3-max|ok|ok|426|
|9|qwen3-max-2026-01-23|ok|ok|473|
|10|qwen3.6-max-preview|ok|ok|722|
|11|qwen3.7-max|ok|ok|641|
|12|qwen3.7-max-2026-05-20|ok|ok|970|
|13|qwen3.7-max-2026-06-08|ok|ok|759|
|14|qwen3.8-max-0902|ok|ok|398|
|15|qwen-plus|ok|ok|373|
|16|qwen-plus-latest|ok|ok|306|
|17|qwen-plus-0112|ok|ok|421|
|18|qwen-plus-1220|ok|ok|446|
|19|qwen-plus-2025-01-25|ok|ok|298|
|20|qwen-plus-2025-04-28|ok|ok|1041|
|21|qwen-plus-2025-07-14|ok|ok|2076|
|22|qwen-plus-2025-07-28|ok|ok|272|
|23|qwen-plus-2025-09-11|ok|ok|311|
|24|qwen-plus-2025-12-01|ok|ok|371|
|25|qwen3.5-plus|ok|ok|1494|
|26|qwen3.5-plus-2026-02-15|ok|ok|2052|
|27|qwen3.5-plus-2026-04-20|ok|ok|482|
|28|qwen3.6-plus|ok|ok|650|
|29|qwen3.6-plus-2026-04-02|ok|ok|528|
|30|qwen3.7-plus-2026-05-26|ok|ok|814|
|31|qwen3-235b-a22b|ok|ok|224|
|32|qwen3-235b-a22b-instruct-2507|ok|ok|244|
|33|qwen3.5-122b-a10b|ok|ok|322|
|34|qwen3.5-397b-a17b|ok|ok|1558|
|35|qwen3.5-35b-a3b|ok|ok|498|
|36|qwen3.6-35b-a3b|ok|ok|273|
|37|qwen3-next-80b-a3b-instruct|ok|ok|269|
|38|deepseek-v3|ok|ok|520|
|39|deepseek-v3.1|ok|ok|307|
|40|deepseek-v3.2|ok|ok|538|
|41|deepseek-v3.2-exp|ok|ok|1518|
|42|deepseek-v4-pro-0813|ok|ok|1051|
|43|kimi-k2.5|ok|ok|297|
|44|kimi-k2.6|ok|ok|320|
|45|Moonshot-Kimi-K2-Instruct|ok|ok|512|
