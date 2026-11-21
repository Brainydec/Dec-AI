
"""Model-provider abstraction for DEC AI."""

import httpx

from .config import settings

TIMEOUT = httpx.Timeout(60.0, connect=15.0)


class ProviderError(Exception):
    pass


class Provider:
    name = "base"

    def available(self) -> bool:
        raise NotImplementedError

    async def complete_json(self, system: str, messages: list[dict]) -> str:
        raise NotImplementedError

    async def search_answer(self, query: str) -> str | None:
        return None


# ---------------- GEMINI ----------------

class Gemini(Provider):
    name = "gemini"

    def available(self):
        return bool(settings.gemini_key)

    def _url(self):
        return (
            "https://generativelanguage.googleapis.com/v1beta/"
            f"models/{settings.gemini_model}:generateContent"
        )

    async def _call(self, body: dict) -> str:
        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            response = await client.post(
                self._url(),
                json=body,
                headers={
                    "x-goog-api-key": settings.gemini_key
                },
            )

        if response.status_code != 200:
            raise ProviderError(
                f"Gemini {response.status_code}: "
                f"{response.text[:1000]}"
            )

        data = response.json()

        try:
            candidate = data["candidates"][0]
            parts = candidate["content"]["parts"]

            result = "".join(
                part.get("text", "")
                for part in parts
            ).strip()

            if not result:
                raise ProviderError(
                    f"Gemini returned empty content: {str(data)[:500]}"
                )

            return result

        except (KeyError, IndexError, TypeError) as error:
            raise ProviderError(
                f"Gemini returned an unexpected response: {str(data)[:1000]}"
            ) from error

    async def complete_json(self, system, messages):
        contents = [
            {
                "role": "user" if message["role"] == "user" else "model",
                "parts": [
                    {"text": message["content"]}
                ],
            }
            for message in messages
        ]

        body = {
            "systemInstruction": {
                "parts": [{"text": system}]
            },
            "contents": contents,
            "generationConfig": {
                "responseMimeType": "application/json",
                "temperature": 0.4,
            },
        }

        return await self._call(body)

    async def search_answer(self, query):
        body = {
            "systemInstruction": {
                "parts": [
                    {
                        "text": (
                            "You are DEC, a voice assistant. "
                            "Answer using Google Search results. "
                            "Give 2-4 short factual sentences. "
                            "Use current information. "
                            "Do not invent facts. "
                            "If information cannot be verified, say so. "
                            "No markdown, lists, or URLs."
                        )
                    }
                ]
            },
            "contents": [
                {
                    "role": "user",
                    "parts": [{"text": query}],
                }
            ],
            "tools": [
                {"google_search": {}}
            ],
        }

        return (await self._call(body)).strip()


# ---------------- GROQ ----------------

class Groq(Provider):
    name = "groq"

    def available(self):
        return bool(settings.groq_key)

    async def complete_json(self, system, messages):
        body = {
            "model": settings.groq_model,
            "messages": [
                {
                    "role": "system",
                    "content": (
                        system
                        + "\n\nIMPORTANT: Return exactly one valid JSON "
                        "object. Required fields: reply (string), "
                        "actions (array), search_query (string or null). "
                        "Do not include Markdown, code fences, or "
                        "explanations outside the JSON."
                    ),
                }
            ] + messages,
            "temperature": 0.4,
        }

        async with httpx.AsyncClient(timeout=TIMEOUT) as client:
            response = await client.post(
                "https://api.groq.com/openai/v1/chat/completions",
                json=body,
                headers={
                    "Authorization": f"Bearer {settings.groq_key}"
                },
            )

        if response.status_code != 200:
            raise ProviderError(
                f"Groq {response.status_code}: "
                f"{response.text[:1000]}"
            )

        try:
            data = response.json()
            content = data["choices"][0]["message"]["content"]

            if not content:
                raise ProviderError(
                    "Groq returned an empty response."
                )

            return content

        except (KeyError, IndexError, TypeError) as error:
            raise ProviderError(
                f"Groq returned an unexpected response: "
                f"{response.text[:1000]}"
            ) from error


# ---------------- PROVIDER REGISTRY ----------------

REGISTRY = {
    "gemini": Gemini(),
    "groq": Groq(),
}


def chain() -> list[Provider]:
    """Return configured providers in the requested fallback order."""

    providers = []

    for name in settings.providers:
        provider = REGISTRY.get(name)

        if provider and provider.available():
            providers.append(provider)

    return providers
    
