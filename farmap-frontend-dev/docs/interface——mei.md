## User

### 1. [POST] sign up http://map.archivemodel.cn/farmap/user/signup

#### request

```json
{
    "name": "xxx",
    "password": "xxx"
}
```

response

所有的 status 都是 0 为正确，1/2/3...为错误，但具体原因可能不同。

```json
{
    "message": "User signed up successfully",
    "status": 0,
    "user": {
        "name": "guest",
        "role": "user",
        "token": "123"
    }
}
```

### 2. [POST] login 接口：http://map.archivemodel.cn/farmap/user/login

#### request & response

和之前说的一样

### 3. [GET] check token http://map.archivemodel.cn/farmap/user/validate-token

```json
{
    "Authorization": "Bearer token"
}
```

#### request & response

和 login 返回一样，只是请求没有 body 只用 headers 的 Authorization

### 4. [GET] check admin authorization http://map.archivemodel.cn/farmap/user/check-admin

#### request

只需要 headers，在后端解析 token 验证就行

```json
{
    "Authorization": "Bearer 123123"
}
```

#### response

暂时我就发现了这三种权限的情况，是根据 token 解析出来后判断再返回的

```json
{
    "message": "Admin check correct" / "This user is not an admin" / "Token is invalid",
    "status": 0 / 1 / 2
}
```

### 5. [GET] get all users http://map.archivemodel.cn/farmap/user/all

获取除了 admin 以外的所有用户，必须是 admin 权限才能正确返回，不然 message 就提示错误信息，users 为空数组

#### request

```json
{
    "Authorization": "Bearer 123123"
}
```

#### response

```json
{
    "message": "Successed to get all users",
    "status": 0,
    "users": [
        {
            "id": 1,
            "name": "Guest",
            "role": "user"
        },
        {
            "id": 2,
            "name": "ZZC",
            "role": "user"
        }
    ]
}
```

### \*6. [POST] change password(PUT->POST) http://map.archivemodel.cn/farmap/user/change-password

#### request

```json
headers: {
    "Authorization": "Bearer 123123"
}
body: {
    "targetUser": "ZZC",
    "newPwd": "123456"
}
```

#### response

修改密码成功或报错的信息。

```json
{
    "message": "xxx",
    "status": 0
}
```

### \*7. [DELETE] delete user http://map.archivemodel.cn/farmap/user/delete

#### request

```json
headers: {
    "Authorization": "Bearer 123123"
}
body: {
    "targetUser": "ZZC"
}
```

#### response

删除用户成功或报错的信息。

```json
{
    "message": "xxx",
    "status": 0
}
```

---

## Guidance

### \*1. [GET] get guidance http://map.archivemodel.cn/farmap/guidance/get

如果该农场没有选择自定义文本的话就按照农场类型来（其实是作物的类型，只是这个字段归属在农场的表里）。

#### parameters

`url?farmId=1&farmType=citrus&month=0`

#### response

```json
{
    "body": [
        {
            "id": 1,
            "text": "body 1",
            "isFormula": false
        },
        {
            "id": 2,
            "text": "body formula 1",
            "isFormula": true
        }
    ],
    "fertile": [
        {
            "id": 3,
            "text": "fertile 1",
            "isFormula": false
        }
    ],
    "pest": [
        {
            "id": 4,
            "text": "pest 1",
            "isFormula": false
        }
    ],
    "park": [
        {
            "id": 5,
            "text": "park 1",
            "isFormula": false
        }
    ]
}
```

### \*2. [PUT] update guidance content http://map.archivemodel.cn/farmap/guidance/update

这个包括了增加删除和更新，我想的是直接给一个完整的数组，然后把对应农场对应类型原来的数据 rows 全删了，重新根据下面的 content 插入新的。【TODO：插入新的月份不用确定吗？？】

这个可以讨论下有没有更好的做法，你没有什么想法的话就暂时这么做吧。

#### request

```json
{
    "farmId": 1,
    "guidanceType": "body",
    "content": [
        {
            "text": "new body1",
            "isFormula": false
        },
        {
            "text": "new body2",
            "isFormula": false
        },
        {
            "text": "new body formula",
            "isFormula": true
        }
    ]
}
```

