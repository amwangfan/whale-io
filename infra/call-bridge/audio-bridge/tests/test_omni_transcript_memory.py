"""Omni 通话转写记录器：未配置 / 配置 WHALE_MEMORY_DB 两种情况（全部离线 mock）。"""
from __future__ import annotations

import socket
import threading

import pytest

import server
from whale_core.memory.store import MemoryKind

USER_EV = {"type": "conversation.item.input_audio_transcription.completed", "transcript": "明天提醒我开会"}
ASSIST_EV = {"type": "response.audio_transcript.done", "transcript": "好的，已记下。"}


class FakeOmniSession:
    """Stand-in for OmniRealtimeSession: fires transcript events on connect, no network."""

    instances: list["FakeOmniSession"] = []

    def __init__(self, *, on_event=None, **_kw):
        self.on_event = on_event
        self.responding = threading.Event()
        self._pending_calls: dict = {}
        self.callback_errors: list[BaseException] = []
        self.closed = False
        FakeOmniSession.instances.append(self)

    def connect(self, timeout: float = 20.0) -> None:
        for ev in (USER_EV, ASSIST_EV):
            try:
                self.on_event(ev)
            except BaseException as e:  # record instead of aborting the call
                self.callback_errors.append(e)

    def _send(self, _msg: dict) -> None:
        pass

    def close(self) -> None:
        self.closed = True


@pytest.fixture
def fake_omni(monkeypatch):
    FakeOmniSession.instances.clear()
    monkeypatch.setattr(server, "OmniRealtimeSession", FakeOmniSession)
    monkeypatch.setattr(server, "tools_enabled", lambda: False)
    monkeypatch.setattr(server, "TOKEN", "")
    return FakeOmniSession


def _run_call(handler, *args) -> None:
    srv, cli = socket.socketpair()
    cli.shutdown(socket.SHUT_WR)  # phone hangs up right after HELLO
    try:
        t = threading.Thread(target=handler, args=(srv, ("127.0.0.1", 0), *args), daemon=True)
        t.start()
        t.join(timeout=10)
        assert not t.is_alive(), "handler did not return"
    finally:
        cli.close()


def test_omni_event_without_memory_db_does_not_raise(fake_omni, monkeypatch):
    monkeypatch.delenv("WHALE_MEMORY_DB", raising=False)
    _run_call(server.handle_omni, None, False)
    (sess,) = fake_omni.instances
    assert sess.callback_errors == []
    assert sess.closed


def test_omni_transcripts_written_and_recorder_closed(fake_omni, monkeypatch, tmp_path):
    import whale_core.core.companion as companion
    import whale_core.memory.sqlite_store as sqlite_store

    db = tmp_path / "mem.db"
    monkeypatch.setenv("WHALE_MEMORY_DB", str(db))
    recorders, stores = [], []

    class SpyRecorder(companion.RealtimeTranscriptRecorder):
        def __init__(self, *a, **kw):
            super().__init__(*a, **kw)
            self.offers: list[tuple[str, str]] = []
            self.closed = False
            recorders.append(self)

        def offer(self, session_id, role, text):
            self.offers.append((role, text))
            return super().offer(session_id, role, text)

        def close(self):
            super().close()
            self.closed = True

    class SpyStore(sqlite_store.SqliteMemoryStore):
        def __init__(self, *a, **kw):
            super().__init__(*a, **kw)
            self.closed = False
            stores.append(self)

        def close(self):
            super().close()
            self.closed = True

    monkeypatch.setattr(companion, "RealtimeTranscriptRecorder", SpyRecorder)
    monkeypatch.setattr(sqlite_store, "SqliteMemoryStore", SpyStore)

    _run_call(server.handle_omni, None, False)

    (sess,) = fake_omni.instances
    assert sess.callback_errors == []
    (rec,) = recorders
    (store,) = stores
    assert rec.offers == [("user", USER_EV["transcript"]), ("assistant", ASSIST_EV["transcript"])]
    assert rec.closed and store.closed

    check = sqlite_store.SqliteMemoryStore(db)
    try:
        eps = check.list_by_kind(MemoryKind.EPISODE)
    finally:
        check.close()
    summaries = sorted(r.content["summary"] for r in eps)
    assert summaries == sorted([USER_EV["transcript"], ASSIST_EV["transcript"]])
    assert {r.content["role"] for r in eps} == {"user", "assistant"}


