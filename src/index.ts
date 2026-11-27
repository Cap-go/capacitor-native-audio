import { registerPlugin } from '@capacitor/core';

import { NativeAudio } from './definitions.js';

const NativeAudio = registerPlugin<NativeAudio>('NativeAudio', {
  web: () => import('./web.js').then((m) => new m.NativeAudioWeb()),
});

export * from './definitions.js';
export { NativeAudio };
