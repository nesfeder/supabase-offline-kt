import SwiftUI
import PhotosUI
import ComposableArchitecture

struct TaskDetailView: View {
    @Bindable var store: StoreOf<TaskDetailFeature>
    @State private var pickerItem: PhotosPickerItem?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Title").font(.caption).foregroundStyle(.secondary)
                    TextField(
                        "Title",
                        text: $store.titleDraft.sending(\.setTitleDraft)
                    )
                    .textFieldStyle(.roundedBorder)
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Notes").font(.caption).foregroundStyle(.secondary)
                    TextField(
                        "Notes",
                        text: $store.notesDraft.sending(\.setNotesDraft),
                        axis: .vertical
                    )
                    .lineLimit(3...8)
                    .textFieldStyle(.roundedBorder)
                }

                HStack {
                    Button("Save") {
                        store.send(.saveTapped)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(store.titleDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                    PhotosPicker(
                        selection: $pickerItem,
                        matching: .images
                    ) {
                        Label(store.isUploading ? "Uploading…" : "Add Photo", systemImage: "photo")
                    }
                    .disabled(store.isUploading)
                    .onChange(of: pickerItem) { _, newValue in
                        guard let newValue else { return }
                        Task {
                            if let data = try? await newValue.loadTransferable(type: Data.self) {
                                let mime = mimeType(for: newValue) ?? "image/jpeg"
                                store.send(.addPhotoPicked(data, contentType: mime))
                            }
                            pickerItem = nil
                        }
                    }
                }

                Divider()

                Text("Photos").font(.headline)

                if store.photos.isEmpty {
                    Text("No photos yet")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                } else {
                    LazyVGrid(
                        columns: [
                            GridItem(.flexible(), spacing: 8),
                            GridItem(.flexible(), spacing: 8),
                            GridItem(.flexible(), spacing: 8),
                        ],
                        spacing: 8
                    ) {
                        ForEach(store.photos) { row in
                            PhotoTile(localFileUrl: row.localFileUrl)
                        }
                    }
                }
            }
            .padding(16)
        }
        .navigationTitle("Task")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { store.send(.onAppear) }
        .alert(
            "Error",
            isPresented: Binding(
                get: { store.error != nil },
                set: { newValue in if !newValue { store.send(.clearError) } }
            ),
            actions: {
                Button("OK") { store.send(.clearError) }
            },
            message: {
                Text(store.error ?? "")
            }
        )
    }

    private func mimeType(for item: PhotosPickerItem) -> String? {
        guard let identifier = item.supportedContentTypes.first?.preferredMIMEType else {
            return nil
        }
        return identifier
    }
}

private struct PhotoTile: View {
    let localFileUrl: String?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8)
                .fill(Color(.secondarySystemBackground))
            if let urlString = localFileUrl,
               let url = URL(string: urlString),
               let image = UIImage(contentsOfFile: url.path) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
            } else {
                Image(systemName: "photo")
                    .font(.largeTitle)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}
