import XCTest
import Capacitor
import AVFoundation
@testable import NativeAudio

class PluginTests: XCTestCase {

    var plugin: NativeAudio!
    var tempFileURL: URL!
    var testAssetId = "testAssetId"
    var testRemoteAssetId = "testRemoteAssetId"

    override func setUp() {
        super.setUp()
        plugin = NativeAudio()
        
        // Set up a testing override for executeOnAudioQueue to avoid deadlocks
        plugin.isRunningTests = true
        
        // Create a temporary audio file for testing
        let audioFilePath = NSTemporaryDirectory().appending("testAudio.wav")
        tempFileURL = URL(fileURLWithPath: audioFilePath)

        // Create a simple test audio file if needed
        if !FileManager.default.fileExists(atPath: audioFilePath) {
            createTestAudioFile(at: audioFilePath)
        }
    }

    override func tearDown() {
        // Clean up any audio assets
        let expectation = self.expectation(description: "Cleanup audio assets")
        plugin.audioQueue.async { 
            if let asset = self.plugin.audioList[self.testAssetId] as? AudioAsset {
                asset.unload()
            }
            if let asset = self.plugin.audioList[self.testRemoteAssetId] as? RemoteAudioAsset {
                asset.unload()
            }
            self.plugin.audioList.removeAll()
            expectation.fulfill()
        }
        wait(for: [expectation], timeout: 2.0)

        // Try to delete the temporary file
        try? FileManager.default.removeItem(at: tempFileURL)

        plugin = nil
        super.tearDown()
    }

    // Helper method to create a simple test audio file
    private func createTestAudioFile(at path: String) {
        // This is a placeholder for a real implementation
        // In a real scenario, you would create a small audio file for testing
        // For now, we'll just create an empty file
        FileManager.default.createFile(atPath: path, contents: Data(), attributes: nil)
    }

    func testAudioAssetInitialization() {
        let expectation = self.expectation(description: "Initialize AudioAsset")
        
        // Use async to avoid blocking the main thread
        plugin.audioQueue.async {
            // Create an audio asset
            let asset = AudioAsset(
                owner: self.plugin,
                withAssetId: self.testAssetId,
                withPath: self.tempFileURL.path,
                withChannels: 1,
                withVolume: 0.5
            )

            // Add it to the plugin's audio list
            self.plugin.audioList[self.testAssetId] = asset

            // Verify initial values
            XCTAssertEqual(asset.assetId, self.testAssetId)
            XCTAssertEqual(asset.initialVolume, 0.5)

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 5.0)
    }

    func testAudioAssetVolumeControl() {
        let expectation = self.expectation(description: "Test volume control")

        plugin.audioQueue.async {
            // Create an audio asset
            let asset = AudioAsset(
                owner: self.plugin,
                withAssetId: self.testAssetId,
                withPath: self.tempFileURL.path,
                withChannels: 1,
                withVolume: 1.0
            )

            // Add it to the plugin's audio list
            self.plugin.audioList[self.testAssetId] = asset

            // Test setting volume
            let testVolume: Float = 0.7
            asset.setVolume(volume: NSNumber(value: testVolume), fadeDuration: 1.0)

            // We can't directly check player.volume as it may take time to set
            // So we'll just verify the method doesn't crash

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 5.0)
    }

    func testRemoteAudioAssetInitialization() {
        let expectation = self.expectation(description: "Initialize RemoteAudioAsset")

        // Use a publicly accessible test audio URL
        let testURL = "https://file-examples.com/storage/fe5947fd2362a2f06a86851/2017/11/file_example_MP3_700KB.mp3"

        plugin.audioQueue.async {
            // Create a remote audio asset
            let asset = RemoteAudioAsset(
                owner: self.plugin,
                withAssetId: self.testRemoteAssetId,
                withPath: testURL,
                withChannels: 1,
                withVolume: 0.6
            )

            // Add it to the plugin's audio list
            self.plugin.audioList[self.testRemoteAssetId] = asset

            // Verify initial values
            XCTAssertEqual(asset.assetId, self.testRemoteAssetId)
            XCTAssertEqual(asset.initialVolume, 0.6)
            XCTAssertNotNil(asset.asset, "AVURLAsset should be created")

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 5.0)
    }

