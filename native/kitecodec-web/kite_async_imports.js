// The imports of kite_async_bridge.c (#183). The host passes its side as the `kiteAsync` member of
// the object it creates the module with: read, seek, open, close and sleep answer with a Promise,
// tags and location answer at once.
//
// With Asyncify an import must park the C stack itself. With JSPI the engine parks it on the Promise.
#if ASYNCIFY == 1
var kiteParked = (start) => Asyncify.handleAsync(start);
#else
var kiteParked = (start) => start();
#endif

addToLibrary({
  $kiteParked: kiteParked,
#if ASYNCIFY == 1
  $kiteParked__deps: ['$Asyncify', '$ccall'],
  // One way to call an export that can park, whichever mechanism the module was linked with.
  $kiteParked__postset: `
    Module['kiteAsyncStrategy'] = 'asyncify';
    Module['kiteAsyncCall'] = (name, args) => ccall(name, 'number', args.map(() => 'number'), args, { async: true });`,
#else
  $kiteParked__postset: `
    Module['kiteAsyncStrategy'] = 'jspi';
    Module['kiteAsyncCall'] = (name, args) => Promise.resolve(Module['_' + name](...args));`,
#endif

  kite_async_read__async: true,
  kite_async_read__deps: ['$kiteParked'],
  kite_async_read: (source, buf, len) => kiteParked(() => Module['kiteAsync'].read(source, buf, len)),

  kite_async_seek__async: true,
  kite_async_seek__deps: ['$kiteParked'],
  kite_async_seek: (source, offset, whence, result) => kiteParked(() => Module['kiteAsync'].seek(source, offset, whence, result)),

  kite_async_open__async: true,
  kite_async_open__deps: ['$kiteParked'],
  kite_async_open: (url, source, size, seekable) => kiteParked(() => Module['kiteAsync'].open(url, source, size, seekable)),

  kite_async_close__async: true,
  kite_async_close__deps: ['$kiteParked'],
  kite_async_close: (source) => kiteParked(() => Module['kiteAsync'].close(source)),

  kite_async_sleep__async: true,
  kite_async_sleep__deps: ['$kiteParked'],
  kite_async_sleep: (usec) => kiteParked(() => Module['kiteAsync'].sleep(usec)),

  kite_async_tags: (source, tags) => Module['kiteAsync'].tags(source, tags),
  kite_async_location: (source, buf, cap) => Module['kiteAsync'].location(source, buf, cap),
});
