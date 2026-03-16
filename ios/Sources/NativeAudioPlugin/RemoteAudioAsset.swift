import AVFoundation

// swiftlint:disable:next type_body_length
public class RemoteAudioAsset: AudioAsset {
    var playerItems: [AVPlayerItem] = []
    var players: [AVPlayer] = []
    var playerObservers: [NSKeyValueObservation] = []
    var notificationObservers: [NSObjectProtocol] = []
    var duration: TimeInterval = 0
    var asset: AVURLAsset?
    private var logger = Logger(logTag: "RemoteAudioAsset")
    static let staticLogger = Logger(logTag: "RemoteAudioAsset")

    init(owner: NativeAudio, withAssetId assetId: String, withPath path: String, withChannels channels: Int?, withVolume volume: Float?, withHeaders headers: [String: String]?) {
        super.init(owner: owner, withAssetId: assetId, withPath: path, withChannels: channels ?? 1, withVolume: volume ?? 1.0)

        let setupBlock = { [weak self] in
            guard let self else { return }
            guard let url = URL(string: path) else {
                self.logger.error("Invalid URL: %@", String(describing: path))
                return
            }

            var options: [String: Any] = [AVURLAssetPreferPreciseDurationAndTimingKey: true]
            if let headers, !headers.isEmpty {
                options["AVURLAssetHTTPHeaderFieldsKey"] = headers
            }

            let asset = AVURLAsset(url: url, options: options)
            self.asset = asset
            let channelCount = min(max(channels ?? Constant.DefaultChannels, 1), Constant.MaxChannels)

            for _ in 0..<channelCount {
                let playerItem = AVPlayerItem(asset: asset)
                let player = AVPlayer(playerItem: playerItem)
                player.volume = self.initialVolume
                player.rate = 1.0
                self.playerItems.append(playerItem)
                self.players.append(player)

                let durationObserver = playerItem.observe(\.status) { [weak self] item, _ in
                    guard let self else { return }
                    self.owner?.executeOnAudioQueue {
                        if item.status == .readyToPlay {
                            self.duration = item.duration.seconds
                        }
                    }
                }
                self.playerObservers.append(durationObserver)

                let observer = player.observe(\.timeControlStatus) { [weak self, weak player] observedPlayer, _ in
                    guard let self, let player, player === observedPlayer else { return }
                    if player.timeControlStatus == .paused &&
                        (player.currentItem?.currentTime() == player.currentItem?.duration || player.currentItem?.duration == .zero) {
                        self.playerDidFinishPlaying(player: player)
                    }
                }
                self.playerObservers.append(observer)
            }
        }

        if owner.isRunningTests {
            setupBlock()
        } else {
            owner.executeOnAudioQueue(setupBlock)
        }
    }

    // Backward-compatible initializer signature (delegates to primary init)
    convenience init(owner: NativeAudio, withAssetId assetId: String, withPath path: String, withChannels channels: Int?, withVolume volume: Float?, withFadeDelay _: Float?, withHeaders headers: [String: String]?) {
        self.init(owner: owner, withAssetId: assetId, withPath: path, withChannels: channels, withVolume: volume, withHeaders: headers)
    }

    deinit {
        for observer in playerObservers {
            observer.invalidate()
        }
        cleanupNotificationObservers()
        for player in players {
            player.pause()
        }
        playerItems = []
        players = []
        playerObservers = []
        cancelFade()
    }

