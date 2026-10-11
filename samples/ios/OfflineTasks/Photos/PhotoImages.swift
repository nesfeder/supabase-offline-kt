// Copyright (c) 2026 nesfeder
// SPDX-License-Identifier: MIT
import SupabaseOffline
import UIKit

/// The images of the photos a screen shows, by photo id: read from the app's own copy while a
/// photo waits to upload, else from the library's copy of its file, fetched and kept first when
/// there is none and the server can be asked.
@Observable
final class PhotoImages {

    /// Each photo's image as it stands, by photo id.
    private(set) var images: [String: TaskDetailModel.PhotoImage] = [:]

    /// Reads and fetches the files.
    @ObservationIgnored private let repository: TaskRepository

    /// The longest side of the image kept, in pixels.
    @ObservationIgnored private let maxPixelSize: Int

    /// Images read through `repository`, scaled to at most `maxPixelSize` pixels.
    init(repository: TaskRepository, maxPixelSize: Int) {
        self.repository = repository
        self.maxPixelSize = maxPixelSize
    }

    /// `photo`'s image as it stands now.
    func image(of photo: TaskPhoto) -> TaskDetailModel.PhotoImage {
        images[photo.id] ?? .loading
    }

    /// Loads `photo`'s image; `forgetting` first drops the one shown, after the file changed.
    func load(_ photo: TaskPhoto, forgetting: Bool = false) async {
        if forgetting || images[photo.id] == nil { images[photo.id] = .loading }
        if let data = await repository.pendingPhotoData(of: photo) {
            images[photo.id] = await Self.image(of: data, maxPixelSize: maxPixelSize).map(TaskDetailModel.PhotoImage.loaded) ?? .unavailable
            return
        }
        switch await repository.photoFile(for: photo) {
        case .onDevice(let file):
            do {
                let data = try await repository.photoData(of: file, for: photo)
                images[photo.id] = await Self.image(of: data, maxPixelSize: maxPixelSize).map(TaskDetailModel.PhotoImage.loaded) ?? .unavailable
            } catch {
                images[photo.id] = .unavailable
            }
        case .notOnDevice:
            images[photo.id] = .notOnDevice
        case .missing:
            images[photo.id] = .missing
        case .unavailable:
            images[photo.id] = .unavailable
        }
    }

    /// The image in `data`, decoded off the main actor.
    @concurrent
    nonisolated private static func image(of data: Data, maxPixelSize: Int) async -> UIImage? {
        PhotoEncoder.downsampled(data, maxPixelSize: maxPixelSize).map(UIImage.init(cgImage:))
    }
}

/// A photo the navigation stack opens.
struct PhotoRoute: Hashable {

    /// The photo.
    let photo: TaskPhoto
}

/// Items for the share sheet, one presentation each.
struct SharedItems: Identifiable {

    /// Tells one presentation from the next.
    let id = UUID()

    /// What is shared: files or links.
    let items: [URL]
}

/// A generated photo, for Try it's upload through a signed URL.
nonisolated enum SamplePhoto {

    /// A small JPEG of a teal square, as a photo picked from the library would arrive.
    static func jpeg() -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: 320, height: 240), format: format)
        return renderer.jpegData(withCompressionQuality: 0.8) { context in
            UIColor.systemTeal.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 320, height: 240))
        }
    }
}
