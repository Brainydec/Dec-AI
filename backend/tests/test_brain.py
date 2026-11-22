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


def test_accessibility_tools_are_allowlisted():
    expected = {
        "accessibility_settings",
        "scroll_screen",
        "read_screen",
        "press_back",
        "close_app",
        "tap_screen",
        "type_text",
    }
    assert expected.issubset(brain.KNOWN_TOOLS)


def test_accessibility_safety_rules_in_system_prompt():
    req = ChatRequest(text="scroll down")
    prompt = brain.build_system(req)
    assert "never use type_text for passwords" in prompt.lower()
    assert "user before tapping" in prompt.lower()


def test_screen_context_is_available_to_model_and_marked_untrusted():
    req = ChatRequest(
        text="What is on my screen?",
        device={
            "accessibility_connected": True,
            "screen_text": "Settings\\nWi-Fi\\nBluetooth",
        },
    )
    prompt = brain.build_system(req)
    assert "Settings" in prompt
    assert "Wi-Fi" in prompt
    assert "untrusted UI text" in prompt
    assert "Accessibility Service connected" in prompt


def test_screen_context_defaults_to_empty():
    req = ChatRequest(text="hello")
    assert req.device.screen_text == ""
    assert req.device.accessibility_connected is False


def test_close_app_is_allowlisted():
    action = brain.clean_actions({"actions": [{"tool": "close_app", "args": {}}]})
    assert len(action) == 1
    assert action[0].tool == "close_app"


def test_navigation_tool_is_allowlisted():
    action = brain.clean_actions({"actions": [{"tool": "navigate", "args": {"to": "recents"}}]})
    assert len(action) == 1
    assert action[0].tool == "navigate"


def test_chat_response_supports_agent_continuation():
    from app.schemas import ChatResponse
    response = ChatResponse(reply="Next step", actions=[{"tool": "read_screen", "args": {}}], more=True)
    assert response.more is True


def test_agent_prompt_describes_observe_plan_act_loop():
    prompt = brain.build_system(ChatRequest(text="Open an app and search"))
    assert '"more": true' in prompt
    assert "[RESULT]" in prompt and "[SCREEN]" in prompt
    assert "up to 8 rounds" in prompt
