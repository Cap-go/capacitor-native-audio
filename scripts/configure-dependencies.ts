#!/usr/bin/env node

/**
 * Capacitor Hook Script: Configure Optional HLS Dependency
 *
 * This script runs during `npx cap sync` and configures whether to include
 * the HLS (m3u8) streaming dependency based on capacitor.config.ts settings.
 *
 * By default, HLS is enabled for backward compatibility.
 * To disable HLS and reduce APK size by ~4MB, set:
 *
 * plugins: {
 *   NativeAudio: {
 *     hls: false
 *   }
 * }
 *
 * Environment variables provided by Capacitor:
 * - CAPACITOR_ROOT_DIR: Root directory of the consuming app
 * - CAPACITOR_CONFIG: JSON stringified config object
 * - CAPACITOR_PLATFORM_NAME: Platform name (android, ios, web)
 * - process.cwd(): Plugin root directory
 */

import * as fs from 'fs';
import * as path from 'path';

// Get environment variables
const PLUGIN_ROOT = process.cwd();
const CONFIG_JSON = process.env.CAPACITOR_CONFIG;
const PLATFORM = process.env.CAPACITOR_PLATFORM_NAME;

// File paths
const gradlePropertiesPath = path.join(PLUGIN_ROOT, 'android', 'gradle.properties');

// ============================================================================
// Logging Utilities
// ============================================================================

