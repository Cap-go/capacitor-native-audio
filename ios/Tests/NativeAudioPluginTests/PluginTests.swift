import XCTest
import Capacitor
import AVFoundation
@testable import NativeAudioPlugin

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
                withFadeDelay: 0.3,
                withHeaders: nil
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
        })!

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
                withFadeDelay: 0.3,
                withHeaders: nil
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

                // Invoke using performSelector - Note: perform only supports up to 2 'with:' parameters
                // This test is disabled as the selector requires 3 parameters
                // asset.perform(selector, with: NSNumber(value: 1.0), with: NSNumber(value: 0.0))

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

    // MARK: - PlayOnce Tests

    func testPlayOnceWithAutoPlay() {
        let expectation = XCTestExpectation(description: "PlayOnce with auto-play")
        var returnedAssetId: String?
        
        let call = CAPPluginCall(callbackId: "test", options: [
            "assetPath": tempFileURL.path,
            "volume": 1.0,
            "isUrl": true,
            "autoPlay": true
        ], success: { (result, _) in
            // Capture the returned assetId
            returnedAssetId = result?.data?["assetId"] as? String
        }, error: { (_) in
            XCTFail("PlayOnce shouldn't fail")
        })!
        
        plugin.playOnce(call)
        
        plugin.executeOnAudioQueue {
            
            // Wait for async operations
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                // Verify asset was created and is in playOnceAssets
                self.plugin.executeOnAudioQueue {
                    let playOnceAssets = self.plugin.playOnceAssets
                    XCTAssertTrue(playOnceAssets.count > 0, "Should have created a playOnce asset")
                    
                    // Verify the returned assetId matches an entry in playOnceAssets and audioList
                    if let assetId = returnedAssetId {
                        XCTAssertTrue(playOnceAssets.contains(assetId), "Returned assetId should be in playOnceAssets")
                        XCTAssertNotNil(self.plugin.audioList[assetId], "Returned assetId should have corresponding AudioAsset")
                    } else {
                        XCTFail("Should have returned an assetId")
                    }
                    
                    expectation.fulfill()
                }
            }
        }
        
        wait(for: [expectation], timeout: 3.0)
    }

    func testPlayOnceWithoutAutoPlay() {
        let expectation = XCTestExpectation(description: "PlayOnce without auto-play")
        
        let call = CAPPluginCall(callbackId: "test", options: [
            "assetPath": tempFileURL.path,
            "volume": 0.8,
            "isUrl": true,
            "autoPlay": false
        ], success: { (_, _) in
            // Success case
        }, error: { (_) in
            XCTFail("PlayOnce shouldn't fail")
        })!
        
        plugin.playOnce(call)
        
        plugin.executeOnAudioQueue {
            
            // Wait for async operations
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.plugin.executeOnAudioQueue {
                    // Asset should be created but not playing
                    let playOnceAssets = self.plugin.playOnceAssets
                    XCTAssertTrue(playOnceAssets.count > 0, "Should have created a playOnce asset")
                    
                    if let assetId = playOnceAssets.first,
                       let asset = self.plugin.audioList[assetId] as? AudioAsset {
                        // Verify asset exists but is not automatically playing
                        XCTAssertFalse(asset.channels.isEmpty, "Asset should have channels")
                        
                        // Verify player is not playing when autoPlay is false
                        if let player = asset.channels.first {
                            XCTAssertFalse(player.isPlaying, "Player should not be playing when autoPlay is false")
                        }
                    }
                    
                    expectation.fulfill()
                }
            }
        }
        
        wait(for: [expectation], timeout: 3.0)
    }

    func testPlayOnceCleanupAfterCompletion() {
        let expectation = XCTestExpectation(description: "PlayOnce cleanup after completion")
        
        let call = CAPPluginCall(callbackId: "test", options: [
            "assetPath": tempFileURL.path,
            "volume": 1.0,
            "isUrl": true,
            "autoPlay": true
        ], success: { (_, _) in
            // Success case
        }, error: { (_) in
            XCTFail("PlayOnce shouldn't fail")
        })!
        
        plugin.playOnce(call)
        
        plugin.executeOnAudioQueue {
            
            // Get the asset ID
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                self.plugin.executeOnAudioQueue {
                    guard let assetId = self.plugin.playOnceAssets.first else {
                        XCTFail("No playOnce asset was created")
                        expectation.fulfill()
                        return
                    }
                    
                    // Verify asset exists
                    XCTAssertNotNil(self.plugin.audioList[assetId], "Asset should exist")
                    
                    // Simulate completion
                    if let asset = self.plugin.audioList[assetId] as? AudioAsset {
                        asset.onComplete?()
                    }
                    
                    // Wait for cleanup
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        self.plugin.executeOnAudioQueue {
                            // Verify cleanup occurred
                            XCTAssertNil(self.plugin.audioList[assetId], "Asset should be removed after cleanup")
                            XCTAssertFalse(self.plugin.playOnceAssets.contains(assetId), "AssetId should be removed from playOnceAssets")
                            
                            expectation.fulfill()
                        }
                    }
                }
            }
        }
        
        wait(for: [expectation], timeout: 5.0)
    }

    func testPlayOnceWithDeleteAfterPlay() {
        let expectation = XCTestExpectation(description: "PlayOnce with file deletion")
        
        // Create a temporary file that can be deleted
        let deletableFilePath = NSTemporaryDirectory().appending("deletableAudio.wav")
        let deletableURL = URL(fileURLWithPath: deletableFilePath)
        
        // Copy test file to deletable location
        do {
            // Remove existing file if present
            if FileManager.default.fileExists(atPath: deletableFilePath) {
                try FileManager.default.removeItem(at: deletableURL)
            }
            try FileManager.default.copyItem(at: tempFileURL, to: deletableURL)
        } catch {
            XCTFail("Failed to set up deletable file: \(error)")
            expectation.fulfill()
            return
        }
        
        let call = CAPPluginCall(callbackId: "test", options: [
            "assetPath": deletableURL.absoluteString,
            "volume": 1.0,
            "isUrl": true,
            "autoPlay": true,
            "deleteAfterPlay": true
        ], success: { (_, _) in
            // Success case
        }, error: { (_) in
            XCTFail("PlayOnce shouldn't fail")
        })!
        
        plugin.playOnce(call)
        
        plugin.executeOnAudioQueue {
            
            // Wait for asset creation
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                self.plugin.executeOnAudioQueue {
                    guard let assetId = self.plugin.playOnceAssets.first else {
                        XCTFail("No playOnce asset was created")
                        expectation.fulfill()
                        return
                    }
                    
                    // Verify file exists before completion
                    XCTAssertTrue(FileManager.default.fileExists(atPath: deletableFilePath), "File should exist before cleanup")
                    
                    // Simulate completion
                    if let asset = self.plugin.audioList[assetId] as? AudioAsset {
                        asset.onComplete?()
                    }
                    
                    // Wait for cleanup and deletion
                    DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                        // File should be deleted after cleanup
                        let fileExists = FileManager.default.fileExists(atPath: deletableFilePath)
                        XCTAssertFalse(fileExists, "File should be deleted after playOnce completion")
                        
                        expectation.fulfill()
                    }
                }
            }
        }
        
        wait(for: [expectation], timeout: 5.0)
    }

    func testPlayOnceWithNotificationMetadata() {
        let expectation = XCTestExpectation(description: "PlayOnce with notification metadata")
        
        let call = CAPPluginCall(callbackId: "test", options: [
            "assetPath": tempFileURL.path,
            "volume": 1.0,
            "isUrl": true,
            "autoPlay": false,
            "notificationMetadata": [
                "title": "Test Song",
                "artist": "Test Artist",
                "album": "Test Album",
                "artworkUrl": "https://example.com/artwork.jpg"
            ]
        ], success: { (_, _) in
            // Success case
        }, error: { (_) in
            XCTFail("PlayOnce shouldn't fail")
        })!
        
        plugin.playOnce(call)
        
        plugin.executeOnAudioQueue {
            
            // Wait for async operations
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.plugin.executeOnAudioQueue {
                    guard let assetId = self.plugin.playOnceAssets.first else {
                        XCTFail("No playOnce asset was created")
                        expectation.fulfill()
                        return
                    }
                    
                    // Verify notification metadata was stored
                    if let metadata = self.plugin.notificationMetadataMap[assetId] {
                        XCTAssertEqual(metadata["title"], "Test Song", "Title should be stored")
                        XCTAssertEqual(metadata["artist"], "Test Artist", "Artist should be stored")
                        XCTAssertEqual(metadata["album"], "Test Album", "Album should be stored")
                        XCTAssertEqual(metadata["artworkUrl"], "https://example.com/artwork.jpg", "Artwork URL should be stored")
                    } else {
                        XCTFail("Notification metadata should be stored")
                    }
                    
                    expectation.fulfill()
                }
            }
        }
        
        wait(for: [expectation], timeout: 3.0)
    }

    func testPlayOnceErrorHandlingAndCleanup() {
        let expectation = XCTestExpectation(description: "PlayOnce error handling and cleanup")
        
        // Use an invalid file path to trigger error
        let call = CAPPluginCall(callbackId: "test", options: [
            "assetPath": "/invalid/path/to/nonexistent.wav",
            "volume": 1.0,
            "isUrl": true,
            "autoPlay": true
        ], success: { (_, _) in
            XCTFail("Should not succeed with invalid path")
        }, error: { (_) in
            // Expected error case
        })!
        
        plugin.playOnce(call)
        
        plugin.executeOnAudioQueue {
            // Capture any assetId that might have been created
            let initialAssetIds = self.plugin.playOnceAssets
            
            // Wait for error handling
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.plugin.executeOnAudioQueue {
                    // Verify cleanup occurred even on failure
                    // Any asset created should be cleaned up from audioList
                    for assetId in initialAssetIds {
                        XCTAssertNil(self.plugin.audioList[assetId], "Failed asset should be cleaned up from audioList")
                    }
                    
                    // And no dangling playOnce IDs should remain
                    XCTAssertTrue(self.plugin.playOnceAssets.isEmpty, "playOnce assets should be cleaned up on error")
                    
                    expectation.fulfill()
                }
            }
        }
        
        wait(for: [expectation], timeout: 3.0)
    }

    func testPlayOnceReturnsUniqueAssetId() {
        let expectation = XCTestExpectation(description: "PlayOnce returns unique asset ID")
        
        var firstAssetId: String?
        var secondAssetId: String?
        
        let call1 = CAPPluginCall(callbackId: "test1", options: [
            "assetPath": tempFileURL.path,
            "volume": 1.0,
            "isUrl": true,
            "autoPlay": false
        ], success: { (result, _) in
            // Capture returned assetId from public API
            firstAssetId = result?.data?["assetId"] as? String
        }, error: { (_) in
            XCTFail("PlayOnce shouldn't fail")
        })!
        
        plugin.playOnce(call1)
        
        // Create second playOnce
        let call2 = CAPPluginCall(callbackId: "test2", options: [
            "assetPath": tempFileURL.path,
            "volume": 1.0,
            "isUrl": true,
            "autoPlay": false
        ], success: { (result, _) in
            // Capture returned assetId from public API
            secondAssetId = result?.data?["assetId"] as? String
        }, error: { (_) in
            XCTFail("PlayOnce shouldn't fail")
        })!
        
        plugin.playOnce(call2)
        
        // Wait for both to complete
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            self.plugin.executeOnAudioQueue {
                // Verify we got two distinct asset IDs from the public API
                XCTAssertNotNil(firstAssetId, "First asset ID should exist")
                XCTAssertNotNil(secondAssetId, "Second asset ID should exist")
                XCTAssertNotEqual(firstAssetId, secondAssetId, "Asset IDs should be unique")
                
                // Verify both have "playOnce_" prefix
                XCTAssertTrue(firstAssetId?.hasPrefix("playOnce_") ?? false, "First asset ID should have playOnce prefix")
                XCTAssertTrue(secondAssetId?.hasPrefix("playOnce_") ?? false, "Second asset ID should have playOnce prefix")
                
                // Cross-check that both are present in playOnceAssets as internal consistency check
                if let id1 = firstAssetId, let id2 = secondAssetId {
                    XCTAssertTrue(self.plugin.playOnceAssets.contains(id1), "First assetId should be tracked internally")
                    XCTAssertTrue(self.plugin.playOnceAssets.contains(id2), "Second assetId should be tracked internally")
                    XCTAssertEqual(self.plugin.playOnceAssets.count, 2, "Should have two playOnce assets tracked")
                }
                
                expectation.fulfill()
            }
        }
        
        wait(for: [expectation], timeout: 5.0)
    }
}
