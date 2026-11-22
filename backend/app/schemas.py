from typing import Any, Literal, Optional
from pydantic import BaseModel, Field


class Turn(BaseModel):
    role: str  # "user" or "assistant"
    content: str


class Memory(BaseModel):
    k: str
    v: str


class DeviceContext(BaseModel):
    time_iso: Optional[str] = None
    timezone: Optional[str] = None
    battery_percent: Optional[int] = None
    charging: Optional[bool] = None
    network: Optional[str] = None
    accessibility_connected: bool = False
    screen_text: str = Field(default="", max_length=5000)


class ChatRequest(BaseModel):
    text: str = Field(min_length=1, max_length=2000)
    session_id: str = "default"
    history: list[Turn] = []
    memories: list[Memory] = []
    personality: str = "friendly, concise, a little witty"
    custom_instructions: str = ""
    response_mode: Literal["voice", "text"] = "voice"
    device: DeviceContext = DeviceContext()


class Action(BaseModel):
    tool: str
    args: dict[str, Any] = {}


class ChatResponse(BaseModel):
    reply: str
    actions: list[Action] = []
    provider: str = ""
    more: bool = False
