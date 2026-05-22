// swift-tools-version:5.5
import PackageDescription

// =====================================================================================
// SwiftPM manifest for Supabase Offline SDK (Kotlin Multiplatform → iOS XCFrameworks).
//
// The two `.binaryTarget`s below resolve from GitHub Releases. Each tagged release on
// `nesfeder/supabase-offline-kt` must publish two assets:
//
//   SupabaseOffline.xcframework.zip
//   SupabaseOfflineSupport.xcframework.zip
//
// `scripts/release-xcframeworks.sh` builds the XCFrameworks, zips them, prints the URLs
// and SHA-256 checksums, and (optionally) uploads them via `gh release`. The script also
// rewrites the `version` / `kmpChecksum` / `supportChecksum` constants below in place.
//
// On `main`, these constants point at the most recent published release. SwiftPM users
// who depend on this package via `.package(url: ..., from: "X.Y.Z")` always resolve a
// tagged commit, so the checksums are always valid for the tag they reference.
//
// If `main` is ever ahead of the latest release (typical between releases), local
// `swift build`s against `main` will fail with a checksum mismatch — that is expected.
// =====================================================================================

let version = "1.0.0"
let releaseBaseUrl = "https://github.com/nesfeder/supabase-offline-kt/releases/download/\(version)"

// Replaced in-place by scripts/release-xcframeworks.sh on each release.
let kmpChecksum     = "0000000000000000000000000000000000000000000000000000000000000000"
let supportChecksum = "0000000000000000000000000000000000000000000000000000000000000000"

let package = Package(
    name: "SupabaseOffline",
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "SupabaseOffline", targets: ["SupabaseOffline"]),
        .library(name: "SupabaseOfflineSupport", targets: ["SupabaseOfflineSupport"])
    ],
    targets: [
        .binaryTarget(
            name: "SupabaseOffline",
            url: "\(releaseBaseUrl)/SupabaseOffline.xcframework.zip",
            checksum: kmpChecksum
        ),
        .binaryTarget(
            name: "SupabaseOfflineSupport",
            url: "\(releaseBaseUrl)/SupabaseOfflineSupport.xcframework.zip",
            checksum: supportChecksum
        )
    ]
)
