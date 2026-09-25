from __future__ import annotations

import json
from typing import Any, Sequence

import httpx

from .base import Conversation, ModelReply, ModelUnavailable, ToolCall, ToolSpec


class OllamaModelClient:
    """Local models via Ollama's OpenAI-compatible endpoint.

    The model must ship a chat template with a tools section; Ollama rejects tool requests outright
    for models that lack one (original llama3, gemma2, gemma3).
    """

    def __init__(self, model_name: str, base_url: str = "http://localhost:11434",
                 max_tokens: int = 2048, timeout_seconds: float = 180.0,
                 temperature: float = 0.0, seed: int = 20250311) -> None:
        self._model_name = model_name
        self._max_tokens = max_tokens
        self._temperature = temperature
        self._seed = seed
        self._http = httpx.AsyncClient(base_url=base_url.rstrip("/"), timeout=timeout_seconds)

    @property
    def name(self) -> str:
        return f"ollama/{self._model_name}"

    async def complete(self, conversation: Conversation, tools: Sequence[ToolSpec]) -> ModelReply:
        try:
            response = await self._http.post("/v1/chat/completions", json={
                "model": self._model_name,
                "max_tokens": self._max_tokens,
                "temperature": self._temperature,
                "seed": self._seed,
                "messages": _render(conversation),
                "tools": [{"type": "function",
                           "function": {"name": tool.name, "description": tool.description,
                                        "parameters": tool.input_schema}} for tool in tools],
            })
        except httpx.HTTPError as exc:
            raise ModelUnavailable(f"ollama request failed: {exc}") from exc
        if response.status_code >= 400:
            raise ModelUnavailable(f"ollama responded {response.status_code}: {response.text}")

        try:
            message = response.json()["choices"][0]["message"]
        except (json.JSONDecodeError, KeyError, IndexError) as exc:
            raise ModelUnavailable(f"ollama returned an unreadable reply: {exc}") from exc
        tool_calls = tuple(
            ToolCall(id=call.get("id") or f"call_{index}",
                     name=call["function"]["name"],
                     arguments=_decode_arguments(call["function"].get("arguments")))
            for index, call in enumerate(message.get("tool_calls") or [])
        )
        usage = response.json().get("usage") or {}
        return ModelReply(text=message.get("content") or "", tool_calls=tool_calls,
                          input_tokens=int(usage.get("prompt_tokens") or 0),
                          output_tokens=int(usage.get("completion_tokens") or 0))


def _decode_arguments(raw: Any) -> dict[str, Any]:
    # Ollama returns arguments as an object for some models and a JSON string for others.
    if isinstance(raw, dict):
        return raw
    if isinstance(raw, str) and raw.strip():
        try:
            return json.loads(raw)
        except json.JSONDecodeError:
            return {}
    return {}


def _render(conversation: Conversation) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = [{"role": "system", "content": conversation.system}]
    for turn in conversation.turns:
        if turn["role"] == "user":
            messages.append({"role": "user", "content": turn["text"]})
        elif turn["role"] == "assistant":
            reply: ModelReply = turn["reply"]
            message: dict[str, Any] = {"role": "assistant", "content": reply.text}
            if reply.tool_calls:
                message["tool_calls"] = [
                    {"id": call.id, "type": "function",
                     "function": {"name": call.name, "arguments": json.dumps(call.arguments)}}
                    for call in reply.tool_calls
                ]
            messages.append(message)
        else:
            messages.extend({"role": "tool", "tool_call_id": result.call_id,
                             "content": result.content} for result in turn["results"])
    return messages
