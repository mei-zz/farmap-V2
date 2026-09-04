import base64
import json
import os
import time
import requests
import collections
import re
from openai import OpenAI
from dotenv import load_dotenv

# 加载环境变量
load_dotenv()

# 初始化腾讯混元客户端
client = OpenAI(
    api_key=os.environ["HUNYUAN_API_KEY"],
    base_url="https://api.hunyuan.cloud.tencent.com/v1",
)


# 图片转base64
def encode_image_base64(path_or_url):
    try:
        print(f"[{time.strftime('%H:%M:%S')}] 正在编码图片：{path_or_url}")

        # 判断是本地路径还是网络URL
        if path_or_url.startswith(('http://', 'https://')):
            # 网络图片处理
            response = requests.get(path_or_url, timeout=30)
            response.raise_for_status()
            if len(response.content) > 10 * 1024 * 1024:
                raise ValueError("单张图片不得超过10MB")
            img_data = base64.b64encode(response.content).decode('utf-8')
            print(f"[{time.strftime('%H:%M:%S')}] 网络图片 {path_or_url} 编码完成")
        else:
            # 本地图片处理
            with open(path_or_url, "rb") as f:
                img_data = base64.b64encode(f.read()).decode('utf-8')
            print(f"[{time.strftime('%H:%M:%S')}] 本地图片 {os.path.basename(path_or_url)} 编码完成")

        return img_data
    except Exception as e:
        raise Exception(f"图片编码失败：{str(e)}")


