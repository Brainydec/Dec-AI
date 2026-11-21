
import json
import logging
import re
import time

from . import db
from .providers import ProviderError, chain, web_search
from .schemas import Action, ChatRequest, ChatResponse

log = logging.getLogger("dec.brain")

TOOLS = """
flashlight {"on": true|false}
volume {"level": 0-100} OR {"direction": "up"|"down"}
brightness {"level": 0-100}
open_app {"name": "app name"}
set_alarm {"hour": 0-23, "minute": 0-59, "message": "label"}
set_timer {"seconds": integer, "message": "label"}
call {"contact": "name or phone number"}
answer_call {}
reject_call {}
send_sms {"to": "contact name or number", "body": "text"}
read_sms {"count": 1-5}
whatsapp {"to": "contact name or number", "body": "text"}
remember {"k": "short key", "v": "value"}
forget {"k": "key"}
"""

SYSTEM = """You are DEC (pronounced "Deck"), a personal voice assistant living on the user's Android phone.
Personality: {personality}
User's custom instructions: {custom}

You reply ONLY with one JSON object, no markdown fences:
{{"reply": "<what to say aloud>", "actions": [{{"tool": "<name>", "args": {{...}}}}], "search_query": null}}

Rules:
- "reply" is spoken aloud: 1-3 short sentences, plain text, no markdown, no emoji.
- Use "actions" only for the tools listed below. Never invent tools.
- For phone calls, SMS and WhatsApp, request the action. The phone asks the user for approval.
- Never invent phone numbers. Pass the contact name as spoken.
- If information is missing, ask a short question and use no actions.
- For news, current events, prices, scores, weather or changing information, set "search_query" to a useful web search question and leave actions empty.
- Do not invent search results. The backend will perform the search.
- Reminders: use set_alarm for a time of day or set_timer for a duration.
- An alarm can only ring within the next 24 hours. Ask for a time if none is provided.
- Current device info: {device}
- Known facts about the user: {memories}

Phone tools:
{tools}"""


def _device_str(device) -> str:
    bits = []

    if device.time_iso:
        bits.append(f"local time {device.time_iso}")

    if device.timezone:
        bits.append(f"timezone {device.timezone}")

    if device.battery_percent is not None:
        charging = " (charging)" if device.charging else ""
        bits.append(f"battery {device.battery_percent}%{charging}")

    if device.network:
        bits.append(f"network {device.network}")

    return ", ".join(bits) or "unknown"


def build_system(req: ChatRequest) -> str:
    memories = "; ".join(
        f"{memory.k}: {memory.v}" for memory in req.memories
    ) or "none"

    return SYSTEM.format(
        personality=req.personality,
        custom=req.custom_instructions or "none",
        device=_device_str(req.device),
        memories=memories,
        tools=TOOLS,
    )


def parse_model_output(raw: str) -> dict:
    """Parse model JSON, with a fallback for imperfect model responses."""

    text = raw.strip()
    text = re.sub(
        r"^```(?:json)?|```$",
        "",
        text,
        flags=re.MULTILINE,
    ).strip()

    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)

        try:
            data = json.loads(match.group(0)) if match else None
        except json.JSONDecodeError:
            data = None

    if not isinstance(data, dict):
        return {
            "reply": text[:500],
            "actions": [],
            "search_query": None,
        }

    return data


KNOWN_TOOLS = {
    "flashlight",
    "volume",
    "brightness",
    "open_app",
    "set_alarm",
    "set_timer",
    "call",
    "answer_call",
    "reject_call",
    "send_sms",
    "read_sms",
    "whatsapp",
    "remember",
    "forget",
}


def clean_actions(data: dict) -> list[Action]:
    actions = []

    for action in data.get("actions") or []:
        if (
            isinstance(action, dict)
            and action.get("tool") in KNOWN_TOOLS
            and isinstance(action.get("args", {}), dict)
        ):
            actions.append(
                Action(
                    tool=action["tool"],
                    args=action.get("args", {}),
                )
            )

    return actions[:6]


async def chat(req: ChatRequest) -> ChatResponse:
    providers = chain()

    if not providers:
        raise ProviderError(
            "No AI provider configured. Set GROQ_API_KEY or GEMINI_API_KEY."
        )

    system = build_system(req)

    messages = [
        {
            "role": item.role
            if item.role in ("user", "assistant")
            else "user",
            "content": item.content,
        }
        for item in req.history[-8:]
    ]

    messages.append({
        "role": "user",
        "content": req.text,
    })

    last_error = None

    for provider in providers:
        started = time.time()

        try:
            raw = await provider.complete_json(system, messages)
            data = parse_model_output(raw)

            reply = str(data.get("reply") or "").strip()
            actions = clean_actions(data)
            query = data.get("search_query")

            if query:
                try:
                    reply = await web_search(str(query))
                except ProviderError as error:
                    log.error("Web search failed: %s", error)
                    reply = (
                        "I couldn't access live web search right now. "
                        "Please try again later."
                    )

                actions = []

            elapsed = int((time.time() - started) * 1000)

            db.log(
                req.session_id,
                provider.name,
                elapsed,
                True,
            )

            return ChatResponse(
                reply=reply or "Okay.",
                actions=actions,
                provider=provider.name,
            )

        except Exception as error:
            last_error = error

            elapsed = int((time.time() - started) * 1000)

            log.exception(
                "Provider %s failed",
                provider.name,
            )

            db.log(
                req.session_id,
                provider.name,
                elapsed,
                False,
                str(error),
            )

    raise ProviderError(f"All providers failed: {last_error}")
            
