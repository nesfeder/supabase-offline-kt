import Foundation
import SupabaseOffline

extension Data {
    func toKotlinByteArray() -> KotlinByteArray {
        let array = KotlinByteArray(size: Int32(count))
        withUnsafeBytes { buffer in
            guard let base = buffer.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                return
            }
            for i in 0..<count {
                array.set(index: Int32(i), value: Int8(bitPattern: base[i]))
            }
        }
        return array
    }
}
