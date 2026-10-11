// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import ImageIO
import UIKit

/// Turns a picked image into the one format the bucket takes: an upright JPEG under 5 MB.
nonisolated enum PhotoEncoder {

    /// The longest side a photo keeps, in pixels.
    static let maxPixelSize = 2_048

    /// Re-encodes the image in `data`, scaled down to ``maxPixelSize`` and then to fit under the
    /// bucket's limit, off the main actor.
    ///
    /// - Throws: ``SampleFailure/photoUnreadable`` when `data` is not an image.
    @concurrent
    nonisolated static func encode(_ data: Data) async throws -> EncodedPhoto {
        var pixelSize = maxPixelSize
        while pixelSize >= 256 {
            // ImageIO applies the photo's orientation, so the JPEG is upright without its EXIF.
            guard let image = downsampled(data, maxPixelSize: pixelSize) else { throw SampleFailure.photoUnreadable }
            for quality in [0.8, 0.6, 0.4] {
                if let jpeg = UIImage(cgImage: image).jpegData(compressionQuality: quality),
                    jpeg.count < PhotoPath.maxBytes
                {
                    return EncodedPhoto(jpeg: jpeg, width: image.width, height: image.height)
                }
            }
            pixelSize /= 2
        }
        throw SampleFailure.photoUnreadable
    }

    /// The image in `data`, upright, its longest side at most `maxPixelSize`; `nil` when `data` is
    /// not an image.
    nonisolated static func downsampled(_ data: Data, maxPixelSize: Int) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
