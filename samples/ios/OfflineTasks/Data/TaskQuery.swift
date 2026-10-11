// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation
import SupabaseOffline

/// The chips above the task list. Each is a filter the server applies, not a sort of the rows the
/// device already has.
nonisolated enum TaskFilter: String, CaseIterable, Identifiable, Sendable {
    case all
    case open
    case done
    case recent

    var id: Self { self }

    /// The chip's label.
    var title: String {
        switch self {
        case .all: "All"
        case .open: "Open"
        case .done: "Done"
        case .recent: "Recent"
        }
    }
}

/// The task list's question to the server: a chip, the search text, and the days Recent covers.
nonisolated struct TaskQuery: Hashable, Sendable {

    /// The chip chosen; choosing one moves Recent's week to end with today.
    var filter: TaskFilter {
        didSet { recentWindow = Self.lastSevenDays(endingOn: .now) }
    }

    /// The search text; blank for none.
    var search: String

    /// The seven days Recent covers, fixed when the query is made, so the query stays the same
    /// one, and keeps its cached rows, all day.
    var recentWindow: DateInterval

    /// A query for `filter` and `search`, its week ending with `now`'s day.
    init(filter: TaskFilter = .all, search: String = "", now: Date = .now) {
        self.filter = filter
        self.search = search
        recentWindow = Self.lastSevenDays(endingOn: now)
    }

    /// Whether this asks for every task, as the list the other screens share does.
    var isEverything: Bool {
        filter == .all && trimmedSearch.isEmpty
    }

    /// The search text without its edges.
    var trimmedSearch: String {
        search.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// `request` narrowed to this query's tasks.
    func applied<Request: OfflinePostgrestFilterBuilder>(to request: Request) -> Request {
        let filtered = filter.applied(to: request, recentWindow: recentWindow)
        return trimmedSearch.isEmpty ? filtered : Self.titleContains(trimmedSearch, filtered)
    }

    // --8<-- [start:search]
    /// The tasks whose title holds `text`, in any case: `ilike`, which the server applies.
    static func titleContains<Request: OfflinePostgrestFilterBuilder>(_ text: String, _ request: Request) -> Request {
        request.ilike("title", pattern: "%\(text)%")
    }
    // --8<-- [end:search]

    // --8<-- [start:same-column]
    /// The tasks created within `window`: two conditions on one column, which the client sends
    /// together, in one group, so the server reads both.
    static func created<Request: OfflinePostgrestFilterBuilder>(within window: DateInterval, _ request: Request) -> Request {
        request
            .gte("created_at", value: window.start)
            .lt("created_at", value: window.end)
    }
    // --8<-- [end:same-column]

    /// The seven days up to the end of `now`'s day, by the device's calendar.
    static func lastSevenDays(endingOn now: Date, calendar: Calendar = .current) -> DateInterval {
        let today = calendar.startOfDay(for: now)
        let tomorrow = calendar.date(byAdding: .day, value: 1, to: today) ?? today
        let start = calendar.date(byAdding: .day, value: -7, to: tomorrow) ?? today
        return DateInterval(start: start, end: tomorrow)
    }

    /// The seven days before the last seven: last week, for Try it's grouped delete.
    static func week(before now: Date, calendar: Calendar = .current) -> DateInterval {
        let recent = lastSevenDays(endingOn: now, calendar: calendar)
        let start = calendar.date(byAdding: .day, value: -7, to: recent.start) ?? recent.start
        return DateInterval(start: start, end: recent.start)
    }
}

nonisolated extension TaskFilter {

    // --8<-- [start:filter-chips]
    /// `request` narrowed to this chip's tasks, by the server.
    func applied<Request: OfflinePostgrestFilterBuilder>(to request: Request, recentWindow: DateInterval) -> Request {
        switch self {
        case .all: request
        case .open: request.neq("done", value: true)
        case .done: request.is("done", value: true)
        case .recent: TaskQuery.created(within: recentWindow, request)
        }
    }
    // --8<-- [end:filter-chips]
}
