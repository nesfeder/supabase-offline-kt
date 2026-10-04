// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT

/// A PostgREST comparison, by the name it has in a request: the operator of
/// ``OfflinePostgrestFilterBuilder/not(_:operator:value:)``.
public enum FilterOperator: String, Sendable, Hashable, CaseIterable {

    /// Equal to.
    case eq

    /// Not equal to.
    case neq

    /// Greater than.
    case gt

    /// Greater than or equal to.
    case gte

    /// Less than.
    case lt

    /// Less than or equal to.
    case lte

    /// Matches a `LIKE` pattern, case-sensitively.
    case like

    /// Matches a `LIKE` pattern, ignoring case.
    case ilike

    /// Matches a POSIX regular expression, case-sensitively.
    case match

    /// Matches a POSIX regular expression, ignoring case.
    case imatch

    /// `IS`: `null`, `true`, `false` or `unknown`.
    case `is`

    /// One of a list, written `(a,b)`.
    case `in`

    /// Contains (`@>`): an array, range or `jsonb` value.
    case cs

    /// Contained by (`<@`).
    case cd

    /// Strictly left of a range (`<<`).
    case sl

    /// Strictly right of a range (`>>`).
    case sr

    /// Does not extend to the left of a range (`&>`).
    case nxl

    /// Does not extend to the right of a range (`&<`).
    case nxr

    /// Adjacent to a range (`-|-`).
    case adj

    /// Overlaps (`&&`).
    case ov

    /// Full-text search with `to_tsquery`.
    case fts

    /// Full-text search with `plainto_tsquery`.
    case plfts

    /// Full-text search with `phraseto_tsquery`.
    case phfts

    /// Full-text search with `websearch_to_tsquery`.
    case wfts
}

extension FilterOperator {

    /// The value forms an operator takes in a description, as the core renders them.
    enum ValueForm {

        /// A list (`in`).
        case list

        /// A pair of bounds, sent as `(lower,upper)`.
        case range

        /// One value, or a literal array or range written out (`cs`, `cd`, `ov`).
        case literal

        /// One value.
        case single
    }

    /// The value form this operator takes.
    var valueForm: ValueForm {
        switch self {
        case .in: return .list
        case .sl, .sr, .nxl, .nxr, .adj: return .range
        case .cs, .cd, .ov: return .literal
        default: return .single
        }
    }
}
