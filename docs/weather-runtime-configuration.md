# FarMap 天气运行配置

## 当前状态

FarMap V2 已将天气读取统一收口到 Spring Boot 后端。登录用户访问农场天气时，后端按农场中心坐标读取当前天气和未来 14 天预报，再把同一份结果提供给：

- 顶部农场上下文天气状态；
- Overview、农业上下文和天气预报页；
- FieldContext、Diagnosis RAG 和 Agent。

返回的关键字段包括 `rainfallForecast24h`、`rainfallProbability24h`、`rainfall14d`、`rainfallForecastDays` 和 `forecast`。天气来源、更新时间和预测性质也会随结果返回，不能把预测当成现场观测。

## 提供方优先级

1. 如果配置了 `GAODE_API_KEY`，优先使用高德当前天气和预报。
2. 未配置高德时，默认使用无需 API Key 的 Open-Meteo Forecast API。
3. 两个提供方都不可用时，接口返回明确的不可用状态，不再用旧的季节性文字冒充实时天气。

默认配置已经写入 `zhgy/zhgy/src/main/resources/application.yml`：

```text
OPEN_METEO_API_ENABLED=true
OPEN_METEO_API_BASE_URL=https://api.open-meteo.com/v1/forecast
OPEN_METEO_API_KEY=
OPEN_METEO_CONNECT_TIMEOUT_MS=5000
OPEN_METEO_READ_TIMEOUT_MS=10000
```

Open-Meteo 的 API 语法和降雨字段说明见 [Forecast API 文档](https://open-meteo.com/en/docs)。当前页面已显示 `Open-Meteo` 数据来源和预测提示，并按 CC BY 4.0 要求保留来源说明，许可说明见 [Open-Meteo Licence](https://open-meteo.com/en/license)。

## 商业部署边界

无 Key 的 Open-Meteo 免费层适合本地开发、评估和原型验证；官方条款将免费 API 限定为非商业使用，并且不承诺可用性。FarMap 若进入商业生产环境，应配置已获得商业许可的天气服务：可以继续使用高德，或将 `OPEN_METEO_API_BASE_URL` 切换为 Open-Meteo 客户端点并填写 `OPEN_METEO_API_KEY`。相关限制和套餐以 [Open-Meteo Pricing](https://open-meteo.com/en/pricing) 与 [Terms](https://open-meteo.com/en/terms) 为准。

## Agent 使用方式

后端真实 Agent 运行前会按用户身份重新组装 FieldContext，天气字段来自上述天气提供方；浏览器传入的天气值不能覆盖服务端可信上下文。Agent 可以直接使用当前温度、天气状况、未来 24 小时降雨量/概率以及未来 14 天逐日预报，并应在回答中标明预测不确定性。
