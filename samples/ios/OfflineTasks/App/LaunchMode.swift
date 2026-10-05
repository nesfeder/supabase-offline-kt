// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

/// What the app runs against: your Supabase project, or the in-memory demo.
enum LaunchMode: Equatable {

    /// The project at `url`, with its publishable key.
    case project(url: URL, publishableKey: String)

    /// The in-memory demo: no project, nothing kept between launches.
    case demo(reason: DemoReason)

    /// Why the app is in demo mode.
    enum DemoReason: Equatable {

        /// `Configuration/Secrets.xcconfig` gave no project URL or no key.
        case noKeys

        /// The launch arguments asked for it, as the UI tests do.
        case requested
    }

    /// The launch argument that opens the app in demo mode whatever the keys say.
    static let demoArgument = "-demo-mode"

    /// The mode the build's Info.plist and the launch arguments call for. The app that hosts the
    /// unit tests runs in demo mode too, so the tests never reach a project.
    static func current(
        info: [String: Any] = Bundle.main.infoDictionary ?? [:],
        arguments: [String] = ProcessInfo.processInfo.arguments,
        environment: [String: String] = ProcessInfo.processInfo.environment
    ) -> LaunchMode {
        if arguments.contains(demoArgument) || environment["XCTestConfigurationFilePath"] != nil {
            return .demo(reason: .requested)
        }
        // The URL comes without its scheme: an xcconfig reads `//` as the start of a comment.
        let host = (info["SupabaseURL"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        let key = (info["SupabasePublishableKey"] as? String ?? "").trimmingCharacters(in: .whitespaces)
        guard !host.isEmpty, !key.isEmpty, !host.contains("$("), let url = URL(string: "https://\(host)"), url.host() != nil
        else {
            return .demo(reason: .noKeys)
        }
        return .project(url: url, publishableKey: key)
    }

    /// Whether this is demo mode.
    var isDemo: Bool {
        if case .demo = self { return true }
        return false
    }
}
