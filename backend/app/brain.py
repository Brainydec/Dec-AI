
import json
import logging
import re
import time

from . import db
from .providers import ProviderError, chain, REGISTRY
from .schemas import Action, ChatRequest, ChatResponse

log = logging.getLogger("dec.brain")

# Tools the PHONE can execute. The phone enforces permissions and confirmations itself.
TOOLS = """
flashlight {"on": true|false}
volume {"level": 0-100} OR {"direction": "up"|"down"}
brightness {"level": 0-100}
open_app {"name": "app name, e.g. chrome, camera, youtube. Use 'browser' for the web browser"}
set_alarm {"hour": 0-23, "minute": 0-59, "message": "label"}
set_timer {"seconds": integer, "message": "label"}
call {"contact": "name exactly as the user said it, or a phone number"}
answer_call {}
reject_call {}
send_sms {"to": "contact name or number", "body": "text"}
read_sms {"count": 1-5}
whatsapp {"to": "contact name or number", "body": "text"}
remember {"k": "short key", "v": "value"} (only when the user asks you to remember something)
forget {"k": "key"}
"""

SYSTEM = """You are DEC (pronounced "Deck"), a personal voice assistant living on the user's Android phone.
Personality: {personality}
User's custom instructions: {custom}

You reply ONLY with one JSON object, no markdown fences:
{{"reply": "Hello!", "actions": [], "search_query": null}}

Rules:
- "reply" is spoken aloud: 1-3 short sentences, plain text, no markdown, no emoji.
- Use "actions" only for the tools listed below. Never invent tools. Several actions are allowed in order.
- When the user explicitly asks you to call someone, you MUST return a call action with {"tool": "call", "args": {"contact": "the contact name exactly as spoken"}}. Do not only say that you are calling.
- For phone calls, SMS and WhatsApp you only REQUEST the action. The phone asks the user for approval, so say things like "Calling your brother." not "Done".
- Never invent phone numbers. Pass the contact name as spoken.
- If you need missing info (message text, who to call, what time), ask a short question and use no actions.
- For news, current events, prices, scores, weather or anything that changes over time, set "search_query" to a good web search question and leave actions empty; the reply will be filled in from the search.
- Reminders: use set_alarm (time of day) or set_timer (duration). An alarm can only ring within the next 24 hours; for later reminders say so. If the user gave no time, ask for one.
- Current device info: {device}
- Known facts about the user: {memories}

Phone tools:
{tools}"""


def _device_str(d) -> str:
    bits = []
    if d.time_iso:
        bits.append(f"local time {d.time_iso}")
    if d.timezone:
        bits.append(f"timezone {d.timezone}")
    if d.battery_percent is not None:
        bits.append(
            f"battery {d.battery_percent}%"
            + (" (charging)" if d.charging else "")
        )
    if d.network:
        bits.append(f"network {d.network}")
    return ", ".join(bits) or "unknown"


def build_system(req: ChatRequest) -> str:
    mem = "; ".join(f"{m.k}: {m.v}" for m in req.memories) or "none"
    return SYSTEM.format(
        personality=req.personality,
        custom=req.custom_instructions or "none",
        device=_device_str(req.device),
        memories=mem,
        tools=TOOLS,
    )


def parse_model_output(raw: str) -> dict:
    """Tolerant JSON parsing; falls back to treating the text as the reply."""
    text = raw.strip()
    text = re.sub(r"^```(?:json)?|```$", "", text, flags=re.MULTILINE).strip()

    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", text, flags=re.DOTALL)
        try:
            data = json.loads(match.group(0)) if match else None
        except json.JSONDecodeError:
            data = None

    if not isinstance(data, dict):
        return {"reply": text[:500], "actions": [], "search_query": None}

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
    out = []

    for action in data.get("actions") or []:
        if (
            isinstance(action, dict)
            and action.get("tool") in KNOWN_TOOLS
            and isinstance(action.get("args", {}), dict)
        ):
            out.append(
                Action(
                    tool=action["tool"],
                    args=action.get("args", {}),
                )
            )

    return out[:6]


def force_call_action(
    text: str,
    actions: list[Action],
    reply: str,
):
    """Ensure explicit call requests produce a phone call action."""

    # Do not initiate a call when the user refuses or cancels.
    if re.search(
        r"\b(?:don't|do not|never|cancel|stop)\s+(?:call|phone|dial)\b",
        text,
        re.I,
    ):
        return actions, reply

    # Do not add a duplicate call action.
    if any(action.tool == "call" for action in actions):
        return actions, reply

    # Recognise direct requests such as:
    # "Call John", "Please call John", "Dial John now".
    match = re.search(
        r"\b(?:please\s+)?(?:call|phone|dial)\s+(.+?)"
        r"(?:\s+(?:right now|now|please|for me))?[?.!,]*$",
        text.strip(),
        re.I,
    )

    if not match:
        match = re.search(
            r"\b(?:make|place)\s+(?:a\s+)?call\s+to\s+(.+?)[?.!,]*$",
            text.strip(),
            re.I,
        )

    if not match:
        return actions, reply

    contact = match.group(1).strip()

    if not contact or contact.lower() in {
        "me",
        "myself",
        "someone",
        "anyone",
    }:
        return actions, reply

    actions.append(
        Action(
            tool="call",
            args={"contact": contact},
        )
    )

    return actions, f"Calling {contact}."


async def chat(req: ChatRequest) -> ChatResponse:
    providers = chain()

    if not providers:
        raise ProviderError(
            "No AI provider configured. Set GEMINI_API_KEY and/or GROQ_API_KEY."
        )

    system = build_system(req)

    messages = [
        {
            "role": t.role if t.role in ("user", "assistant") else "user",
            "content": t.content,
        }
        for t in req.history[-8:]
    ]

    messages.append(
        {
            "role": "user",
            "content": req.text,
        }
    )

    last_err = None

    for provider in providers:
        start_time = time.time()

        try:
            raw = await provider.complete_json(system, messages)
            data = parse_model_output(raw)

            reply = str(data.get("reply") or "").strip()
            actions = clean_actions(data)

            # Guarantee a call action for direct call requests.
            actions, reply = force_call_action(
                req.text,
                actions,
                reply,
            )

            query = data.get("search_query")

            if query:
                answer = None

                searcher = (
                    provider
                    if provider.name == "gemini"
                    else REGISTRY["gemini"]
                    if REGISTRY["gemini"].available()
                    else None
                )

                if searcher:
                    try:
                        answer = await searcher.search_answer(str(query))
                    except ProviderError as error:
                        log.warning("search failed: %s", error)

                reply = (
                    answer
                    or "I couldn't reach the web search right now, so I can't check that."
                )
                actions = []

            db.log(
                req.session_id,
                provider.name,
                int((time.time() - start_time) * 1000),
                True,
            )

            return ChatResponse(
                reply=reply or "Okay.",
                actions=actions,
                provider=provider.name,
            )

        except (ProviderError, Exception) as error:
            last_err = error

            log.error(
                "provider %s failed: %s",
                provider.name,
                error,
            )

            db.log(
                req.session_id,
                provider.name,
                int((time.time() - start_time) * 1000),
                False,
                str(error),
            )

    raise ProviderError(f"All providers failed: {last_err}")
            
