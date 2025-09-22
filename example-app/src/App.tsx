import { useEffect, useMemo, useRef, useState } from 'react';
import { NativeAudio } from '@capgo/native-audio';
import type { PluginListenerHandle } from '@capacitor/core';
import './App.css';

type AssetKey = 'local' | 'remote';

interface AssetDefinition {
  assetId: string;
  assetPath: string;
  label: string;
  description: string;
  isUrl?: boolean;
}

interface AssetStatus {
  loaded: boolean;
  duration?: number;
}

const assets: Record<AssetKey, AssetDefinition> = {
  local: {
    assetId: 'local-beep',
    assetPath: 'audio/beep.wav',
    label: 'Local Beep',
    description: 'Short 440Hz tone packaged with the web assets.'
  },
  remote: {
    assetId: 'remote-demo',
    assetPath: 'https://samplelib.com/lib/preview/mp3/sample-3s.mp3',
    label: 'Remote MP3',
    description: '3 second MP3 streamed over HTTPS.',
    isUrl: true
  }
};

const INITIAL_STATE: Record<AssetKey, AssetStatus> = {
  local: { loaded: false },
  remote: { loaded: false }
};

const App = () => {
  const [activeAsset, setActiveAsset] = useState<AssetKey>('local');
  const [assetState, setAssetState] = useState<Record<AssetKey, AssetStatus>>(INITIAL_STATE);
  const [volumeMap, setVolumeMap] = useState<Record<AssetKey, number>>({ local: 1, remote: 1 });
  const [statusMessage, setStatusMessage] = useState('Idle');
  const [currentPosition, setCurrentPosition] = useState(0);
  const [isLooping, setIsLooping] = useState(false);
  const [playingAsset, setPlayingAsset] = useState<AssetKey | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const playingAssetRef = useRef<AssetKey | null>(null);

  useEffect(() => {
    playingAssetRef.current = playingAsset;
  }, [playingAsset]);

  useEffect(() => {
    let completeHandle: PluginListenerHandle | undefined;
    let timeHandle: PluginListenerHandle | undefined;

    const setup = async () => {
      try {
        await NativeAudio.configure({ focus: true, background: true, ignoreSilent: false, fade: false });
      } catch (error) {
        // Configure fails silently on web if permissions are missing; surface to UI instead
        setErrorMessage(normalizeError(error));
      }

      try {
        completeHandle = await NativeAudio.addListener('complete', event => {
          const assetKey = findAssetKeyById(event.assetId);
          if (!assetKey) {
            return;
          }
          setPlayingAsset(prev => (prev === assetKey ? null : prev));
          setStatusMessage(`${assets[assetKey].label} finished playing.`);
          setCurrentPosition(0);
        });

        timeHandle = await NativeAudio.addListener('currentTime', event => {
          const assetKey = findAssetKeyById(event.assetId);
          if (!assetKey) {
            return;
          }
          if (playingAssetRef.current === assetKey) {
            setCurrentPosition(event.currentTime);
          }
        });
      } catch (error) {
        setErrorMessage(normalizeError(error));
      }
    };

    setup();

    return () => {
      completeHandle?.remove();
      timeHandle?.remove();
    };
  }, []);

  const activeDefinition = useMemo(() => assets[activeAsset], [activeAsset]);
  const activeStatus = assetState[activeAsset];
  const activeVolume = volumeMap[activeAsset];

  const preloadAsset = async (key: AssetKey) => {
    const definition = assets[key];
    try {
      await NativeAudio.preload({
        assetId: definition.assetId,
        assetPath: definition.assetPath,
        isUrl: definition.isUrl,
        audioChannelNum: 1,
        volume: volumeMap[key]
      });
      const durationResult = await NativeAudio.getDuration({ assetId: definition.assetId }).catch(() => undefined);
      const duration = durationResult?.duration;
      setAssetState(prev => ({
        ...prev,
        [key]: {
          loaded: true,
          duration
        }
      }));
      setStatusMessage(
        `${definition.label} preloaded${typeof duration === 'number' ? ` (${duration.toFixed(2)}s)` : ''}.`
      );
      setErrorMessage(null);
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  const unloadAsset = async (key: AssetKey) => {
    const definition = assets[key];
    try {
      await NativeAudio.unload({ assetId: definition.assetId });
      setAssetState(prev => ({
        ...prev,
        [key]: { loaded: false }
      }));
      if (playingAsset === key) {
        setPlayingAsset(null);
        setCurrentPosition(0);
      }
      setStatusMessage(`${definition.label} unloaded.`);
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  const playAsset = async (key: AssetKey) => {
    const definition = assets[key];
    try {
      if (!assetState[key].loaded) {
        await preloadAsset(key);
      }
      await NativeAudio.play({ assetId: definition.assetId });
      setPlayingAsset(key);
      setStatusMessage(`Playing ${definition.label}...`);
      setErrorMessage(null);
      if (!assetState[key].duration) {
        const { duration } = await NativeAudio.getDuration({ assetId: definition.assetId }).catch(() => ({ duration: undefined }));
        setAssetState(prev => ({
          ...prev,
          [key]: {
            ...prev[key],
            duration
          }
        }));
      }
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  const pauseAsset = async () => {
    if (!playingAsset) {
      return;
    }
    try {
      await NativeAudio.pause({ assetId: assets[playingAsset].assetId });
      setStatusMessage(`Paused ${assets[playingAsset].label}.`);
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  const resumeAsset = async () => {
    if (!playingAsset) {
      return;
    }
    try {
      await NativeAudio.resume({ assetId: assets[playingAsset].assetId });
      setStatusMessage(`Resumed ${assets[playingAsset].label}.`);
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  const stopAsset = async () => {
    if (!playingAsset) {
      return;
    }
    try {
      await NativeAudio.stop({ assetId: assets[playingAsset].assetId });
      setPlayingAsset(null);
      setCurrentPosition(0);
      setStatusMessage(`Stopped ${assets[playingAsset].label}.`);
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  const toggleLoop = async () => {
    if (!playingAsset) {
      return;
    }
    try {
      if (!isLooping) {
        await NativeAudio.loop({ assetId: assets[playingAsset].assetId });
        setStatusMessage(`${assets[playingAsset].label} is looping.`);
      } else {
        await NativeAudio.stop({ assetId: assets[playingAsset].assetId });
        await NativeAudio.play({ assetId: assets[playingAsset].assetId });
        setStatusMessage(`Loop disabled for ${assets[playingAsset].label}.`);
      }
      setIsLooping(prev => !prev);
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  const handleVolumeChange = async (key: AssetKey, rawValue: string) => {
    const value = Number(rawValue);
    setVolumeMap(prev => ({ ...prev, [key]: value }));
    if (!assetState[key].loaded) {
      return;
    }
    try {
      await NativeAudio.setVolume({ assetId: assets[key].assetId, volume: value });
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  const refreshPlaybackState = async (key: AssetKey) => {
    const definition = assets[key];
    try {
      const [{ currentTime }, { duration }, { isPlaying }] = await Promise.all([
        NativeAudio.getCurrentTime({ assetId: definition.assetId }),
        NativeAudio.getDuration({ assetId: definition.assetId }),
        NativeAudio.isPlaying({ assetId: definition.assetId })
      ]);
      setCurrentPosition(currentTime);
      setAssetState(prev => ({
        ...prev,
        [key]: { loaded: prev[key].loaded, duration }
      }));
      setPlayingAsset(isPlaying ? key : null);
      setStatusMessage(`${definition.label}: ${isPlaying ? 'playing' : 'stopped'} at ${currentTime.toFixed(2)}s.`);
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  const clearCache = async () => {
    try {
      await NativeAudio.clearCache();
      setStatusMessage('Remote cache cleared.');
    } catch (error) {
      setErrorMessage(normalizeError(error));
    }
  };

  return (
    <main className="app">
      <h1>@capgo/native-audio</h1>
      <p className="tagline">Interactive Capacitor example with local and remote audio assets.</p>

      <section className="panel">
        <h2>Choose Asset</h2>
        <div className="asset-selector">
          {Object.entries(assets).map(([key, definition]) => (
            <button
              key={definition.assetId}
              type="button"
              className={`chip ${activeAsset === key ? 'chip--active' : ''}`}
              onClick={() => setActiveAsset(key as AssetKey)}
            >
              {definition.label}
            </button>
          ))}
        </div>
        <p className="asset-description">{activeDefinition.description}</p>
      </section>

      <section className="panel">
        <h2>Asset Controls</h2>
        <div className="actions">
          <button type="button" onClick={() => preloadAsset(activeAsset)}>
            {activeStatus.loaded ? 'Re-preload' : 'Preload'}
          </button>
          <button type="button" onClick={() => playAsset(activeAsset)}>
            Play
          </button>
          <button type="button" onClick={pauseAsset} disabled={!playingAsset}>
            Pause
          </button>
          <button type="button" onClick={resumeAsset} disabled={!playingAsset}>
            Resume
          </button>
          <button type="button" onClick={stopAsset} disabled={!playingAsset}>
            Stop
          </button>
          <button type="button" onClick={() => unloadAsset(activeAsset)} disabled={!activeStatus.loaded}>
            Unload
          </button>
        </div>
        <div className="slider-group">
          <label htmlFor="volume">Volume ({activeVolume.toFixed(2)})</label>
          <input
            id="volume"
            type="range"
            min="0.1"
            max="1"
            step="0.05"
            value={activeVolume}
            onChange={event => handleVolumeChange(activeAsset, event.target.value)}
          />
        </div>
        <div className="secondary-actions">
          <button type="button" onClick={toggleLoop} disabled={!playingAsset}>
            {isLooping ? 'Disable Loop' : 'Loop Current'}
          </button>
          <button type="button" onClick={() => refreshPlaybackState(activeAsset)}>
            Refresh State
          </button>
          <button type="button" onClick={clearCache}>
            Clear Remote Cache
          </button>
        </div>
      </section>

      <section className="panel">
        <h2>Playback Status</h2>
        <ul className="status-list">
          <li>
            <strong>Active asset:</strong> {activeDefinition.label}
          </li>
          <li>
            <strong>Loaded:</strong> {activeStatus.loaded ? 'Yes' : 'No'}
          </li>
          <li>
            <strong>Currently playing:</strong> {playingAsset ? assets[playingAsset].label : 'None'}
          </li>
          <li>
            <strong>Position:</strong> {currentPosition.toFixed(2)}s
          </li>
          <li>
            <strong>Duration:</strong>{' '}
            {typeof activeStatus.duration === 'number' ? `${activeStatus.duration.toFixed(2)}s` : 'Unknown'}
          </li>
        </ul>
        <p className="status-message">{statusMessage}</p>
        {errorMessage && <p className="error">{errorMessage}</p>}
      </section>

      <section className="panel">
        <h2>Try it on device</h2>
        <ol className="instructions">
          <li>Install dependencies with <code>npm install</code> inside the <code>example</code> folder.</li>
          <li>Start web demo via <code>npm run dev</code> or sync native platforms with <code>npm run sync</code>.</li>
          <li>Run <code>npm run ios</code> or <code>npm run android</code> to open the Capacitor shell apps.</li>
        </ol>
      </section>
    </main>
  );
};

const findAssetKeyById = (assetId: string): AssetKey | null => {
  const entry = Object.entries(assets).find(([, definition]) => definition.assetId === assetId);
  return entry ? (entry[0] as AssetKey) : null;
};

const normalizeError = (error: unknown): string => {
  if (!error) {
    return 'Unknown error.';
  }
  if (error instanceof Error) {
    return error.message;
  }
  if (typeof error === 'string') {
    return error;
  }
  try {
    return JSON.stringify(error);
  } catch (jsonError) {
    return String(error);
  }
};

export default App;
