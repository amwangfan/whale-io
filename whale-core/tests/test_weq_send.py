"""WeQWebSendPort 离线测试：登录、发送、会话过期重登、失败如实报 False。

注入 fake opener，不触网、不依赖真实 WeQ。
"""
import json

import pytest

from whale_core.life.initiative import ProactiveDraft
from whale_core.life.weq_send import MAX_TEXT_CHARS, WeQSendError, WeQWebSendPort

BASE = "http://127.0.0.1:7690"
TOKEN = "synthetic-web-token"
UID = "u_X5hSynthetic"


class FakeResponse:
    def __init__(self, status=200, body=None, set_cookie=None, raw=None):
        self.status = status
        self._raw = raw if raw is not None else json.dumps(body if body is not None else {}).encode()
        self.headers = {"Set-Cookie": set_cookie} if set_cookie else {}

    def read(self):
        return self._raw

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


class FakeOpener:
    """按 (method, path) 队列回放响应；记录请求供断言。"""

    def __init__(self, responses):
        self.responses = list(responses)
        self.requests = []

    def __call__(self, request, timeout):
        self.requests.append({"url": request.full_url, "body": request.data,
                              "headers": dict(request.header_items()), "timeout": timeout})
        item = self.responses.pop(0) if self.responses else FakeResponse(200, {})
        if isinstance(item, Exception):
            raise item
        return item


def draft(text="测试回复", channel="direct_message"):
    return ProactiveDraft("d1", text, "QQ 回复", ("qq:m1",), channel=channel)


def login_ok():
    return FakeResponse(200, raw=b"ok", set_cookie="weq_session=abc123; Path=/; HttpOnly")


def send_result(ok=True, extra=None):
    outcome = {"ok": ok, "peerType": "c2c", "targetId": UID, **(extra or {})}
    return FakeResponse(200, {"result": {"data": {"json": outcome}}})


def test_login_and_send_success():
    opener = FakeOpener([login_ok(), send_result(True)])
    port = WeQWebSendPort(BASE, TOKEN, target_uid=UID, opener=opener)
    assert port.send(draft()) is True
    assert port.last_receipt["ok"] is True
    login_req, send_req = opener.requests
    assert login_req["url"] == BASE + "/_auth/login"
    assert json.loads(login_req["body"]) == {"token": TOKEN}
    assert send_req["url"] == BASE + "/trpc/account.sendElements"
    assert send_req["headers"]["Cookie"] == "weq_session=abc123"
    payload = json.loads(send_req["body"])["json"]
    assert payload["peerType"] == "c2c"
    assert payload["targetId"] == UID
    assert payload["elements"] == [{"kind": "text", "textContent": "测试回复"}]
    assert TOKEN not in json.dumps(send_req["headers"])  # token 不随发送请求外泄


def test_send_rejected_by_server_is_false():
    opener = FakeOpener([login_ok(), send_result(False, {"errMsg": "完整离线模式"})])
    port = WeQWebSendPort(BASE, TOKEN, target_uid=UID, opener=opener)
    assert port.send(draft()) is False
    assert port.last_receipt["errMsg"] == "完整离线模式"


def test_session_expiry_relogins_once():
    expired = FakeResponse(401, raw=b"unauthorized")
    opener = FakeOpener([login_ok(), expired, login_ok(), send_result(True)])
    port = WeQWebSendPort(BASE, TOKEN, target_uid=UID, opener=opener)
    assert port.send(draft()) is True
    assert len(opener.requests) == 4  # login, 401, relogin, resend


def test_login_failure_raises_and_send_reports_false():
    opener = FakeOpener([FakeResponse(401, raw=b"unauthorized")])
    port = WeQWebSendPort(BASE, TOKEN, target_uid=UID, opener=opener)
    with pytest.raises(WeQSendError):
        port.login()
    opener2 = FakeOpener([FakeResponse(401, raw=b"unauthorized")])
    port2 = WeQWebSendPort(BASE, TOKEN, target_uid=UID, opener=opener2)
    assert port2.send(draft()) is False  # send 内登录失败 → False 不抛


def test_unreachable_and_http_errors_are_false():
    import urllib.error
    opener = FakeOpener([urllib.error.URLError("refused")])
    port = WeQWebSendPort(BASE, TOKEN, target_uid=UID, opener=opener)
    assert port.send(draft()) is False


def test_scoping_and_limits():
    from types import SimpleNamespace
    opener = FakeOpener([login_ok(), send_result(True)])
    port = WeQWebSendPort(BASE, TOKEN, target_uid=UID, opener=opener)
    assert port.send(draft(channel="hud")) is False       # 非 direct_message 不发
    # 空文本/超长：ProactiveDraft 构造期已拦空文本，这里用鸭子对象测端口自身的防御层
    assert port.send(SimpleNamespace(channel="direct_message", text="  ")) is False
    assert port.send(SimpleNamespace(channel="direct_message",
                                     text="长" * (MAX_TEXT_CHARS + 1))) is False
    assert opener.requests == []                           # 以上都没触网


def test_expected_account_fields_passed_when_configured():
    opener = FakeOpener([login_ok(), send_result(True)])
    port = WeQWebSendPort(BASE, TOKEN, target_uid=UID, expected_account_uin="123",
                          expected_account_config_id="123_ab", opener=opener)
    assert port.send(draft()) is True
    payload = json.loads(opener.requests[1]["body"])["json"]
    assert payload["expectedAccountUin"] == "123"
    assert payload["expectedAccountConfigId"] == "123_ab"


def test_constructor_validation():
    with pytest.raises(ValueError):
        WeQWebSendPort("", TOKEN, target_uid=UID)
    with pytest.raises(ValueError):
        WeQWebSendPort(BASE, "", target_uid=UID)
    with pytest.raises(ValueError):
        WeQWebSendPort(BASE, TOKEN, target_uid=" ")
