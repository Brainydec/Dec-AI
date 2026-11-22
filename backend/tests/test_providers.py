import asyncio

from app.providers import Groq, should_retry_groq_tool_error


def test_groq_tool_choice_none_error_triggers_fallback():
    error = {
        "message": "Tool choice is none, but model called a tool",
    }
    assert should_retry_groq_tool_error(
        "openai/gpt-oss-20b", error, "llama-3.3-70b-versatile"
    )
    # The exact message is recognised even when Groq omits an error code
    # or the configured primary model is not named GPT-OSS.
    assert should_retry_groq_tool_error(
        "some-other-model", error, "llama-3.3-70b-versatile"
    )


def test_groq_legacy_tool_use_failed_still_triggers_fallback():
    assert should_retry_groq_tool_error(
        "openai/gpt-oss-20b",
        {"code": "tool_use_failed", "message": "tool call mismatch"},
        "llama-3.3-70b-versatile",
    )


def test_groq_does_not_retry_unrelated_errors_or_same_model():
    assert not should_retry_groq_tool_error(
        "openai/gpt-oss-20b",
        {"message": "Invalid API key"},
        "llama-3.3-70b-versatile",
    )
    assert not should_retry_groq_tool_error(
        "openai/gpt-oss-20b",
        {"message": "Tool choice is none, but model called a tool"},
        "openai/gpt-oss-20b",
    )


def test_groq_complete_json_retries_exact_tool_choice_error(monkeypatch):
    import app.providers as providers

    monkeypatch.setattr(providers.settings, "groq_key", "test-key")
    monkeypatch.setattr(providers.settings, "groq_model", "openai/gpt-oss-20b")
    monkeypatch.setenv("GROQ_FALLBACK_MODEL", "llama-3.3-70b-versatile")

    class Response:
        def __init__(self, status, payload, text):
            self.status_code = status
            self._payload = payload
            self.text = text

        def json(self):
            return self._payload

    class Client:
        calls = []

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

        async def post(self, url, json, headers):
            self.calls.append(json["model"])
            if len(self.calls) == 1:
                return Response(
                    400,
                    {"error": {"message": "Tool choice is none, but model called a tool"}},
                    "tool choice mismatch",
                )
            return Response(
                200,
                {"choices": [{"message": {"content": '{"reply":"ok","actions":[]}'}}]},
                "",
            )

    monkeypatch.setattr(providers.httpx, "AsyncClient", lambda **kwargs: Client())
    result = asyncio.run(Groq().complete_json("system", [{"role": "user", "content": "hi"}]))

    assert result == '{"reply":"ok","actions":[]}'
    assert Client.calls == ["openai/gpt-oss-20b", "llama-3.3-70b-versatile"]
