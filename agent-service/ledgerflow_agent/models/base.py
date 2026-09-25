from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Protocol, Sequence


class ModelUnavailable(Exception):
    """No reply could be obtained from the model: it timed out, refused, or was unreachable.

    This is a tier-3 condition, not an error to surface. An event the model cannot settle is an
    event for a human, and that is the same answer the loop already gives when reasoning runs out.
    """


@dataclass(frozen=True)
class ToolCall:
    id: str
    name: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class ToolResult:
    call_id: str
    content: str


@dataclass(frozen=True)
class ModelReply:
    text: str
    tool_calls: tuple[ToolCall, ...] = ()
    #: Reported by the provider, not counted locally. Zero means the provider did not say.
    input_tokens: int = 0
    output_tokens: int = 0


@dataclass
class ToolSpec:
    name: str
    description: str
    input_schema: dict[str, Any]


@dataclass
class Conversation:
    """Provider-neutral transcript. Each provider adapter renders this into its own wire format."""

    system: str
    turns: list[Any] = field(default_factory=list)

    def add_user(self, text: str) -> None:
        self.turns.append({"role": "user", "text": text})

    def add_assistant(self, reply: ModelReply) -> None:
        self.turns.append({"role": "assistant", "reply": reply})

    def add_tool_results(self, results: Sequence[ToolResult]) -> None:
        self.turns.append({"role": "tool", "results": list(results)})


class ModelClient(Protocol):
    """Every model provider implements exactly this."""

    @property
    def name(self) -> str: ...

    async def complete(self, conversation: Conversation, tools: Sequence[ToolSpec]) -> ModelReply: ...
