import base64
import json
import os
import time  # 用于打印时间戳，方便追踪进度
from openai import OpenAI
from dotenv import load_dotenv

# 加载环境变量（如果使用.env文件）
load_dotenv()

# 初始化客户端：密钥只从本地环境读取，不进入代码仓库
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY")
if not OPENAI_API_KEY:
    raise RuntimeError("OPENAI_API_KEY is not set")
client = OpenAI(api_key=OPENAI_API_KEY)

# 定价（参考OpenAI官方最新定价）
INPUT_PRICE_PER_1K = 0.01
OUTPUT_PRICE_PER_1K = 0.03

def encode_image_base64(path):
    """将图片编码为base64，并输出进度"""
    try:
        print(f"[{time.strftime('%H:%M:%S')}] 正在编码图片：{path}")
        with open(path, "rb") as f:
            img_data = base64.b64encode(f.read()).decode()
        print(f"[{time.strftime('%H:%M:%S')}] 图片 {os.path.basename(path)} 编码完成")
        return img_data
    except Exception as e:
        raise Exception(f"图片编码失败：{str(e)}")

def analyze_tree_images(image_paths):
    """分析图片并输出实时进度"""
    # 1. 检查图片数量
    if not (1 <= len(image_paths) <= 5):
        raise ValueError("请提供 1–5 张图片")
    print(f"[{time.strftime('%H:%M:%S')}] 检测到 {len(image_paths)} 张图片，开始处理...")

    # 2. 定义系统提示和模板
    print(f"[{time.strftime('%H:%M:%S')}] 准备分析提示词和模板...")
    system_prompt = (
        "你是一位专业的果树栽培与植保专家，拥有20年果园实地经验。请根据提供的果树图片进行详细诊断分析，"
        "要求如下：\n"
        "1. 每个诊断项必须给出具体描述和量化指标（如病斑比例用30%-40%表示）\n"
        "2. 对于不确定的内容，基于专业经验给出可能性判断\n"
        "3. 综合建议必须包含具体操作措施和频率\n"
        "4. 特别关注叶片病斑特征、果实发育状态和营养失衡迹象\n"
        "5. 使用专业术语但保持可读性\n"
        "输出必须为严格JSON格式，不包含任何额外文本。"
    )
    json_template = {
        "树种识别": {
            "种类": "具体树种名称（如'脆李'、'脐橙'）",
            "置信度": "高/中/低（基于图像特征判断）"
        },
        "图像质量诊断": {
            "图像完整性": "是否展示关键部位（全树/枝叶/果实）",
            "图像清晰度": "具体描述（如'叶脉清晰可见'/'果实细节模糊'）",
            "光照条件": "自然光/逆光/阴影占比等",
            "拍摄建议": "具体改进建议（如'避开强光时段拍摄'）"
        },
        "当前生长阶段": "具体阶段（如'盛花期''幼果膨大期''着色期'）",
        "树势诊断": {
            "树形结构": "主枝分布、树冠密度描述",
            "枝条生长": "新梢长度、木质化程度",
            "树势综合判断": "强/中/弱（结合多项指标）"
        },
        "叶部状态诊断": {
            "叶色": "具体颜色描述（如'黄绿色''深绿带紫边'）",
            "叶面积大小": "与标准对比（偏大/正常/偏小）",
            "叶面病斑比例": "估算百分比（0-100%）",
            "叶片状态总结": "病斑特征（形状/颜色/分布）"
        },
        "果实状态诊断": {
            "挂果量": "稀疏/中等/密集（或估算数量）",
            "果实大小": "与品种标准对比",
            "果实色泽": "具体描述（如'青绿带红晕'）",
            "异常果实比例": "畸形果/病斑果占比"
        },
        "营养状况诊断": {
            "氮素状态": "根据叶色、新梢生长判断",
            "磷素状态": "根据根系、花芽分化判断",
            "钾素状态": "根据果实品质、抗逆性判断",
            "中微量元素": "具体缺乏元素（如铁、锌）及症状"
        },
        "病虫害诊断": {
            "疑似病害": "具体病害名称（如'黑星病''白粉病'）",
            "病斑描述": "大小/形状/颜色/病征（如霉层）",
            "虫害迹象": "虫孔/分泌物/虫体描述",
            "病害严重度": "分级（轻度<30%/中度30-60%/重度>60%）"
        },
        "果叶比与树体评估": {
            "可见叶片数估计": "大致数量（如150片）",
            "可见果实数估计": "大致数量（如30个）",
            "果叶比估计": "比例（如1:15）",
            "是否合理": "判断依据（品种标准/树势）"
        },
        "综合建议": {
            "施肥建议": "肥料类型/用量/施用时间",
            "病害处理建议": "药剂名称/浓度/施用频率",
            "树势提升建议": "修剪方案/土壤改良措施",
            "补充说明": "需注意事项/后续观察要点"
        }
    }
    user_prompt = (
        "请严格按照以下要求分析图片：\n"
        "1. 详细描述每个观察点，避免简单结论（如'叶色不正常'应改为'叶色呈黄绿色，下部叶片有紫红色边缘，疑似磷钾缺乏'）\n"
        "2. 对病斑特征：描述形状（圆形/不规则）、大小（mm级）、颜色演变、分布位置（叶缘/叶脉间）\n"
        "3. 对营养诊断：指出具体缺乏元素及典型症状（如'新叶黄化提示缺铁'）\n"
        "4. 综合建议：给出可操作的具体方案（如'花后2周喷施0.3%磷酸二氢钾，10天1次共2次'）\n"
        "5. 使用专业但易懂的术语（如'幼果膨大期''环状剥皮'）\n\n"
        "输出格式模板："
    )

    # 3. 编码所有图片
    print(f"[{time.strftime('%H:%M:%S')}] 开始编码所有图片...")
    image_contents = []
    for p in image_paths:
        # 检查图片是否存在
        if not os.path.exists(p):
            raise FileNotFoundError(f"图片不存在：{p}")
        # 编码图片并添加到内容列表
        img_base64 = encode_image_base64(p)
        image_contents.append({
            "type": "image_url",
            "image_url": {"url": f"data:image/jpeg;base64,{img_base64}"}
        })
    print(f"[{time.strftime('%H:%M:%S')}] 所有图片编码完成，准备调用GPT-4o API...")

    # 4. 构建消息列表
    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": [
            {"type": "text", "text": user_prompt + json.dumps(json_template, ensure_ascii=False)},
            *image_contents
        ]}
    ]

    # 5. 调用API（最耗时步骤，输出进度）
    try:
        print(f"[{time.strftime('%H:%M:%S')}] 开始调用GPT-4o API...（这可能需要几秒到几十秒）")
        start_time = time.time()
        response = client.chat.completions.create(
            model="gpt-4o",    #4o类模型-vision
            messages=messages,
            temperature=0,
            response_format={"type": "json_object"}
        )
        end_time = time.time()
        print(f"[{time.strftime('%H:%M:%S')}] API调用完成，耗时 {round(end_time - start_time, 2)} 秒")
    except Exception as e:
        raise Exception(f"API调用失败：{str(e)}")

    # 6. 解析结果
    print(f"[{time.strftime('%H:%M:%S')}] 开始解析API返回结果...")
    try:
        result = response.choices[0].message.content
        result_json = json.loads(result) if isinstance(result, str) else result
        print(f"[{time.strftime('%H:%M:%S')}] 结果解析完成，格式验证通过")
    except Exception as e:
        raise Exception(f"结果解析失败：{str(e)}")

    # 7. 计算token和费用
    print(f"[{time.strftime('%H:%M:%S')}] 计算token用量和费用...")
    usage = response.usage
    cost_estimated = {
        "input_cost_usd": round((usage.prompt_tokens / 1000) * INPUT_PRICE_PER_1K, 6),
        "output_cost_usd": round((usage.completion_tokens / 1000) * OUTPUT_PRICE_PER_1K, 6),
        "total_cost_usd": round(
            (usage.prompt_tokens / 1000) * INPUT_PRICE_PER_1K +
            (usage.completion_tokens / 1000) * OUTPUT_PRICE_PER_1K,
            6
        )
    }

    return {
        "analysis_result": result_json,
        "token_usage": {
            "input_tokens": usage.prompt_tokens,
            "output_tokens": usage.completion_tokens,
            "total_tokens": usage.prompt_tokens + usage.completion_tokens
        },
        "cost_estimate_usd": cost_estimated
    }

def main():
    # 图片路径（请替换为实际路径）
    image_paths = [
        "E:/zhgy/test-data/blueberry1/1.jpg",
        "E:/zhgy/test-data/blueberry1/2.jpg",
        "E:/zhgy/test-data/blueberry1/3.jpg",
        "E:/zhgy/test-data/blueberry1/4.jpg"
    ]

    try:
        # 执行分析并实时输出
        print(f"[{time.strftime('%H:%M:%S')}] 程序启动，开始分析图片...")
        res = analyze_tree_images(image_paths)

        # 输出最终结果
        print("\n" + "="*50)
        print("最终分析结果：")
        print(json.dumps(res["analysis_result"], ensure_ascii=False, indent=2))
        print("\n" + "="*50)
        print("Token 用量：", res["token_usage"])
        print("费用预估：", res["cost_estimate_usd"])
        print("\n" + "="*50)
        print(f"[{time.strftime('%H:%M:%S')}] 程序执行完毕")

    except Exception as e:
        # 即使出错也会输出错误信息，不会无提示崩溃
        print(f"\n[{time.strftime('%H:%M:%S')}] 程序出错：{str(e)}")

if __name__ == "__main__":
    main()
