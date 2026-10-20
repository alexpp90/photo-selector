"""Normalised UI tree and finding data models."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from typing import Any


@dataclass
class Node:
    """Single element in a normalised UI tree."""

    id: str
    parent: str | None
    role: str
    name: str
    bounds: list[float]
    interactive: bool
    visible: bool
    overlay: bool = False
    text: str = ""
    clipped: bool | None = None
    # The node scrolls its content (Android `scrollable`); its children may be cut at its edges.
    scrollable: bool = False

    @property
    def x(self) -> float:
        return float(self.bounds[0])

    @property
    def y(self) -> float:
        return float(self.bounds[1])

    @property
    def w(self) -> float:
        return float(self.bounds[2])

    @property
    def h(self) -> float:
        return float(self.bounds[3])

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Node:
        return cls(
            id=str(data["id"]),
            parent=data.get("parent"),
            role=str(data["role"]),
            name=str(data.get("name", "")),
            bounds=[float(v) for v in data["bounds"]],
            interactive=bool(data["interactive"]),
            visible=bool(data["visible"]),
            overlay=bool(data.get("overlay", False)),
            text=str(data.get("text", "")),
            clipped=data.get("clipped"),
            scrollable=bool(data.get("scrollable", False)),
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "parent": self.parent,
            "role": self.role,
            "name": self.name,
            "bounds": [
                int(v) if float(v).is_integer() else round(float(v), 2)
                for v in self.bounds
            ],
            "interactive": self.interactive,
            "visible": self.visible,
            "overlay": self.overlay,
            "text": self.text,
            "clipped": self.clipped,
            "scrollable": self.scrollable,
        }


@dataclass
class Tree:
    """Normalised checkpoint UI tree across Android, macOS, and Linux."""

    platform: str
    window: dict[str, Any]
    nodes: list[Node] = field(default_factory=list)

    @property
    def window_w(self) -> float:
        return float(self.window["w"])

    @property
    def window_h(self) -> float:
        return float(self.window["h"])

    @property
    def unit(self) -> str:
        return str(self.window.get("unit", "px"))

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Tree:
        return cls(
            platform=str(data["platform"]),
            window=dict(data["window"]),
            nodes=[Node.from_dict(n) for n in data.get("nodes", [])],
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "platform": self.platform,
            "window": dict(self.window),
            "nodes": [n.to_dict() for n in self.nodes],
        }


@dataclass
class Finding:
    """A deterministic or AI-reported finding for a checkpoint."""

    id: str
    source: str
    product: str
    config: str
    journey: str
    checkpoint: str
    rule: str
    severity: str
    confidence: str
    region: list[float] | None
    message: str
    evidence: list[str] = field(default_factory=list)
    new_since_last_run: bool = True

    @staticmethod
    def make_id(
        product: str,
        journey: str,
        checkpoint: str,
        rule: str,
        subject: list[str] | tuple[str, ...] | list[float] | None = None,
    ) -> str:
        """Compute stable 12-hex-char SHA-1 id (config- and date-independent)."""
        if subject is None:
            subj_str = ""
        elif all(isinstance(x, str) for x in subject):
            subj_str = ",".join(sorted(str(x) for x in subject))
        else:
            subj_str = ",".join(str(int(round(float(x), -1))) for x in subject)
        raw = f"{product}|{journey}|{checkpoint}|{rule}|{subj_str}"
        return hashlib.sha1(raw.encode("utf-8")).hexdigest()[:12]

    @classmethod
    def from_dict(cls, data: dict[str, Any]) -> Finding:
        reg = data.get("region")
        return cls(
            id=str(data["id"]),
            source=str(data["source"]),
            product=str(data["product"]),
            config=str(data["config"]),
            journey=str(data["journey"]),
            checkpoint=str(data["checkpoint"]),
            rule=str(data["rule"]),
            severity=str(data["severity"]),
            confidence=str(data["confidence"]),
            region=[float(v) for v in reg] if isinstance(reg, list) else None,
            message=str(data["message"]),
            evidence=[str(e) for e in data.get("evidence", [])],
            new_since_last_run=bool(data.get("new_since_last_run", True)),
        )

    def to_dict(self) -> dict[str, Any]:
        reg_out: list[int | float] | None = None
        if self.region is not None:
            reg_out = [
                int(v) if float(v).is_integer() else round(float(v), 2)
                for v in self.region
            ]
        return {
            "id": self.id,
            "source": self.source,
            "product": self.product,
            "config": self.config,
            "journey": self.journey,
            "checkpoint": self.checkpoint,
            "rule": self.rule,
            "severity": self.severity,
            "confidence": self.confidence,
            "region": reg_out,
            "message": self.message,
            "evidence": list(self.evidence),
            "new_since_last_run": self.new_since_last_run,
        }
