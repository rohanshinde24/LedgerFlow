from __future__ import annotations

from typing import Sequence

from .base import Conversation, ModelReply, ToolSpec


class ScriptedModelClient:
    """Replays a fixed list of replies. Lets the agent loop, capability dispatch, and proposal
    validation be tested deterministically without a network call or an API key."""

    def __init__(self, replies: Sequence[ModelReply], name: str = "scripted") -> None:
        self._replies = list(replies)
        self._name = name
        self.calls: list[Conversation] = []

    @property
    def name(self) -> str:
        return self._name

    async def complete(self, conversation: Conversation, tools: Sequence[ToolSpec]) -> ModelReply:
        self.calls.append(conversation)
        if not self._replies:
            raise AssertionError("ScriptedModelClient ran out of replies")
        return self._replies.pop(0)