---

### Weather

### \*1. [GET] get weather introductions http://map.archivemodel.cn/farmap/weather/intro

farmId,farmType 这两个参数，至少有一个

12 个月的简短物候或者月份介绍，同 guidance，如果该农场没有选择自定义文本的话就按照农场类型来。

#### parameters

`url?farmId=1&farmType=citrus`

#### response

数组一共 12 个子数组，每个子数组包含多个当月的一句话介绍。

```json
{
    "intro": [
        [
            {
                "id": 23,
                "text": "1月的节气包括小寒和大寒，寒冷气候达到顶点，注意保护果树根系防止冻害，为来年春季做准备。"
            },
            {
                "id": 24,
                "text": "沃柑的采收期，花芽形态分化期，冬梢萌发期。"
            },
            ...
        ],
        [...]
    ]
}
```

### \*2. get accumulated temperatures http://map.archivemodel.cn/farmap/weather/accumulated-temperature

去年整年和今年至今的积温，根据不同作物的生长温度来计算，现在我们基本上都算作 10℃。

返回的数组里的值是每天的积温。

ps：现在固定返回 farm_id=1 的积温模拟数据

#### parameters

`url?farmType=citrus`

#### response

```json
{
    "last": [
        {
            "id": 1,
            "date": "2024-01-01",
            "accTemp": 23.12
        },
        {
            "id": 2,
            "date": "2024-01-02",
            "accTemp": 24.23
        },
        ...
        {
            "id": 365,
            "date": "2024-12-31",
            "accTemp": 5000.34
        }
    ],
    "this": [
        {
            "id": 366,
            "date": "2024-01-01",
            "accTemp": 21.12
        },
        {
            "id": 367,
            "date": "2024-01-02",
            "accTemp": 23.23
        },
        ...
        {
            "id": 200,
            "date": "2024-00-04",
            "accTemp": 1300.34
        }
    ]
}
```

---

## Model

### 1. [GET] get model list http://map.archivemodel.cn/farmap/model/list

#### parameters

`url?farmId=1`

#### response

暂时根据模型的请求和返回类型来分，请求和返回都包括 text/figure/hybrid。

分别是请求或者返回只需要文本，只需要图片，两者都需要。

这个部分的请求可能需要 FormData，因为前端发送文件应该是只能用表单。

```json
{
    "message": "Successed to read model list",
    "status": 0,
    "models": [
        {
            "id": 5,
            "name": "沃柑病虫害检测模型",
            "order": 1,
            "reqType": "figure",
            "resType": "hybrid",
            "url": "https://xxx.xxx.com/xxx"
        },
        {
            "id": 6,
            "name": "xxx模型",
            "order": 2,
            "reqType": "text",
            "resType": "text",
            "url": "https://yyy.yyy.com/yyy"
        },
        ...
    ]
}
```

### 2. [POST] create model list http://map.archivemodel.cn/farmap/model/list

#### request

```json
{
    "farmId": 2,
    "models": [
        {
            "name": "xxx模型",
            "order": 1,
            "reqType": "text",
            "resType": "text",
            "url": "xxx"
        },
        {
            "name": "yyy模型",
            "order": 2,
            "reqType": "figure",
            "resType": "text",
            "url": "yyy"
        },
        ...
    ]
}
```

#### response

成功或失败的信息。

```json
{
    "message": "Successed to create model list",
    "status": 0
}
```

### 3. [PUT] update model list http://map.archivemodel.cn/farmap/model/list

#### request

根据模型的 id 把旧的信息替换为新的，但是如果同一个农场的模型顺序有重复就返回个错误（应该不会出错）。

```json
{
    "farmId": 1,
    "newModels": [
        {
            "id": 5,
            "name": "沃柑病虫害检测模型",
            "order": 2,
            "reqType": "figure",
            "resType": "hybrid",
            "url": "https://xxx.xxx.com/xxx"
        },
        {
            "id": 6,
            "name": "xxx模型",
            "order": 1,
            "reqType": "text",
            "resType": "text",
            "url": "https://yyy.yyy.com/yyy"
        }
    ]
}
```

#### response

```json
{
    "message": "Successed to update model list",
    "status": 0
}
```

