// Mocha's per-test default is 2000 ms and Kotlin does not raise it for browser tests, so any test
// that runs longer than two seconds is killed while it is still working. KitePlayer proved this the
// expensive way: the same suite passed on one CI run and failed on the next, unchanged, with
// "Error: Timeout of 2000ms exceeded".
//
// Raising the mocha ceiling weakens no assertion. The real bound stays the Kotlin timeout on the
// test itself; this only stops the harness killing a test that is still working. The browser and
// disconnect timeouts move with it so a genuinely hung run still ends rather than hanging the job.
config.set({
    client: Object.assign({}, config.client, {
        mocha: Object.assign({}, (config.client || {}).mocha, { timeout: 600000 }),
    }),
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 60000,
});
