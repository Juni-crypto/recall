// swift-tools-version:5.10
import PackageDescription

// The parts of Recall that don't need an iPhone: text rules, the database and prompts.
// `swift test` runs them on a Mac.
let package = Package(
    name: "RecallCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "RecallCore", targets: ["RecallCore"])],
    targets: [
        .target(name: "RecallCore", linkerSettings: [.linkedLibrary("sqlite3")]),
        .testTarget(name: "RecallCoreTests", dependencies: ["RecallCore"]),
    ]
)
