"""Offline security parity tests; run with python3 tests/test_ring_hub_security.py.

Load both standalone files by path with a clean environment. No real config,
network sockets, external services, or legacy pytest conftest are used.
"""
import contextlib
import importlib.util
import io
import os
from pathlib import Path
import sys
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
HUB_DIR = ROOT / "infra" / "call-bridge" / "audio-bridge"
HUB_PATHS = (HUB_DIR / "ring_hub.py", HUB_DIR / "signaling" / "ring_hub.py")
TEST_TOKEN = "offline-test-token"


def load_hub(path):
    spec = importlib.util.spec_from_file_location("offline_" + path.parent.name, path)
    module = importlib.util.module_from_spec(spec)
    with mock.patch.dict(os.environ, {}, clear=True):
        spec.loader.exec_module(module)
    return module


class RingHubSecurityTests(unittest.TestCase):
    def setUp(self):
        self.hubs = [load_hub(path) for path in HUB_PATHS]

    def test_header_only_auth_matrix(self):
        cases = (
            ({}, None, False),
            ({}, {}, False),
            ({"token": TEST_TOKEN}, {}, False),
            ({}, {"Authorization": "Bearer wrong"}, False),
            ({}, {"Authorization": "Basic " + TEST_TOKEN}, False),
            ({}, {"Authorization": "Bearer " + TEST_TOKEN}, True),
            ({}, {"Authorization": "bEaReR  " + TEST_TOKEN + " "}, True),
            ({}, {"X-Ring-Token": TEST_TOKEN}, True),
            ({"token": "wrong"}, {"X-Ring-Token": TEST_TOKEN}, True),
            ({"token": TEST_TOKEN}, {"X-Ring-Token": "wrong"}, False),
        )
        for hub in self.hubs:
            for token in ("", TEST_TOKEN):
                hub.TOKEN = token
                for params, headers, expected in cases:
                    with self.subTest(hub=hub.__file__, token_set=bool(token), headers=headers):
                        self.assertEqual(hub._auth(params, headers), bool(token) and expected)

    def test_both_headers_use_constant_time_comparison(self):
        for hub in self.hubs:
            hub.TOKEN = TEST_TOKEN
            for headers in ({"Authorization": "Bearer " + TEST_TOKEN},
                            {"X-Ring-Token": TEST_TOKEN}):
                with self.subTest(hub=hub.__file__, headers=headers):
                    with mock.patch.object(hub.hmac, "compare_digest", return_value=True) as compare:
                        self.assertTrue(hub._auth({}, headers))
                        compare.assert_called_once_with(TEST_TOKEN, TEST_TOKEN)

    def test_startup_guard_before_socket_creation(self):
        for hub in self.hubs:
            for token in ("", TEST_TOKEN):
                for host in ("127.0.0.1", "localhost", "::1", "0.0.0.0", "::", "192.0.2.1"):
                    with self.subTest(hub=hub.__file__, token_set=bool(token), host=host):
                        hub.TOKEN = token
                        with mock.patch.object(sys, "argv", ["ring_hub.py", "--host", host, "--port", "0"]), \
                             mock.patch.object(hub, "ThreadingHTTPServer") as server, \
                             contextlib.redirect_stdout(io.StringIO()), \
                             contextlib.redirect_stderr(io.StringIO()) as stderr:
                            if not token and host not in ("127.0.0.1", "localhost", "::1"):
                                with self.assertRaises(SystemExit) as error:
                                    hub.main()
                                self.assertEqual(error.exception.code, 2)
                                self.assertIn("SIGNAL_TOKEN is required", stderr.getvalue())
                                server.assert_not_called()
                            else:
                                hub.main()
                                server.assert_called_once_with((host, 0), hub.Handler)
                                server.return_value.serve_forever.assert_called_once_with()
                                server.return_value.server_close.assert_called_once_with()

    def test_protected_http_routes_reject_query_and_body_tokens(self):
        for hub in self.hubs:
            hub.TOKEN = TEST_TOKEN
            for method, paths in (
                ("do_GET", ("/v1/devices", "/v1/ring", "/v1/poll", "/wait")),
                ("do_POST", ("/ring", "/v1/ring", "/hangup", "/v1/hangup")),
            ):
                for path in paths:
                    with self.subTest(hub=hub.__file__, method=method, path=path):
                        handler = hub.Handler.__new__(hub.Handler)
                        handler.path = path + "?token=" + TEST_TOKEN
                        handler.headers = {}
                        handler._read_json = mock.Mock(return_value={"token": TEST_TOKEN})
                        handler._json = mock.Mock()
                        with mock.patch.object(hub, "_deliver") as deliver:
                            getattr(handler, method)()
                        handler._json.assert_called_once_with(401, {"ok": False, "error": "unauthorized"})
                        deliver.assert_not_called()
            handler = hub.Handler.__new__(hub.Handler)
            handler.headers = {}
            handler.send_error = mock.Mock()
            handler._upgrade_ws({"token": TEST_TOKEN})
            handler.send_error.assert_called_once_with(401)

    def test_empty_token_cannot_authorize_mutations(self):
        for hub in self.hubs:
            hub.TOKEN = ""
            for method, path in (("do_GET", "/v1/ring"), ("do_GET", "/v1/devices"),
                                 ("do_POST", "/ring"), ("do_POST", "/hangup")):
                with self.subTest(hub=hub.__file__, method=method, path=path):
                    handler = hub.Handler.__new__(hub.Handler)
                    handler.path = path
                    handler.headers = {}
                    handler._read_json = mock.Mock(return_value={})
                    handler._json = mock.Mock()
                    getattr(handler, method)()
                    handler._json.assert_called_once_with(401, {"ok": False, "error": "unauthorized"})


if __name__ == "__main__":
    unittest.main()