def test_echo_call_closes_without_name_error(monkeypatch):
    monkeypatch.setattr(server, "TOKEN", "")
    errors: list[BaseException] = []

    def run(conn, addr):
        try:
            server.handle_echo(conn, addr, None, False, False)
        except BaseException as e:
            errors.append(e)

    _run_call(run)
    assert errors == []


def test_agent_mode_does_not_allocate_unused_memory_worker(monkeypatch, tmp_path):
    """Agent has no transcript callback; it must not leak a recorder on disconnect."""
    db = tmp_path / "should-not-exist.db"
    monkeypatch.setenv("WHALE_MEMORY_DB", str(db))
    monkeypatch.setattr(server, "TOKEN", "")
    _run_call(server.handle_agent, None, False)
    assert not db.exists()



def test_selected_persona_card_is_applied_to_omni_instructions():
    from whale_core.memory.store import InMemoryStore
    from whale_core.ui.persona_cards import import_external_card, switch_card
    from whale_core.ui.effective_persona import build_effective_persona

    store = InMemoryStore()
    import_external_card(store, card_id="call-test", title="通话人格",
                         prompt_text="喜欢鲸鱼和星空，说话自然。")
    switch_card(store, "call-test")
    instructions = server.append_persona_card_instructions("base omni instructions", store)
    assert instructions.startswith("base omni instructions")
    assert build_effective_persona(store).model_context in instructions
    assert "喜欢鲸鱼和星空" in instructions
    assert "Global constraints" not in instructions


def test_no_selected_persona_preserves_omni_instructions():
    from whale_core.memory.store import InMemoryStore

    assert server.append_persona_card_instructions(
        "base omni instructions", InMemoryStore()) == "base omni instructions"


def test_structured_persona_fields_reach_omni_instructions():
    from whale_core.memory.store import InMemoryStore
    from whale_core.ui.persona_cards import PersonaCard, switch_card

    store = InMemoryStore()
    card = PersonaCard(card_id="call-structured", title="结构人格", name="鲸鲸",
                       summary="住在海边", temperament="好奇",
                       relationship_style="一起探索", example_dialogue="去看看那片海吧")
    store.put("persona:card:" + card.card_id, card.as_dict())
    switch_card(store, card.card_id)
    instructions = server.append_persona_card_instructions("base", store)
    for value in ("鲸鲸", "住在海边", "好奇", "一起探索", "去看看那片海吧"):
        assert value in instructions


def test_missing_active_card_preserves_omni_instructions():
    from whale_core.memory.store import InMemoryStore

    store = InMemoryStore()
    store.put("persona:active_card", {"card_id": "deleted"})
    assert server.append_persona_card_instructions("base", store) == "base"


def test_unavailable_persona_store_preserves_omni_instructions():
    class UnavailableStore:
        def get(self, _key):
            raise OSError("offline store")

    assert server.append_persona_card_instructions("base", UnavailableStore()) == "base"


def test_tts_metadata_does_not_enter_omni_instructions():
    from whale_core.memory.store import InMemoryStore
    from whale_core.ui.persona_cards import PersonaCard, switch_card

    store = InMemoryStore()
    card = PersonaCard(card_id="call-voice", title="voice", name="鲸鲸",
                       voice_hint="voice-only-marker", timbre_hint="timbre-only-marker",
                       speaking_rate=1.37)
    store.put("persona:card:" + card.card_id, card.as_dict())
    switch_card(store, card.card_id)
    instructions = server.append_persona_card_instructions("base", store)
    assert "鲸鲸" in instructions
    assert "voice-only-marker" not in instructions
    assert "timbre-only-marker" not in instructions
    assert "1.37" not in instructions
