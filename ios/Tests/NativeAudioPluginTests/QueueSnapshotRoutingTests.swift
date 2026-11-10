import AVFoundation
import Capacitor
import MediaPlayer
import XCTest
@testable import NativeAudioPlugin

final class QueueSnapshotRoutingTests: XCTestCase {

    var plugin = NativeAudio()
    var tempFileURL = URL(fileURLWithPath: NSTemporaryDirectory().appending("queueTest.wav"))

    override func setUp() {
        super.setUp()
        plugin = NativeAudio()
        plugin.isRunningTests = true
        if !FileManager.default.fileExists(atPath: tempFileURL.path) {
            FileManager.default.createFile(atPath: tempFileURL.path, contents: Data(), attributes: nil)
        }
    }

    override func tearDown() {
        plugin.executeOnAudioQueue {
            self.plugin.audioList.removeAll()
            self.plugin.playbackQueueIds = []
            self.plugin.playbackQueueItemById = [:]
            self.plugin.playbackQueueIndex = -1
        }
        super.tearDown()
    }

    private func makeCall(callbackId: String, options: [String: Any]) -> CAPPluginCall {
        guard let call = CAPPluginCall(callbackId: callbackId, options: options, success: { _, _ in }, error: { _ in }) else {
            XCTFail("Failed to create CAPPluginCall")
            fatalError("Failed to create CAPPluginCall")
        }
        return call
    }

    func testSetQueue_skipsItemsMissingAssetPath() {
        let expectation = XCTestExpectation(description: "setQueue filters invalid items")

        let call = makeCall(callbackId: "setQueue", options: [
            "items": [
                ["assetId": "valid", "assetPath": tempFileURL.path, "isUrl": true],
                ["assetId": "invalid", "assetPath": ""]
            ],
            "autoPlay": false
        ])

        plugin.setQueue(call)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
            self.plugin.executeOnAudioQueue {
                XCTAssertEqual(self.plugin.playbackQueueIds.count, 1)
                XCTAssertEqual(self.plugin.playbackQueueIds.first, "valid")
                expectation.fulfill()
            }
        }

        wait(for: [expectation], timeout: 5.0)
    }

    func testGetPlaybackState_returnsFiniteDurationFallback() {
        let expectation = XCTestExpectation(description: "getPlaybackState snapshot")

        plugin.executeOnAudioQueue {
            let asset = AudioAsset(
                owner: self.plugin,
                withAssetId: "snapshot",
                withPath: self.tempFileURL.path,
                withChannels: 1,
                withVolume: 1.0
            )
            self.plugin.audioList["snapshot"] = asset
            self.plugin.currentlyPlayingAssetId = "snapshot"
        }

        let call = makeCall(callbackId: "snapshot", options: [:])
        plugin.getPlaybackState(call)

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
            self.plugin.executeOnAudioQueue {
                if let asset = self.plugin.audioList["snapshot"] as? AudioAsset {
                    let duration = asset.getDuration()
                    XCTAssertTrue(duration.isFinite || duration == 0)
                } else {
                    XCTFail("Expected snapshot asset")
                }
                expectation.fulfill()
            }
        }

        wait(for: [expectation], timeout: 5.0)
    }

    func testRemoteAssetGetPlaybackRate_returnsRequestedRateWhileIdle() {
        let expectation = XCTestExpectation(description: "remote getRate")

        let remoteUrl = "https://file-examples.com/storage/fe5947fd2362a2f06a86851/2017/11/file_example_MP3_700KB.mp3"
        plugin.executeOnAudioQueue {
            let asset = RemoteAudioAsset(
                owner: self.plugin,
                withAssetId: "remote-rate",
                withPath: remoteUrl,
                withChannels: 1,
                withVolume: 1.0,
                withHeaders: nil
            )
            self.plugin.audioList["remote-rate"] = asset
            asset.setRate(rate: NSNumber(value: 1.75))
            XCTAssertEqual(asset.getPlaybackRate(), 1.75, accuracy: 0.001)
            expectation.fulfill()
        }

        wait(for: [expectation], timeout: 5.0)
    }

    func testSetSpeaker_resolvesWithoutError() {
        let call = makeCall(callbackId: "speaker", options: [:])
        plugin.setSpeaker(call)
    }

    func testRefreshQueueRemoteCommands_disabledWithoutShowNotification() {
        plugin.executeOnAudioQueue {
            self.plugin.showNotification = false
            self.plugin.playbackQueueIds = ["a", "b"]
            self.plugin.refreshQueueRemoteCommands()
            let commandCenter = MPRemoteCommandCenter.shared()
            XCTAssertFalse(commandCenter.nextTrackCommand.isEnabled)
            XCTAssertFalse(commandCenter.previousTrackCommand.isEnabled)
        }
    }
}
