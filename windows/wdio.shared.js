export const config = {
  runner: 'local',
  specs: ['./e2e/**/*.e2e.js'],
  maxInstances: 1,
  logLevel: 'info',
  waitforTimeout: 90_000,
  connectionRetryTimeout: 120_000,
  connectionRetryCount: 1,
  framework: 'mocha',
  reporters: ['spec'],
  mochaOpts: {
    ui: 'bdd',
    timeout: 180_000,
  },
};
