import Foundation
import SupabaseOffline

public extension KotlinInt {
    var swiftInt: Int {
        return Int(truncatingIfNeeded: self.intValue)
    }
}