# 主分析函数
def analyze_tree_images(image_paths):
    if not (1 <= len(image_paths) <= 5):
        raise ValueError("请提供 1~5 张图片")

    print(f"[{time.strftime('%H:%M:%S')}] 检测到 {len(image_paths)} 张图片，开始处理...")

    # 系统提示（完整保留）
    system_prompt = (
        "你是一位专业的农事指导栽培与植保专家，拥有20年实地经验。请根据提供的1～5张多角度图像进行综合详细诊断分析，"
        "要求如下：\n"
        "1. 每个诊断项必须给出具体描述和量化指标（如病斑比例用30%-40%表示）\n"
        "2. 对于不确定的内容，基于专业经验给出可能性判断\n"
        "3. 综合建议必须包含具体操作措施和频率\n"
        "4. 特别关注叶片病斑特征、果实发育状态和营养失衡迹象\n"
        "5. 使用专业术语但保持可读性\n"
        " 输出必须为严格JSON格式，不包含任何额外文本（如代码块标记```json等），并且字段顺序不改变。"  # 新增：禁止返回多余标记
    )

    # 使用OrderedDict保持字段顺序
    json_template = collections.OrderedDict([
        ("树种识别", collections.OrderedDict([
            ("种类", "具体树种名称（如'脆李'、'脐橙'）"),
            ("置信度", "高/中/低（基于图像特征判断）")
        ])),
        ("图像质量诊断", collections.OrderedDict([
            ("图像完整性", "是否展示关键部位或需要补充拍摄（全树/枝叶/果实）"),
            ("图像清晰度", "具体描述（如'叶脉清晰可见'/'果实细节模糊'）"),
            ("光照条件", "自然光/逆光/阴影占比等"),
            ("拍摄建议", "具体改进建议（如'避开强光时段拍摄'）")
        ])),
        ("当前生长阶段",
         "典型物候阶段（如'休眠期BBCH[00]'、'萌芽期BBCH[01-07]'、'展叶期BBCH[09-11]'、'花蕾期BBCH[51-59]'、'开花期BBCH[60-69]'、'谢花期BBCH[69-71]'、'幼果期BBCH[71-73]'、'膨大期BBCH[75-79]'、'转色期BBCH[81-83]'、'成熟期BBCH[87-89]'、'采后期BBCH[91-97]'）"),
        ("长势诊断", collections.OrderedDict([
            ("冠层结构", "主枝粗细和通直度、冠幅大小、完整度"),
            ("枝条形态", "枝条密度与分布均匀度、枝条分布角度"),
            ("新稍生长", "新梢数量、长度和木质化程度"),
            ("花芽生长", "花芽数量（过多/适宜/过少），花芽饱满度（高/中/底）"),
            ("长势综合判断", "强/中/弱（结合多项指标）")
        ])),
        ("叶部状态诊断", collections.OrderedDict([
            ("叶色", "具体颜色描述（如'黄绿色'、'深绿带紫边'）"),
            ("叶面积大小", "与标准对比（偏大/正常/偏小）"),
            ("叶面病斑比例", "估算百分比（0-100%）"),
            ("叶片状态总结", "病斑特征（形状/颜色/分布）")
        ])),
        ("果实状态诊断", collections.OrderedDict([
            ("挂果量", "稀疏/中等/密集（或估算数量）"),
            ("果实大小", "与品种标准对比"),
            ("果实色泽", "具体描述（如'青绿带红晕'）"),
            ("异常果实比例", "畸形果/病斑果占比")
        ])),
        ("营养状况诊断", collections.OrderedDict([
            ("氮素状态", "根据叶色、新梢生长判断"),
            ("磷素状态", "根据根系、花芽分化判断"),
            ("钾素状态", "根据果实品质、抗逆性判断"),
            ("中微量元素", "具体缺乏元素（如铁、锌）及症状")
        ])),
        ("病虫害诊断", collections.OrderedDict([
            ("疑似病害", "具体病害名称（如'黑星病'、'白粉病'）"),
            ("病斑描述", "大小/形状/颜色/病征（如霉层）"),
            ("虫害迹象", "虫孔/分泌物/虫体描述"),
            ("病害严重度", "分级（轻度<30%/中度30-60%/重度>60%）")
        ])),
        ("果叶比与树体评估", collections.OrderedDict([
            ("可见叶片数估计", "大致数量（如150片）"),
            ("可见果实数估计", "大致数量（如30个）"),
            ("果叶比估计", "比例（如1:15）"),
            ("是否合理", "判断依据（品种标准/树势）")
        ])),
        ("综合建议", collections.OrderedDict([
            ("施肥建议", "肥料类型/用量/施用时间"),
            ("病害处理建议", "药剂名称/浓度/施用频率"),
            ("树势提升建议", "修剪方案/土壤改良措施"),
            ("补充说明", "需注意事项/后续观察要点")
        ])),
        ("回答置信度", "回答整体准确性的比例(0%-100%)")
    ])

    user_prompt = (
            "请严格按照以下要求分析图片：\n"
            "0.对于种类识别：直接给出结论\n"
            "1. 详细描述每个观察点，避免简单结论（如'叶色不正常'应改为'叶色呈黄绿色，下部叶片有紫红色边缘，疑似磷钾缺乏'）\n"
            "2. 对病斑特征：描述形状（圆形/不规则）、大小（mm级）、颜色演变、分布位置（叶缘/叶脉间）\n"
            "3. 对营养诊断：指出具体缺乏元素及典型症状（如'新叶黄化提示缺铁'）\n"
            "4. 综合建议：给出可操作的具体详细方案（如'花后2周喷施0.3%磷酸二氢钾，10天1次共2次'）\n"
            "5. 使用专业但易懂的术语（如'幼果膨大期'、'环状剥皮'）\n\n"
            "输出格式模板：\n" + json.dumps(json_template, ensure_ascii=False, indent=2)
    )

    # 编码所有图片
    print(f"[{time.strftime('%H:%M:%S')}] 开始编码所有图片...")
    image_contents = []
    for p in image_paths:
        # 不再检查本地文件是否存在，因为可能是网络图片
        img_base64 = encode_image_base64(p)
        image_contents.append({
            "type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{img_base64}"}
        })
    print(f"[{time.strftime('%H:%M:%S')}] 所有图片编码完成，准备调用混元多模态 API...")

    # 构造messages
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": [
            {"type": "text", "text": user_prompt},
            *image_contents
        ]}
    ]

    # 调用模型
    try:
        print(f"[{time.strftime('%H:%M:%S')}] 开始调用混元多模态 API...")
        start_time = time.time()
        completion = client.chat.completions.create(
            model="hunyuan-turbos-vision",
            messages=messages,
            temperature=0,
        )
        end_time = time.time()
        print(f"[{time.strftime('%H:%M:%S')}] API调用完成，耗时 {round(end_time - start_time, 2)} 秒")
    except Exception as e:
        raise Exception(f"API调用失败：{str(e)}")

    # 解析返回结果（核心修复部分）
    try:
        result_text = completion.choices[0].message.content
        print(f"[{time.strftime('%H:%M:%S')}] 原始返回结果：\n{result_text}")

        # 去除前后的代码块标记（关键修复）
        result_text = result_text.strip()
        if result_text.startswith('```json'):
            result_text = result_text[len('```json'):].strip()
        if result_text.endswith('```'):
            result_text = result_text[:-len('```')].strip()

        # 解析JSON
        result_json = json.loads(result_text, object_pairs_hook=collections.OrderedDict)
        print(f"[{time.strftime('%H:%M:%S')}] JSON 解析成功")
    except Exception as e:
        raise Exception(f"返回内容不是合法 JSON，解析失败：{str(e)}\n原始内容：{result_text}")

    confidence_text = str(result_json.get("回答置信度", "80%"))
    match = re.search(r"(\d+(?:\.\d+)?)", confidence_text)
    confidence = min(max(float(match.group(1)) / 100, 0), 1) if match else 0.8
    tree_type = result_json.get("树种识别", {}).get("种类", "未知")
    quality = result_json.get("图像质量诊断", {}).get("图像完整性", "已完成图像质量检查")

    # 与前端 PlantAnalysisData 契约保持一致。
    return {
        "plant_validation": {
            "consistent": True,
            "message": "图像分析完成",
            "details": f"已完成 {len(image_paths)} 张图片的综合诊断",
            "confidence": confidence,
        },
        "analysis_result": result_json,
        "validation": {
            "plant_type": tree_type,
            "quality": f"通过：{quality}",
            "count": str(len(image_paths)),
        },
    }


def main():
    image_paths = [
        "test-data/strawberry1/1.jpg"
    ]

    try:
        print(f"[{time.strftime('%H:%M:%S')}] 程序启动，开始分析图片...")
        res = analyze_tree_images(image_paths)

        print("\n" + "=" * 50)
        print("最终分析结果：")
        print(json.dumps(res["analysis_result"], ensure_ascii=False, indent=2))
        print("\n" + "=" * 50)
        print(f"[{time.strftime('%H:%M:%S')}] 程序执行完毕")
    except Exception as e:
        print(f"\n[{time.strftime('%H:%M:%S')}] 程序出错：{str(e)}")


if __name__ == "__main__":
    main()
