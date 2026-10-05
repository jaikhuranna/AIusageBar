// swift-tools-version:5.9
// The pure rules only (Core/), so they can be tested with `swift test` anywhere,
// Linux included. The app and the widgets are built by the Xcode project that
// project.yml generates; they compile Core/ in directly.
import PackageDescription

let package = Package(
    name: "AIusageCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    targets: [
        .target(name: "AIusageCore", path: "Core"),
        .testTarget(name: "AIusageCoreTests", dependencies: ["AIusageCore"], path: "CoreTests"),
    ]
)
