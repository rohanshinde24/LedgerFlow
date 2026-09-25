from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="LEDGERFLOW_", extra="ignore")

    financial_core_url: str = "http://localhost:8080"
    core_timeout_seconds: float = 15.0

    model_provider: str = "ollama"
    model_name: str = "claude-sonnet-4-6"
    model_max_tokens: int = 2048
    ollama_model: str = "qwen2.5:7b"
    ollama_base_url: str = "http://localhost:11434"
    #: Deliberately tight. Exceeding it escalates to a human rather than failing, so a slow model is
    #: a routing decision and not an outage. Waiting longer buys an answer nobody is still waiting
    #: for.
    model_timeout_seconds: float = 180.0
    #: Greedy decoding with a fixed seed. A categorisation that changes when nothing about the
    #: transaction changed is not a judgement, and an evaluation that cannot separate a policy change
    #: from sampling noise measures nothing.
    model_temperature: float = 0.0
    model_seed: int = 20250311
    anthropic_api_key: str | None = Field(
        default=None,
        validation_alias=AliasChoices("LEDGERFLOW_ANTHROPIC_API_KEY", "ANTHROPIC_API_KEY"),
    )

    max_agent_iterations: int = 6
    max_transaction_iterations: int = 8


def load_settings() -> Settings:
    return Settings()
