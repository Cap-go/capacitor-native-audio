# Native Audio Plugin Tests

This directory contains tests for the Capacitor Native Audio Plugin.

## Running Tests

The easiest way to run these tests is through Xcode:

1. Open the `Plugin.xcworkspace` file in Xcode
2. Select the "Plugin" scheme
3. Go to Product > Test (or press ⌘+U)

## Test Coverage

The tests cover the following functionality:

- Basic initialization of `AudioAsset` and `RemoteAudioAsset` classes
- Volume control
- Fade effects (fade in/out)
- Notification observer pattern
- Cache clearing
- Plugin preloading

## Notes for Test Development

- The tests use a temporary audio file created at runtime
- For remote audio tests, a publicly accessible MP3 file is used
- Some tests use reflection to access private methods (for testing purposes only)
- All tests run on the plugin's audio queue to maintain thread safety

## Troubleshooting

If tests fail:

1. Make sure the iOS simulator is available
2. Check that the audio session is properly configured
3. For remote tests, ensure network connectivity is available

To view detailed test logs, expand the test navigator in Xcode and click on the failing test. 
