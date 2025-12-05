#!/usr/bin/env node
'use strict';
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
var __createBinding =
  (this && this.__createBinding) ||
  (Object.create
    ? function (o, m, k, k2) {
        if (k2 === undefined) k2 = k;
        var desc = Object.getOwnPropertyDescriptor(m, k);
        if (!desc || ('get' in desc ? !m.__esModule : desc.writable || desc.configurable)) {
          desc = {
            enumerable: true,
            get: function () {
              return m[k];
            },
          };
        }
        Object.defineProperty(o, k2, desc);
      }
    : function (o, m, k, k2) {
        if (k2 === undefined) k2 = k;
        o[k2] = m[k];
      });
var __setModuleDefault =
  (this && this.__setModuleDefault) ||
  (Object.create
    ? function (o, v) {
        Object.defineProperty(o, 'default', { enumerable: true, value: v });
      }
    : function (o, v) {
        o['default'] = v;
      });
var __importStar =
  (this && this.__importStar) ||
  (function () {
    var ownKeys = function (o) {
      ownKeys =
        Object.getOwnPropertyNames ||
        function (o) {
          var ar = [];
          for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
          return ar;
        };
      return ownKeys(o);
    };
    return function (mod) {
      if (mod && mod.__esModule) return mod;
      var result = {};
      if (mod != null)
        for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== 'default') __createBinding(result, mod, k[i]);
      __setModuleDefault(result, mod);
      return result;
    };
  })();
Object.defineProperty(exports, '__esModule', { value: true });
exports.getConfig = getConfig;
exports.configureAndroid = configureAndroid;
exports.configureIOS = configureIOS;
exports.configureWeb = configureWeb;
const fs = __importStar(require('fs'));
const path = __importStar(require('path'));
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
function log(message, emoji = '', color = '') {
  const emojiPart = emoji ? `${emoji} ` : '';
  const colorCode = color || colors.reset;
  const resetCode = color ? colors.reset : '';
  console.log(`${colorCode}${emojiPart}${message}${resetCode}`);
}
function logSuccess(message) {
  log(message, '✔', colors.green);
}
function logError(message) {
  log(message, '✖', colors.red);
}
function logInfo(message) {
  log(message, 'ℹ', colors.blue);
}
function logWarning(message) {
  log(message, '⚠', colors.yellow);
}
/**
 * Parse NativeAudio configuration from Capacitor config
 * Default: hls = true (for backward compatibility)
 */
function getConfig() {
  const defaultConfig = {
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
    logError(`Error parsing config: ${error.message}`);
    return defaultConfig;
  }
}
/**
 * Log the current configuration status
 */
function logConfig(config) {
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
 * Write gradle.properties file for Android
 * Injects NativeAudio properties while preserving existing content
 */
function configureAndroid(config) {
  logInfo('Configuring Android dependencies...');
  try {
    // Read existing gradle.properties if it exists
    let existingContent = '';
    if (fs.existsSync(gradlePropertiesPath)) {
      existingContent = fs.readFileSync(gradlePropertiesPath, 'utf8');
    }
    // Remove existing NativeAudio properties (if any)
    const lines = existingContent.split('\n');
    const filteredLines = [];
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
    const nativeAudioProperties = [];
    nativeAudioProperties.push('');
    nativeAudioProperties.push('# NativeAudio Optional Dependencies (auto-generated)');
    nativeAudioProperties.push('# Generated by NativeAudio hook script');
    nativeAudioProperties.push(`nativeAudio.hls.include=${config.hls ? 'true' : 'false'}`);
    // Combine: existing content + new NativeAudio properties
    const newContent = filteredLines.join('\n') + '\n' + nativeAudioProperties.join('\n') + '\n';
    fs.writeFileSync(gradlePropertiesPath, newContent, 'utf8');
    logSuccess('Updated gradle.properties');
  } catch (error) {
    logError(`Error updating gradle.properties: ${error.message}`);
  }
}
// ============================================================================
// iOS: No Configuration Needed (yet)
// ============================================================================
/**
 * iOS platform - HLS is handled natively by AVPlayer
 */
function configureIOS() {
  logInfo('iOS uses native AVPlayer for HLS - no additional configuration needed');
}
// ============================================================================
// Web: No Configuration Needed
// ============================================================================
/**
 * Web platform doesn't need native dependency configuration
 */
function configureWeb() {
  logInfo('Web platform - no native dependency configuration needed');
}
// ============================================================================
// Main Execution
// ============================================================================
function main() {
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
