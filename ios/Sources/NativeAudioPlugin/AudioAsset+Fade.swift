import AVFoundation

extension AudioAsset {

    func dispatchComplete() {
        if dispatchedCompleteMap[assetId] == true {
            return
        }
        owner?.notifyListeners("complete", data: ["assetId": assetId])
        dispatchedCompleteMap[assetId] = true
    }

    func cancelFade() {
        fadeTask?.cancel()
        fadeTask = nil
    }

    fileprivate func performLocalFadeOutPauseOnMain(audio: AVAudioPlayer, beforePause: ((TimeInterval, TimeInterval) -> Void)?) {
        let elapsed = audio.currentTime
        let duration = audio.duration.isFinite ? audio.duration : 0
        beforePause?(elapsed, duration)
        audio.pause()
    }

    fileprivate func scheduleLocalFadeOutPauseOnMain(audio: AVAudioPlayer, beforePause: ((TimeInterval, TimeInterval) -> Void)?) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.performLocalFadeOutPauseOnMain(audio: audio, beforePause: beforePause)
        }
    }

    /// Same main-thread pause and `beforePause(elapsed, duration)` as fade-out-to-pause when no fade runs (e.g. volume already zero).
    internal func schedulePauseWithPositionRecording(audio: AVAudioPlayer, beforePause: ((TimeInterval, TimeInterval) -> Void)?) {
        scheduleLocalFadeOutPauseOnMain(audio: audio, beforePause: beforePause)
    }

    fileprivate func scheduleLocalFadeOutStopOnMain(audio: AVAudioPlayer, beforeStop: ((TimeInterval, TimeInterval) -> Void)?) {
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.performLocalFadeOutStopOnMain(audio: audio, beforeStop: beforeStop)
        }
    }

    fileprivate func performLocalFadeOutStopOnMain(audio: AVAudioPlayer, beforeStop: ((TimeInterval, TimeInterval) -> Void)?) {
        let elapsed = audio.currentTime
        let duration = audio.duration.isFinite ? audio.duration : 0
        beforeStop?(elapsed, duration)
        audio.stop()
        dispatchComplete()
    }

    func fadeIn(audio: AVAudioPlayer, fadeInDuration: TimeInterval, targetVolume: Float) {
        cancelFade()
        let steps = Int(fadeInDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else { return }
        let fadeStep = targetVolume / Float(steps)
        var currentVolume: Float = 0

        var task: DispatchWorkItem?
        task = DispatchWorkItem { [weak self] in
            guard let self else { return }
            for _ in 0..<steps {
                guard let task, !task.isCancelled, self.isPlaying(), audio.isPlaying else { return }
                currentVolume += fadeStep
                DispatchQueue.main.async {
                    audio.volume = min(max(currentVolume, 0), targetVolume)
                }
                Thread.sleep(forTimeInterval: TimeInterval(self.fadeDelaySecs))
            }
        }
        fadeTask = task
        if let task {
            fadeQueue.async(execute: task)
        }
    }

    /// - Parameter beforeStop: Called on the main queue immediately before `stop()` when `toPause` is false,
    ///   so the plugin can refresh Now Playing (rate 0) at the final elapsed time.
    /// - Parameter beforePause: Called on the main queue immediately before `pause()` when `toPause` is true,
    ///   so the plugin can persist `timeBeforePause` and update Now Playing at the actual stop position.
    ///   Kept last so call sites can use a trailing closure for fade-out-to-pause.
    func fadeOut(
        audio: AVAudioPlayer,
        fadeOutDuration: TimeInterval,
        toPause: Bool = false,
        beforeStop: ((TimeInterval, TimeInterval) -> Void)? = nil,
        beforePause: ((TimeInterval, TimeInterval) -> Void)? = nil
    ) {
        cancelFade()
        let steps = Int(fadeOutDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else {
            if toPause {
                scheduleLocalFadeOutPauseOnMain(audio: audio, beforePause: beforePause)
            } else {
                scheduleLocalFadeOutStopOnMain(audio: audio, beforeStop: beforeStop)
            }
            return
        }
        var currentVolume = audio.volume
        let fadeStep = currentVolume / Float(steps)

        var task: DispatchWorkItem?
        task = DispatchWorkItem { [weak self] in
            guard let self else { return }
            for _ in 0..<steps {
                guard let task, !task.isCancelled, self.isPlaying(), audio.isPlaying else { return }
                currentVolume -= fadeStep
                DispatchQueue.main.async {
                    audio.volume = max(currentVolume, 0)
                }
                Thread.sleep(forTimeInterval: TimeInterval(self.fadeDelaySecs))
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                if toPause {
                    self.performLocalFadeOutPauseOnMain(audio: audio, beforePause: beforePause)
                } else {
                    self.performLocalFadeOutStopOnMain(audio: audio, beforeStop: beforeStop)
                }
            }
        }
        fadeTask = task
        if let task {
            fadeQueue.async(execute: task)
        }
    }

    func fadeTo(audio: AVAudioPlayer, fadeDuration: TimeInterval, targetVolume: Float) {
        cancelFade()
        let steps = Int(fadeDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else { return }

        let minVolume = zeroVolume
        var currentVolume = max(audio.volume, minVolume)
        let safeTargetVolume = max(targetVolume, minVolume)
        let ratio = pow(safeTargetVolume / currentVolume, 1.0 / Float(steps))

        var task: DispatchWorkItem?
        task = DispatchWorkItem { [weak self] in
            guard let self else { return }
            for _ in 0..<steps {
                guard let task, !task.isCancelled, self.isPlaying(), audio.isPlaying else { return }
                currentVolume *= ratio
                DispatchQueue.main.async {
                    audio.volume = min(max(currentVolume, minVolume), self.maxVolume)
                }
                Thread.sleep(forTimeInterval: TimeInterval(self.fadeDelaySecs))
            }
        }
        fadeTask = task
        if let task {
            fadeQueue.async(execute: task)
        }
    }
}