### 4. [DELETE] delete a model in list http://map.archivemodel.cn/farmap/model/item

#### request

可能需要用 url 判断一下 id 和模型对不对的上，免得请求发错误删了。

```json
{
    "id": 5,
    "url": "xxx"
}
```

#### response

```json
{
    "message": "Successed to delete a model",
    "status": 0
}
```

## Big Model

### 1.[POST] 发起对话请求 /ai-model/analyze

用户可以通过该接口，传入 1-5 张图片的 url（公网可访问的路径，前端存储到对象存储中，把得到的 url 返回），获取作物相关的专业建议 json 字段。

```http
Content-Type: application/json
X-API-Key: sk-******************
authentication:token
```

request

```json
{
    "imageUrls": [
        "https://preview.qiantucdn.com/freepik/free-vector/65656c5557c17.jpg!w1024_new_small_1",
        "https://ts1.tc.mm.bing.net/th/id/R-C.420fab226d4322a90c403d679a153607?rik=oir2XJMmNVviYA&riu=http%3a%2f%2fn.sinaimg.cn%2fsinakd20200604s%2f3%2fw1125h478%2f20200604%2fb6fa-iumkapx2807772.jpg&ehk=0W6XLYY9kPpBjfc1xnefBHSgm%2fDkW99wXlyAz4ynp%2bQ%3d&risl=&pid=ImgRaw&r=0",
        "https://img95.699pic.com/photo/30797/5512.jpg_wh300.jpg"
    ]
}
```

response

```json
{
    "plant_validation": {
        "consistent": true,
        "message": "上传的图片属于同一植物类别",
        "details": "第1张: Vaccinium (置信度: 0.42)\n第2张: Vaccinium (置信度: 0.77)\n第3张: Vaccinium (置信度: 0.85)",
        "confidence": 0.68032
    },
    "analysis_result": {
        "树种识别": {
            "种类": "蓝莓",
            "置信度": "高"
        },
        "图像质量诊断": {
            "图像完整性": "展示了蓝莓的叶片、果实等关键部位，但缺少全树的整体画面，建议补充拍摄全树照片以了解整体生长状况",
            "图像清晰度": "叶脉清晰可见，果实细节也较为清楚",
            "光照条件": "自然光，光线充足",
            "拍摄建议": "补充拍摄全树照片，可从不同角度拍摄以全面观察树体情况"
        },
        "当前生长阶段": "膨大期BBCH[75-79]",
        "长势诊断": {
            "冠层结构": "主枝粗细较为均匀，通直度较好，冠幅大小适中，完整度较高",
            "枝条形态": "枝条密度适中，分布较为均匀，枝条分布角度合理",
            "新稍生长": "从图片中难以清晰判断新梢数量、长度和木质化程度，但整体看起来新梢生长较为正常",
            "花芽生长": "图片中未明显显示花芽，无法判断花芽数量和饱满度",
            "长势综合判断": "中"
        },
        "叶部状态诊断": {
            "叶色": "大部分叶片为深绿色，但存在部分叶片有病斑，颜色不均",
            "叶面积大小": "与标准相比正常",
            "叶面病斑比例": "从图二来看，病斑叶片约占观察叶片的10%-20%",
            "叶片状态总结": "病斑呈不规则形状，大小约5-10mm，颜色从边缘的褐色逐渐向中间变为黑色，主要分布在叶缘部分"
        },
        "果实状态诊断": {
            "挂果量": "中等，从图中局部来看果实数量较多",
            "果实大小": "与蓝莓品种标准相比，果实大小较为正常",
            "果实色泽": "部分果实为深紫色，部分为蓝紫色，还有少量处于绿转色的阶段",
            "异常果实比例": "从图中看，病斑果占比约5%-10%，畸形果不明显"
        },
        "营养状况诊断": {
            "氮素状态": "叶片颜色整体较深绿，新梢生长正常，推测氮素供应较为充足",
            "磷素状态": "从图片中难以直接判断磷素状态，但果实发育正常，可能磷素供应基本满足需求",
            "钾素状态": "果实色泽较好，抗逆性看起来尚可，推测钾素供应基本正常",
            "中微量元素": "从叶片病斑情况看，可能存在一定的微量元素缺乏，如镁元素缺乏可能导致叶脉间失绿，但图片中表现不明显"
        },
        "病虫害诊断": {
            "疑似病害": "可能是炭疽病或叶斑病",
            "病斑描述": "病斑呈不规则形状，大小约5-10mm，颜色从边缘的褐色逐渐向中间变为黑色，部分病斑表面可能有霉层（图二不够清晰难以确定）",
            "虫害迹象": "未发现虫孔、分泌物和虫体",
            "病害严重度": "轻度<30%"
        },
        "果叶比与树体评估": {
            "可见叶片数估计": "约50片",
            "可见果实数估计": "约20个",
            "果叶比估计": "1:2.5",
            "是否合理": "对于蓝莓来说，该果叶比基本合理，但需结合树体整体状况进一步判断"
        },
        "综合建议": {
            "施肥建议": "在果实膨大期，每隔10天喷施一次0.2%-0.3%的磷酸二氢钾溶液，连续喷施2-3次，以促进果实发育和提高果实品质；在采果后，每株施入有机肥2-3kg，并配合施入适量的氮磷钾复合肥，增强树势",
            "病害处理建议": "使用70%甲基硫菌灵可湿性粉剂800-1000倍液，每隔7-10天喷施一次，连续喷施2-3次，重点喷施叶片病斑部位；同时，加强果园的通风透光和排水管理，降低果园湿度，减少病害发生",
            "树势提升建议": "在冬季休眠期，对过密枝、交叉枝、病弱枝等进行适当修剪，改善树冠通风透光条件；定期对土壤进行疏松，增加土壤透气性，可适量添加有机物质改良土壤结构",
            "补充说明": "后续需密切关注果实发育情况和叶片病斑变化，如病害有加重趋势，应及时调整用药方案；同时，在果实成熟前，注意控制水分，提高果实品质"
        },
        "回答置信度": "80%"
    },
    "validation": {
        "plant_type": "植物类型验证通过，疑似非植物图片数量: 0",
        "quality": "图片质量验证通过",
        "count": "图片数量合理: 3张"
    }
}
```

