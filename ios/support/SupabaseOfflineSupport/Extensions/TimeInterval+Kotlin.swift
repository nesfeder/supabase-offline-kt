import Foundation
import SupabaseOffline

extension TimeInterval {
    func toKotlinMillis() -> KotlinLong {
        return KotlinLong(value: Int64(self * 1000))
    }
}