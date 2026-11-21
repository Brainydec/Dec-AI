"""Model-provider abstraction. Add a class + register it to support another provider."""
import httpx

from .config import settings

TIMEOUT = httpx.Timeout(40.0, connect=10.0)


class ProviderError(Exception):
    pass


class Provider:
    name = "base"

    def available(self) -> bool:
        raise NotImplementedError

    async def complete_json(self, system: str, messages: list[dict]) -> str:
        raise NotImplementedError

    async def search_answer(self, query: str) -> str | None:
        return None  # provider has no web search


class Gemini(Provider):
    name = "gemini"

    def available(self):
        return bool(settings.gemini_key)

    def _url(self):
        return f"https://generativelanguage.googleapis.com/v1beta/models/{settings.gemini_model}:generateContent"

    async def _call(self, body: dict) -> str:
        async with httpx.AsyncClient(timeout=TIMEOUT) as c:
            r = await c.post(self._url(), json=body, headers={"x-goog-api-key": settings.gemini_key})
        if r.status_code != 200:
            raise ProviderError(f"gemini {r.status_code}: {r.text[:200]}")
        data = r.json()
        try:
            parts = data["candidates"][0]["content"]["parts"]
            return "".join(p.get("text", "") for p in parts)
        except (KeyError, IndexError) as e:
            raise ProviderError(f"gemini bad response: {str(data)[:200]}") from e

    async def complete_json(self, system, messages):
        contents = [
            {"role": "user" if m["role"] == "user" else "model", "parts": [{"text": m["content"]}]} for m in messages
        ]
        body = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": contents,
            "generationConfig": {"responseMimeType": "application/json", "temperature": 0.4},
        }
        return await self._call(body)

    async def search_answer(self, query):
        body = {
            "systemInstruction": {
                "parts": [{"text": "Answer for a voice assistant: 2-4 short plain sentences, no markdown, no lists, no URLs. Use today's information."}]
            },
            "contents": [{"role": "user", "parts": [{"text": query}]}],
            "tools": [{"google_search": {}}],
        }
        return (await self._call(body)).strip()


class Groq(Provider):
    name = "groq"

    def available(self):
        return bool(settings.groq_key)

    async def complete_json(self, system, messages):
        body = {
            "model": settings.groq_model,
            "messages": [{"role": "system", "content": system}] + messages,
            "response_format": {"type": "json_object"},
            "temperature": 0.4,
        }
        async with httpx.AsyncClient(timeout=TIMEOUT) as c:
            r = await c.post(
                "https://api.groq.com/openai/v1/chat/completions",
                json=body,
                headers={"Authorization": f"Bearer {settings.groq_key}"},
            )
        if r.status_code != 200:
            raise ProviderError(f"groq {r.status_code}: {r.text[:200]}")
        return r.json()["choices"][0]["message"]["content"]


REGISTRY = {"gemini": Gemini(), "groq": Groq()}


def chain() -> list[Provider]:
    return [REGISTRY[n] for n in settings.providers if n in REGISTRY and REGISTRY[n].available()]
