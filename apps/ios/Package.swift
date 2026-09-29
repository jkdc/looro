// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "LearningCore",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [.library(name: "LearningCore", targets: ["LearningCore"])],
    targets: [
        .target(name: "LearningCore", path: "Core"),
        .testTarget(name: "LearningCoreTests", dependencies: ["LearningCore"], path: "Tests")
    ]
)
