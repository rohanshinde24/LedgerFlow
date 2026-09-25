from __future__ import annotations

from typing import Any, Sequence

from anthropic import AnthropicError, AsyncAnthropic

from .base import Conversation, ModelReply, ModelUnavailable, ToolCall, ToolSpec


class AnthropicModelClient:
    def __init__(self, model_name: str, api_key: str | None, max_tokens: int = 2048,
                 temperature: float = 0.0) -> None:
        self._model_name = model_name
        self._max_tokens = max_tokens
        self._temperature = temperature
        self._client = AsyncAnthropic(api_key=api_key) if api_key else AsyncAnthropic()

    @property
    def name(self) -> str:
        return self._model_name

    async def complete(self, conversation: Conversation, tools: Sequence[ToolSpec]) -> ModelReply:
        try:
            response = await self._client.messages.create(
                model=self._model_name,
                max_tokens=self._max_tokens,
                temperature=self._temperature,
                system=conversation.system,
                tools=[{"name": tool.name, "description": tool.description,
                        "input_schema": tool.input_schema} for tool in tools],
                messages=_render(conversation),
            )
        except AnthropicError as exc:
            raise ModelUnavailable(f"anthropic request failed: {exc}") from exc

        text_parts: list[str] = []
        tool_calls: list[ToolCall] = []
        for block in response.content:
            if block.type == "text":
                text_parts.append(block.text)
            elif block.type == "tool_use":
                tool_calls.append(ToolCall(id=block.id, name=block.name, arguments=dict(block.input)))
        return ModelReply(text="\n".join(text_parts), tool_calls=tuple(tool_calls),
                          input_tokens=response.usage.input_tokens,
                          output_tokens=response.usage.output_tokens)


def _render(conversation: Conversation) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []
    for turn in conversation.turns:
        if turn["role"] == "user":
            messages.append({"role": "user", "content": turn["text"]})
        elif turn["role"] == "assistant":
            reply: ModelReply = turn["reply"]
            content: list[dict[str, Any]] = []
            if reply.text:
                content.append({"type": "text", "text": reply.text})
            for call in reply.tool_calls:
                content.append({"type": "tool_use", "id": call.id, "name": call.name,
                                "input": call.arguments})
            messages.append({"role": "assistant", "content": content})
        else:
            messages.append({"role": "user", "content": [
                {"type": "tool_result", "tool_use_id": result.call_id, "content": result.content}
                for result in turn["results"]
            ]})
    return messages
