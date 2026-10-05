// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import Foundation

extension OfflineBucketApi {

    /// How an upload or an update stores its object.
    public struct UploadOptions: Sendable, Hashable {

        /// The object's content type, such as `image/png`; `nil` lets Storage infer it from the path.
        public var contentType: String?

        /// Whether to replace an object already at the path, which otherwise fails the upload.
        public var upsert: Bool

        /// Metadata of your own, stored with the object and returned in its ``FileObjectV2/metadata``.
        public var userMetadata: [String: OfflineJSON]?

        /// Options with the values given; by default Storage infers the content type and an
        /// existing object is never replaced.
        public init(contentType: String? = nil, upsert: Bool = false, userMetadata: [String: OfflineJSON]? = nil) {
            self.contentType = contentType
            self.upsert = upsert
            self.userMetadata = userMetadata
        }

        /// These options as the core reads them.
        func coreText() throws -> String {
            var fields: [String: OfflineJSON] = ["upsert": .bool(upsert)]
            if let contentType { fields["contentType"] = .string(contentType) }
            if let userMetadata { fields["metadata"] = .object(userMetadata) }
            return try RowCoding.jsonText(for: OfflineJSON.object(fields))
        }
    }

    /// An image transformation Storage applies before it answers; a field left `nil` keeps the
    /// original's.
    public struct ImageTransformation: Sendable, Hashable {

        /// How an image is fitted to a width and a height.
        public enum Resize: Sendable, Hashable {

            /// Keeps the aspect ratio, fills the size and crops what falls outside it.
            case cover

            /// Keeps the aspect ratio and fits inside the size.
            case contain

            /// Stretches to the size, ignoring the aspect ratio.
            case fill

            /// The mode's name as Storage reads it.
            var coreName: String {
                switch self {
                case .cover: return "cover"
                case .contain: return "contain"
                case .fill: return "fill"
                }
            }
        }

        /// The width in pixels.
        public var width: Int?

        /// The height in pixels.
        public var height: Int?

        /// How the image is fitted to ``width`` and ``height``.
        public var resize: Resize?

        /// The quality, from 1 to 100; Storage's default is 80. Another value fails the request
        /// before it is sent.
        public var quality: Int?

        /// The format to answer in, such as `origin` for the original's.
        public var format: String?

        /// A transformation with the values given.
        public init(
            width: Int? = nil,
            height: Int? = nil,
            resize: Resize? = nil,
            quality: Int? = nil,
            format: String? = nil
        ) {
            self.width = width
            self.height = height
            self.resize = resize
            self.quality = quality
            self.format = format
        }

        /// This transformation as the core reads it.
        func coreText() throws -> String {
            var fields: [String: OfflineJSON] = [:]
            if let width { fields["width"] = .int(width) }
            if let height { fields["height"] = .int(height) }
            if let resize { fields["resize"] = .string(resize.coreName) }
            if let quality { fields["quality"] = .int(quality) }
            if let format { fields["format"] = .string(format) }
            return try RowCoding.jsonText(for: OfflineJSON.object(fields))
        }
    }

    /// Which objects a listing returns, and in what order.
    public struct StorageListFilter: Sendable, Hashable {

        /// The order of a sorted listing.
        public enum SortOrder: Sendable, Hashable {

            /// Smallest first.
            case asc

            /// Largest first.
            case desc

            /// The order's name as Storage reads it.
            var coreName: String {
                switch self {
                case .asc: return "asc"
                case .desc: return "desc"
                }
            }
        }

        /// The column a listing is sorted by, and in which order.
        public struct SortBy: Sendable, Hashable {

            /// The column, such as `name`, `created_at` or `updated_at`.
            public var column: String

            /// The order.
            public var order: SortOrder

            /// Sorting by `column` in `order`.
            public init(column: String, order: SortOrder = .asc) {
                self.column = column
                self.order = order
            }
        }

        /// The most objects to return; `nil` for Storage's default.
        public var limit: Int?

        /// How many objects to skip first, for paging.
        public var offset: Int?

        /// Only the objects whose names contain this text.
        public var search: String?

        /// The sort; `nil` for Storage's default.
        public var sortBy: SortBy?

        /// A filter with the values given, in supabase-swift's order.
        public init(limit: Int? = nil, offset: Int? = nil, sortBy: SortBy? = nil, search: String? = nil) {
            self.limit = limit
            self.offset = offset
            self.sortBy = sortBy
            self.search = search
        }

        /// This filter as the core reads it.
        func coreText() throws -> String {
            var fields: [String: OfflineJSON] = [:]
            if let limit { fields["limit"] = .int(limit) }
            if let offset { fields["offset"] = .int(offset) }
            if let search { fields["search"] = .string(search) }
            if let sortBy {
                fields["sortBy"] = ["column": .string(sortBy.column), "order": .string(sortBy.order.coreName)]
            }
            return try RowCoding.jsonText(for: OfflineJSON.object(fields))
        }
    }
}
