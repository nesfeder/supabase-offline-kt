// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import CryptoKit
import Foundation

/// The five tasks Try it's Add five sample tasks writes.
nonisolated enum SampleTasks {

    /// Their titles, in the order they list.
    static let titles = ["Buy milk", "Call the bank", "Water the plants", "Book the dentist", "Return the library books"]
}

/// The tasks Import the starter list adds once per user.
nonisolated enum StarterList {

    /// Their titles; each one's position makes its id.
    static let titles = ["Read the tour on the Sync tab", "Add a photo to a task", "Go offline and add a task"]
}

extension DeviceID {

    /// An id made from `name`, the same every time: a name-based UUID (version 5 layout, from
    /// SHA-256), in lower case. Writing a row under it twice writes the same row.
    nonisolated static func derived(from name: String) -> String {
        var bytes = Array(SHA256.hash(data: Data(name.utf8)).prefix(16))
        bytes[6] = (bytes[6] & 0x0F) | 0x50
        bytes[8] = (bytes[8] & 0x3F) | 0x80
        let uuid = UUID(
            uuid: (
                bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]
            )
        )
        return uuid.uuidString.lowercased()
    }
}