    func playerDidFinishPlaying(player: AVPlayer) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            self.owner?.notifyListeners("complete", data: ["assetId": self.assetId])
            self.dispatchedCompleteMap[self.assetId] = true
            self.onComplete?()
        }
    }

    override func play(time: TimeInterval, volume: Float? = nil) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !players.isEmpty else { return }
            if playIndex >= players.count {
                playIndex = 0
            }
            cancelFade()
            let player = players[playIndex]
            player.seek(to: CMTimeMakeWithSeconds(max(time, 0), preferredTimescale: 1))
            player.volume = volume ?? self.initialVolume
            player.play()
            playIndex = (playIndex + 1) % players.count
            startCurrentTimeUpdates()
        }
    }

    // Backward-compatible signature
    override func play(time: TimeInterval, delay: TimeInterval) {
        let validDelay = max(delay, 0)
        if validDelay > 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + validDelay) { [weak self] in
                self?.play(time: time, volume: nil)
            }
        } else {
            play(time: time, volume: nil)
        }
    }

    override func pause() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !players.isEmpty && playIndex < players.count else { return }
            cancelFade()
            players[playIndex].pause()
            stopCurrentTimeUpdates()
        }
    }

    override func resume() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !players.isEmpty && playIndex < players.count else { return }

            let player = players[playIndex]
            player.play()
            cleanupNotificationObservers()
            let observer = NotificationCenter.default.addObserver(
                forName: NSNotification.Name.AVPlayerItemDidPlayToEndTime,
                object: player.currentItem,
                queue: OperationQueue.main
            ) { [weak self, weak player] notification in
                guard let self, let player else { return }
                if let currentItem = notification.object as? AVPlayerItem, player.currentItem === currentItem {
                    self.playerDidFinishPlaying(player: player)
                }
            }
            notificationObservers.append(observer)
            startCurrentTimeUpdates()
        }
    }

    override func stop() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            stopCurrentTimeUpdates()
            cancelFade()
            for player in players {
                player.pause()
                player.seek(to: .zero, completionHandler: { _ in
                    player.actionAtItemEnd = .pause
                })
            }
            playIndex = 0
            self.owner?.notifyListeners("complete", data: ["assetId": self.assetId as Any])
            self.dispatchedCompleteMap[self.assetId] = true
        }
    }

    override func loop() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            cleanupNotificationObservers()
            for (index, player) in players.enumerated() {
                player.actionAtItemEnd = .none
                guard let playerItem = player.currentItem else { continue }
                let observer = NotificationCenter.default.addObserver(
                    forName: .AVPlayerItemDidPlayToEndTime,
                    object: playerItem,
                    queue: OperationQueue.main
                ) { [weak player] notification in
                    guard let player,
                          let item = notification.object as? AVPlayerItem,
                          player.currentItem === item else { return }
                    player.seek(to: .zero)
                    player.play()
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

    public func cleanupNotificationObservers() {
        for observer in notificationObservers {
            NotificationCenter.default.removeObserver(observer)
        }
        notificationObservers = []
    }

    override func unload() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            cancelFade()
            stopCurrentTimeUpdates()
            stop()
            cleanupNotificationObservers()
            for observer in playerObservers {
                observer.invalidate()
            }
            playerObservers = []
            players = []
            playerItems = []
        }
    }

    override func setVolume(volume: NSNumber, fadeDuration: Double) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            cancelFade()
            let validVolume = min(max(volume.floatValue, Constant.MinVolume), Constant.MaxVolume)
            for player in players {
                if isPlaying() && fadeDuration > 0 {
                    fadeTo(player: player, fadeOutDuration: fadeDuration, targetVolume: validVolume)
                } else {
                    player.volume = validVolume
                }
            }
        }
    }

    override func setVolume(volume: NSNumber) {
        setVolume(volume: volume, fadeDuration: 0)
    }

    override func setRate(rate: NSNumber) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            let validRate = min(max(rate.floatValue, Constant.MinRate), Constant.MaxRate)
            for player in players {
                player.rate = validRate
            }
        }
    }

    override func isPlaying() -> Bool {
        var result = false
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !players.isEmpty && playIndex < players.count else {
                result = false
                return
            }
            result = players[playIndex].timeControlStatus == .playing
        }
        return result
    }

    override func getCurrentTime() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !players.isEmpty && playIndex < players.count else { return }
            result = players[playIndex].currentTime().seconds
        }
        return result
    }

    override func getDuration() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !players.isEmpty && playIndex < players.count else { return }
            let player = players[playIndex]
            if player.currentItem?.duration == CMTime.indefinite {
                result = 0
                return
            }
            result = player.currentItem?.duration.seconds ?? 0
        }
        return result
    }

    override func playWithFade(time: TimeInterval, volume: Float?, fadeInDuration: TimeInterval) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !players.isEmpty && playIndex < players.count else { return }
            let player = players[playIndex]
            player.seek(to: CMTimeMakeWithSeconds(time, preferredTimescale: 1)) { [weak self] _ in
                guard let self else { return }
                DispatchQueue.main.async {
                    if player.timeControlStatus != .playing {
                        player.volume = 0
                        player.play()
                        self.fadeIn(player: player, fadeInDuration: fadeInDuration, targetVolume: volume ?? self.initialVolume)
                        self.playIndex = (self.playIndex + 1) % self.players.count
                        self.startCurrentTimeUpdates()
                    }
                }
            }
        }
    }

    override func playWithFade(time: TimeInterval) {
        playWithFade(time: time, volume: nil, fadeInDuration: TimeInterval(Constant.DefaultFadeDuration))
    }

    override func stopWithFade(fadeOutDuration: TimeInterval, toPause: Bool = false) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !players.isEmpty && playIndex < players.count else {
                if !toPause { stop() }
                return
            }
            let player = players[playIndex]
            if player.timeControlStatus == .playing {
                fadeOut(player: player, fadeOutDuration: fadeOutDuration, toPause: toPause)
            } else if !toPause {
                stop()
            }
        }
    }

    override func stopWithFade() {
        stopWithFade(fadeOutDuration: TimeInterval(Constant.DefaultFadeDuration), toPause: false)
    }

}
