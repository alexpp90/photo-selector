"""Exploratory interaction testing (`lab.py explore`).

Drives the live app on a device (or the mock), picks interactions with a seeded selector,
records what each should do before it runs, and checks the result with the lab's oracles and an
optional AI judge. Everything a run writes stays in its run directory.
"""

from __future__ import annotations

from .actions import Action, ActionCategory, CandidateInteraction, ScreenState, categorize_action
from .ai_adapters import (
    AIJudgeError,
    AIProvider,
    CliJudge,
    JudgeContext,
    Judgement,
    NoneProvider,
    PacketExportAdapter,
    create_ai_adapter,
)
from .discovery import (
    ScreenInspectionResult,
    detect_active_modal,
    identify_screen_mode,
    inspect_screen,
    load_discovery_rules,
    normalize_product_name,
)
from .driver import (
    AndroidDeviceDriver,
    AppCrashedError,
    AppLeftError,
    DeviceDriver,
    DriverError,
    DriverExecutionError,
    DriverSettlingError,
    DriverTimeoutError,
    LinuxDeviceDriver,
    MacOSDeviceDriver,
    MockDeviceDriver,
)
from .expectations import STANDARD_FORBIDDEN_UI_NODES, Expectation, ExpectationRecorder, read_counter
from .orchestrator import (
    ExploratoryOrchestrator,
    NoTargetError,
    RunSummary,
    TargetConfig,
    WorkerResult,
    group_targets,
    parse_duration_string,
)
from .reporter import (
    AggregatedFinding,
    compute_root_signature,
    deduplicate_findings,
    generate_explore_report,
    sort_findings,
)
from .selector import ActionSelector, EmptyCandidatesError, SelectionPolicy, SelectionRecord
from .verifier import InteractionVerifier, StepResult, normalize_product_for_verifier, verify_step

__all__ = [
    "Action", "ActionCategory", "CandidateInteraction", "ScreenState", "categorize_action",
    "ScreenInspectionResult", "detect_active_modal", "identify_screen_mode", "inspect_screen",
    "load_discovery_rules", "normalize_product_name",
    "ActionSelector", "EmptyCandidatesError", "SelectionPolicy", "SelectionRecord",
    "DeviceDriver", "AndroidDeviceDriver", "LinuxDeviceDriver", "MacOSDeviceDriver", "MockDeviceDriver",
    "DriverError", "DriverTimeoutError", "DriverExecutionError", "DriverSettlingError",
    "AppCrashedError", "AppLeftError",
    "Expectation", "ExpectationRecorder", "STANDARD_FORBIDDEN_UI_NODES", "read_counter",
    "AIProvider", "NoneProvider", "PacketExportAdapter", "CliJudge", "AIJudgeError",
    "JudgeContext", "Judgement", "create_ai_adapter",
    "StepResult", "InteractionVerifier", "verify_step", "normalize_product_for_verifier",
    "ExploratoryOrchestrator", "NoTargetError", "TargetConfig", "WorkerResult", "RunSummary",
    "group_targets", "parse_duration_string",
    "AggregatedFinding", "compute_root_signature", "deduplicate_findings", "generate_explore_report",
    "sort_findings",
]