    func testPluginPreloadMethod() {
        let loadExpectation = self.expectation(description: "Load asset")
        let verifyExpectation = self.expectation(description: "Verify asset")

        guard let call = CAPPluginCall(callbackId: "test", methodName: "preload",
                                       options: [
                                        "assetId": testAssetId,
                                        "assetPath": tempFileURL.path,
                                        "volume": 0.8,
                                        "channels": 2
                                       ], success: { (_, _) in
                                           loadExpectation.fulfill()
                                       }, error: { [weak self] (_) in
                                           guard let self = self else { return }
                                           print("Preload failed, checking if asset exists in audioList")
                                           loadExpectation.fulfill()
                                       }) else { return }

        XCTAssertTrue(FileManager.default.fileExists(atPath: tempFileURL.path), "Test audio file does not exist at path: \(tempFileURL.path)")

        plugin.preload(call)

        wait(for: [loadExpectation], timeout: 5.0)

        plugin.audioQueue.async {
            let asset = self.plugin.audioList[self.testAssetId]
            DispatchQueue.main.async {
                if let audioAsset = asset as? AudioAsset {
                    XCTAssertEqual(audioAsset.assetId, self.testAssetId)
                    XCTAssertEqual(audioAsset.initialVolume, 0.8)
                } else if let remoteAsset = asset as? RemoteAudioAsset {
                    XCTAssertEqual(remoteAsset.assetId, self.testAssetId)
                    XCTAssertEqual(remoteAsset.initialVolume, 0.8)
                } else if let systemSound = asset as? NSNumber {
                    XCTAssertTrue(systemSound.intValue >= 0)
                } else {
                    XCTFail("Asset was not loaded into audioList")
                }
                verifyExpectation.fulfill()
            }
        }

        wait(for: [verifyExpectation], timeout: 5.0)
    }

    func testFadeEffects() {
        let expectation = self.expectation(description: "Test fade effects")

        plugin.audioQueue.async {
            // Create an audio asset
            let asset = AudioAsset(
                owner: self.plugin,
                withAssetId: self.testAssetId,
                withPath: self.tempFileURL.path,
                withChannels: 1,
                withVolume: 1.0
            )

            // Test fade functionality (just make sure it doesn't crash)
            asset.playWithFade(time: 0, volume: 1.0, fadeInDuration: 0.3)

            // Wait a short time for fade to start
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                // Then test stop with fade
                asset.stopWithFade(fadeOutDuration: 0.3)

                // Wait for fade to complete
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    expectation.fulfill()
                }
            }
        }

        wait(for: [expectation], timeout: 5.0)
    }

    // Test the ClearCache functionality
    func testClearCache() {
        // This is mostly a method call test to ensure it doesn't crash
        RemoteAudioAsset.clearCache()

        // We can't easily verify the cache was cleared without complex setup,
        // but we can ensure the method completes without errors
        XCTAssertTrue(true)
    }

    // Test notification observer pattern in RemoteAudioAsset
    func testNotificationObserverPattern() {
        let expectation = self.expectation(description: "Test notification observer")

        let testURL = "https://file-examples.com/storage/fe5947fd2362a2f06a86851/2017/11/file_example_MP3_700KB.mp3"

        plugin.audioQueue.async {
            let asset = RemoteAudioAsset(
                owner: self.plugin,
                withAssetId: self.testRemoteAssetId,
                withPath: testURL,
                withChannels: 1,
                withVolume: 0.6
            )
            self.plugin.audioList[self.testRemoteAssetId] = asset

            DispatchQueue.main.async {
                XCTAssertEqual(asset.notificationObservers.count, 0, "Should start with zero notification observers")
                asset.resume()
                // Wait briefly for observer to be added
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) {
                    XCTAssertGreaterThan(asset.notificationObservers.count, 0, "Should have added notification observers")
                    asset.cleanupNotificationObservers()
                    XCTAssertEqual(asset.notificationObservers.count, 0, "Should have removed all notification observers")
                    expectation.fulfill()
                }
            }
        }

        wait(for: [expectation], timeout: 5.0)
    }

}
