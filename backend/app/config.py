
import os


class Settings:
    """All secrets come from environment variables."""

    api_token = os.environ.get("DEC_API_TOKEN", "")

    gemini_key = os.environ.get("GEMINI_API_KEY", "")
    groq_key = os.environ.get("GROQ_API_KEY", "")
    tavily_key = os.environ.get("TAVILY_API_KEY", "")

    providers = [
        provider.strip()
        for provider in os.environ.get(
            "DEC_PROVIDERS", "groq,gemini"
        ).split(",")
        if provider.strip()
    ]

    gemini_model = os.environ.get(
        "GEMINI_MODEL", "gemini-2.5-flash"
    )

    groq_model = os.environ.get(
        "GROQ_MODEL", "openai/gpt-oss-20b"
    )

    db_path = os.environ.get("DEC_DB_PATH", "/tmp/dec.db")


settings = Settings()
