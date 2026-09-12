import { config as base } from './wdio.shared.js';

export const config = {
  ...base,
  services: [
    [
      'tauri',
      {
        appBinaryPath: './src-tauri/target/release/gecis-windows.exe',
        driverProvider: 'external',
        autoInstallTauriDriver: false,
        autoDownloadEdgeDriver: true,
        captureFrontendLogs: true,
        captureBackendLogs: true,
      },
    ],
  ],
  capabilities: [
    {
      'tauri:options': {
        application: './src-tauri/target/release/gecis-windows.exe',
      },
    },
  ],
};
