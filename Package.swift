// swift-tools-version:5.9
import PackageDescription
import Foundation

// The manifest stays at the repository root so the package can be added by URL;
// the Swift sources live in ios/ next to the Android project in android/.
// JsonUI comes from GitHub; set JSONUI_PATH to build against a local checkout.
let jsonUI: Package.Dependency = ProcessInfo.processInfo.environment["JSONUI_PATH"].map { .package(path: $0) }
    ?? .package(url: "https://github.com/bclnet/JsonUI", branch: "claude/ios-android-form-libraries-2jjfw5")

let package = Package(
    name: "JsonMind",
    platforms: [.iOS(.v15), .macOS(.v12), .tvOS(.v15), .watchOS(.v8)],
    products: [
        // Minds for JsonUI documents: the schema, prompts and replies, token budgets, sessions, providers
        // and the command vocabulary. No UI, no networking: a token stream (TokenX) plugs into MindProvider.
        .library(name: "JsonMind", targets: ["JsonMind"]),
    ],
    dependencies: [jsonUI],
    targets: [
        .target(name: "JsonMind", dependencies: [.product(name: "JsonUICore", package: "JsonUI")], path: "ios/Sources/JsonMind"),
        .testTarget(name: "JsonMindTests", dependencies: ["JsonMind"], path: "ios/Tests/JsonMindTests"),
    ]
)
