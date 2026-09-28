import Foundation
@testable import JsonMind
import JsonUICore

enum Examples {
    /// The repository's examples directory (this file is ios/Tests/JsonMindTests/TestSupport.swift).
    static var directory: URL {
        var url = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { url.deleteLastPathComponent() }
        return url.appendingPathComponent("examples")
    }

    static func mind(_ name: String) throws -> Mind {
        Mind(try JsonValue.parse(try Data(contentsOf: directory.appendingPathComponent("minds/\(name).json"))))
    }
}
