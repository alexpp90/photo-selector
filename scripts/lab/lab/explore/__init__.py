"""Exploratory interaction testing engine.

Provides live screen inspection, candidate interaction enumeration, seeded PRNG
action selection with categorical weighting, target device drivers, pre-action
structured expectations, deterministic layout/fs oracles, AI adapters, and verification.
"""

from __future__ import annotations

from .actions import (
    Action,
    ActionCategory,
    CandidateInteraction,
    ScreenState,
    categorize_action,
)
from .ai_adapters import (
    AIProvider,
    AntigravityAdapter,
    BaseAIProvider,
    ClaudeCLIAdapter,
    NoneAIProvider,
    NoneProvider,
    OfflineReviewIngest,
    PacketExportAdapter,
    auto_detect_ai_provider,
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
    DeviceDriver,
    DriverError,
    DriverExecutionError,
    DriverSettlingError,
    DriverTimeoutError,
    LinuxDeviceDriver,
    MacOSDeviceDriver,
    MockDeviceDriver,
)
from .expectations import (
    STANDARD_FORBIDDEN_UI_NODES,
    Expectation,
    ExpectationRecorder,
    record_cull_expectation,
    record_navigation_expectation,
    record_undo_expectation,
)
from .selector import (
    ActionSelector,
    EmptyCandidatesError,
    SelectionPolicy,
    SelectionRecord,
)
from .orchestrator import (
    DeviceState,
    ExploratoryOrchestrator,
    RunSummary,
    TargetConfig,
    WorkerResult,
    parse_duration_string,
)
from .verifier import (
    SEVERITY_BLOCKER,
    SEVERITY_MAJOR,
    SEVERITY_MINOR,
    SEVERITY_POLISH,
    InteractionVerifier,
    StepResult,
    compute_root_signature,
    normalize_product_for_verifier,
    verify_step,
)
from .reporter import (
    AggregatedFinding,
    ExploreReporter,
    deduplicate_findings,
    deduplicate_findings_with_counts,
    format_backlog_entry,
    generate_explore_report,
    is_signature_in_backlog,
    log_findings_to_canonical_backlog,
    resolve_canonical_backlog_path,
    sort_findings,
)

__all__ = [
    # Actions & State
    "Action",
    "ActionCategory",
    "CandidateInteraction",
    "ScreenState",
    "categorize_action",
    # Discovery
    "ScreenInspectionResult",
    "detect_active_modal",
    "identify_screen_mode",
    "inspect_screen",
    "load_discovery_rules",
    "normalize_product_name",
    # Selector
    "ActionSelector",
    "EmptyCandidatesError",
    "SelectionPolicy",
    "SelectionRecord",
    # Drivers & Errors
    "DeviceDriver",
    "AndroidDeviceDriver",
    "LinuxDeviceDriver",
    "MacOSDeviceDriver",
    "MockDeviceDriver",
    "DriverError",
    "DriverTimeoutError",
    "DriverExecutionError",
    "DriverSettlingError",
    "AppCrashedError",
    # Expectations (M2)
    "Expectation",
    "ExpectationRecorder",
    "STANDARD_FORBIDDEN_UI_NODES",
    "record_navigation_expectation",
    "record_cull_expectation",
    "record_undo_expectation",
    # AI Adapters (M2)
    "AIProvider",
    "BaseAIProvider",
    "NoneAIProvider",
    "NoneProvider",
    "ClaudeCLIAdapter",
    "AntigravityAdapter",
    "PacketExportAdapter",
    "OfflineReviewIngest",
    "create_ai_adapter",
    "auto_detect_ai_provider",
    # Verification & Oracles (M2)
    "StepResult",
    "InteractionVerifier",
    "verify_step",
    "compute_root_signature",
    "normalize_product_for_verifier",
    "SEVERITY_BLOCKER",
    "SEVERITY_MAJOR",
    "SEVERITY_MINOR",
    "SEVERITY_POLISH",
    # Orchestrator & CLI (M3)
    "ExploratoryOrchestrator",
    "parse_duration_string",
    "DeviceState",
    "TargetConfig",
    "WorkerResult",
    "RunSummary",
    # Deduplication & Reporting (M4)
    "AggregatedFinding",
    "ExploreReporter",
    "deduplicate_findings",
    "deduplicate_findings_with_counts",
    "sort_findings",
    "generate_explore_report",
    "log_findings_to_canonical_backlog",
    "format_backlog_entry",
    "resolve_canonical_backlog_path",
    "is_signature_in_backlog",
]
