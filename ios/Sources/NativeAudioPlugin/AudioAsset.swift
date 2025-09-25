//
//  AudioAsset.swift
//  Plugin
//
//  Created by priyank on 2020-05-29.
//  Copyright © 2022 Martin Donadieu. All rights reserved.
//

import AVFoundation

/**
 * AudioAsset class handles local audio playback via AVAudioPlayer
 * Supports volume control, fade effects, rate changes, and looping
 */
public class AudioAsset: NSObject, AVAudioPlayerDelegate {

    var channels: [AVAudioPlayer] = []
    var playIndex: Int = 0
    var assetId: String = ""
    var initialVolume: Float = 1.0
    var fadeDelay: Float = 1.0
    weak var owner: NativeAudio?

    // Constants for fade effect
    let FADESTEP: Float = 0.05
    let FADEDELAY: Float = 0.08

    // Maximum number of channels to prevent excessive resource usage
    private let MAX_CHANNELS = Constant.MaxChannels

    private var currentTimeTimer: Timer?
    internal var fadeTimer: Timer?

    /**
     * Initialize a new audio asset
     * - Parameters:
     *   - owner: The plugin that owns this asset
     *   - assetId: Unique identifier for this asset
     *   - path: File path to the audio file
     *   - channels: Number of simultaneous playback channels (polyphony)
     *   - volume: Initial volume (0.0-1.0)
     *   - delay: Fade delay in seconds
     */
    init(owner: NativeAudio, withAssetId assetId: String, withPath path: String!, withChannels channels: Int!, withVolume volume: Float!, withFadeDelay delay: Float!) {

        self.owner = owner
        self.assetId = assetId
        self.channels = []
        self.initialVolume = min(max(volume ?? Constant.DefaultVolume, Constant.MinVolume), Constant.MaxVolume) // Validate volume range
        self.fadeDelay = max(delay ?? Constant.DefaultFadeDelay, 0.0) // Ensure non-negative delay

        super.init()

        guard let encodedPath = path.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) else {
            print("Failed to encode path: \(String(describing: path))")
            return
        }

        // Try to create URL from string first, fall back to file URL if that fails
        let pathUrl: URL
        if let url = URL(string: encodedPath) {
            pathUrl = url
        } else {
            pathUrl = URL(fileURLWithPath: encodedPath)
        }

        // Limit channels to a reasonable maximum to prevent resource issues
        let channelCount = min(max(channels ?? 1, 1), MAX_CHANNELS)

