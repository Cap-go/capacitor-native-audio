import AVFoundation

extension RemoteAudioAsset {

    func fadeIn(player: AVPlayer, fadeInDuration: TimeInterval, targetVolume: Float) {
        cancelFade()
        let steps = Int(fadeInDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else { return }
        let fadeStep = targetVolume / Float(steps)
        var currentVolume: Float = 0

        var task: DispatchWorkItem?
        task = DispatchWorkItem { [weak self] in
            guard let self else { return }
            for _ in 0..<steps {
                guard let task, !task.isCancelled, self.isPlaying(), player.timeControlStatus == .playing else { return }
                currentVolume += fadeStep
                DispatchQueue.main.async {
                    player.volume = min(currentVolume, targetVolume)
                }
                Thread.sleep(forTimeInterval: TimeInterval(self.fadeDelaySecs))
            }
        }
        fadeTask = task
        if let task {
            fadeQueue.async(execute: task)
        }
    }

    func fadeOut(player: AVPlayer, fadeOutDuration: TimeInterval, toPause: Bool = false) {
        cancelFade()
        let steps = Int(fadeOutDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else { return }
        let fadeStep = player.volume / Float(steps)
        var currentVolume = player.volume

        var task: DispatchWorkItem?
        task = DispatchWorkItem { [weak self] in
            guard let self else { return }
            for _ in 0..<steps {
                guard let task, !task.isCancelled, self.isPlaying(), player.timeControlStatus == .playing else { return }
                currentVolume -= fadeStep
                DispatchQueue.main.async {
                    player.volume = max(currentVolume, 0)
                }
                Thread.sleep(forTimeInterval: TimeInterval(self.fadeDelaySecs))
            }
            DispatchQueue.main.async { [weak self] in
                guard let self else { return }
                if toPause {
                    player.pause()
                } else {
                    player.pause()
                    player.seek(to: .zero)
                    self.owner?.notifyListeners("complete", data: ["assetId": self.assetId as Any])
                    self.dispatchedCompleteMap[self.assetId] = true
                }
            }
        }
        fadeTask = task
        if let task {
            fadeQueue.async(execute: task)
        }
    }

    func fadeTo(player: AVPlayer, fadeOutDuration: TimeInterval, targetVolume: Float) {
        cancelFade()
        let steps = Int(fadeOutDuration / TimeInterval(fadeDelaySecs))
        guard steps > 0 else { return }

        let minVolume = zeroVolume
        var currentVolume: Float = max(player.volume, minVolume)
        let safeTargetVolume: Float = max(targetVolume, minVolume)
        let ratio = pow(safeTargetVolume / currentVolume, 1.0 / Float(steps))

        var task: DispatchWorkItem?
        task = DispatchWorkItem { [weak self] in
            guard let self else { return }
            for _ in 0..<steps {
                guard let task, !task.isCancelled, self.isPlaying(), player.timeControlStatus == .playing else { return }
                currentVolume *= ratio
                DispatchQueue.main.async {
                    player.volume = min(max(currentVolume, minVolume), self.maxVolume)
                }
                Thread.sleep(forTimeInterval: TimeInterval(self.fadeDelaySecs))
            }
        }
        fadeTask = task
        if let task {
            fadeQueue.async(execute: task)
        }
    }

    static func clearCache() {
        DispatchQueue.global(qos: .background).sync {
            let urls = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)
            if let cachePath = urls.first {
                do {
                    let fileURLs = try FileManager.default.contentsOfDirectory(at: cachePath, includingPropertiesForKeys: nil)
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
