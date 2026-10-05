// swift-tools-version: 6.2
// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import PackageDescription

/// An app's code as Xcode 26 sets a new app up: Swift 6, every declaration on the main actor unless
/// it says otherwise, and approachable concurrency's two upcoming features that change what
/// compiles. It must build against the package with no warning (scripts/build-swift-consumer.sh);
/// it is not part of the published package.
let package = Package(
    name: "MainActorConsumer",
    platforms: [.iOS(.v16)],
    dependencies: [.package(path: "..")],
    targets: [
        .target(
            name: "MainActorConsumer",
            dependencies: [
                .product(name: "SupabaseOffline", package: "swift"),
                .product(name: "SupabaseOfflineTesting", package: "swift"),
            ],
            swiftSettings: [
                .swiftLanguageMode(.v6),
                .defaultIsolation(MainActor.self),
                .enableUpcomingFeature("InferIsolatedConformances"),
                .enableUpcomingFeature("NonisolatedNonsendingByDefault"),
                // This target's warnings only: Xcode builds a dependency with its warnings suppressed.
                .treatAllWarnings(as: .error),
            ]
        )
    ]
)
