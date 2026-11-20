import asyncio
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
os.environ["DEC_DB_PATH"] = "/tmp/dec_test.db"

from app import brain, db  # noqa: E402
from app.schemas import ChatRequest  # noqa: E402


def test_parse_plain_json():
    d = brain.parse_model_output('{"reply":"hi","actions":[{"tool":"flashlight","args":{"on":true}}]}')
    assert d["reply"] == "hi"
    assert brain.clean_actions(d)[0].tool == "flashlight"


def test_parse_fenced_and_noisy():
    d = brain.parse_model_output('```json\n{"reply":"ok","actions":[]}\n```')
    assert d["reply"] == "ok"
    d = brain.parse_model_output('Sure! {"reply":"x","actions":[]} thanks')
    assert d["reply"] == "x"


def test_parse_garbage_becomes_reply():
    assert brain.parse_model_output("just words")["reply"] == "just words"


def test_unknown_tools_dropped():
    d = {"actions": [{"tool": "rm_rf", "args": {}}, {"tool": "call", "args": {"contact": "brother"}}]}
    assert [a.tool for a in brain.clean_actions(d)] == ["call"]


def test_system_prompt_contains_context():
    req = ChatRequest(text="hi", memories=[{"k": "brother", "v": "Sam"}], device={"battery_percent": 42})
    s = brain.build_system(req)
    assert "brother: Sam" in s and "battery 42%" in s


def test_chat_with_fake_provider(monkeypatch):
    db.init()

    class Fake:
        name = "fake"

        async def complete_json(self, system, messages):
            return '{"reply":"Turning it on.","actions":[{"tool":"flashlight","args":{"on":true}}],"search_query":null}'

    monkeypatch.setattr(brain, "chain", lambda: [Fake()])
    r = asyncio.run(brain.chat(ChatRequest(text="flashlight on")))
    assert r.reply == "Turning it on." and r.actions[0].args == {"on": True}
