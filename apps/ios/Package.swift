// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "LearningCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "LearningCore", targets: ["LearningCore"])],
    dependencies: [
        // SQLite driver for the learning database (Core/Database). Pinned to
        // an exact stable release, like the WebRTC dependency the app uses:
        // the durable learner store's driver is a reviewed decision, not
        // whatever a fresh checkout resolves to. 7.8.0 is the newest 7.x
        // whose manifest still declares swift-tools-version 6.0, the level
        // this package pins.
        .package(url: "https://github.com/groue/GRDB.swift.git", exact: "7.8.0"),
    ],
    targets: [
        .target(name: "LearningCore", dependencies: [.product(name: "GRDB", package: "GRDB.swift")], path: "Core"),
        .testTarget(name: "LearningCoreTests", dependencies: ["LearningCore"], path: "Tests")
    ]
)
