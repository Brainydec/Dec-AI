import hmac
import logging

from fastapi import Depends, FastAPI, Header, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import ValidationError

from . import brain, db
from .config import settings
from .providers import ProviderError, chain
from .schemas import ChatRequest, ChatResponse

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("dec")

app = FastAPI(title="DEC AI backend", version="1.0.0", description="Brain for the DEC Android assistant.")
db.init()


def _token_ok(value: str) -> bool:
    if not settings.api_token:
        return False
    return hmac.compare_digest(value.encode(), f"Bearer {settings.api_token}".encode())


def require_auth(authorization: str = Header(default="")):
    if not settings.api_token:
        raise HTTPException(503, "Server has no DEC_API_TOKEN configured")
    if not _token_ok(authorization):
        raise HTTPException(401, "Unauthorized")


@app.get("/health")
def health():
    """Unauthenticated; used by Render and the Dockerfile HEALTHCHECK."""
    return {"status": "ok", "providers": [p.name for p in chain()], "token_configured": bool(settings.api_token)}


@app.post("/v1/chat", response_model=ChatResponse, dependencies=[Depends(require_auth)])
async def chat(req: ChatRequest):
    try:
        return await brain.chat(req)
    except ProviderError as e:
        log.error("chat failed: %s", e)
        raise HTTPException(502, str(e)) from e


@app.get("/v1/stats", dependencies=[Depends(require_auth)])
def stats():
    return db.stats()


@app.websocket("/ws")
async def ws(socket: WebSocket):
    """Same contract as /v1/chat. First message must be {"token": "..."}; then send ChatRequest JSON."""
    await socket.accept()
    try:
        hello = await socket.receive_json()
        if not _token_ok("Bearer " + str(hello.get("token", ""))):
            await socket.close(code=4401)
            return
        while True:
            payload = await socket.receive_json()
            try:
                resp = await brain.chat(ChatRequest(**payload))
                await socket.send_json(resp.model_dump())
            except (ValidationError, ProviderError) as e:
                await socket.send_json({"error": str(e)[:300]})
    except WebSocketDisconnect:
        pass
