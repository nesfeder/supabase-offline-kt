// swift-tools-version: 6.0
// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import PackageDescription

/// The release this manifest resolves the core framework from; set by the release workflow.
let version = "0.0.0"

/// The checksum of that release's framework archive; set by the release workflow with `version`.
let checksum = "0000000000000000000000000000000000000000000000000000000000000000"

/// The core framework: a local build when `SUPABASE_OFFLINE_LOCAL_CORE=1` (CONTRIBUTING.md,
/// "The Swift package"), otherwise the release's archive.
let core: Target =
    Context.environment["SUPABASE_OFFLINE_LOCAL_CORE"] == "1"
    ? .binaryTarget(name: "SupabaseOfflineCore", path: "LocalCore/SupabaseOfflineCore.xcframework")
    : .binaryTarget(
        name: "SupabaseOfflineCore",
        url: "https://github.com/nesfeder/supabase-offline-swift/releases/download/\(version)/SupabaseOfflineCore.xcframework.zip",
        checksum: checksum
    )

/// Swift 6, and imports internal unless a file says otherwise, so no public signature can name a
/// type of the core framework.
let swiftSettings: [SwiftSetting] = [
    .swiftLanguageMode(.v6),
    .enableUpcomingFeature("InternalImportsByDefault"),
]

let package = Package(
    name: "SupabaseOffline",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "SupabaseOffline", targets: ["SupabaseOffline"]),
        .library(name: "SupabaseOfflineTesting", targets: ["SupabaseOfflineTesting"]),
    ],
    targets: [
        core,
        .target(
            name: "SupabaseOffline",
            dependencies: ["SupabaseOfflineCore"],
            swiftSettings: swiftSettings,
            // The core framework is static: Apple's SQLite and the C++ runtime come from the app's link.
            linkerSettings: [.linkedLibrary("sqlite3"), .linkedLibrary("c++")]
        ),
        .target(
            name: "SupabaseOfflineTesting",
            dependencies: ["SupabaseOffline", "SupabaseOfflineCore"],
            swiftSettings: swiftSettings
        ),
        .testTarget(
            name: "SupabaseOfflineTests",
            dependencies: ["SupabaseOffline", "SupabaseOfflineTesting", "SupabaseOfflineCore"],
            swiftSettings: swiftSettings
        ),
    ]
)
