import ipaddress
import os
import socket
import time
import uuid
from urllib.parse import urlparse

import requests
from flask import Flask, request, jsonify
from hunyuan_vision import analyze_tree_images, is_configured as vision_is_configured

app = Flask(__name__)

# 简单的API密钥验证
API_KEYS = [value.strip() for value in os.environ.get('API_KEYS', '').split(',') if value.strip()]

def verify_api_key(api_key):
    """验证API密钥"""
    return api_key in API_KEYS


CONTRACT_VERSION = 'ai.v1'
MAX_IMAGE_BYTES = int(os.environ.get('FARMAP_INTELLIGENCE_MAX_IMAGE_BYTES', str(12 * 1024 * 1024)))


def _request_id(data=None):
    value = data.get('requestId') if isinstance(data, dict) else None
    return value if isinstance(value, str) and value.strip() else str(uuid.uuid4())


def _contract_error(code, message, request_id, status=400, retryable=False):
    return jsonify({
        'contractVersion': CONTRACT_VERSION,
        'requestId': request_id,
        'status': 'FAILED',
        'error': {'code': code, 'message': message, 'requestId': request_id, 'retryable': retryable},
    }), status


def _service_authorized():
    configured = os.environ.get('FARMAP_INTELLIGENCE_SERVICE_TOKEN', '').strip()
    if not configured and os.environ.get('FARMAP_INTELLIGENCE_ALLOW_UNAUTHENTICATED_LOCAL', '').lower() == 'true':
        return True
    authorization = request.headers.get('Authorization', '')
    return bool(configured) and authorization == f'Bearer {configured}'


def _safe_image_ref(value):
    if not isinstance(value, str) or not value.strip():
        return False, 'image uri must be a non-empty string'
    value = value.strip()
    if value.startswith('data:image/') and ';base64,' in value:
        try:
            import base64
            raw = base64.b64decode(value.split(',', 1)[1], validate=True)
            if not raw or len(raw) > MAX_IMAGE_BYTES:
                return False, 'image payload is empty or too large'
        except Exception:
            return False, 'image data URI is invalid'
        return True, None
    parsed = urlparse(value)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname:
        return False, 'only validated http(s) object references are supported'
    host = parsed.hostname.lower()
    blocked_names = {'localhost', 'localhost.localdomain', 'ip6-localhost', 'metadata.google.internal'}
    if host in blocked_names:
        return False, 'image host is blocked'
    try:
        address = ipaddress.ip_address(host)
        if address.is_private or address.is_loopback or address.is_link_local or address.is_reserved:
            return False, 'private or link-local image host is blocked'
    except ValueError:
        allowed = {item.strip().lower() for item in os.environ.get('FARMAP_ALLOWED_IMAGE_HOSTS', '').split(',') if item.strip()}
        # Explicitly configured internal object hosts are trusted deployment
        # boundaries; arbitrary hostnames still undergo DNS private-range checks.
        if host in allowed:
            return True, None
        try:
            resolved = {item[4][0] for item in socket.getaddrinfo(host, None)}
            for value in resolved:
                address = ipaddress.ip_address(value)
                if address.is_private or address.is_loopback or address.is_link_local or address.is_reserved:
                    return False, 'image host resolves to a private or link-local address'
        except socket.gaierror:
            return False, 'image host cannot be resolved'
    allowed = {item.strip().lower() for item in os.environ.get('FARMAP_ALLOWED_IMAGE_HOSTS', '').split(',') if item.strip()}
    if host not in allowed:
        return False, 'image host is not in FARMAP_ALLOWED_IMAGE_HOSTS'
    return True, None


def _v1_images(data, request_id):
    images = data.get('images') if isinstance(data, dict) else None
    if not isinstance(images, list) or not 1 <= len(images) <= 5:
        return None, _contract_error('AI_INVALID_REQUEST', 'images must contain 1 to 5 items', request_id)
    refs = []
    for item in images:
        uri = item.get('uri') if isinstance(item, dict) else item
        valid, reason = _safe_image_ref(uri)
        if not valid:
            code = 'AI_IMAGE_FETCH_BLOCKED' if isinstance(uri, str) and uri.startswith(('http://', 'https://')) else 'AI_IMAGE_INVALID'
            return None, _contract_error(code, reason, request_id)
        refs.append(uri)
    return refs, None


@app.before_request
def _v1_auth_guard():
    if request.path.startswith('/v1/') and not _service_authorized():
        return _contract_error('AI_AUTH_REQUIRED', 'service authentication is required', _request_id(request.get_json(silent=True) or {}), 401)

def is_valid_url(url):
    """验证URL是否有效"""
    try:
        result = requests.head(url, timeout=5)
        return result.status_code == 200
    except:
        return False

@app.route('/analyze', methods=['POST'])
def analyze():
    """LEGACY compatibility endpoint; commercial callers must use /v1/vision/analyze."""
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


@app.route('/v1/health', methods=['GET'])
def v1_health():
    vision_status = 'READY' if vision_is_configured() else 'UNAVAILABLE'
    status = 'READY' if vision_status == 'READY' else 'DEGRADED'
    return jsonify({
        'contractVersion': CONTRACT_VERSION,
        'status': status,
        'process': 'READY',
        'vision': {'status': vision_status, 'model': 'hunyuan-turbos-vision'},
        'textEmbedding': {'status': 'DELEGATED_TO_LOCAL_AI'},
        'imageEmbedding': {'status': 'DELEGATED_TO_LOCAL_AI'},
    })


@app.route('/v1/capabilities', methods=['GET'])
def v1_capabilities():
    return jsonify({
        'contractVersion': CONTRACT_VERSION,
        'tasks': ['VISION_ANALYZE'],
        'endpoints': {'health': '/v1/health', 'capabilities': '/v1/capabilities', 'vision': '/v1/vision/analyze'},
        'vision': {'status': 'READY' if vision_is_configured() else 'UNAVAILABLE', 'maxImages': 5},
    })


@app.route('/v1/vision/analyze', methods=['POST'])
def v1_vision_analyze():
    started = time.perf_counter()
    data = request.get_json(silent=True) or {}
    request_id = _request_id(data)
    if data.get('contractVersion') != CONTRACT_VERSION or data.get('task') != 'VISION_ANALYZE':
        return _contract_error('AI_INVALID_REQUEST', 'contractVersion=ai.v1 and task=VISION_ANALYZE are required', request_id)
    images, error = _v1_images(data, request_id)
    if error:
        return error
    if not vision_is_configured():
        return _contract_error('AI_MODEL_UNAVAILABLE', 'vision model is not configured', request_id, 503, True)
    try:
        analysis = analyze_tree_images(images)
        return jsonify({
            'contractVersion': CONTRACT_VERSION,
            'requestId': request_id,
            'traceId': data.get('traceId'),
            'status': 'SUCCEEDED',
            'model': 'hunyuan-turbos-vision',
            'observations': [{'kind': 'VISION_ANALYSIS', 'content': analysis.get('analysis_result', analysis), 'confidence': analysis.get('plant_validation', {}).get('confidence')}],
            'warnings': [],
            'latencyMs': round((time.perf_counter() - started) * 1000),
            'analysis': analysis,
        })
    except Exception as exc:
        message = str(exc)
        code = 'AI_MODEL_TIMEOUT' if 'timeout' in message.lower() else 'AI_INTERNAL_ERROR'
        return _contract_error(code, 'vision inference failed', request_id, 504 if code == 'AI_MODEL_TIMEOUT' else 502, code == 'AI_MODEL_TIMEOUT')

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
