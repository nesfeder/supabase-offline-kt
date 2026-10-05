// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import SupabaseOfflineTesting
import SwiftUI

#Preview {
    TodoListView(
        store: TodoStore(
            offline: .inMemory(server: FakeServer { _ in
                .json(#"[{"id":"7f4c8e2a-1b3d-4e5f-8a9b-0c1d2e3f4a5b","title":"Buy milk","done":false}]"#)
            })
        )
    )
}
