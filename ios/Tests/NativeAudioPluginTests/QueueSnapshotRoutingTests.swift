import Capacitor
import MediaPlayer
import XCTest
@testable import NativeAudioPlugin

final class QueueSnapshotRoutingTests: XCTestCase {

    var plugin = NativeAudio()

    override func setUp() {
        super.setUp()
        plugin = NativeAudio()
        plugin.isRunningTests = true
    }

    override func tearDown() {
        plugin.readOnAudioQueue {
            self.plugin.audioList.removeAll()
            self.plugin.playbackQueueIds = []
            self.plugin.playbackQueueItemById = [:]
            self.plugin.playbackQueueIndex = -1
        }
        super.tearDown()
    }

    func testRefreshQueueRemoteCommands_disabledWithoutShowNotification() {
        plugin.readOnAudioQueue {
            self.plugin.showNotification = false
            self.plugin.playbackQueueIds = ["a", "b"]
            self.plugin.refreshQueueRemoteCommands()
            let commandCenter = MPRemoteCommandCenter.shared()
            XCTAssertFalse(commandCenter.nextTrackCommand.isEnabled)
            XCTAssertFalse(commandCenter.previousTrackCommand.isEnabled)
        }
    }

    func testRemoteAssetGetPlaybackRate_returnsRequestedRateWhileIdle() {
        let expectation = XCTestExpectation(description: "remote requested rate")
        let tempPath = NSTemporaryDirectory().appending("remote-rate.mp3")
        FileManager.default.createFile(atPath: tempPath, contents: Data(), attributes: nil)

        plugin.executeOnAudioQueue {
            let asset = RemoteAudioAsset(
                owner: self.plugin,
                withAssetId: "remote-rate",
                withPath: "file://\(tempPath)",
                withChannels: 1,
                withVolume: 1.0,
                withHeaders: nil
            )
            asset.setRate(rate: NSNumber(value: 1.75))
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                XCTAssertEqual(asset.getPlaybackRate(), 1.75, accuracy: 0.001)
                expectation.fulfill()
            }
        }

        wait(for: [expectation], timeout: 3.0)
    }

    func testBuildQueueStateDictionary_reflectsQueueSelection() {
        let state = plugin.readOnAudioQueue { () -> [String: Any] in
            self.plugin.playbackQueueIds = ["one", "two"]
            self.plugin.playbackQueueIndex = 1
            return self.plugin.buildQueueStateDictionary()
        }

        XCTAssertEqual(state["count"] as? Int, 2)
        XCTAssertEqual(state["index"] as? Int, 1)
        XCTAssertEqual(state["assetId"] as? String, "two")
    }
}
