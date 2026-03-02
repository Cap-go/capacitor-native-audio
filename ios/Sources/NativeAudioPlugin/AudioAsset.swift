import AVFoundation

// swiftlint:disable:next type_body_length
public class AudioAsset: NSObject, AVAudioPlayerDelegate {

    var channels: [AVAudioPlayer] = []
    var playIndex: Int = 0
    var assetId: String = ""
    var initialVolume: Float = 1.0
    let zeroVolume: Float = 0.001
    let maxVolume: Float = 1.0
    weak var owner: NativeAudio?
    var onComplete: (() -> Void)?

    let fadeDelaySecs: Float = 0.08
    private var currentTimeTimer: Timer?
    internal var fadeTimer: Timer?
    var fadeTask: DispatchWorkItem?
    let fadeQueue: DispatchQueue = DispatchQueue(label: "com.audioasset.fadeQueue")
    var dispatchedCompleteMap: [String: Bool] = [:]

    private var logger = Logger(logTag: "AudioAsset")

    init(owner: NativeAudio, withAssetId assetId: String, withPath path: String, withChannels channels: Int?, withVolume volume: Float?) {
        self.owner = owner
        self.assetId = assetId
        self.channels = []
        self.initialVolume = min(max(volume ?? Constant.DefaultVolume, Constant.MinVolume), Constant.MaxVolume)

        super.init()

        guard let encodedPath = path.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            logger.error("Failed to encode path: %@", String(describing: path))
            return
        }

        let pathUrl = URL(string: encodedPath) ?? URL(fileURLWithPath: encodedPath)
        let channelCount = min(max(channels ?? 1, 1), Constant.MaxChannels)

        let setupBlock = { [weak self] in
            guard let self else { return }
            for _ in 0..<channelCount {
                do {
                    let player = try AVAudioPlayer(contentsOf: pathUrl)
                    player.delegate = self
                    player.enableRate = true
                    player.volume = self.initialVolume
                    player.rate = 1.0
                    player.prepareToPlay()
                    self.channels.append(player)
                } catch {
                    self.logger.error("Error loading audio file: %@", error.localizedDescription)
                }
            }
        }

