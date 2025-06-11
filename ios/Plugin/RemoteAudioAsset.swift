import AVFoundation
import os.log

/**
 * RemoteAudioAsset extends AudioAsset to handle remote (URL-based) audio files
 * Provides network audio playback using AVPlayer instead of AVAudioPlayer
 */
public class RemoteAudioAsset: AudioAsset {
    var playerItems: [AVPlayerItem] = []
    var players: [AVPlayer] = []
    var playerObservers: [NSKeyValueObservation] = []
    var notificationObservers: [NSObjectProtocol] = []
    var duration: TimeInterval = 0
    var asset: AVURLAsset?
    private var logger = Logger(logTag: "RemoteAudioAsset")
    private static let staticLogger = Logger(logTag: "RemoteAudioAsset")

    override init(owner: NativeAudio, withAssetId assetId: String, withPath path: String!, withChannels channels: Int!, withVolume volume: Float!) {
        super.init(owner: owner, withAssetId: assetId, withPath: path, withChannels: channels ?? 1, withVolume: volume ?? 1.0)

        owner.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard let url = URL(string: path ?? "") else {
                logger.error("Invalid URL: %@", String(describing: path))
                return
            }

            let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: true])
            self.asset = asset

            // Limit channels to a reasonable maximum to prevent resource issues
            let channelCount = min(max(channels ?? Constant.DefaultChannels, 1), Constant.MaxChannels)

            for _ in 0..<channelCount {
                let playerItem = AVPlayerItem(asset: asset)
                let player = AVPlayer(playerItem: playerItem)
                // Apply volume constraints consistent with AudioAsset
                player.volume = self.initialVolume
                player.rate = 1.0
                self.playerItems.append(playerItem)
                self.players.append(player)

                // Add observer for duration
                let durationObserver = playerItem.observe(\.status) { [weak self] item, _ in
                    guard let strongSelf = self else { return }
                    strongSelf.owner?.executeOnAudioQueue {
                        if item.status == .readyToPlay {
                            strongSelf.duration = item.duration.seconds
                        }
                    }
                }
                self.playerObservers.append(durationObserver)

                // Add observer for playback finished
                let observer = player.observe(\.timeControlStatus) { [weak self, weak player] observedPlayer, _ in
                    guard let strongSelf = self,
                          let strongPlayer = player,
                          strongPlayer === observedPlayer else { return }

                    if strongPlayer.timeControlStatus == .paused &&
                        (strongPlayer.currentItem?.currentTime() == strongPlayer.currentItem?.duration ||
                            strongPlayer.currentItem?.duration == .zero) {
                        strongSelf.playerDidFinishPlaying(player: strongPlayer)
                    }
                }
                self.playerObservers.append(observer)
            }
        }
    }

    deinit {
        // Clean up observers
        for observer in playerObservers {
            observer.invalidate()
        }

        for observer in notificationObservers {
            NotificationCenter.default.removeObserver(observer)
        }

        // Clean up players
        for player in players {
            player.pause()
        }

        playerItems = []
        players = []
        playerObservers = []
        notificationObservers = []
        cancelFade()
    }

    func playerDidFinishPlaying(player: AVPlayer) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            self.owner?.notifyListeners("complete", data: [
                "assetId": self.assetId
            ])
            self.dispatchedCompleteMap[assetId] = true
        }
    }

    /**
     * Play the audio from the specified time with optional delay
     * - Parameters:
     *   - time: Start time in seconds
     *   - volume: Volume level (0.0-1.0)
     */
    override func play(time: TimeInterval, volume: Float? = nil) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !players.isEmpty else { return }

            // Reset play index if it's out of bounds
            if playIndex >= players.count {
                playIndex = 0
            }

            let player = players[playIndex]

            // Ensure non-negative values for time and delay
            let validTime = max(time, 0)

            player.seek(to: CMTimeMakeWithSeconds(validTime, preferredTimescale: 1))
            player.volume = (volume ?? self.initialVolume)
            player.play()
            playIndex = (playIndex + 1) % players.count
            startCurrentTimeUpdates()
        }
    }

    override func pause() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !players.isEmpty && playIndex < players.count else { return }

            cancelFade()

            let player = players[playIndex]
            player.pause()
            stopCurrentTimeUpdates()
        }
    }

    override func resume() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !players.isEmpty && playIndex < players.count else { return }

            let player = players[playIndex]
            player.play()

            // Add notification observer for when playback stops
            cleanupNotificationObservers()

            // Capture weak reference to self
            let observer = NotificationCenter.default.addObserver(
                forName: NSNotification.Name.AVPlayerItemDidPlayToEndTime,
                object: player.currentItem,
                queue: OperationQueue.main) { [weak self, weak player] notification in
                guard let strongSelf = self, let strongPlayer = player else { return }

                if let currentItem = notification.object as? AVPlayerItem,
                   strongPlayer.currentItem == currentItem {
                    strongSelf.playerDidFinishPlaying(player: strongPlayer)
                }
            }
            notificationObservers.append(observer)
            startCurrentTimeUpdates()
        }
    }

    override func stop() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            stopCurrentTimeUpdates()
            cancelFade()

            for player in players {
                // First pause
                player.pause()
                // Then reset to beginning
                player.seek(to: .zero, completionHandler: { _ in
                    // Reset any loop settings
                    player.actionAtItemEnd = .pause
                })
                self.owner?.notifyListeners("complete", data: [
                    "assetId": self.assetId as Any
                ])
                self.dispatchedCompleteMap[assetId] = true
            }
            // Reset playback state
            playIndex = 0
        }
    }

    override func loop() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            cleanupNotificationObservers()

            for (index, player) in players.enumerated() {
                player.actionAtItemEnd = .none

                guard let playerItem = player.currentItem else { continue }

                let observer = NotificationCenter.default.addObserver(
                    forName: .AVPlayerItemDidPlayToEndTime,
                    object: playerItem,
                    queue: OperationQueue.main) { [weak player] notification in
                    guard let strongPlayer = player,
                          let item = notification.object as? AVPlayerItem,
                          strongPlayer.currentItem === item else { return }

                    strongPlayer.seek(to: .zero)
                    strongPlayer.play()
                }

                notificationObservers.append(observer)

                if index == playIndex {
                    player.seek(to: .zero)
                    player.play()
                }
            }

            startCurrentTimeUpdates()
        }
    }

    private func cleanupNotificationObservers() {
        for observer in notificationObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        notificationObservers = []
    }

    @objc func playerItemDidReachEnd(notification: Notification) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            if let playerItem = notification.object as? AVPlayerItem,
               let player = players.first(where: { $0.currentItem == playerItem }) {
                player.seek(to: .zero)
                player.play()
            }
        }
    }

    override func unload() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            cancelFade()
            stopCurrentTimeUpdates()
            stop()

            cleanupNotificationObservers()

            // Remove KVO observers
            for observer in playerObservers {
                observer.invalidate()
            }
            playerObservers = []
            players = []
            playerItems = []
        }
    }

    /**
     * Set the volume for all audio channels
     * - Parameter volume: Volume level (0.0-1.0)
     */
    override func setVolume(volume: NSNumber!, fadeDuration: Double) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            cancelFade()
            // Ensure volume is in valid range
            let validVolume = min(max(volume.floatValue, Constant.MinVolume), Constant.MaxVolume)
            for player in players {
                if isPlaying() && fadeDuration > 0 {
                    self.logger.debug("Fade to volume %2f over @%2f seconds", validVolume, fadeDuration)
                    self.fadeTo(player: player, fadeOutDuration: fadeDuration, targetVolume: validVolume)
                } else {
                    self.logger.debug("Set volume to %2f", validVolume)
                    player.volume = validVolume
                }
            }
        }
    }

    override func setRate(rate: NSNumber!) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            // Ensure rate is in valid range
            let validRate = min(max(rate.floatValue, Constant.MinRate), Constant.MaxRate)
            for player in players {
                player.rate = validRate
            }
        }
    }

    override func isPlaying() -> Bool {
        var result = false
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !players.isEmpty && playIndex < players.count else {
                result = false
                return
            }
            let player = players[playIndex]
            result = player.timeControlStatus == .playing
        }
        return result
    }

    override func getCurrentTime() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !players.isEmpty && playIndex < players.count else {
                result = 0
                return
            }
            let player = players[playIndex]
            result = player.currentTime().seconds
        }
        return result
    }

    override func getDuration() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !players.isEmpty && playIndex < players.count else {
                result = 0
                return
            }
            let player = players[playIndex]
            if player.currentItem?.duration == CMTime.indefinite {
                result = 0
                return
            }
            result = player.currentItem?.duration.seconds ?? 0
        }
        return result
    }

    /**
     * Play the audio with fade-in effect
     * - Parameters:
     *   - time: Start time in seconds
     *   - volume: Volume level (0.0-1.0)
     *   - fadeInDuration: Duration of the fade-in effect in seconds
     */
    override func playWithFade(time: TimeInterval, volume: Float?, fadeInDuration: TimeInterval) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }
            guard !players.isEmpty && playIndex < players.count else { return }

            let player = players[playIndex]
            player.seek(to: CMTimeMakeWithSeconds(time, preferredTimescale: 1))

            if player.timeControlStatus != .playing {
                player.volume = 0 // Start with volume at 0
                player.play()
                self.fadeIn(player: player, fadeInDuration: fadeInDuration, targetVolume: volume ?? self.initialVolume)
                playIndex = (playIndex + 1) % players.count
                startCurrentTimeUpdates()
            }
        }
    }

    func fadeIn(player: AVPlayer, fadeInDuration: TimeInterval, targetVolume: Float) {
        cancelFade()
        let steps = Int(fadeInDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else { return }
        let fadeStep = targetVolume / Float(steps)
        var currentVolume: Float = 0

        logger.debug("Beginning fade in at time %2f over @%2f seconds to target volume %2f in %d steps (step duration: %2fs)", getCurrentTime(), fadeInDuration, targetVolume, steps, fadeDelaySecs)

        var task: DispatchWorkItem!
        task = DispatchWorkItem { [weak self] in
            guard !task.isCancelled else { return }
            guard let strongSelf = self, strongSelf.isPlaying(), player.timeControlStatus == .playing else {
                task.cancel()
                return
            }
            for _ in 0..<steps {
                let previousCurrentVolume = currentVolume
                currentVolume += fadeStep
                DispatchQueue.main.async {
                    guard let strongerSelf = self, strongerSelf.isPlaying() else {
                        return
                    }
                    let thisTargetVolume = min(currentVolume, targetVolume)
                    strongerSelf.logger.verbose("Fade in step: from %2f to %2f to target %2f", previousCurrentVolume, currentVolume, thisTargetVolume)
                    player.volume = thisTargetVolume
                }
                Thread.sleep(forTimeInterval: TimeInterval(strongSelf.fadeDelaySecs))
            }
            strongSelf.logger.debug("Fade in complete at time %2f", strongSelf.getCurrentTime())
        }
        fadeTask = task
        fadeQueue.async(execute: task)
    }

    override func stopWithFade(fadeOutDuration: TimeInterval, toPause: Bool = false) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !players.isEmpty && playIndex < players.count else {
                if !toPause {
                    stop()
                }
                return
            }

            let player = players[playIndex]

            if player.timeControlStatus == .playing {
                self.fadeOut(player: player, fadeOutDuration: fadeOutDuration, toPause: toPause)
            } else {
                if !toPause {
                    stop()
                }
            }
        }
    }

    func fadeOut(player: AVPlayer, fadeOutDuration: TimeInterval, toPause: Bool = false) {
        cancelFade()
        let steps = Int(fadeOutDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else { return }
        let fadeStep = player.volume / Float(steps)
        var currentVolume: Float = player.volume

        logger.debug("Beginning fade out from volume %2f at time %2f over @%2f seconds in %d steps (step duration: %2fs)", currentVolume, getCurrentTime(), fadeOutDuration, steps, fadeDelaySecs)

        var task: DispatchWorkItem!
        task = DispatchWorkItem { [weak self] in
            for _ in 0..<steps {
                guard !task.isCancelled else { return }
                guard let strongSelf = self, strongSelf.isPlaying(), player.timeControlStatus == .playing else {
                    task.cancel()
                    return
                }
                let previousCurrentVolume = currentVolume
                currentVolume -= fadeStep
                DispatchQueue.main.async {
                    let thisTargetVolume = max(currentVolume, 0)
                    strongSelf.logger.verbose("Fade out step: from %2f to %2f to target %2f", previousCurrentVolume, currentVolume, thisTargetVolume)
                    player.volume = thisTargetVolume
                }
                Thread.sleep(forTimeInterval: TimeInterval(strongSelf.fadeDelaySecs))
            }
            DispatchQueue.main.async {
                guard let strongSelf = self, strongSelf.isPlaying(), player.timeControlStatus == .playing else {
                    task.cancel()
                    return
                }
                if toPause {
                    player.pause()
                } else {
                    player.pause()
                    player.seek(to: .zero)
                    if strongSelf.assetId == "" {
                        return
                    }
                    strongSelf.owner?.notifyListeners("complete", data: [
                        "assetId": strongSelf.assetId as Any
                    ])
                    strongSelf.dispatchedCompleteMap[strongSelf.assetId] = true
                }
                strongSelf.logger.debug("Fade out complete at time %2f", strongSelf.getCurrentTime())
            }
        }
        fadeTask = task
        fadeQueue.async(execute: task)
    }

    /// Exponential fade to a target volume over a duration.
    /// - Parameters:
    ///   - player: The AVPlayer to fade.
    ///   - fadeOutDuration: Duration of the fade in seconds.
    ///   - targetVolume: The target volume (0.0-1.0).
    func fadeTo(player: AVPlayer, fadeOutDuration: TimeInterval, targetVolume: Float) {
        cancelFade() // Cancel any ongoing fade

        let steps = Int(fadeOutDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else { return }

        // Use inherited zeroVolume and maxVolume for safe bounds
        let minVolume = zeroVolume
        var currentVolume: Float = max(player.volume, minVolume)
        let safeTargetVolume: Float = max(targetVolume, minVolume)

        // Calculate the exponential ratio for each step
        let ratio = pow(safeTargetVolume / currentVolume, 1.0 / Float(steps))

        logger.debug("Beginning exponential fade from volume %2f to %2f at time %2f over %2f seconds in %d steps (step duration: %2fs)", currentVolume, safeTargetVolume, getCurrentTime(), fadeOutDuration, steps, fadeDelaySecs)

        var task: DispatchWorkItem!
        task = DispatchWorkItem { [weak self] in
            guard !task.isCancelled else { return }
            guard let strongSelf = self, strongSelf.isPlaying(), player.timeControlStatus == .playing else {
                task.cancel()
                return
            }
            for _ in 0..<steps {
                let previousCurrentVolume = currentVolume
                currentVolume *= ratio
                DispatchQueue.main.async {
                    guard let strongerSelf = self, strongerSelf.isPlaying(), player.timeControlStatus == .playing else {
                        return
                    }
                    let thisTargetVolume = min(max(currentVolume, minVolume), strongSelf.maxVolume)
                    strongerSelf.logger.verbose("Exponential fade step: from %2f to %2f to target %2f", previousCurrentVolume, currentVolume, thisTargetVolume)
                    player.volume = thisTargetVolume
                }
                Thread.sleep(forTimeInterval: TimeInterval(strongSelf.fadeDelaySecs))
            }
            strongSelf.logger.debug("Exponential fade complete at time %2f", strongSelf.getCurrentTime())
        }
        fadeTask = task
        fadeQueue.async(execute: task)
    }

    static func clearCache() {
        DispatchQueue.global(qos: .background).sync {
            let urls = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
            if let cachePath = urls.first {
                do {
                    let fileURLs = try FileManager.default.contentsOfDirectory(at: cachePath, includingPropertiesForKeys: nil)
                    // Clear all audio file types
                    let audioExtensions = ["mp3", "wav", "aac", "m4a", "ogg", "mp4", "caf", "aiff"]
                    for fileURL in fileURLs where audioExtensions.contains(fileURL.pathExtension.lowercased()) {
                        try FileManager.default.removeItem(at: fileURL)
                    }
                } catch {
                    staticLogger.error("Error clearing audio cache: %@", error.localizedDescription)
                }
            }
        }
    }

}
