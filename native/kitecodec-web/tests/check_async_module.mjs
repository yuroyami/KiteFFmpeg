// Checks one asynchronous codec module against the real FFmpeg inside it (#183):
//   node check_async_module.mjs <kite-jspi.mjs | kite-asyncify.mjs> <jspi | asyncify>
// Every provider call answers from a timer, so every one parks the C stack that made it.
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

const [modulePath, expected] = process.argv.slice(2);
const fixture = JSON.parse(fs.readFileSync(new URL('./hls-fixture.json', import.meta.url), 'utf8'));
const files = Object.fromEntries(Object.entries(fixture.files).map(([name, data]) => [name, Buffer.from(data, 'base64')]));
const createModule = (await import(pathToFileURL(modulePath).href)).default;

if (expected === 'jspi' && typeof WebAssembly.Suspending !== 'function') {
  console.error(`Node ${process.version} has no JavaScript Promise Integration, which ${modulePath} needs.`);
  process.exit(3);
}

const failures = [];
const check = (condition, message) => { if (!condition) failures.push(message); };
const delay = (millis) => new Promise((resolve) => setTimeout(resolve, millis));
const SEEK_SIZE = 0x10000;
const AVERROR_EOF = -541478725;

/** One module with a host side that serves `playlist` at live.m3u8 or media.m3u8 and the fixture's files. */
async function open(playlist, name) {
  const sources = new Map();
  const counts = { reads: 0, opens: 0, closes: 0, sleeps: 0, inFlight: 0, deepest: 0 };
  let nextId = 1;
  let M;
  const parked = async (millis, body) => {
    counts.inFlight++;
    counts.deepest = Math.max(counts.deepest, counts.inFlight);
    await delay(millis);
    counts.inFlight--;
    return body();
  };
  const host = {
    read: (source, buf, len) => parked(1, () => {
      counts.reads++;
      const s = sources.get(source);
      if (!s) return -2;
      if (s.position >= s.bytes.length) return -1;
      const count = Math.min(len, s.bytes.length - s.position);
      M.HEAPU8.set(s.bytes.subarray(s.position, s.position + count), buf);
      s.position += count;
      return count;
    }),
    seek: (source, offset, whence, result) => parked(0, () => {
      const s = sources.get(source);
      if (!s) return -2;
      if (whence === SEEK_SIZE) {
        M.HEAP64[result >> 3] = BigInt(s.bytes.length);
        return 0;
      }
      s.position = Number(M.HEAP64[offset >> 3]);
      M.HEAP64[result >> 3] = BigInt(s.position);
      return 0;
    }),
    open: (url, source, size, seekable) => parked(2, () => {
      // FFmpeg loads a live playlist again through the opener, by the address the input was given.
      const asked = M.UTF8ToString(url).split('/').pop();
      const bytes = asked === name ? Buffer.from(playlist) : files[asked];
      if (!bytes) return -3;
      counts.opens++;
      const id = nextId++;
      sources.set(id, { bytes, position: 0 });
      M.HEAP32[source >> 2] = id;
      M.HEAP64[size >> 3] = BigInt(bytes.length);
      M.HEAP32[seekable >> 2] = 1;
      return 0;
    }),
    close: (source) => parked(1, () => { counts.closes++; sources.delete(source); }),
    sleep: (usec) => { counts.sleeps++; return delay(usec / 1000); },
    tags: () => 0,
    // Zero says the bytes came from the address that was asked for.
    location: () => 0,
  };
  M = await createModule({ kiteAsync: host, printErr: () => {} });
  const text = (value) => {
    const size = M.lengthBytesUTF8(value) + 1;
    const at = M._malloc(size);
    M.stringToUTF8(value, at, size);
    return at;
  };
  const root = nextId++;
  const bytes = Buffer.from(playlist);
  sources.set(root, { bytes, position: 0 });
  const out = M._malloc(4);
  const size = M._malloc(8);
  M.HEAP64[size >> 3] = BigInt(bytes.length);
  const interrupt = M._ffkmp_interrupt_new();
  const status = await M.kiteAsyncCall('ffkmp_async_open_input', [
    out, root, size, text(`https://kite.test/${name}`), 0, text('application/vnd.apple.mpegurl'), 1 | 4, 0, 0, 0, 0, interrupt,
  ]);
  const context = M.HEAP32[out >> 2];
  const packet = M._ffkmp_packet_alloc();
  return {
    M, counts, status, context, interrupt, sources,
    /** Packets until a read fails, and the code it failed with. */
    async drain() {
      let packets = 0;
      for (;;) {
        const read = await M.kiteAsyncCall('ffkmp_fmt_read_frame', [context, packet]);
        if (read < 0) return { packets, ended: read };
        packets++;
        M._ffkmp_packet_unref(packet);
      }
    },
    async close() {
      M._ffkmp_packet_free(packet);
      await M.kiteAsyncCall('ffkmp_fmt_close_input_io', [out]);
      // The free takes the address of the cell's pointer, and clears it.
      const slot = M._malloc(4);
      M.HEAP32[slot >> 2] = interrupt;
      M._ffkmp_interrupt_free(slot);
    },
  };
}