上传的不是同一类的返回

```json
{
    "image_urls": [
        "https://preview.qiantucdn.com/freepik/free-vector/65656c5557c17.jpg!w1024_new_small_1",
        "https://ts1.tc.mm.bing.net/th/id/R-C.420fab226d4322a90c403d679a153607?rik=oir2XJMmNVviYA&riu=http%3a%2f%2fn.sinaimg.cn%2fsinakd20200604s%2f3%2fw1125h478%2f20200604%2fb6fa-iumkapx2807772.jpg&ehk=0W6XLYY9kPpBjfc1xnefBHSgm%2fDkW99wXlyAz4ynp%2bQ%3d&risl=&pid=ImgRaw&r=0",
        "https://img95.699pic.com/photo/30797/5512.jpg_wh300.jpg",
        "https://ts1.tc.mm.bing.net/th/id/R-C.e2eb7242be8b6ff3152c3546f728d3fe?rik=WpYubobcq15qxg&riu=http%3a%2f%2fimg95.699pic.com%2fphoto%2f50080%2f9655.jpg_wh860.jpg&ehk=7rmKOb6tgsmDZj%2fAlaDFDP%2bUoNo%2fdrjG6cYX8gMCvSg%3d&risl=&pid=ImgRaw&r=0"
    ]
}
```

```json
{
    "plant_validation": {
        "consistent": false,
        "message": "上传的图片不属于同一植物类别",
        "details": "第1张: Vaccinium (置信度: 0.42)\n第2张: Vaccinium (置信度: 0.77)\n第3张: Vaccinium (置信度: 0.85)\n第4张: Citrus (置信度: 0.63)",
        "confidence": 0.666925
    }
}
```

### 2.[GET] 专家登录返回待修改的粗略列表信息 /expert/pending-cases

ps:user 表中，role 字段添加了一个 expert 的身份。通过专家的身份登录，获得的 token 传入接口。

```http
authentication: Bearer {token}
```

response