        if owner.isRunningTests {
            setupBlock()
        } else {
            owner.executeOnAudioQueue(setupBlock)
        }
    }

    // Backward-compatible initializer signature
    init(owner: NativeAudio, withAssetId assetId: String, withPath path: String, withChannels channels: Int?, withVolume volume: Float?, withFadeDelay _: Float?) {
        self.owner = owner
        self.assetId = assetId
        self.channels = []
        self.initialVolume = min(max(volume ?? Constant.DefaultVolume, Constant.MinVolume), Constant.MaxVolume)
        super.init()

        guard let encodedPath = path.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            logger.error("Failed to encode path: %@", String(describing: path))
            return
        }

        let pathUrl = URL(string: encodedPath) ?? URL(fileURLWithPath: encodedPath)
        let channelCount = min(max(channels ?? 1, 1), Constant.MaxChannels)
        let setupBlock = { [weak self] in
            guard let self else { return }
            for _ in 0..<channelCount {
                do {
                    let player = try AVAudioPlayer(contentsOf: pathUrl)
                    player.delegate = self
                    player.enableRate = true
                    player.volume = self.initialVolume
                    player.rate = 1.0
                    player.prepareToPlay()
                    self.channels.append(player)
                } catch {
                    self.logger.error("Error loading audio file: %@", error.localizedDescription)
                }
            }
        }
        if owner.isRunningTests {
            setupBlock()
        } else {
            owner.executeOnAudioQueue(setupBlock)
        }
    }

    deinit {
        currentTimeTimer?.invalidate()
        currentTimeTimer = nil
        fadeTimer?.invalidate()
        fadeTimer = nil
        cancelFade()
        for player in channels where player.isPlaying {
            player.stop()
        }
        channels = []
    }

    func getCurrentTime() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !channels.isEmpty, playIndex < channels.count else { return }
            result = channels[playIndex].currentTime
        }
        return result
    }

    func setCurrentTime(time: TimeInterval) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !channels.isEmpty, playIndex < channels.count else { return }
            let player = channels[playIndex]
            let validTime = min(max(time, 0), player.duration)
            player.currentTime = validTime
        }
    }

    func getDuration() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !channels.isEmpty, playIndex < channels.count else { return }
            result = channels[playIndex].duration
        }
        return result
    }

    func play(time: TimeInterval, volume: Float? = nil) {
        stopCurrentTimeUpdates()
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !channels.isEmpty else { return }
            if playIndex >= channels.count {
                playIndex = 0
            }
            owner?.activateSession()
            cancelFade()

            let player = channels[playIndex]
            let validTime = min(max(time, 0), player.duration)
            player.currentTime = validTime
            player.numberOfLoops = 0
            player.volume = volume ?? initialVolume
            player.play()

            playIndex = (playIndex + 1) % channels.count
            startCurrentTimeUpdates()
        }
    }

    // Backward-compatible signature
    func play(time: TimeInterval, delay: TimeInterval) {
        let validDelay = max(delay, 0)
        if validDelay > 0 {
            DispatchQueue.main.asyncAfter(deadline: .now() + validDelay) { [weak self] in
                self?.play(time: time, volume: nil)
            }
        } else {
            play(time: time, volume: nil)
        }
    }

    func playWithFade(time: TimeInterval) {
        playWithFade(time: time, volume: nil, fadeInDuration: TimeInterval(Constant.DefaultFadeDuration))
    }

    func playWithFade(time: TimeInterval, volume: Float?, fadeInDuration: TimeInterval) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !channels.isEmpty else { return }
            if playIndex >= channels.count {
                playIndex = 0
            }

            let player = channels[playIndex]
            player.currentTime = time
            player.numberOfLoops = 0
            player.volume = 0
            player.play()
            playIndex = (playIndex + 1) % channels.count
            startCurrentTimeUpdates()
            fadeIn(audio: player, fadeInDuration: fadeInDuration, targetVolume: volume ?? initialVolume)
        }
    }

    func pause() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !channels.isEmpty, playIndex < channels.count else { return }
            cancelFade()
            channels[playIndex].pause()
            stopCurrentTimeUpdates()
        }
    }

    func resume() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !channels.isEmpty, playIndex < channels.count else { return }
            let player = channels[playIndex]
            let timeOffset = player.deviceCurrentTime + 0.01
            player.play(atTime: timeOffset)
            startCurrentTimeUpdates()
        }
    }

    func stop() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            cancelFade()
            stopCurrentTimeUpdates()
            for player in channels {
                if player.isPlaying {
                    player.stop()
                }
                player.currentTime = 0
                player.numberOfLoops = 0
            }
            playIndex = 0
            dispatchComplete()
        }
    }

    func stopWithFade() {
        stopWithFade(fadeOutDuration: TimeInterval(Constant.DefaultFadeDuration), toPause: false)
    }

    func stopWithFade(fadeOutDuration: TimeInterval, toPause: Bool = false) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            guard !channels.isEmpty, playIndex < channels.count else {
                if !toPause { stop() }
                return
            }
            let player = channels[playIndex]
            if player.isPlaying && player.volume > 0 {
                fadeOut(audio: player, fadeOutDuration: fadeOutDuration, toPause: toPause)
            } else if !toPause {
                stop()
            }
        }
    }

    func loop() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            cancelFade()
            stop()
            guard !channels.isEmpty, playIndex < channels.count else { return }
            let player = channels[playIndex]
            player.delegate = self
            player.numberOfLoops = -1
            player.play()
            playIndex = (playIndex + 1) % channels.count
            startCurrentTimeUpdates()
        }
    }

    func unload() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            cancelFade()
            stop()
            stopCurrentTimeUpdates()
            channels = []
        }
    }

    func setVolume(volume: NSNumber) {
        setVolume(volume: volume, fadeDuration: 0)
    }

    func setVolume(volume: NSNumber, fadeDuration: Double) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            cancelFade()
            let validVolume = min(max(volume.floatValue, Constant.MinVolume), Constant.MaxVolume)
            for player in channels {
                if player.isPlaying && fadeDuration > 0 {
                    fadeTo(audio: player, fadeDuration: fadeDuration, targetVolume: validVolume)
                } else {
                    player.volume = validVolume
                }
            }
        }
    }

    func setRate(rate: NSNumber) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            let validRate = min(max(rate.floatValue, Constant.MinRate), Constant.MaxRate)
            for player in channels {
                player.rate = validRate
            }
        }
    }

    func isPlaying() -> Bool {
        var result = false
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            result = channels.contains(where: { $0.isPlaying })
        }
        return result
    }

    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self else { return }
            dispatchComplete()
            onComplete?()
            owner?.audioPlayerDidFinishPlaying(player, successfully: flag)
        }
    }

    func playerDecodeError(player: AVAudioPlayer, error: NSError?) {
        if let error {
            logger.error("AudioAsset decode error: %@", error.localizedDescription)
        }
    }

    internal func startCurrentTimeUpdates() {
        stopCurrentTimeUpdates()
        dispatchedCompleteMap[assetId] = false
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            let timer = Timer(timeInterval: 0.1, repeats: true) { [weak self] _ in
                guard let self, let owner else {
                    self?.stopCurrentTimeUpdates()
                    return
                }
                if self.isPlaying() {
                    owner.notifyCurrentTime(self)
                } else {
                    self.stopCurrentTimeUpdates()
                }
            }
            self.currentTimeTimer = timer
            RunLoop.current.add(timer, forMode: .common)
        }
    }

    internal func stopCurrentTimeUpdates() {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            logger.debug("Stop current time updates")
            self.currentTimeTimer?.invalidate()
            self.currentTimeTimer = nil
        }
    }

}
