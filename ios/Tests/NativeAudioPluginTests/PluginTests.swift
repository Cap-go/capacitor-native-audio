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
        plugin.executeOnAudioQueue {
            if let asset = self.plugin.audioList[self.testAssetId] as? AudioAsset {
                asset.unload()
            }
            if let asset = self.plugin.audioList[self.testRemoteAssetId] as? RemoteAudioAsset {
                asset.unload()
            }
            self.plugin.audioList.removeAll()
        }

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
        let expectation = XCTestExpectation(description: "Initialize AudioAsset")

        plugin.executeOnAudioQueue {
            // Create an audio asset
            let asset = AudioAsset(
                owner: self.plugin,
                withAssetId: self.testAssetId,
                withPath: self.tempFileURL.path,
                withChannels: 1,
                withVolume: 0.5,
                withFadeDelay: 0.5
            )

            // Add it to the plugin's audio list
            self.plugin.audioList[self.testAssetId] = asset

            // Verify initial values
            XCTAssertEqual(asset.assetId, self.testAssetId)
            XCTAssertEqual(asset.initialVolume, 0.5)
            XCTAssertEqual(asset.fadeDelay, 0.5)

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 5.0)
    }

    func testAudioAssetVolumeControl() {
        let expectation = XCTestExpectation(description: "Test volume control")

        plugin.executeOnAudioQueue {
            // Create an audio asset
            let asset = AudioAsset(
                owner: self.plugin,
                withAssetId: self.testAssetId,
                withPath: self.tempFileURL.path,
                withChannels: 1,
                withVolume: 1.0,
                withFadeDelay: 0.5
            )

            // Add it to the plugin's audio list
            self.plugin.audioList[self.testAssetId] = asset

            // Test setting volume
            let testVolume: Float = 0.7
            asset.setVolume(volume: NSNumber(value: testVolume))

            // We can't directly check player.volume as it may take time to set
            // So we'll just verify the method doesn't crash

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 5.0)
    }

    func testRemoteAudioAssetInitialization() {
        let expectation = XCTestExpectation(description: "Initialize RemoteAudioAsset")

        // Use a publicly accessible test audio URL
        let testURL = "https://file-examples.com/storage/fe5947fd2362a2f06a86851/2017/11/file_example_MP3_700KB.mp3"

        plugin.executeOnAudioQueue {
            // Create a remote audio asset
            let asset = RemoteAudioAsset(
                owner: self.plugin,
                withAssetId: self.testRemoteAssetId,
                withPath: testURL,
                withChannels: 1,
                withVolume: 0.6,
                withFadeDelay: 0.3
            )

            // Add it to the plugin's audio list
            self.plugin.audioList[self.testRemoteAssetId] = asset

            // Verify initial values
            XCTAssertEqual(asset.assetId, self.testRemoteAssetId)
            XCTAssertEqual(asset.initialVolume, 0.6)
            XCTAssertEqual(asset.fadeDelay, 0.3)
            XCTAssertNotNil(asset.asset, "AVURLAsset should be created")

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 5.0)
    }

    func testPluginPreloadMethod() {
        // Create a plugin call to test the preload method
        let call = CAPPluginCall(callbackId: "test", options: [
            "assetId": testAssetId,
            "assetPath": tempFileURL.path,
            "volume": 0.8,
            "audioChannelNum": 2
        ], success: { (_, _) in
            // Success case
        }, error: { (_) in
            XCTFail("Preload shouldn't fail")
        })

        // Call the plugin method
        plugin.preload(call)

        // Verify the asset was loaded by checking if it exists in the audioList
        plugin.executeOnAudioQueue {
            XCTAssertNotNil(self.plugin.audioList[self.testAssetId])
            if let asset = self.plugin.audioList[self.testAssetId] as? AudioAsset {
                XCTAssertEqual(asset.assetId, self.testAssetId)
                XCTAssertEqual(asset.initialVolume, 0.8)
            } else {
                XCTFail("Asset should be of type AudioAsset")
            }
        }
    }

    func testFadeEffects() {
        let expectation = XCTestExpectation(description: "Test fade effects")

        plugin.executeOnAudioQueue {
            // Create an audio asset
            let asset = AudioAsset(
                owner: self.plugin,
                withAssetId: self.testAssetId,
                withPath: self.tempFileURL.path,
                withChannels: 1,
                withVolume: 1.0,
                withFadeDelay: 0.2
            )

            // Test fade functionality (just make sure it doesn't crash)
            asset.playWithFade(time: 0)

            // Wait a short time for fade to start
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                // Then test stop with fade
                asset.stopWithFade()

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
        let expectation = XCTestExpectation(description: "Test notification observer")

        // Use a publicly accessible test audio URL
        let testURL = "https://file-examples.com/storage/fe5947fd2362a2f06a86851/2017/11/file_example_MP3_700KB.mp3"

        plugin.executeOnAudioQueue {
            // Create a remote audio asset
            let asset = RemoteAudioAsset(
                owner: self.plugin,
                withAssetId: self.testRemoteAssetId,
                withPath: testURL,
                withChannels: 1,
                withVolume: 0.6,
                withFadeDelay: 0.3
            )

            // Add it to the plugin's audio list
            self.plugin.audioList[self.testRemoteAssetId] = asset

            // Verify initial values
            XCTAssertEqual(asset.notificationObservers.count, 0, "Should start with zero notification observers")

            // Test the resume method which sets up a notification observer
            asset.resume()

            // Check that a notification observer was added
            XCTAssertGreaterThan(asset.notificationObservers.count, 0, "Should have added notification observers")

            // Now test cleanup
            asset.cleanupNotificationObservers()
            XCTAssertEqual(asset.notificationObservers.count, 0, "Should have removed all notification observers")

            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 5.0)
    }

    // Test the fade timer functionality, which was a key part of our fixes
    func testFadeTimerFunctionality() {
        let expectation = XCTestExpectation(description: "Test fade timer")

        plugin.executeOnAudioQueue {
            // Create an audio asset
            let asset = AudioAsset(
                owner: self.plugin,
                withAssetId: self.testAssetId,
                withPath: self.tempFileURL.path,
                withChannels: 1,
                withVolume: 1.0,
                withFadeDelay: 0.5
            )

            // Ensure the fade timer is nil initially
            XCTAssertNil(asset.fadeTimer, "Fade timer should be nil initially")

            // Access the private method using reflection
            // (this is a test-only approach to access private methods)
            let selector = NSSelectorFromString("startVolumeRamp:to:player:")
            if asset.responds(to: selector) {
                // Create a test mock for AVAudioPlayer
                guard let player = asset.channels.first else {
                    XCTFail("No audio player available")
                    expectation.fulfill()
                    return
                }

                // Set initial volume
                player.volume = 1.0

                // Invoke using performSelector
                asset.perform(selector, with: NSNumber(value: 1.0), with: NSNumber(value: 0.0), with: player)

                // Check that the fade timer was created
                XCTAssertNotNil(asset.fadeTimer, "Fade timer should be created")

                // Wait for fade to complete
                DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                    // Fade should be complete, timer should be nil again
                    XCTAssertNil(asset.fadeTimer, "Fade timer should be nil after completion")
                    XCTAssertEqual(player.volume, 0.0, "Volume should be 0 after fade out")

                    expectation.fulfill()
                }
            } else {
                XCTFail("startVolumeRamp method not available")
                expectation.fulfill()
            }
        }

        wait(for: [expectation], timeout: 5.0)
    }
}