```json
{
    "code": 1,
    "msg": null,
    "data": {
        "total": 3,
        "list": [
            {
                "requestId": "d8fa5648-6f02-4a84-a982-05f65524be7b",
                "uploadTime": "2025-08-20T22:58:33",
                "imageCount": 1,
                "generateTime": "2025-08-20T22:58:49",
                "revisionCount": 0
            },
            {
                "requestId": "27e8135c-b63a-4efe-b8c3-6d2ea76f9177",
                "uploadTime": "2025-08-20T00:23:21",
                "imageCount": 1,
                "generateTime": "2025-08-20T00:23:44",
                "revisionCount": 0
            },
            {
                "requestId": "317f3687-1f02-4719-bfcd-8b4c579dedf3",
                "uploadTime": "2025-08-20T00:22:58",
                "imageCount": 1,
                "generateTime": "2025-08-20T00:23:17",
                "revisionCount": 0
            }
        ],
        "pageNum": 1,
        "pageSize": 10,
        "size": 3,
        "startRow": 1,
        "endRow": 3,
        "pages": 1,
        "prePage": 0,
        "nextPage": 0,
        "isFirstPage": true,
        "isLastPage": true,
        "hasPreviousPage": false,
        "hasNextPage": false,
        "navigatePages": 8,
        "navigatepageNums": [1],
        "navigateFirstPage": 1,
        "navigateLastPage": 1
    }
}
```

ps:之后专家通过点击"requestId": "d8fa5648-6f02-4a84-a982-05f65524be7b",来得到图片的预览和相应的 json 内容

### 3.[GET] 专家通过列表点击得到图片预览和相应 json 内容 /expert/cases/{requestId}

```http
authentication: Bearer {token}
```

ps:前端通过 imageUrls 来展示图片

response:

```json
{
    "code": 1,
    "msg": null,
    "data": {
        "userRequestInfo": {
            "requestId": "27e8135c-b63a-4efe-b8c3-6d2ea76f9177",
            "imageUrls": "https://pic.nximg.cn/file/20230811/33760392_173922575125_2.jpg",
            "uploadTime": "2025-08-20T00:23:21",
            "status": 1
        },
        "initialResultInfo": {
            "jsonData": "{\n  \"叶部状态诊断\": {\n    \"叶面积大小\": \"正常\",\n    \"叶色\": \"深绿色\",\n    \"叶面病斑比例\": \"0%\",\n    \"叶片状态总结\": \"叶片表面光滑，无病斑，叶脉清晰，叶缘无异常\"\n  },\n  \"综合建议\": {\n    \"施肥建议\": \"在蓝莓生长期间，可每隔1 - 2个月追施一次平衡型复合肥，每株用量约50 - 100克；花期前增施磷钾肥，如磷酸二氢钾，稀释成0.2% - 0.3%的溶液进行叶面喷施，每隔7 - 10天喷一次，共喷2 - 3次；果实膨大期可适量补充钙肥，可通过叶面喷施或土壤基施的方式，叶面喷施浓度为0.3% - 0.5%，每隔7 - 10天喷一次，共喷2 - 3次\",\n    \"病害处理建议\": \"目前未见明显病害，日常预防可每隔1 - 2个月喷施一次杀菌剂，如多菌灵或甲基托布津，稀释成800 - 1000倍液进行全园喷雾\",\n    \"树势提升建议\": \"定期对蓝莓树进行修剪，疏除过密枝、交叉枝、病弱枝等，以保持树冠通风透光；每年秋季可对土壤进行深翻，深度约20 - 30厘米，并增施有机肥，如腐熟的农家肥或堆肥，每株施入20 - 30千克，改善土壤结构和肥力\",\n    \"补充说明\": \"注意观察蓝莓树的生长情况，特别是在高温高湿季节，要加强病虫害的监测和防治；同时，保持果园良好的排水系统，避免积水导致根部病害\"\n  },\n  \"当前生长阶段\": \"由于仅为果实特写，无法准确判断整体生长阶段，但从果实饱满度和色泽来看，可能处于果实成熟前期\",\n  \"树势诊断\": {\n    \"树形结构\": \"因仅展示果实和叶片，无法判断主枝分布和树冠密度\",\n    \"枝条生长\": \"因仅展示果实和叶片，无法判断新梢长度和木质化程度\",\n    \"树势综合判断\": \"因信息有限，无法准确判断\"\n  },\n  \"果实状态诊断\": {\n    \"挂果量\": \"因仅为两颗果实特写，无法估算整体挂果量\",\n    \"果实大小\": \"从图片看，果实饱满，大小较为均匀，符合蓝莓品种标准\",\n    \"果实色泽\": \"果实呈深蓝色，表面有一层白霜，色泽鲜亮\",\n    \"异常果实比例\": \"0%\"\n  },\n  \"树种识别\": {\n    \"种类\": \"蓝莓\",\n    \"置信度\": \"高\"\n  },\n  \"病虫害诊断\": {\n    \"疑似病害\": \"目前未见明显病害迹象\",\n    \"虫害迹象\": \"未见虫孔、分泌物及虫体\",\n    \"病害严重度\": \"0%\",\n    \"病斑描述\": \"无病斑\"\n  },\n  \"图像质量诊断\": {\n    \"拍摄建议\": \"可适当增加一些背景元素，展示果园环境，使图像更具完整性\",\n    \"光照条件\": \"为人工光源，光线均匀，无阴影\",\n    \"图像清晰度\": \"叶脉、果实表面细节清晰可见\",\n    \"图像完整性\": \"仅展示了蓝莓果实和一片叶子，未展示全树、枝叶等关键部位\"\n  },\n  \"营养状况诊断\": {\n    \"磷素状态\": \"因缺乏根系和花芽分化等信息，无法准确判断\",\n    \"中微量元素\": \"未见明显缺素症状，但蓝莓对锌、硼等微量元素较为敏感，可适当补充\",\n    \"钾素状态\": \"从果实色泽和饱满度来看，钾素供应较为充足\",\n    \"氮素状态\": \"叶片深绿，新梢生长可能较为正常，氮素供应基本适宜\"\n  },\n  \"果叶比与树体评估\": {\n    \"可见叶片数估计\": \"1片\",\n    \"是否合理\": \"因仅为单果单叶特写，无法依据品种标准和树势判断合理性\",\n    \"可见果实数估计\": \"2个\",\n    \"果叶比估计\": \"因数据过少，无法准确估计\"\n  }\n}",
            "modelVersion": "hunyuan-turbo-vision"
        },
        "revisionRecords": []
    }
}
```

