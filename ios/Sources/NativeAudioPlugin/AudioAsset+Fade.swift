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

    func fadeOut(audio: AVAudioPlayer, fadeOutDuration: TimeInterval, toPause: Bool = false) {
        cancelFade()
        let steps = Int(fadeOutDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else { return }
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
                    audio.pause()
                } else {
                    audio.stop()
                    self.dispatchComplete()
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