const colors = {
  reset: '\x1b[0m',
  bright: '\x1b[1m',
  green: '\x1b[32m',
  red: '\x1b[31m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  cyan: '\x1b[36m',
  gray: '\x1b[90m',
};

/**
 * Logs a message to the console with an optional leading emoji and optional color formatting.
 *
 * @param message - The text to log
 * @param emoji - Optional emoji or symbol to prepend to the message
 * @param color - Optional ANSI color code to apply to the message; if omitted the default/reset color is used
 */
function log(message: string, emoji = '', color = ''): void {
  const emojiPart = emoji ? `${emoji} ` : '';
  const colorCode = color || colors.reset;
  const resetCode = color ? colors.reset : '';
  console.log(`${colorCode}${emojiPart}${message}${resetCode}`);
}

/**
 * Log a success message prefixed with a green checkmark.
 *
 * @param message - The text to log as a success message
 */
function logSuccess(message: string): void {
  log(message, '✔', colors.green);
}

/**
 * Logs an error message prefixed with a red "✖" marker.
 *
 * @param message - The error text to log
 */
function logError(message: string): void {
  log(message, '✖', colors.red);
}

/**
 * Logs an informational message prefixed with a blue "ℹ" icon.
 *
 * @param message - The message text to output as an informational log
 */
function logInfo(message: string): void {
  log(message, 'ℹ', colors.blue);
}

/**
 * Logs a warning-styled message using the warning emoji and yellow color.
 *
 * @param message - Text to log as a warning
 */
function logWarning(message: string): void {
  log(message, '⚠', colors.yellow);
}

// ============================================================================
// Configuration Parsing
// ============================================================================

interface NativeAudioConfig {
  hls: boolean;
}

/**
 * Reads CAPACITOR_CONFIG and returns the NativeAudio configuration, defaulting HLS to enabled.
 *
 * If CAPACITOR_CONFIG is missing or cannot be parsed, returns the default configuration with HLS enabled.
 *
 * @returns The NativeAudioConfig with `hls` set to `true` if HLS should be included, `false` otherwise.
 */
function getConfig(): NativeAudioConfig {
  const defaultConfig: NativeAudioConfig = {
    hls: true, // Enabled by default for backward compatibility
  };

  try {
    if (!CONFIG_JSON) {
      logInfo('No CAPACITOR_CONFIG found, using defaults (HLS enabled)');
      return defaultConfig;
    }

    const config = JSON.parse(CONFIG_JSON);
    const nativeAudioConfig = config.plugins?.NativeAudio || {};

    return {
      hls: nativeAudioConfig.hls !== false, // Default to true unless explicitly set to false
    };
  } catch (error) {
    logError(`Error parsing config: ${(error as Error).message}`);
    return defaultConfig;
  }
}

/**
 * Print the NativeAudio HLS inclusion status and approximate APK size impact to the console.
 *
 * @param config - NativeAudio configuration whose `hls` flag indicates whether HLS (m3u8) is enabled
 */
function logConfig(config: NativeAudioConfig): void {
  log('\nNativeAudio configuration:', '', colors.bright);

  if (config.hls) {
    console.log(
      `  ${colors.green}✔${colors.reset} ${colors.bright}HLS (m3u8)${colors.reset}: ${colors.green}enabled${colors.reset} (includes media3-exoplayer-hls, adds ~4MB to APK)`,
    );
  } else {
    console.log(
      `  ${colors.yellow}○${colors.reset} ${colors.bright}HLS (m3u8)${colors.reset}: ${colors.yellow}disabled${colors.reset} (reduces APK size by ~4MB)`,
    );
  }
  console.log('');
}

// ============================================================================
// Android: Gradle Configuration
// ============================================================================

/**
 * Write or update android/gradle.properties to set whether the NativeAudio HLS dependency is included.
 *
 * Preserves existing non-NativeAudio properties and replaces any previously generated NativeAudio section.
 *
 * @param config - Configuration specifying whether HLS (m3u8) dependency should be included (`config.hls = true` enables it)
 */
function configureAndroid(config: NativeAudioConfig): void {
  logInfo('Configuring Android dependencies...');

  try {
    // Read existing gradle.properties if it exists
    let existingContent = '';
    if (fs.existsSync(gradlePropertiesPath)) {
      existingContent = fs.readFileSync(gradlePropertiesPath, 'utf8');
    }

    // Remove existing NativeAudio properties (if any)
    const lines = existingContent.split('\n');
    const filteredLines: string[] = [];
    let inNativeAudioSection = false;
    let lastWasEmpty = false;

    for (const line of lines) {
      // Check if this is a NativeAudio property or comment
      if (
        line.trim().startsWith('# NativeAudio') ||
        line.trim().startsWith('nativeAudio.') ||
        line.trim() === '# Generated by NativeAudio hook script'
      ) {
        inNativeAudioSection = true;
        continue; // Skip this line
      }

      // If we were in NativeAudio section and hit a non-empty line, we're done
      if (inNativeAudioSection && line.trim() !== '') {
        inNativeAudioSection = false;
      }

      // Add non-NativeAudio lines, but avoid multiple consecutive empty lines
      if (!inNativeAudioSection) {
        if (line.trim() === '') {
          if (!lastWasEmpty) {
            filteredLines.push(line);
            lastWasEmpty = true;
          }
        } else {
          filteredLines.push(line);
          lastWasEmpty = false;
        }
      }
    }

    // Build new NativeAudio properties section
    const nativeAudioProperties: string[] = [];
    nativeAudioProperties.push('');
    nativeAudioProperties.push('# NativeAudio Optional Dependencies (auto-generated)');
    nativeAudioProperties.push('# Generated by NativeAudio hook script');
    nativeAudioProperties.push(`nativeAudio.hls.include=${config.hls ? 'true' : 'false'}`);

    // Combine: existing content + new NativeAudio properties
    const newContent = filteredLines.join('\n') + '\n' + nativeAudioProperties.join('\n') + '\n';

    fs.writeFileSync(gradlePropertiesPath, newContent, 'utf8');
    logSuccess('Updated gradle.properties');
  } catch (error) {
    logError(`Error updating gradle.properties: ${(error as Error).message}`);
  }
}

// ============================================================================
// iOS: No Configuration Needed (yet)
// ============================================================================

/**
 * Indicate that iOS requires no extra configuration for HLS because AVPlayer provides native support.
 */
function configureIOS(): void {
  logInfo('iOS uses native AVPlayer for HLS - no additional configuration needed');
}

// ============================================================================
// Web: No Configuration Needed
// ============================================================================

/**
 * Indicate that the Web platform requires no native dependency configuration.
 *
 * Logs an informational message stating that no native dependency configuration is necessary for the web platform.
 */
function configureWeb(): void {
  logInfo('Web platform - no native dependency configuration needed');
}

// ============================================================================
// Main Execution
/**
 * Orchestrates platform-specific configuration of NativeAudio optional dependencies.
 *
 * Reads the current NativeAudio configuration and applies the appropriate configuration
 * for the detected Capacitor platform (`android`, `ios`, or `web`). If the platform is
 * unknown or unspecified, defaults to configuring Android for backward compatibility.
 * Progress and result messages are logged during the process.
 */

function main(): void {
  const config = getConfig();

  switch (PLATFORM) {
    case 'android':
      log('Configuring optional dependencies for NativeAudio', '🔧', colors.cyan);
      logConfig(config);
      configureAndroid(config);
      logSuccess('Configuration complete\n');
      break;

    case 'ios':
      log('Configuring NativeAudio for iOS', '🔧', colors.cyan);
      logConfig(config);
      configureIOS();
      logSuccess('Configuration complete\n');
      break;

    case 'web':
      configureWeb();
      break;

    default:
      // If platform is not specified, configure all platforms (backward compatibility)
      log('Configuring optional dependencies for NativeAudio', '🔧', colors.blue);
      logConfig(config);
      logWarning(`Unknown platform: ${PLATFORM || 'undefined'}, configuring Android`);
      configureAndroid(config);
      logSuccess('Configuration complete\n');
      break;
  }
}

// Run if executed directly
if (require.main === module) {
  main();
}

export { getConfig, configureAndroid, configureIOS, configureWeb };