### 4.[POST]专家提交修改接口 /expert/cases/{request_id}/revise

专家提交修改的信息

```http
Content-Type: application/json
authentication: Bearer {token}
```

request

```json
{
    "revisedJson": "{\"叶部状态诊断\": \"修改功能测试\", \"field2\": \"value2\"}",
    "isAgree": 1,
    "revisionNotes": "这是一个修改功能测试"
}
```

response

```json
{
    "code": 1,
    "msg": null,
    "data": "提交修改成功"
}
```

### 5.[GET]获取案例修改历史接口 /expert/cases/{request_id}/history

ps：单独获取某个案例的所有修改记录（用于专家对比多次修改）

```http
Content-Type: application/json
authentication: Bearer {token}
```

response

```json
{
    "code": 1,
    "msg": null,
    "data": [
        {
            "revisionId": "71c50b3d-9637-4aab-990f-a2887ce7190e",
            "expertId": 8,
            "revisionTime": "2025-08-21T00:51:41",
            "isAgree": 1,
            "revisionNotes": "这是一个修改功能测试3333"
        },
        {
            "revisionId": "43eeda79-6460-40cb-bba7-47b78ea6a7f4",
            "expertId": 7,
            "revisionTime": "2025-08-21T00:36:53",
            "isAgree": 1,
            "revisionNotes": "这是一个修改功能测试"
        }
    ]
}
```

### 6.共识判断接口【自动触发，无需前端调用】

```markdown
代码的 TODO 需要实现核心字段的共识度计算。目前是直接假设共识度达标
程序设定的是，只有三位专家进行了修改提交才达到共识，修改 user_requests 表中 status 为 3（用于后面进入外部数据库）
```

```markdown
TODO： 1.实现将 user_requests 表中 status 为 3 的图片和对应的 json 内容加入外部知识库
2.RAG，进行回答 3.测试是否有效
```
