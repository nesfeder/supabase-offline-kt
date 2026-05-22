import Foundation

func encodeToJsonArray<T: Encodable>(
    values: [T],
    dateEncodingStrategy: JSONEncoder.DateEncodingStrategy = .iso8601
) throws -> String {
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = dateEncodingStrategy
    let data = try encoder.encode(values)
    return String(data: data, encoding: .utf8) ?? "[]"
}

func encodeToJsonObject<T: Encodable>(
    value: T,
    dateEncodingStrategy: JSONEncoder.DateEncodingStrategy = .iso8601
) throws -> String {
    let encoder = JSONEncoder()
    encoder.dateEncodingStrategy = dateEncodingStrategy
    let data = try encoder.encode(value)
    return String(data: data, encoding: .utf8) ?? "{}"
}