        owner.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }
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
                    print("Error loading audio file: \(error.localizedDescription)")
                    print("Path: \(String(describing: path))")
                }
            }
        }
    }

    deinit {
        currentTimeTimer?.invalidate()
        currentTimeTimer = nil

        fadeTimer?.invalidate()
        fadeTimer = nil

        // Clean up any players that might still be playing
        for player in channels {
            if player.isPlaying {
                player.stop()
            }
        }
        channels = []
    }

    /**
     * Get the current playback time
     * - Returns: Current time in seconds
     */
    func getCurrentTime() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            if channels.isEmpty || playIndex >= channels.count {
                result = 0
                return
            }
            let player = channels[playIndex]
            result = player.currentTime
        }
        return result
    }

    /**
     * Set the current playback time
     * - Parameter time: Time in seconds
     */
    func setCurrentTime(time: TimeInterval) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            if channels.isEmpty || playIndex >= channels.count {
                return
            }
            let player = channels[playIndex]
            // Ensure time is valid
            let validTime = min(max(time, 0), player.duration)
            player.currentTime = validTime
        }
    }

    /**
     * Get the total duration of the audio file
     * - Returns: Duration in seconds
     */
    func getDuration() -> TimeInterval {
        var result: TimeInterval = 0
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            if channels.isEmpty || playIndex >= channels.count {
                result = 0
                return
            }
            let player = channels[playIndex]
            result = player.duration
        }
        return result
    }

    /**
     * Play the audio from the specified time with optional delay
     * - Parameters:
     *   - time: Start time in seconds
     *   - delay: Delay before playback in seconds
     */
    func play(time: TimeInterval, delay: TimeInterval) {
        stopCurrentTimeUpdates()
        stopFadeTimer()

        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !channels.isEmpty else { return }

            // Reset play index if it's out of bounds
            if playIndex >= channels.count {
                playIndex = 0
            }

            // Ensure the audio session is active before playing
            owner?.activateSession()

            let player = channels[playIndex]
            // Ensure time is within valid range
            let validTime = min(max(time, 0), player.duration)
            player.currentTime = validTime
            player.numberOfLoops = 0

            // Use a valid delay (non-negative)
            let validDelay = max(delay, 0)

            if validDelay > 0 {
                player.play(atTime: player.deviceCurrentTime + validDelay)
            } else {
                player.play()
            }

            playIndex = (playIndex + 1) % channels.count
            startCurrentTimeUpdates()
        }
    }

    func playWithFade(time: TimeInterval) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !channels.isEmpty else { return }

            // Reset play index if it's out of bounds
            if playIndex >= channels.count {
                playIndex = 0
            }

            let player = channels[playIndex]
            player.currentTime = time

            if !player.isPlaying {
                player.numberOfLoops = 0
                player.volume = 0 // Start with volume at 0
                player.play()
                playIndex = (playIndex + 1) % channels.count
                startCurrentTimeUpdates()

                // Start fade-in
                startVolumeRamp(from: 0, to: initialVolume, player: player)
            } else {
                if player.volume < initialVolume {
                    // Continue fade-in if already in progress
                    startVolumeRamp(from: player.volume, to: initialVolume, player: player)
                }
            }
        }
    }

    private func startVolumeRamp(from startVolume: Float, to endVolume: Float, player: AVAudioPlayer) {
        stopFadeTimer()

        let steps = abs(endVolume - startVolume) / FADESTEP
        guard steps > 0 else { return }

        let timeInterval = FADEDELAY / steps
        var currentStep = 0
        let totalSteps = Int(ceil(steps))

        player.volume = startVolume

        // Create timer on main thread
        DispatchQueue.main.async { [weak self, weak player] in
            guard let self = self else { return }

            let timer = Timer.scheduledTimer(withTimeInterval: TimeInterval(timeInterval), repeats: true) { [weak self, weak player] timer in
                guard let strongSelf = self, let strongPlayer = player else {
                    timer.invalidate()
                    return
                }

                currentStep += 1
                let progress = Float(currentStep) / Float(totalSteps)
                let newVolume = startVolume + progress * (endVolume - startVolume)

                // Update player on audio queue
                strongSelf.owner?.executeOnAudioQueue {
                    strongPlayer.volume = newVolume
                }

                if currentStep >= totalSteps {
                    strongSelf.owner?.executeOnAudioQueue {
                        strongPlayer.volume = endVolume
                    }
                    timer.invalidate()

                    // Update timer reference on main thread
                    DispatchQueue.main.async {
                        strongSelf.fadeTimer = nil
                    }
                }
            }

            self.fadeTimer = timer
            RunLoop.current.add(timer, forMode: .common)
        }
    }

    internal func stopFadeTimer() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            if let timer = self.fadeTimer {
                timer.invalidate()
                self.fadeTimer = nil
            }
        }
    }

    func pause() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            stopCurrentTimeUpdates()

            // Check for valid playIndex
            guard !channels.isEmpty && playIndex < channels.count else { return }

            let player = channels[playIndex]
            player.pause()
        }
    }

    func resume() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            // Check for valid playIndex
            guard !channels.isEmpty && playIndex < channels.count else { return }

            let player = channels[playIndex]
            let timeOffset = player.deviceCurrentTime + 0.01
            player.play(atTime: timeOffset)
            startCurrentTimeUpdates()
        }
    }

    func stop() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            stopCurrentTimeUpdates()
            stopFadeTimer()

            for player in channels {
                if player.isPlaying {
                    player.stop()
                }
                player.currentTime = 0
                player.numberOfLoops = 0
            }
            playIndex = 0
        }
    }

    func stopWithFade() {
        // Store current player locally to avoid race conditions with playIndex
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            guard !channels.isEmpty && playIndex < channels.count else {
                stop()
                return
            }

            let player = channels[playIndex]
            if player.isPlaying && player.volume > 0 {
                startVolumeRamp(from: player.volume, to: 0, player: player)

                // Schedule the stop when fade is complete
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Int(FADEDELAY * 1000))) { [weak self, weak player] in
                    guard let strongSelf = self, let strongPlayer = player else { return }

                    if strongPlayer.volume < strongSelf.FADESTEP {
                        strongSelf.stop()
                    }
                }
            } else {
                stop()
            }
        }
    }

    func loop() {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            self.stop()

            guard !channels.isEmpty && playIndex < channels.count else { return }

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
            guard let self = self else { return }

            self.stop()
            stopCurrentTimeUpdates()
            stopFadeTimer()
            channels = []
        }
    }

    /**
     * Set the volume for all audio channels
     * - Parameter volume: Volume level (0.0-1.0)
     */
    func setVolume(volume: NSNumber!) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            // Ensure volume is in valid range
            let validVolume = min(max(volume.floatValue, Constant.MinVolume), Constant.MaxVolume)
            for player in channels {
                player.volume = validVolume
            }
        }
    }

    /**
     * Set the playback rate for all audio channels
     * - Parameter rate: Playback rate (0.5-2.0 is typical range)
     */
    func setRate(rate: NSNumber!) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            // Ensure rate is in valid range
            let validRate = min(max(rate.floatValue, Constant.MinRate), Constant.MaxRate)
            for player in channels {
                player.rate = validRate
            }
        }
    }

    /**
     * AVAudioPlayerDelegate method called when playback finishes
     */
    public func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            self.owner?.notifyListeners("complete", data: [
                "assetId": self.assetId
            ])

            // Notify the owner that this player finished
            // The owner will check if any other assets are still playing
            owner?.audioPlayerDidFinishPlaying(player, successfully: flag)
        }
    }

    func playerDecodeError(player: AVAudioPlayer!, error: NSError!) {
        if let error = error {
            print("AudioAsset decode error: \(error.localizedDescription)")
        }
    }

    func isPlaying() -> Bool {
        var result: Bool = false
        owner?.executeOnAudioQueue { [weak self] in
            guard let self = self else { return }

            if channels.isEmpty || playIndex >= channels.count {
                result = false
                return
            }
            let player = channels[playIndex]
            result = player.isPlaying
        }
        return result
    }

    internal func startCurrentTimeUpdates() {
        DispatchQueue.main.async { [weak self] in
            guard let strongSelf = self else { return }

            strongSelf.stopCurrentTimeUpdates() // Ensure no duplicate timers

            strongSelf.currentTimeTimer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
                guard let strongSelf = self, let strongOwner = strongSelf.owner else {
                    self?.stopCurrentTimeUpdates()
                    return
                }

                if strongSelf.isPlaying() {
                    strongOwner.notifyCurrentTime(strongSelf)
                } else {
                    strongSelf.stopCurrentTimeUpdates()
                }
            }
            if let timer = strongSelf.currentTimeTimer {
                RunLoop.current.add(timer, forMode: .common)
            }
        }
    }

    internal func stopCurrentTimeUpdates() {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            self.currentTimeTimer?.invalidate()
            self.currentTimeTimer = nil
        }
    }
}
