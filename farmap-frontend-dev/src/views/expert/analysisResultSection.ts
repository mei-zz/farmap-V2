export const analysisResultSections = [
    {
        title: "树种识别",
        fields: [
            { label: "种类", name: ["树种识别", "种类"] },
            // { label: "置信度", name: ["树种识别", "置信度"] },
        ],
    },
    {
        title: "图像质量诊断",
        fields: [
            { label: "图像完整性", name: ["图像质量诊断", "图像完整性"], type: "textarea" },
            { label: "图像清晰度", name: ["图像质量诊断", "图像清晰度"] },
            { label: "光照条件", name: ["图像质量诊断", "光照条件"] },
            { label: "拍摄建议", name: ["图像质量诊断", "拍摄建议"] },
        ],
    },
    {
        title: "当前生长阶段",
        fields: [{ label: "当前生长阶段", name: ["当前生长阶段"] }],
    },
    {
        title: "长势诊断",
        fields: [
            { label: "冠层结构", name: ["长势诊断", "冠层结构"], type: "textarea" },
            { label: "枝条形态", name: ["长势诊断", "枝条形态"] },
            { label: "新稍生长", name: ["长势诊断", "新稍生长"], type: "textarea" },
            { label: "花芽生长", name: ["长势诊断", "花芽生长"] },
            { label: "长势综合判断", name: ["长势诊断", "长势综合判断"] },
        ],
    },
    {
        title: "叶部状态诊断",
        fields: [
            { label: "叶色", name: ["叶部状态诊断", "叶色"] },
            { label: "叶面积大小", name: ["叶部状态诊断", "叶面积大小"] },
            { label: "叶面病斑比例", name: ["叶部状态诊断", "叶面病斑比例"] },
            { label: "叶片状态总结", name: ["叶部状态诊断", "叶片状态总结"], type: "textarea" },
        ],
    },
    {
        title: "果实状态诊断",
        fields: [
            { label: "挂果量", name: ["果实状态诊断", "挂果量"] },
            { label: "果实大小", name: ["果实状态诊断", "果实大小"] },
            { label: "果实色泽", name: ["果实状态诊断", "果实色泽"] },
            { label: "异常果实比例", name: ["果实状态诊断", "异常果实比例"] },
        ],
    },
    {
        title: "营养状况诊断",
        fields: [
            { label: "氮素状态", name: ["营养状况诊断", "氮素状态"] },
            { label: "磷素状态", name: ["营养状况诊断", "磷素状态"] },
            { label: "钾素状态", name: ["营养状况诊断", "钾素状态"] },
            { label: "中微量元素", name: ["营养状况诊断", "中微量元素"], type: "textarea" },
        ],
    },
    {
        title: "病虫害诊断",
        fields: [
            { label: "疑似病害", name: ["病虫害诊断", "疑似病害"] },
            { label: "病斑描述", name: ["病虫害诊断", "病斑描述"], type: "textarea" },
            { label: "虫害迹象", name: ["病虫害诊断", "虫害迹象"] },
            { label: "病害严重度", name: ["病虫害诊断", "病害严重度"] },
        ],
    },
    {
        title: "果叶比与树体评估",
        fields: [
            { label: "可见叶片数估计", name: ["果叶比与树体评估", "可见叶片数估计"] },
            { label: "可见果实数估计", name: ["果叶比与树体评估", "可见果实数估计"] },
            { label: "果叶比估计", name: ["果叶比与树体评估", "果叶比估计"] },
            { label: "是否合理", name: ["果叶比与树体评估", "是否合理"] },
        ],
    },
    {
        title: "综合建议",
        fields: [
            { label: "施肥建议", name: ["综合建议", "施肥建议"], type: "textarea" },
            { label: "病害处理建议", name: ["综合建议", "病害处理建议"], type: "textarea" },
            { label: "树势提升建议", name: ["综合建议", "树势提升建议"], type: "textarea" },
            { label: "补充说明", name: ["综合建议", "补充说明"], type: "textarea" },
        ],
    },
    // {
    //     title: "回答置信度",
    //     fields: [{ label: "回答置信度", name: ["回答置信度"] }],
    // },
];
