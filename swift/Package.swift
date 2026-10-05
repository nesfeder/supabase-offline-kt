// swift-tools-version: 6.0
// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import PackageDescription

/// The release whose framework archive the package is built on; the release sets it.
let version = "0.0.0"

/// The SHA-256 checksum of that archive; the release sets it with `version`.
let checksum = "0000000000000000000000000000000000000000000000000000000000000000"

/// Swift 6, and imports internal unless a file says otherwise, so no public signature can name a
/// type of the framework underneath.
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
        .binaryTarget(
            name: "SupabaseOfflineCore",
            url: "https://github.com/nesfeder/supabase-offline-kt/releases/download/\(version)/SupabaseOfflineCore.xcframework.zip",
            checksum: checksum
        ),
        .target(
            name: "SupabaseOffline",
            dependencies: ["SupabaseOfflineCore"],
            resources: [.copy("PrivacyInfo.xcprivacy")],
            swiftSettings: swiftSettings,
            // The framework is static: Apple's SQLite and the C++ runtime come from the app's link.
            linkerSettings: [.linkedLibrary("sqlite3"), .linkedLibrary("c++")]
        ),
        .target(
            name: "SupabaseOfflineTesting",
            dependencies: ["SupabaseOffline", "SupabaseOfflineCore"],
            swiftSettings: swiftSettings
        ),
    ]
)

// MARK: Development only
// Everything from the line above to the end is left out of the manifest a release publishes
// (scripts/swift-release.py). In a checkout the framework is the local build in LocalCore/
// (scripts/prepare-local-swift-package.sh; CONTRIBUTING.md, "The Swift package"), whatever is on
// disk, so the answer never depends on the folder; SUPABASE_OFFLINE_LOCAL_CORE=0 takes the
// release's archive instead. The tests are a target here only.

if Context.environment["SUPABASE_OFFLINE_LOCAL_CORE"] != "0",
    let index = package.targets.firstIndex(where: { $0.name == "SupabaseOfflineCore" })
{
    package.targets[index] = .binaryTarget(
        name: "SupabaseOfflineCore",
        path: "LocalCore/SupabaseOfflineCore.xcframework"
    )
}

package.targets.append(
    .testTarget(
        name: "SupabaseOfflineTests",
        dependencies: ["SupabaseOffline", "SupabaseOfflineTesting", "SupabaseOfflineCore"],
        swiftSettings: swiftSettings
    )
)
