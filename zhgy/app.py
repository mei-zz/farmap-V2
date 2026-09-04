import os
import requests
from flask import Flask, request, jsonify
from hunyuan_vision import analyze_tree_images

app = Flask(__name__)

# 简单的API密钥验证
API_KEYS = os.environ.get('API_KEYS', 'your-api-key').split(',')

def verify_api_key(api_key):
    """验证API密钥"""
    return api_key in API_KEYS

def is_valid_url(url):
    """验证URL是否有效"""
    try:
        result = requests.head(url, timeout=5)
        return result.status_code == 200
    except:
        return False

@app.route('/analyze', methods=['POST'])
def analyze():
    """分析图片接口"""
    # 获取请求头中的API密钥
    api_key = request.headers.get('X-API-Key')
    
    # 验证API密钥
    if not api_key or not verify_api_key(api_key):
        return jsonify({'error': 'Invalid or missing API key'}), 401
    
    # 获取请求体中的图片URL列表
    data = request.get_json()
    image_urls = data.get('image_urls')
    
    if not image_urls or not isinstance(image_urls, list):
        return jsonify({'error': 'Missing or invalid image_urls parameter. Must be a list of image URLs.'}), 400
    
    if len(image_urls) < 1 or len(image_urls) > 5:
        return jsonify({'error': 'Please provide 1 to 5 image URLs'}), 400
    
    # 验证所有URL是否有效
    for url in image_urls:
        if not isinstance(url, str) or not url.startswith(('http://', 'https://')):
            return jsonify({'error': f'Invalid URL: {url}. Must be a valid HTTP/HTTPS URL.'}), 400
        
        # 可选：验证URL是否可访问
        # if not is_valid_url(url):
        #     return jsonify({'error': f'URL not accessible: {url}'}), 400
    
    try:
        # 调用分析函数
        result = analyze_tree_images(image_urls)
        return jsonify(result)
    except Exception as e:
        return jsonify({'error': str(e)}), 500

@app.route('/health', methods=['GET'])
def health_check():
    """健康检查接口"""
    return jsonify({'status': 'healthy'})

@app.route('/', methods=['GET'])
def home():
    """首页"""
    return jsonify({
        'message': 'Welcome to the Tree Image Analysis API',
        'endpoints': {
            'health_check': '/health',
            'analyze': '/analyze'
        },
        'usage': 'POST to /analyze with X-API-Key header and JSON body containing image_urls array'
    })

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=int(os.environ.get('PORT', 5000)), debug=False)