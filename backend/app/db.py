"""SQLite usage log. Created automatically on startup.

NOTE: Render's free tier has an ephemeral disk, so this file may reset when
the service restarts. That is fine: it only holds monitoring data. The
user's memories live on the phone and are sent with each request.
"""
import sqlite3
import time
from contextlib import closing

from .config import settings


def _conn():
    return sqlite3.connect(settings.db_path, timeout=5)


def init():
    with closing(_conn()) as c:
        c.execute(
            "CREATE TABLE IF NOT EXISTS usage(ts REAL, session TEXT, provider TEXT, ms INTEGER, ok INTEGER, error TEXT)"
        )
        c.commit()


def log(session: str, provider: str, ms: int, ok: bool, error: str = ""):
    try:
        with closing(_conn()) as c:
            c.execute("INSERT INTO usage VALUES(?,?,?,?,?,?)", (time.time(), session, provider, ms, int(ok), error[:300]))
            c.commit()
    except Exception:
        pass


def stats():
    with closing(_conn()) as c:
        row = c.execute("SELECT COUNT(*), COALESCE(SUM(ok),0), COALESCE(AVG(ms),0) FROM usage").fetchone()
    return {"requests": row[0], "ok": row[1], "avg_ms": int(row[2])}
