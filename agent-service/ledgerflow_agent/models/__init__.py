from ..config import Settings
from .base import (
    Conversation,
    ModelClient,
    ModelReply,
    ModelUnavailable,
    ToolCall,
    ToolResult,
    ToolSpec,
)
from .scripted import ScriptedModelClient

__all__ = [
    "Conversation",
    "ModelClient",
    "ModelReply",
    "ModelUnavailable",
    "ScriptedModelClient",
    "ToolCall",
    "ToolResult",
    "ToolSpec",
    "build_model_client",
]


def build_model_client(settings: Settings) -> ModelClient:
    if settings.model_provider == "anthropic":
        from .anthropic_client import AnthropicModelClient

        return AnthropicModelClient(settings.model_name, settings.anthropic_api_key,
                                    settings.model_max_tokens, settings.model_temperature)
    if settings.model_provider == "ollama":
        from .ollama_client import OllamaModelClient

        return OllamaModelClient(settings.ollama_model, settings.ollama_base_url,
                                 settings.model_max_tokens, settings.model_timeout_seconds,
                                 settings.model_temperature, settings.model_seed)
    raise ValueError(f"unsupported model provider: {settings.model_provider}")
