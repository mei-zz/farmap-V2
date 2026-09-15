import os
import unittest

os.environ.setdefault("FARMAP_INTELLIGENCE_ALLOW_UNAUTHENTICATED_LOCAL", "true")

import app as local_app  # noqa: E402


class LocalAiContractTest(unittest.TestCase):
    def test_v1_health_and_validation(self):
        class Request:
            headers = {}

        response = local_app.v1_health(Request())
        self.assertEqual("ai.v1", response["contractVersion"])
        invalid = local_app.v1_embed_text(Request(), {"contractVersion": "ai.v1", "task": "TEXT_EMBED", "texts": []})
        self.assertEqual(400, invalid.status_code)


if __name__ == "__main__":
    unittest.main()
