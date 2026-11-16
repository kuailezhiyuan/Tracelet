// swift-tools-version:5.9
import Foundation
import PackageDescription

let corePath = "sdk/rust-core/out/TraceletCore.xcframework"
let coreInfoPlist = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()
    .appendingPathComponent(corePath)
    .appendingPathComponent("Info.plist")
let coreTarget: Target = FileManager.default.fileExists(atPath: coreInfoPlist.path)
    ? .binaryTarget(name: "TraceletCore", path: corePath)
    : .binaryTarget(
        name: "TraceletCore",
        url: "https://github.com/kuailezhiyuan/Tracelet/releases/download/memolanes-core-8c500e46/TraceletCore.xcframework.zip",
        checksum: "824dde2ca3b30b3a3077cb914c9ec709c73d51789bdc142652438aa578b5774e"
    )

let package = Package(
    name: "TraceletSDK",
    platforms: [
        .iOS(.v14)
    ],
    products: [
        .library(
            name: "TraceletSDK",
            targets: ["TraceletSDK"]
        ),
    ],
    targets: [
        .target(
            name: "TraceletSDK",
            dependencies: ["TraceletCore"],
            path: "sdk/ios/Sources/TraceletSDK",
            linkerSettings: [
                .linkedLibrary("sqlite3")
            ]
        ),
        coreTarget,
        .testTarget(
            name: "TraceletSDKTests",
            dependencies: ["TraceletSDK"],
            path: "sdk/ios/Tests/TraceletSDKTests"
        ),
    ]
)
