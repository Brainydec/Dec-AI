
"""Model-provider abstraction with independent Tavily web search."""
import logging
import os

import httpx

from .config import settings

log = logging.getLogger("dec.providers")

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
        try:
            async with httpx.AsyncClient(timeout=TIMEOUT) as client:
                response = await client.post(
                    self._url(),
                    json=body,
                    headers={"x-goog-api-key": settings.gemini_key},
                )

            if response.status_code != 200:
                raise ProviderError(
                    f"Gemini {response.status_code}: {response.text[:500]}"
                )

            data = response.json()
            parts = data["candidates"][0]["content"]["parts"]
            return "".join(part.get("text", "") for part in parts)

        except (KeyError, IndexError, TypeError) as error:
            raise ProviderError("Gemini returned an unexpected response.") from error
        except httpx.HTTPError as error:
            raise ProviderError(f"Gemini connection error: {error}") from error

    async def complete_json(self, system, messages):
        contents = [
            {
                "role": "user" if message["role"] == "user" else "model",
                "parts": [{"text": message["content"]}],
            }
            for message in messages
        ]

        body = {
            "systemInstruction": {"parts": [{"text": system}]},
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
                "parts": [{
                    "text": (
                        "Answer as a voice assistant. Give 2-4 short, "
                        "accurate sentences. Use current information. "
                        "Do not use markdown."
                    )
                }]
            },
            "contents": [
                {"role": "user", "parts": [{"text": query}]}
            ],
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
            "messages": [
                {"role": "system", "content": system},
                *messages,
            ],
            "temperature": 0.4,
        }

        try:
            async with httpx.AsyncClient(timeout=TIMEOUT) as client:
                response = await client.post(
                    "https://api.groq.com/openai/v1/chat/completions",
                    json=body,
                    headers={
                        "Authorization": f"Bearer {settings.groq_key}"
                    },
                )

            # Groq's GPT-OSS models can emit an internal Harmony tool call
            # even though DEC uses prompt-described tools and sends no API tools.
            # Groq rejects that mismatch with tool_use_failed (HTTP 400).
            if response.status_code == 400:
                try:
                    error_data = response.json().get("error", {})
                except (ValueError, AttributeError):
                    error_data = {}
                fallback = os.environ.get(
                    "GROQ_FALLBACK_MODEL", "llama-3.3-70b-versatile"
                ).strip()
                if (
                    error_data.get("code") == "tool_use_failed"
                    and model.startswith("openai/gpt-oss")
                    and fallback
                    and fallback != model
                ):
                    retry_body = {**body, "model": fallback}
                    response = await client.post(
                        "https://api.groq.com/openai/v1/chat/completions",
                        json=retry_body,
                        headers={"Authorization": f"Bearer {settings.groq_key}"},
                    )

            if response.status_code != 200:
                raise ProviderError(
                    f"Groq {response.status_code}: {response.text[:500]}"
                )

            data = response.json()
            return data["choices"][0]["message"]["content"]

        except (KeyError, IndexError, TypeError) as error:
            raise ProviderError("Groq returned an unexpected response.") from error
        except httpx.HTTPError as error:
            raise ProviderError(f"Groq connection error: {error}") from error


class Tavily(Provider):
    """Independent live web-search provider."""

    name = "tavily"

    def available(self):
        return bool(settings.tavily_key)

    async def search_answer(self, query):
        if not self.available():
            raise ProviderError("Tavily API key is not configured.")

        body = {
            "api_key": settings.tavily_key,
            "query": query,
            "search_depth": "basic",
            "topic": "general",
            "max_results": 5,
            "include_answer": "advanced",
        }

        try:
            async with httpx.AsyncClient(timeout=TIMEOUT) as client:
                response = await client.post(
                    "https://api.tavily.com/search",
                    json=body,
                )

            if response.status_code != 200:
                raise ProviderError(
                    f"Tavily {response.status_code}: {response.text[:500]}"
                )

            data = response.json()
            answer = str(data.get("answer") or "").strip()

            results = data.get("results") or []
            sources = []

            for result in results[:3]:
                title = str(result.get("title") or "").strip()
                if title:
                    sources.append(title)

            if answer and sources:
                return answer + " Sources include: " + "; ".join(sources) + "."

            if answer:
                return answer

            summaries = [
                str(result.get("content") or "").strip()
                for result in results[:3]
                if result.get("content")
            ]

            if summaries:
                return " ".join(summaries)

            raise ProviderError("Tavily returned no useful search results.")

        except httpx.HTTPError as error:
            raise ProviderError(f"Tavily connection error: {error}") from error


REGISTRY = {
    "gemini": Gemini(),
    "groq": Groq(),
    "tavily": Tavily(),
}


def chain() -> list[Provider]:
    """Return configured AI conversation providers."""
    return [
        REGISTRY[name]
        for name in settings.providers
        if name in REGISTRY
        and name != "tavily"
        and REGISTRY[name].available()
    ]


async def web_search(query: str) -> str:
    """Use Tavily first, then Gemini Google Search as a fallback."""

    searchers = []

    if REGISTRY["tavily"].available():
        searchers.append(REGISTRY["tavily"])

    if REGISTRY["gemini"].available():
        searchers.append(REGISTRY["gemini"])

    if not searchers:
        raise ProviderError(
            "No web search provider configured. Add TAVILY_API_KEY."
        )

    errors = []

    for searcher in searchers:
        try:
            result = await searcher.search_answer(query)

            if result:
                return result

        except ProviderError as error:
            log.warning("%s web search failed: %s", searcher.name, error)
            errors.append(f"{searcher.name}: {error}")

    raise ProviderError("All web search providers failed. " + " | ".join(errors))
    
