// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SwiftUI
import UIKit

/// The system's share sheet for `items`: a photo's file, or links to photos.
struct ShareSheet: UIViewControllerRepresentable {

    /// What is shared.
    let items: [URL]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
