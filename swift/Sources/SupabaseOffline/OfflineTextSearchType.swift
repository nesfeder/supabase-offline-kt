// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// How a full-text search reads its query text; `nil` where a search takes one means `to_tsquery`
/// syntax (`cat & dog`).
public enum OfflineTextSearchType: String, Sendable, Hashable, CaseIterable {

    /// Plain words, all required (`plainto_tsquery`).
    case plain

    /// Words as a phrase, in order (`phraseto_tsquery`).
    case phrase

    /// Search-engine syntax: quotes, `or`, `-` (`websearch_to_tsquery`).
    case websearch

    /// The operator a search of this type is sent as.
    var filterOperator: FilterOperator {
        switch self {
        case .plain: return .plfts
        case .phrase: return .phfts
        case .websearch: return .wfts
        }
    }
}
