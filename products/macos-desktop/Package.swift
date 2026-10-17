// swift-tools-version: 6.0
import Foundation
import PackageDescription

// UI test hooks (`--ui-scenario`, `--ui-self-test`, `--screenshot`) drive the app and can trash
// files, so they are compiled only into debug builds and into builds made for the lab and the
// smoke test (`PST_TEST_HOOKS=1`, see scripts/build_app.sh --test-hooks) — never into a release.
let testHookSettings: [SwiftSetting] = ProcessInfo.processInfo.environment["PST_TEST_HOOKS"] == "1"
    ? [.define("PST_TEST_HOOKS")]
    : [.define("PST_TEST_HOOKS", .when(configuration: .debug))]

let package = Package(
    name: "PhotoSelector",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "PhotoSelectorKit",
            targets: ["PhotoSelectorKit"]
        ),
        .executable(
            name: "PhotoSelectorApp",
            targets: ["PhotoSelectorApp"]
        )
    ],
    dependencies: [],
    targets: [
        // Headless Domain & Infrastructure Framework
        .target(
            name: "PhotoSelectorKit",
            dependencies: [],
            path: "src/PhotoSelectorKit",
            linkerSettings: [
                .unsafeFlags(["-Xlinker", "-w"])
            ]
        ),
        // Native SwiftUI macOS Executable
        .executableTarget(
            name: "PhotoSelectorApp",
            dependencies: [
                "PhotoSelectorKit"
            ],
            path: "src/PhotoSelectorApp",
            swiftSettings: testHookSettings,
            linkerSettings: [
                .unsafeFlags(["-Xlinker", "-w"])
            ]
        ),
        // Automated Unit Tests
        .testTarget(
            name: "PhotoSelectorKitTests",
            dependencies: [
                "PhotoSelectorKit"
            ],
            path: "tests/unit",
            linkerSettings: [
                .unsafeFlags(["-Xlinker", "-w"])
            ]
        )
    ]
)
