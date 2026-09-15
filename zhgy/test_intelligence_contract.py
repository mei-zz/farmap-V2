import os
import sys
import unittest

os.environ.setdefault("FARMAP_INTELLIGENCE_ALLOW_UNAUTHENTICATED_LOCAL", "true")
os.environ.pop("HUNYUAN_API_KEY", None)
sys.path.insert(0, os.path.dirname(__file__))

from app import app  # noqa: E402


class IntelligenceContractTest(unittest.TestCase):
    def setUp(self):
        self.client = app.test_client()

    def test_health_is_versioned_and_model_unavailability_is_explicit(self):
        response = self.client.get("/v1/health")
        self.assertEqual(200, response.status_code)
        payload = response.get_json()
        self.assertEqual("ai.v1", payload["contractVersion"])
        self.assertEqual("UNAVAILABLE", payload["vision"]["status"])

    def test_ssrf_and_protocol_refs_are_blocked(self):
        for uri in ("http://localhost/x", "http://127.0.0.1/x", "http://169.254.169.254/latest", "file:///tmp/a.jpg", "ftp://example/a.jpg"):
            response = self.client.post("/v1/vision/analyze", json={
                "contractVersion": "ai.v1", "task": "VISION_ANALYZE", "requestId": "ssrf", "images": [{"uri": uri}]
            })
            self.assertEqual(400, response.status_code)
            self.assertEqual("AI_IMAGE_FETCH_BLOCKED" if uri.startswith("http") else "AI_IMAGE_INVALID", response.get_json()["error"]["code"])

    def test_too_many_images_and_missing_auth_are_contract_errors(self):
        too_many = self.client.post("/v1/vision/analyze", json={
            "contractVersion": "ai.v1", "task": "VISION_ANALYZE", "images": [{"uri": "data:image/png;base64,AA=="}] * 6
        })
        self.assertEqual(400, too_many.status_code)
        self.assertEqual("AI_INVALID_REQUEST", too_many.get_json()["error"]["code"])

        os.environ["FARMAP_INTELLIGENCE_SERVICE_TOKEN"] = "test-token"
        try:
            unauthorized = self.client.get("/v1/capabilities")
            self.assertEqual(401, unauthorized.status_code)
            self.assertEqual("AI_AUTH_REQUIRED", unauthorized.get_json()["error"]["code"])
        finally:
            os.environ.pop("FARMAP_INTELLIGENCE_SERVICE_TOKEN", None)

    def test_valid_data_uri_reaches_model_gate_without_fabricated_success(self):
        response = self.client.post("/v1/vision/analyze", json={
            "contractVersion": "ai.v1", "task": "VISION_ANALYZE", "requestId": "r-valid",
            "images": [{"uri": "data:image/png;base64,AA=="}]
        })
        self.assertEqual(503, response.status_code)
        self.assertEqual("AI_MODEL_UNAVAILABLE", response.get_json()["error"]["code"])


if __name__ == "__main__":
    unittest.main()
