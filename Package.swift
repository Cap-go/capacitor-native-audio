// swift-tools-version: 5.8
import PackageDescription

let package = Package(
    name: "CapgoNativeAudio",
    platforms: [
        .iOS(.v14)
    ],
    products: [
        .library(
            name: "CapgoNativeAudio",
            targets: ["NativeAudioPlugin"]
        )
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "7.0.0")
    ],
    targets: [
        .target(
            name: "NativeAudioPlugin",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm")
            ],
            path: "ios/Sources/NativeAudioPlugin"),
        .testTarget(
            name: "NativeAudioPluginTests",
            dependencies: ["NativeAudioPlugin"],
            path: "ios/Tests/NativeAudioPluginTests")
    ]
)