// The module says which mechanism it was linked with, and which bridge it holds.
{
  const run = await open(fixture.playlist, 'media.m3u8');
  check(run.M.kiteAsyncStrategy === expected, `the module says it is ${run.M.kiteAsyncStrategy}, not ${expected}`);
  check(run.M._ffkmp_async_bridge_version() === 1, `bridge version ${run.M._ffkmp_async_bridge_version()}`);
  check(run.status === 0, `the open failed with ${run.status}`);
  check(await run.M.kiteAsyncCall('ffkmp_fmt_find_stream_info', [run.context]) === 0, 'find_stream_info failed');
  check(run.M._ffkmp_fmt_nb_streams(run.context) === 1, `${run.M._ffkmp_fmt_nb_streams(run.context)} streams`);

  // A finished stream reads every packet through parked reads and nested opens.
  const all = await run.drain();
  check(all.packets === 40 && all.ended === AVERROR_EOF, `read ${all.packets} packets of 40 and ended with ${all.ended}`);
  check(run.counts.opens === 5, `${run.counts.opens} nested opens, and the stream has an initialization and four segments`);

  // A seek parks too, and takes its 64-bit time through memory.
  const micros = run.M._malloc(8);
  run.M.HEAP64[micros >> 3] = 2_000_000n;
  check(await run.M.kiteAsyncCall('ffkmp_async_seek_micros', [run.context, -1, micros]) >= 0, 'the seek to 2 s failed');
  const rest = await run.drain();
  check(rest.packets === 20, `${rest.packets} packets after a seek to 2 s, of 20`);

  await run.close();
  check(run.counts.closes === run.counts.opens, `${run.counts.opens} nested opens and ${run.counts.closes} closes`);
  check(run.counts.deepest === 1, `${run.counts.deepest} provider calls were in flight at once`);
  check(run.counts.reads > 10, `only ${run.counts.reads} reads parked`);
}

// A live stream that stops growing waits on the timer import, and the event loop runs meanwhile.
{
  const live = fixture.playlist.replace('#EXT-X-ENDLIST\n', '').replace('#EXT-X-PLAYLIST-TYPE:VOD\n', '');
  const run = await open(live, 'live.m3u8');
  check(run.status === 0, `the live open failed with ${run.status}`);
  let beats = 0;
  const beat = setInterval(() => beats++, 5);
  const waited = 1500;
  const started = Date.now();
  setTimeout(() => run.M._ffkmp_interrupt_raise(run.interrupt), waited);
  // Without this, a module whose wait holds the thread would never let the timer above fire.
  const watchdog = setTimeout(() => { console.error('the live wait never returned'); process.exit(2); }, 20_000);
  const read = await run.drain();
  clearTimeout(watchdog);
  clearInterval(beat);
  const took = Date.now() - started;
  check(read.ended < 0, 'the interrupted read did not fail');
  check(took >= waited - 50 && took < waited + 1000, `the interrupted read returned after ${took} ms, and the interrupt came at ${waited} ms`);
  check(run.counts.sleeps >= 5, `the wait reached the timer import ${run.counts.sleeps} times`);
  check(beats >= waited / 5 / 2, `the event loop ran ${beats} times in ${took} ms`);
  await run.close();
  check(run.counts.closes === run.counts.opens, `${run.counts.opens} nested opens and ${run.counts.closes} closes after an interrupt`);
}

if (failures.length > 0) {
  console.error(`${modulePath} failed:\n- ${failures.join('\n- ')}`);
  process.exit(1);
}
console.log(`${modulePath}: ${expected} passed`);
